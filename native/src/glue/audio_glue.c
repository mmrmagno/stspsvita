/* Audio backend for VitaAudio: OpenAL playback + libvorbisfile OGG decode.
 * SFX are fully decoded into an AL buffer and played on a shared source pool;
 * music streams (see stsv_music_*). Portable: the desktop harness links the
 * host's OpenAL/vorbisfile, the Vita links vitasdk's. */

#include <AL/al.h>
#include <AL/alc.h>
#include <vorbis/vorbisfile.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

static ALCdevice *g_device;
static ALCcontext *g_context;
static int g_ready;

static void stsv_start_worker(void);

#define STSV_SOURCE_POOL 30
static ALuint g_sources[STSV_SOURCE_POOL];

/* The Vita speaker is quiet at the game's mix, so boost the decoded PCM. */
#define STSV_PCM_BOOST_NUM 19
#define STSV_PCM_BOOST_DEN 10

static void stsv_boost_pcm(short *pcm, size_t samples) {
    for (size_t i = 0; i < samples; i++) {
        int v = ((int) pcm[i] * STSV_PCM_BOOST_NUM) / STSV_PCM_BOOST_DEN;
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        pcm[i] = (short) v;
    }
}

void stsv_audio_init(void) {
    if (g_ready) {
        return;
    }
    g_device = alcOpenDevice(NULL);
    if (g_device == NULL) {
        fprintf(stderr, "[audio] alcOpenDevice failed\n");
        return;
    }
    g_context = alcCreateContext(g_device, NULL);
    if (g_context == NULL || !alcMakeContextCurrent(g_context)) {
        fprintf(stderr, "[audio] alcCreateContext failed\n");
        return;
    }
    alGenSources(STSV_SOURCE_POOL, g_sources);
    stsv_start_worker();
    /* Keep every AL gain within [0, 1]: this mixer wraps instead of clamping. */
    alListenerf(AL_GAIN, 1.0f);
    g_ready = 1;
    fprintf(stderr, "[audio] OpenAL ready (%d sources, pcm boost %d/%d)\n",
            STSV_SOURCE_POOL, STSV_PCM_BOOST_NUM, STSV_PCM_BOOST_DEN);
}

/* In-memory OGG source for ov_open_callbacks (TeaVM has no stable C paths for
 * ux0: files, so the Java side hands us the encoded bytes like FreeType does). */
typedef struct { const char *data; size_t size; size_t pos; } stsv_ogg_mem;

static size_t stsv_ogg_read(void *ptr, size_t sz, size_t nmemb, void *ds) {
    stsv_ogg_mem *m = (stsv_ogg_mem *) ds;
    size_t want = sz * nmemb, avail = m->size - m->pos;
    if (want > avail) want = avail;
    memcpy(ptr, m->data + m->pos, want);
    m->pos += want;
    return sz ? want / sz : 0;
}
static int stsv_ogg_seek(void *ds, ogg_int64_t off, int whence) {
    stsv_ogg_mem *m = (stsv_ogg_mem *) ds;
    ogg_int64_t base = (whence == SEEK_CUR) ? (ogg_int64_t) m->pos
                     : (whence == SEEK_END) ? (ogg_int64_t) m->size : 0;
    ogg_int64_t np = base + off;
    if (np < 0 || np > (ogg_int64_t) m->size) return -1;
    m->pos = (size_t) np;
    return 0;
}
static long stsv_ogg_tell(void *ds) { return (long) ((stsv_ogg_mem *) ds)->pos; }

/* Minimal RIFF/WAVE PCM16 loader: a handful of StS UI SFX
 * (SOTE_SFX_UIHover/UIClick) are .wav, not .ogg. Returns AL buffer or 0. */
static int stsv_load_wav(const unsigned char *d, int len) {
    if (len < 44 || memcmp(d, "RIFF", 4) != 0 || memcmp(d + 8, "WAVE", 4) != 0) {
        return 0;
    }
    int channels = 0, rate = 0, bits = 0;
    const unsigned char *pcm = NULL;
    unsigned pcm_len = 0;
    int pos = 12;
    while (pos + 8 <= len) {
        unsigned sz = d[pos + 4] | (d[pos + 5] << 8) | (d[pos + 6] << 16)
                    | ((unsigned) d[pos + 7] << 24);
        if (memcmp(d + pos, "fmt ", 4) == 0 && pos + 8 + 16 <= len) {
            int fmt = d[pos + 8] | (d[pos + 9] << 8);
            channels = d[pos + 10] | (d[pos + 11] << 8);
            rate = d[pos + 12] | (d[pos + 13] << 8) | (d[pos + 14] << 16)
                 | (d[pos + 15] << 24);
            bits = d[pos + 22] | (d[pos + 23] << 8);
            if (fmt != 1) {
                fprintf(stderr, "[audio] wav: unsupported format %d\n", fmt);
                return 0;
            }
        } else if (memcmp(d + pos, "data", 4) == 0) {
            pcm = d + pos + 8;
            pcm_len = sz;
            if (pcm + pcm_len > d + len) pcm_len = (unsigned) (d + len - pcm);
        }
        pos += 8 + (int) ((sz + 1u) & ~1u);
    }
    if (pcm == NULL || channels < 1 || channels > 2 || bits != 16 || rate <= 0) {
        fprintf(stderr, "[audio] wav: unsupported layout ch=%d bits=%d rate=%d\n",
                channels, bits, rate);
        return 0;
    }
    /* Boost into a copy: pcm points into the caller's (Java-owned) bytes. */
    short *boosted = (short *) malloc(pcm_len);
    if (boosted == NULL) {
        return 0;
    }
    memcpy(boosted, pcm, pcm_len);
    stsv_boost_pcm(boosted, pcm_len / 2);
    ALuint buffer = 0;
    alGenBuffers(1, &buffer);
    alBufferData(buffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16,
                 boosted, (ALsizei) pcm_len, (ALsizei) rate);
    free(boosted);
    return (int) buffer;
}

enum { STSV_JOB_QUEUED, STSV_JOB_DECODING, STSV_JOB_DONE, STSV_JOB_FAILED };

typedef struct stsv_job {
    ALuint buffer;
    char *ogg;
    int len;
    char *path;
    char *pcm;
    size_t size;
    ALenum format;
    ALsizei rate;
    int state;
    int cancelled;
    struct stsv_job *next;
} stsv_job;

typedef struct { ALuint buffer; ALuint source; int paused; } stsv_pending_play;

#define STSV_PENDING_PLAYS 32
static stsv_pending_play g_pending[STSV_PENDING_PLAYS];
static stsv_job *g_jobs;
static pthread_mutex_t g_jobs_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_jobs_cond = PTHREAD_COND_INITIALIZER;
static int g_async;

static char *stsv_decode_ogg(const char *data, int len, size_t *out_size, ALenum *out_format, ALsizei *out_rate) {
    stsv_ogg_mem mem = { data, (size_t) len, 0 };
    ov_callbacks cbs = { stsv_ogg_read, stsv_ogg_seek, NULL, stsv_ogg_tell };
    OggVorbis_File vf;
    int ov_err = ov_open_callbacks(&mem, &vf, NULL, 0, cbs);
    if (ov_err != 0) {
        fprintf(stderr, "[audio] ov_open_callbacks failed err=%d (%d bytes, first=%02x%02x%02x%02x)\n",
                ov_err, len,
                (unsigned char) data[0],
                (unsigned char) data[1],
                (unsigned char) data[2],
                (unsigned char) data[3]);
        return NULL;
    }
    vorbis_info *vi = ov_info(&vf, -1);
    ALenum format = (vi->channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
    ALsizei rate = (ALsizei) vi->rate;

    /* Exact size: growing by doubling fails on a fragmented heap. */
    size_t cap = 1u << 16, size = 0;
    ogg_int64_t total_pcm = ov_pcm_total(&vf, -1);
    if (total_pcm > 0) {
        cap = (size_t) total_pcm * (size_t) vi->channels * 2 + 4096;
    }
    char *pcm = (char *) malloc(cap);
    if (pcm == NULL) {
        fprintf(stderr, "[audio] sound pcm malloc FAILED (%u bytes)\n",
                (unsigned) cap);
        ov_clear(&vf);
        return NULL;
    }
    int bitstream;
    for (;;) {
        if (size + 4096 > cap) {
            cap *= 2;
            char *grown = (char *) realloc(pcm, cap);
            if (grown == NULL) { free(pcm); ov_clear(&vf); return NULL; }
            pcm = grown;
        }
        long n = ov_read(&vf, pcm + size, 4096, 0, 2, 1, &bitstream); /* 16-bit LE signed */
        if (n <= 0) break;
        size += (size_t) n;
    }
    ov_clear(&vf);

    stsv_boost_pcm((short *) pcm, size / 2);
    *out_size = size;
    *out_format = format;
    *out_rate = rate;
    return pcm;
}

static char *stsv_read_file(const char *path, int *len) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        fprintf(stderr, "[audio] open failed %s\n", path);
        return NULL;
    }
    char *buf = NULL;
    long size = fseek(f, 0, SEEK_END) == 0 ? ftell(f) : -1;
    if (size > 0 && fseek(f, 0, SEEK_SET) == 0) {
        buf = (char *) malloc((size_t) size);
        if (buf != NULL && fread(buf, 1, (size_t) size, f) != (size_t) size) {
            free(buf);
            buf = NULL;
        }
    }
    fclose(f);
    *len = (int) size;
    return buf;
}

static void *stsv_decode_worker(void *arg) {
    (void) arg;
    for (;;) {
        pthread_mutex_lock(&g_jobs_lock);
        stsv_job *job = NULL;
        while (job == NULL) {
            for (stsv_job *j = g_jobs; j; j = j->next) {
                if (j->state == STSV_JOB_QUEUED) { job = j; break; }
            }
            if (job == NULL) pthread_cond_wait(&g_jobs_cond, &g_jobs_lock);
        }
        job->state = STSV_JOB_DECODING;
        pthread_mutex_unlock(&g_jobs_lock);
        size_t size = 0;
        ALenum format = 0;
        ALsizei rate = 0;
        if (job->ogg == NULL && job->path != NULL) {
            job->ogg = stsv_read_file(job->path, &job->len);
        }
        char *pcm = job->ogg ? stsv_decode_ogg(job->ogg, job->len, &size, &format, &rate) : NULL;
        pthread_mutex_lock(&g_jobs_lock);
        job->pcm = pcm;
        job->size = size;
        job->format = format;
        job->rate = rate;
        job->state = pcm ? STSV_JOB_DONE : STSV_JOB_FAILED;
        pthread_mutex_unlock(&g_jobs_lock);
    }
    return NULL;
}

static void stsv_start_worker(void) {
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 256 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    g_async = pthread_create(&thread, &attr, stsv_decode_worker, NULL) == 0;
    pthread_attr_destroy(&attr);
    if (!g_async) {
        fprintf(stderr, "[audio] decode thread failed, decoding inline\n");
    }
}

static int stsv_buffer_pending(ALuint buffer) {
    int pending = 0;
    pthread_mutex_lock(&g_jobs_lock);
    for (stsv_job *j = g_jobs; j; j = j->next) {
        if (j->buffer == buffer && !j->cancelled) { pending = 1; break; }
    }
    pthread_mutex_unlock(&g_jobs_lock);
    return pending;
}

static void stsv_drop_pending_source(ALuint source) {
    for (int i = 0; i < STSV_PENDING_PLAYS; i++) {
        if (g_pending[i].source == source) g_pending[i].source = g_pending[i].buffer = 0;
    }
}

static int stsv_source_reserved(ALuint source) {
    for (int i = 0; i < STSV_PENDING_PLAYS; i++) {
        if (g_pending[i].source == source) return 1;
    }
    return 0;
}

void stsv_sound_pump(void) {
    if (!g_async) return;
    stsv_job *done = NULL;
    pthread_mutex_lock(&g_jobs_lock);
    stsv_job **link = &g_jobs;
    while (*link) {
        stsv_job *j = *link;
        if (j->state == STSV_JOB_DONE || j->state == STSV_JOB_FAILED) {
            *link = j->next;
            j->next = done;
            done = j;
        } else {
            link = &j->next;
        }
    }
    pthread_mutex_unlock(&g_jobs_lock);
    while (done) {
        stsv_job *j = done;
        done = j->next;
        int ok = !j->cancelled && j->state == STSV_JOB_DONE;
        if (ok) {
            alBufferData(j->buffer, j->format, j->pcm, (ALsizei) j->size, j->rate);
        } else if (!j->cancelled) {
            fprintf(stderr, "[audio] newSound FAILED %s\n", j->path ? j->path : "(memory)");
        }
        for (int i = 0; i < STSV_PENDING_PLAYS && !j->cancelled; i++) {
            if (g_pending[i].buffer == j->buffer && g_pending[i].source != 0) {
                if (ok) {
                    alSourcei(g_pending[i].source, AL_BUFFER, (ALint) j->buffer);
                    alSourcePlay(g_pending[i].source);
                    if (g_pending[i].paused) alSourcePause(g_pending[i].source);
                }
                g_pending[i].source = g_pending[i].buffer = 0;
            }
        }
        free(j->pcm);
        free(j->ogg);
        free(j->path);
        free(j);
    }
}

static int stsv_sound_load_sync(const void *data, int len) {
    size_t size = 0;
    ALenum format = 0;
    ALsizei rate = 0;
    char *pcm = stsv_decode_ogg((const char *) data, len, &size, &format, &rate);
    if (pcm == NULL) {
        return 0;
    }
    ALuint buffer = 0;
    alGenBuffers(1, &buffer);
    alBufferData(buffer, format, pcm, (ALsizei) size, rate);
    free(pcm);
    return (int) buffer;
}

static int stsv_queue_job(stsv_job *job) {
    alGenBuffers(1, &job->buffer);
    pthread_mutex_lock(&g_jobs_lock);
    job->next = g_jobs;
    g_jobs = job;
    pthread_cond_signal(&g_jobs_cond);
    pthread_mutex_unlock(&g_jobs_lock);
    return (int) job->buffer;
}

/* Queue a whole .ogg (encoded bytes at data/len) for decoding into a new AL
 * buffer. Returns the buffer name (>0) or 0 on failure. Used for short SFX. */
int stsv_sound_load(const void *data, int len) {
    if (!g_ready || data == NULL || len <= 0) {
        return 0;
    }
    if (len > 12 && memcmp(data, "RIFF", 4) == 0) {
        return stsv_load_wav((const unsigned char *) data, len);
    }
    if (!g_async) {
        return stsv_sound_load_sync(data, len);
    }
    stsv_job *job = (stsv_job *) calloc(1, sizeof *job);
    char *copy = (char *) malloc((size_t) len);
    if (job == NULL || copy == NULL) {
        free(job);
        free(copy);
        return stsv_sound_load_sync(data, len);
    }
    memcpy(copy, data, (size_t) len);
    job->ogg = copy;
    job->len = len;
    return stsv_queue_job(job);
}

/* Returns -1, not 0, when the caller should read the file itself. */
int stsv_sound_load_path(const void *utf8, int len) {
    if (!g_ready || !g_async || utf8 == NULL || len <= 0) {
        return -1;
    }
    stsv_job *job = (stsv_job *) calloc(1, sizeof *job);
    char *path = (char *) malloc((size_t) len + 1);
    if (job == NULL || path == NULL) {
        free(job);
        free(path);
        return -1;
    }
    memcpy(path, utf8, (size_t) len);
    path[len] = '\0';
    job->path = path;
    return stsv_queue_job(job);
}

void stsv_sound_free(int buffer) {
    if (!g_ready || buffer == 0) return;
    ALuint b = (ALuint) buffer;
    pthread_mutex_lock(&g_jobs_lock);
    for (stsv_job *j = g_jobs; j; j = j->next) {
        if (j->buffer == b) j->cancelled = 1;
    }
    pthread_mutex_unlock(&g_jobs_lock);
    for (int i = 0; i < STSV_PENDING_PLAYS; i++) {
        if (g_pending[i].buffer == b) g_pending[i].source = g_pending[i].buffer = 0;
    }
    /* stop any source still bound to it, then delete */
    for (int i = 0; i < STSV_SOURCE_POOL; i++) {
        ALint bound = 0;
        alGetSourcei(g_sources[i], AL_BUFFER, &bound);
        if ((ALuint) bound == b) {
            alSourceStop(g_sources[i]);
            alSourcei(g_sources[i], AL_BUFFER, 0);
        }
    }
    alDeleteBuffers(1, &b);
}

/* Play a loaded buffer on a free (or stolen) pool source. Returns the source
 * name used as libGDX's sound id, or 0. pan is -1..1 -> x position. */
int stsv_sound_play(int buffer, float volume, float pitch, float pan, int loop) {
    if (!g_ready || buffer == 0) return 0;
    stsv_sound_pump();
    ALuint src = 0;
    for (int i = 0; i < STSV_SOURCE_POOL; i++) {
        ALint state = 0;
        alGetSourcei(g_sources[i], AL_SOURCE_STATE, &state);
        if (state != AL_PLAYING && state != AL_PAUSED && !stsv_source_reserved(g_sources[i])) {
            src = g_sources[i];
            break;
        }
    }
    if (src == 0) src = g_sources[0]; /* all busy: steal the first */
    stsv_drop_pending_source(src);
    alSourceStop(src);
    int pending = g_async && stsv_buffer_pending((ALuint) buffer);
    alSourcei(src, AL_BUFFER, pending ? 0 : buffer);
    alSourcef(src, AL_GAIN, volume < 0.0f ? 0.0f : (volume > 1.0f ? 1.0f : volume));
    alSourcef(src, AL_PITCH, pitch <= 0.0f ? 1.0f : pitch);
    alSource3f(src, AL_POSITION, pan, 0.0f, 0.0f);
    alSourcei(src, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
    if (pending) {
        for (int i = 0; i < STSV_PENDING_PLAYS; i++) {
            if (g_pending[i].source == 0) {
                g_pending[i].buffer = (ALuint) buffer;
                g_pending[i].source = src;
                g_pending[i].paused = 0;
                return (int) src;
            }
        }
        fprintf(stderr, "[audio] pending play table full, dropping a play\n");
        return 0;
    }
    alSourcePlay(src);
    return (int) src;
}

void stsv_sound_stop(int source) {
    if (!g_ready || source == 0) return;
    stsv_drop_pending_source((ALuint) source);
    alSourceStop((ALuint) source);
}

void stsv_sound_set_volume(int source, float volume) {
    if (!g_ready || source == 0) return;
    alSourcef((ALuint) source, AL_GAIN, volume < 0.0f ? 0.0f : (volume > 1.0f ? 1.0f : volume));
}

void stsv_sound_set_pitch(int source, float pitch) {
    if (!g_ready || source == 0) return;
    alSourcef((ALuint) source, AL_PITCH, pitch <= 0.0f ? 1.0f : pitch);
}

void stsv_sound_set_looping(int source, int loop) {
    if (!g_ready || source == 0) return;
    alSourcei((ALuint) source, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
}

static void stsv_mark_pending_paused(ALuint source, int paused) {
    for (int i = 0; i < STSV_PENDING_PLAYS; i++) {
        if (g_pending[i].source == source) g_pending[i].paused = paused;
    }
}

void stsv_sound_pause(int source) {
    if (!g_ready || source == 0) return;
    stsv_mark_pending_paused((ALuint) source, 1);
    alSourcePause((ALuint) source);
}

void stsv_sound_resume(int source) {
    if (!g_ready || source == 0) return;
    stsv_mark_pending_paused((ALuint) source, 0);
    ALint state = 0;
    alGetSourcei((ALuint) source, AL_SOURCE_STATE, &state);
    if (state == AL_PAUSED) alSourcePlay((ALuint) source);
}

/* ---- Music: OGG decoded into a small queue of PCM chunks ---- */

#define STSV_MUSIC_BUFFERS 4
#define STSV_MUSIC_CHUNK   (16 * 1024)

typedef struct {
    OggVorbis_File vf;
    char *ogg;              /* optional owned stream for the legacy memory opener */
    stsv_ogg_mem mem;
    ALuint source;
    ALuint buffers[STSV_MUSIC_BUFFERS];
    ALenum format;
    ALsizei rate;
    int looping;
    int playing;            /* app intent, so we can recover from underruns */
} stsv_music;

static long stsv_music_fill(stsv_music *m, char *buf) {
    long total = 0;
    int bs;
    while (total < STSV_MUSIC_CHUNK) {
        long n = ov_read(&m->vf, buf + total, (int) (STSV_MUSIC_CHUNK - total), 0, 2, 1, &bs);
        if (n <= 0) break;
        total += n;
    }
    if (total > 0) {
        stsv_boost_pcm((short *) buf, (size_t) total / 2);
    }
    return total;
}

static int64_t stsv_music_finish_open(stsv_music *m) {
    vorbis_info *vi = ov_info(&m->vf, -1);
    if (vi == NULL) {
        ov_clear(&m->vf);
        free(m->ogg);
        free(m);
        return 0;
    }
    m->format = (vi->channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
    m->rate = (ALsizei) vi->rate;
    alGenSources(1, &m->source);
    alGenBuffers(STSV_MUSIC_BUFFERS, m->buffers);
    char chunk[STSV_MUSIC_CHUNK];
    for (int i = 0; i < STSV_MUSIC_BUFFERS; i++) {
        long got = stsv_music_fill(m, chunk);
        if (got <= 0) break;
        alBufferData(m->buffers[i], m->format, chunk, (ALsizei) got, m->rate);
        alSourceQueueBuffers(m->source, 1, &m->buffers[i]);
    }
    return (int64_t) (intptr_t) m;
}

int64_t stsv_music_open(const void *data, int len) {
    if (!g_ready || data == NULL || len <= 0) return 0;
    stsv_music *m = (stsv_music *) calloc(1, sizeof(stsv_music));
    if (m == NULL) return 0;
    m->ogg = (char *) malloc((size_t) len);
    if (m->ogg == NULL) { free(m); return 0; }
    memcpy(m->ogg, data, (size_t) len);
    m->mem.data = m->ogg; m->mem.size = (size_t) len; m->mem.pos = 0;
    ov_callbacks cbs = { stsv_ogg_read, stsv_ogg_seek, NULL, stsv_ogg_tell };
    if (ov_open_callbacks(&m->mem, &m->vf, NULL, 0, cbs) != 0) {
        free(m->ogg); free(m);
        return 0;
    }
    return stsv_music_finish_open(m);
}

int64_t stsv_music_open_path(const void *path_utf8, int len) {
    if (!g_ready || path_utf8 == NULL || len <= 0) return 0;
    char *path = (char *) malloc((size_t) len + 1u);
    if (path == NULL) return 0;
    memcpy(path, path_utf8, (size_t) len);
    path[len] = '\0';

    stsv_music *m = (stsv_music *) calloc(1, sizeof(stsv_music));
    if (m == NULL) { free(path); return 0; }
    int opened = ov_fopen(path, &m->vf);
    free(path);
    if (opened != 0) {
        free(m);
        return 0;
    }
    return stsv_music_finish_open(m);
}

void stsv_music_update(int64_t handle) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    ALint processed = 0;
    alGetSourcei(m->source, AL_BUFFERS_PROCESSED, &processed);
    char chunk[STSV_MUSIC_CHUNK];
    while (processed-- > 0) {
        ALuint buf = 0;
        alSourceUnqueueBuffers(m->source, 1, &buf);
        long got = stsv_music_fill(m, chunk);
        if (got <= 0 && m->looping) {
            ov_pcm_seek(&m->vf, 0);
            got = stsv_music_fill(m, chunk);
        }
        if (got > 0) {
            alBufferData(buf, m->format, chunk, (ALsizei) got, m->rate);
            alSourceQueueBuffers(m->source, 1, &buf);
        }
    }
    /* recover if the source starved but the app still wants it playing */
    if (m->playing) {
        ALint state = 0, queued = 0;
        alGetSourcei(m->source, AL_SOURCE_STATE, &state);
        alGetSourcei(m->source, AL_BUFFERS_QUEUED, &queued);
        if (state != AL_PLAYING && queued > 0) {
            alSourcePlay(m->source);
        } else if (queued == 0) {
            m->playing = 0; /* stream finished, non-looping */
        }
    }
}

void stsv_music_play(int64_t handle) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    m->playing = 1;
    alSourcePlay(m->source);
}

void stsv_music_pause(int64_t handle) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    m->playing = 0;
    alSourcePause(m->source);
}

void stsv_music_stop(int64_t handle) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    m->playing = 0;
    alSourceStop(m->source);
    ov_pcm_seek(&m->vf, 0);
}

int stsv_music_is_playing(int64_t handle) {
    if (!g_ready || handle == 0) return 0;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    ALint state = 0;
    alGetSourcei(m->source, AL_SOURCE_STATE, &state);
    return state == AL_PLAYING ? 1 : 0;
}

void stsv_music_set_volume(int64_t handle, float volume) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    alSourcef(m->source, AL_GAIN, volume < 0.0f ? 0.0f : (volume > 1.0f ? 1.0f : volume));
}

void stsv_music_set_looping(int64_t handle, int loop) {
    if (!g_ready || handle == 0) return;
    ((stsv_music *) (intptr_t) handle)->looping = loop;
}

void stsv_music_free(int64_t handle) {
    if (!g_ready || handle == 0) return;
    stsv_music *m = (stsv_music *) (intptr_t) handle;
    alSourceStop(m->source);
    alDeleteSources(1, &m->source);
    alDeleteBuffers(STSV_MUSIC_BUFFERS, m->buffers);
    ov_clear(&m->vf);
    free(m->ogg);
    free(m);
}
