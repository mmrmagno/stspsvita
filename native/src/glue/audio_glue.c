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

static ALCdevice *g_device;
static ALCcontext *g_context;
static int g_ready;

#define STSV_SOURCE_POOL 30
static ALuint g_sources[STSV_SOURCE_POOL];

/* Software loudness boost (19/10 = 1.9x), applied to decoded PCM with
 * saturation. The Vita speaker is quiet at StS's mix levels, but any AL
 * gain > 1.0 makes this port's fixed-point mixer WRAP samples. */
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
    /* No AL-side gain boost: this AL port's mixer WRAPS instead of clamping
     * whenever gain x sample overflows s16 (listener gain 2.0 = channels at
     * 100% went fully silent; 1.9 broke content-dependently). Loudness is
     * boosted in our decode path instead (stsv_boost_pcm, saturating), so
     * every gain OpenAL ever sees stays in [0, 1]. */
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
 * (SOTE_SFX_UIHover/UIClick) are.wav, not.ogg. Returns AL buffer or 0. */
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

/* Decode a whole.ogg (encoded bytes at data/len) into a new AL buffer. Returns
 * the buffer name (>0) or 0 on failure. Used for short SFX. */
int stsv_sound_load(const void *data, int len) {
    if (!g_ready || data == NULL || len <= 0) {
        return 0;
    }
    if (len > 12 && memcmp(data, "RIFF", 4) == 0) {
        return stsv_load_wav((const unsigned char *) data, len);
    }
    stsv_ogg_mem mem = { (const char *) data, (size_t) len, 0 };
    ov_callbacks cbs = { stsv_ogg_read, stsv_ogg_seek, NULL, stsv_ogg_tell };
    OggVorbis_File vf;
    int ov_err = ov_open_callbacks(&mem, &vf, NULL, 0, cbs);
    if (ov_err != 0) {
        fprintf(stderr, "[audio] ov_open_callbacks failed err=%d (%d bytes, first=%02x%02x%02x%02x)\n",
                ov_err, len,
                (unsigned char) ((const char *) data)[0],
                (unsigned char) ((const char *) data)[1],
                (unsigned char) ((const char *) data)[2],
                (unsigned char) ((const char *) data)[3]);
        return 0;
    }
    vorbis_info *vi = ov_info(&vf, -1);
    ALenum format = (vi->channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
    ALsizei rate = (ALsizei) vi->rate;

    /* Exact-size allocation: the doubling-realloc chain peaked
     * at ~2x the decoded size in one contiguous block, and an 8s stereo SFX
     * (STS_UnlockScreen_v1) hit a fragmented-heap moment and failed silently
     * ([audio] newSound FAILED with no ov_open error). Memory callbacks are
     * seekable, so ov_pcm_total is available; fall back to the grow loop if
     * it is not. */
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
        return 0;
    }
    int bitstream;
    for (;;) {
        if (size + 4096 > cap) {
            cap *= 2;
            char *grown = (char *) realloc(pcm, cap);
            if (grown == NULL) { free(pcm); ov_clear(&vf); return 0; }
            pcm = grown;
        }
        long n = ov_read(&vf, pcm + size, 4096, 0, 2, 1, &bitstream); /* 16-bit LE signed */
        if (n <= 0) break;
        size += (size_t) n;
    }
    ov_clear(&vf);

    stsv_boost_pcm((short *) pcm, size / 2);
    ALuint buffer = 0;
    alGenBuffers(1, &buffer);
    alBufferData(buffer, format, pcm, (ALsizei) size, rate);
    free(pcm);
    return (int) buffer;
}

void stsv_sound_free(int buffer) {
    if (!g_ready || buffer == 0) return;
    ALuint b = (ALuint) buffer;
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
    ALuint src = 0;
    for (int i = 0; i < STSV_SOURCE_POOL; i++) {
        ALint state = 0;
        alGetSourcei(g_sources[i], AL_SOURCE_STATE, &state);
        if (state != AL_PLAYING && state != AL_PAUSED) { src = g_sources[i]; break; }
    }
    if (src == 0) src = g_sources[0]; /* all busy: steal the first */
    alSourceStop(src);
    alSourcei(src, AL_BUFFER, buffer);
    alSourcef(src, AL_GAIN, volume < 0.0f ? 0.0f : (volume > 1.0f ? 1.0f : volume));
    alSourcef(src, AL_PITCH, pitch <= 0.0f ? 1.0f : pitch);
    alSource3f(src, AL_POSITION, pan, 0.0f, 0.0f);
    alSourcei(src, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
    alSourcePlay(src);
    return (int) src;
}

void stsv_sound_stop(int source) {
    if (!g_ready || source == 0) return;
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

void stsv_sound_pause(int source) {
    if (!g_ready || source == 0) return;
    alSourcePause((ALuint) source);
}

void stsv_sound_resume(int source) {
    if (!g_ready || source == 0) return;
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
