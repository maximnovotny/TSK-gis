package cz.gsprep.core;

import java.nio.ByteBuffer;

/**
 * Minimal OpenGL ES 3.1 surface used by {@link GpuEngine}.
 * On Android it is backed by android.opengl.GLES31, in desktop tests by Mesa (llvmpipe).
 */
public interface Gl {
    int GL_COMPUTE_SHADER = 0x91B9;
    int GL_COMPILE_STATUS = 0x8B81;
    int GL_LINK_STATUS = 0x8B82;
    int GL_TEXTURE_2D = 0x0DE1;
    int GL_TEXTURE_2D_ARRAY = 0x8C1A;
    int GL_TEXTURE0 = 0x84C0;
    int GL_TEXTURE_MIN_FILTER = 0x2801;
    int GL_TEXTURE_MAG_FILTER = 0x2800;
    int GL_TEXTURE_WRAP_S = 0x2802;
    int GL_TEXTURE_WRAP_T = 0x2803;
    int GL_NEAREST = 0x2600;
    int GL_CLAMP_TO_EDGE = 0x812F;
    int GL_RGBA8 = 0x8058;
    int GL_RGBA16F = 0x881A;
    int GL_RGBA = 0x1908;
    int GL_UNSIGNED_BYTE = 0x1401;
    int GL_WRITE_ONLY = 0x88B9;
    int GL_SHADER_STORAGE_BUFFER = 0x90D2;
    int GL_STATIC_DRAW = 0x88E4;
    int GL_FRAMEBUFFER = 0x8D40;
    int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    int GL_PACK_ALIGNMENT = 0x0D05;
    int GL_UNPACK_ALIGNMENT = 0x0CF5;
    int GL_MAX_TEXTURE_SIZE = 0x0D33;
    int GL_MAX_ARRAY_TEXTURE_LAYERS = 0x88FF;
    int GL_VERSION = 0x1F02;
    int GL_RENDERER = 0x1F01;
    int GL_NO_ERROR = 0;
    int GL_TEXTURE_FETCH_BARRIER_BIT = 0x00000008;
    int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020;
    int GL_ALL_BARRIER_BITS = 0xFFFFFFFF;

    int createShader(int type);
    void shaderSource(int shader, String src);
    void compileShader(int shader);
    int getShaderi(int shader, int pname);
    String getShaderInfoLog(int shader);
    void deleteShader(int shader);

    int createProgram();
    void attachShader(int program, int shader);
    void linkProgram(int program);
    int getProgrami(int program, int pname);
    String getProgramInfoLog(int program);
    void useProgram(int program);
    void deleteProgram(int program);

    int getUniformLocation(int program, String name);
    void uniform1i(int loc, int v);
    void uniform2i(int loc, int a, int b);
    void uniform4i(int loc, int a, int b, int c, int d);
    void uniform1f(int loc, float v);
    void uniform2f(int loc, float a, float b);

    int genTexture();
    void deleteTexture(int tex);
    void activeTexture(int unit);
    void bindTexture(int target, int tex);
    void texParameteri(int target, int pname, int value);
    void texStorage2D(int target, int levels, int internalFormat, int w, int h);
    void texStorage3D(int target, int levels, int internalFormat, int w, int h, int depth);
    void texSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, ByteBuffer data);
    void bindImageTexture(int unit, int tex, int level, boolean layered, int layer, int access, int format);

    int genBuffer();
    void deleteBuffer(int buf);
    void bindBuffer(int target, int buf);
    void bufferData(int target, ByteBuffer data, int usage);
    void bindBufferBase(int target, int index, int buf);

    void dispatchCompute(int x, int y, int z);
    void memoryBarrier(int bits);
    void finish();

    int genFramebuffer();
    void deleteFramebuffer(int fb);
    void bindFramebuffer(int target, int fb);
    void framebufferTexture2D(int target, int attachment, int texTarget, int tex, int level);
    int checkFramebufferStatus(int target);
    void pixelStorei(int pname, int value);
    void readPixels(int x, int y, int w, int h, int format, int type, ByteBuffer dst);

    int getInteger(int pname);
    int getError();
    String getString(int name);
}
