package com.stsvita.backend;

import java.nio.ByteBuffer;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.audio.AudioRecorder;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * Audio backend: OpenAL playback + libvorbisfile OGG decode (audio_glue.c).
 * SFX are decoded up front into an AL buffer ({@link VitaSound}); music is
 * still silent (streaming lands next).
 */
@Include("stsvita_glue.h")
public class VitaAudio implements Audio {

    @Import(name = "stsv_sound_load")
    static native int nSoundLoad(Address data, int len);
    @Import(name = "stsv_sound_free")
    static native void nSoundFree(int buffer);
    @Import(name = "stsv_sound_play")
    static native int nSoundPlay(int buffer, float volume, float pitch, float pan, int loop);
    @Import(name = "stsv_sound_stop")
    static native void nSoundStop(int source);
    @Import(name = "stsv_sound_pause")
    static native void nSoundPause(int source);
    @Import(name = "stsv_sound_resume")
    static native void nSoundResume(int source);
    @Import(name = "stsv_sound_set_volume")
    static native void nSoundSetVolume(int source, float volume);
    @Import(name = "stsv_sound_set_pitch")
    static native void nSoundSetPitch(int source, float pitch);
    @Import(name = "stsv_sound_set_looping")
    static native void nSoundSetLooping(int source, int loop);

    @Import(name = "stsv_music_open")
    static native long nMusicOpen(Address data, int len);
    @Import(name = "stsv_music_open_path")
    static native long nMusicOpenPath(Address path, int len);
    @Import(name = "stsv_music_free")
    static native void nMusicFree(long handle);
    @Import(name = "stsv_music_play")
    static native void nMusicPlay(long handle);
    @Import(name = "stsv_music_pause")
    static native void nMusicPause(long handle);
    @Import(name = "stsv_music_stop")
    static native void nMusicStop(long handle);
    @Import(name = "stsv_music_update")
    static native void nMusicUpdate(long handle);
    @Import(name = "stsv_music_is_playing")
    static native int nMusicIsPlaying(long handle);
    @Import(name = "stsv_music_set_volume")
    static native void nMusicSetVolume(long handle, float volume);
    @Import(name = "stsv_music_set_looping")
    static native void nMusicSetLooping(long handle, int loop);

    /** Open streaming music instances, pumped once per frame by {@link #update()}. */
    private static final java.util.ArrayList<VitaMusic> ACTIVE = new java.util.ArrayList<>();

    /** Called each frame from VitaApplication to refill music stream buffers. */
    public static void update() {
        for (int i = 0; i < ACTIVE.size(); i++) {
            VitaMusic m = ACTIVE.get(i);
            if (m.handle != 0) {
                nMusicUpdate(m.handle);
                m.pollCompletion();
            }
        }
    }

    @Override
    public AudioDevice newAudioDevice(int samplingRate, boolean isMono) {
        throw new UnsupportedOperationException("VitaAudio.newAudioDevice");
    }

    @Override
    public AudioRecorder newAudioRecorder(int samplingRate, boolean isMono) {
        throw new UnsupportedOperationException("VitaAudio.newAudioRecorder");
    }

    @Override
    public Sound newSound(FileHandle fileHandle) {
        byte[] ogg = fileHandle.readBytes();
        if (ogg.length == 0) {
            return new VitaSound(0);
        }
        // Copy to an unsafe buffer so its address is stable while the glue
        // decodes it synchronously; release it once decoded.
        ByteBuffer buffer = BufferUtils.newUnsafeByteBuffer(ogg.length);
        BufferUtils.copy(ogg, 0, buffer, ogg.length);
        int handle = nSoundLoad(Buffers.addr(buffer), ogg.length);
        BufferUtils.disposeUnsafeByteBuffer(buffer);
        if (handle == 0) {
            VitaNatives.log("[audio] newSound FAILED " + fileHandle.path()
                    + " (" + ogg.length + " bytes)");
        }
        return new VitaSound(handle);
    }

    @Override
    public Music newMusic(FileHandle file) {
        long t0 = System.nanoTime();
        byte[] path = file.path().getBytes();
        long handle = nMusicOpenPath(Address.ofData(path), path.length);
        long totalMs = (System.nanoTime() - t0) / 1000000L;
        VitaNatives.log("[audio] newMusic " + file.path() + " path-stream total="
                + totalMs + "ms"
                + (handle == 0 ? " OPEN-FAILED" : ""));
        if (handle == 0) {
            return new SilentMusic();
        }
        VitaMusic music = new VitaMusic(handle);
        ACTIVE.add(music);
        return music;
    }

    /** Streamed OGG music backed by a C OpenAL streaming source. */
    private static final class VitaMusic implements Music {
        long handle;
        private boolean looping;
        private float volume = 1f;
        private boolean wasPlaying;
        private OnCompletionListener onCompletion;

        VitaMusic(long handle) {
            this.handle = handle;
        }

        /** Fire the completion callback once when a non-looping stream ends. */
        void pollCompletion() {
            boolean playing = handle != 0 && nMusicIsPlaying(handle) != 0;
            if (wasPlaying && !playing && !looping && onCompletion != null) {
                onCompletion.onCompletion(this);
            }
            wasPlaying = playing;
        }

        @Override
        public void play() {
            if (handle != 0) {
                nMusicPlay(handle);
                wasPlaying = true;
            }
        }

        @Override
        public void pause() {
            if (handle != 0) nMusicPause(handle);
        }

        @Override
        public void stop() {
            if (handle != 0) nMusicStop(handle);
            wasPlaying = false;
        }

        @Override
        public boolean isPlaying() {
            return handle != 0 && nMusicIsPlaying(handle) != 0;
        }

        @Override
        public void setLooping(boolean isLooping) {
            looping = isLooping;
            if (handle != 0) nMusicSetLooping(handle, isLooping ? 1 : 0);
        }

        @Override
        public boolean isLooping() {
            return looping;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
            if (handle != 0) nMusicSetVolume(handle, volume);
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public void setPan(float pan, float volume) {
            setVolume(volume);
        }

        @Override
        public void setPosition(float position) {
        }

        @Override
        public float getPosition() {
            return 0f;
        }

        @Override
        public void dispose() {
            if (handle != 0) {
                ACTIVE.remove(this);
                nMusicFree(handle);
                handle = 0;
            }
        }

        @Override
        public void setOnCompletionListener(OnCompletionListener listener) {
            this.onCompletion = listener;
        }
    }

    /** One decoded SFX buffer; each play() grabs a source from the C pool. */
    private static final class VitaSound implements Sound {
        private final int buffer;
        private long lastId;

        VitaSound(int buffer) {
            this.buffer = buffer;
        }

        private long start(float volume, float pitch, float pan, boolean loop) {
            if (buffer == 0) {
                return -1;
            }
            lastId = nSoundPlay(buffer, volume, pitch, pan, loop ? 1 : 0);
            return lastId;
        }

        @Override
        public long play() {
            return start(1f, 1f, 0f, false);
        }

        @Override
        public long play(float volume) {
            return start(volume, 1f, 0f, false);
        }

        @Override
        public long play(float volume, float pitch, float pan) {
            return start(volume, pitch, pan, false);
        }

        @Override
        public long loop() {
            return start(1f, 1f, 0f, true);
        }

        @Override
        public long loop(float volume) {
            return start(volume, 1f, 0f, true);
        }

        @Override
        public long loop(float volume, float pitch, float pan) {
            return start(volume, pitch, pan, true);
        }

        @Override
        public void stop() {
            if (lastId != 0) nSoundStop((int) lastId);
        }

        @Override
        public void pause() {
            if (lastId != 0) nSoundPause((int) lastId);
        }

        @Override
        public void resume() {
            if (lastId != 0) nSoundResume((int) lastId);
        }

        @Override
        public void dispose() {
            nSoundFree(buffer);
        }

        @Override
        public void stop(long soundId) {
            nSoundStop((int) soundId);
        }

        @Override
        public void pause(long soundId) {
            nSoundPause((int) soundId);
        }

        @Override
        public void resume(long soundId) {
            nSoundResume((int) soundId);
        }

        @Override
        public void setLooping(long soundId, boolean looping) {
            nSoundSetLooping((int) soundId, looping ? 1 : 0);
        }

        @Override
        public void setPitch(long soundId, float pitch) {
            nSoundSetPitch((int) soundId, pitch);
        }

        @Override
        public void setVolume(long soundId, float volume) {
            nSoundSetVolume((int) soundId, volume);
        }

        @Override
        public void setPan(long soundId, float pan, float volume) {
            nSoundSetVolume((int) soundId, volume);
        }
    }

    private static final class SilentMusic implements Music {
        private boolean playing;
        private boolean looping;
        private float volume = 1f;
        private float position;

        @Override
        public void play() {
            playing = true;
        }

        @Override
        public void pause() {
            playing = false;
        }

        @Override
        public void stop() {
            playing = false;
            position = 0f;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void setLooping(boolean isLooping) {
            looping = isLooping;
        }

        @Override
        public boolean isLooping() {
            return looping;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public float getVolume() {
            return volume;
        }

        @Override
        public void setPan(float pan, float volume) {
            this.volume = volume;
        }

        @Override
        public void setPosition(float position) {
            this.position = position;
        }

        @Override
        public float getPosition() {
            return position;
        }

        @Override
        public void dispose() {
        }

        @Override
        public void setOnCompletionListener(OnCompletionListener listener) {
        }
    }
}
