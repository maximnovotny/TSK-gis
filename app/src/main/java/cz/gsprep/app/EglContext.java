package cz.gsprep.app;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES31;

/** Off-screen OpenGL ES 3.1+ context bound to the calling thread (1x1 pbuffer). */
final class EglContext {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    String version = "";
    String renderer = "";

    void create() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            throw new IllegalStateException("EGL nelze inicializovat");
        }
        int[] attr = {
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new IllegalStateException("Zařízení nepodporuje OpenGL ES 3");
        }
        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("Nelze vytvořit GL kontext");
        int[] surfAttr = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttr, 0);
        if (surface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("Nelze vytvořit pbuffer");
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent selhalo");
        }
        version = String.valueOf(GLES31.glGetString(GLES31.GL_VERSION));
        renderer = String.valueOf(GLES31.glGetString(GLES31.GL_RENDERER));
        if (!supportsEs31(version)) {
            release();
            throw new IllegalStateException("Potřebuji OpenGL ES 3.1 (zařízení: " + version + ")");
        }
    }

    static boolean supportsEs31(String v) {
        // "OpenGL ES 3.2 V@..." / "OpenGL ES 3.1 ..."
        int i = v.indexOf("ES ");
        if (i < 0 || i + 6 > v.length()) return false;
        int major = v.charAt(i + 3) - '0';
        int minor = v.charAt(i + 5) - '0';
        return major > 3 || (major == 3 && minor >= 1);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
        }
        surface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }
}
