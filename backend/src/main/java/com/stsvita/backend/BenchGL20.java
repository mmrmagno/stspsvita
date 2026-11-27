package com.stsvita.backend;

import java.nio.Buffer;
import java.nio.FloatBuffer;

public final class BenchGL20 extends VitaGL20 {

    public static final int DRAWS = 0;
    public static final int VERTS = 1;
    public static final int BINDS = 2;
    public static final int BINDS_SAME = 3;
    public static final int BLEND_FUNCS = 4;
    public static final int BLEND_SAME = 5;
    public static final int PROGRAMS = 6;
    public static final int PROGRAMS_SAME = 7;
    public static final int TOGGLES = 8;
    public static final int TEX_UPLOADS = 9;
    public static final int SUB_UPLOADS = 10;
    public static final int SHADER_COMPILES = 11;
    public static final int BUFFER_BYTES = 12;
    public static final int UNIFORMS = 13;
    public static final int COUNT = 14;

    public static final long[] counts = new long[COUNT];
    public static boolean trace;
    public static long drawUs;

    private final int[] boundTex = new int[32];
    private int activeUnit;
    private int blendSrc = -1;
    private int blendDst = -1;
    private int program = -1;

    private void traceDraw(int count) {
        if (trace) {
            VitaNatives.log("[bench-draw] tex=" + boundTex[activeUnit] + " n=" + count + " blend=" + blendSrc + ","
                    + blendDst + " prog=" + program);
        }
    }

    @Override
    public void glDrawElements(int mode, int count, int type, Buffer indices) {
        counts[DRAWS]++;
        counts[VERTS] += count;
        long t0 = VitaNatives.micros();
        super.glDrawElements(mode, count, type, indices);
        drawUs += VitaNatives.micros() - t0;
        traceDraw(count);
    }

    @Override
    public void glDrawElements(int mode, int count, int type, int indices) {
        counts[DRAWS]++;
        counts[VERTS] += count;
        long t0 = VitaNatives.micros();
        super.glDrawElements(mode, count, type, indices);
        drawUs += VitaNatives.micros() - t0;
    }

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        counts[DRAWS]++;
        counts[VERTS] += count;
        long t0 = VitaNatives.micros();
        super.glDrawArrays(mode, first, count);
        drawUs += VitaNatives.micros() - t0;
    }

    @Override
    public void glActiveTexture(int texture) {
        activeUnit = (texture - 0x84C0) & 31;
        super.glActiveTexture(texture);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        counts[BINDS]++;
        if (boundTex[activeUnit] == texture) {
            counts[BINDS_SAME]++;
        }
        boundTex[activeUnit] = texture;
        super.glBindTexture(target, texture);
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        counts[BLEND_FUNCS]++;
        if (sfactor == blendSrc && dfactor == blendDst) {
            counts[BLEND_SAME]++;
        }
        blendSrc = sfactor;
        blendDst = dfactor;
        super.glBlendFunc(sfactor, dfactor);
    }

    @Override
    public void glUseProgram(int p) {
        counts[PROGRAMS]++;
        if (p == program) {
            counts[PROGRAMS_SAME]++;
        }
        program = p;
        super.glUseProgram(p);
    }

    @Override
    public void glEnable(int cap) {
        counts[TOGGLES]++;
        super.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        counts[TOGGLES]++;
        super.glDisable(cap);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border,
            int format, int type, Buffer pixels) {
        counts[TEX_UPLOADS]++;
        super.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    @Override
    public void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height,
            int border, int imageSize, Buffer data) {
        counts[TEX_UPLOADS]++;
        super.glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
            int format, int type, Buffer pixels) {
        counts[SUB_UPLOADS]++;
        super.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    @Override
    public void glCompileShader(int shader) {
        counts[SHADER_COMPILES]++;
        super.glCompileShader(shader);
    }

    @Override
    public void glBufferData(int target, int size, Buffer data, int usage) {
        counts[BUFFER_BYTES] += size;
        super.glBufferData(target, size, data, usage);
    }

    @Override
    public void glBufferSubData(int target, int offset, int size, Buffer data) {
        counts[BUFFER_BYTES] += size;
        super.glBufferSubData(target, offset, size, data);
    }

    @Override
    public void glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value) {
        counts[UNIFORMS]++;
        super.glUniformMatrix4fv(location, count, transpose, value);
    }

    @Override
    public void glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset) {
        counts[UNIFORMS]++;
        super.glUniformMatrix4fv(location, count, transpose, value, offset);
    }

    @Override
    public void glUniform1i(int location, int x) {
        counts[UNIFORMS]++;
        super.glUniform1i(location, x);
    }
}
