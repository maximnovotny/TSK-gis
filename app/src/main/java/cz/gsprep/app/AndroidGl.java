package cz.gsprep.app;

import android.opengl.GLES31;

import java.nio.ByteBuffer;

import cz.gsprep.core.Gl;

/** {@link Gl} backed by android.opengl.GLES31. */
final class AndroidGl implements Gl {
    private final int[] tmp = new int[1];

    @Override public int createShader(int type) { return GLES31.glCreateShader(type); }
    @Override public void shaderSource(int shader, String src) { GLES31.glShaderSource(shader, src); }
    @Override public void compileShader(int shader) { GLES31.glCompileShader(shader); }
    @Override public int getShaderi(int shader, int pname) { GLES31.glGetShaderiv(shader, pname, tmp, 0); return tmp[0]; }
    @Override public String getShaderInfoLog(int shader) { return GLES31.glGetShaderInfoLog(shader); }
    @Override public void deleteShader(int shader) { GLES31.glDeleteShader(shader); }

    @Override public int createProgram() { return GLES31.glCreateProgram(); }
    @Override public void attachShader(int program, int shader) { GLES31.glAttachShader(program, shader); }
    @Override public void linkProgram(int program) { GLES31.glLinkProgram(program); }
    @Override public int getProgrami(int program, int pname) { GLES31.glGetProgramiv(program, pname, tmp, 0); return tmp[0]; }
    @Override public String getProgramInfoLog(int program) { return GLES31.glGetProgramInfoLog(program); }
    @Override public void useProgram(int program) { GLES31.glUseProgram(program); }
    @Override public void deleteProgram(int program) { GLES31.glDeleteProgram(program); }

    @Override public int getUniformLocation(int program, String name) { return GLES31.glGetUniformLocation(program, name); }
    @Override public void uniform1i(int loc, int v) { GLES31.glUniform1i(loc, v); }
    @Override public void uniform2i(int loc, int a, int b) { GLES31.glUniform2i(loc, a, b); }
    @Override public void uniform4i(int loc, int a, int b, int c, int d) { GLES31.glUniform4i(loc, a, b, c, d); }
    @Override public void uniform1f(int loc, float v) { GLES31.glUniform1f(loc, v); }
    @Override public void uniform2f(int loc, float a, float b) { GLES31.glUniform2f(loc, a, b); }

    @Override public int genTexture() { GLES31.glGenTextures(1, tmp, 0); return tmp[0]; }
    @Override public void deleteTexture(int tex) { tmp[0] = tex; GLES31.glDeleteTextures(1, tmp, 0); }
    @Override public void activeTexture(int unit) { GLES31.glActiveTexture(unit); }
    @Override public void bindTexture(int target, int tex) { GLES31.glBindTexture(target, tex); }
    @Override public void texParameteri(int target, int pname, int value) { GLES31.glTexParameteri(target, pname, value); }
    @Override public void texStorage2D(int target, int levels, int fmt, int w, int h) { GLES31.glTexStorage2D(target, levels, fmt, w, h); }
    @Override public void texStorage3D(int target, int levels, int fmt, int w, int h, int d) { GLES31.glTexStorage3D(target, levels, fmt, w, h, d); }
    @Override public void texSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, ByteBuffer data) {
        GLES31.glTexSubImage2D(target, level, x, y, w, h, format, type, data);
    }
    @Override public void bindImageTexture(int unit, int tex, int level, boolean layered, int layer, int access, int format) {
        GLES31.glBindImageTexture(unit, tex, level, layered, layer, access, format);
    }

    @Override public int genBuffer() { GLES31.glGenBuffers(1, tmp, 0); return tmp[0]; }
    @Override public void deleteBuffer(int buf) { tmp[0] = buf; GLES31.glDeleteBuffers(1, tmp, 0); }
    @Override public void bindBuffer(int target, int buf) { GLES31.glBindBuffer(target, buf); }
    @Override public void bufferData(int target, ByteBuffer data, int usage) { GLES31.glBufferData(target, data.remaining(), data, usage); }
    @Override public void bindBufferBase(int target, int index, int buf) { GLES31.glBindBufferBase(target, index, buf); }

    @Override public void dispatchCompute(int x, int y, int z) { GLES31.glDispatchCompute(x, y, z); }
    @Override public void memoryBarrier(int bits) { GLES31.glMemoryBarrier(bits); }
    @Override public void finish() { GLES31.glFinish(); }

    @Override public int genFramebuffer() { GLES31.glGenFramebuffers(1, tmp, 0); return tmp[0]; }
    @Override public void deleteFramebuffer(int fb) { tmp[0] = fb; GLES31.glDeleteFramebuffers(1, tmp, 0); }
    @Override public void bindFramebuffer(int target, int fb) { GLES31.glBindFramebuffer(target, fb); }
    @Override public void framebufferTexture2D(int target, int att, int texTarget, int tex, int level) {
        GLES31.glFramebufferTexture2D(target, att, texTarget, tex, level);
    }
    @Override public int checkFramebufferStatus(int target) { return GLES31.glCheckFramebufferStatus(target); }
    @Override public void pixelStorei(int pname, int value) { GLES31.glPixelStorei(pname, value); }
    @Override public void readPixels(int x, int y, int w, int h, int format, int type, ByteBuffer dst) {
        GLES31.glReadPixels(x, y, w, h, format, type, dst);
    }

    @Override public int getInteger(int pname) { GLES31.glGetIntegerv(pname, tmp, 0); return tmp[0]; }
    @Override public int getError() { return GLES31.glGetError(); }
    @Override public String getString(int name) { return GLES31.glGetString(name); }
}
