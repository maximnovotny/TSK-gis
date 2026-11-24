package cz.gsprep.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Whole image pipeline on the GPU (OpenGL ES 3.1 compute):
 * pre (denoise + tone) -> upscale (Real-ESRGAN tiles or bicubic) -> sharpen -> readback.
 * Must be created and used on the thread that owns the GL context.
 */
public final class GpuEngine {
    public static final int DEFAULT_TILE = 256;
    public static final int DEFAULT_HALO = 16;
    /** Full-image passes are split into bands of this many rows (keeps each GPU submission short). */
    private static final int BAND = 512;

    private final Gl gl;
    private final Model model;
    private final int tile, halo;
    public final int maxTextureSize;

    private int progPre, progResample, progConv0, progConv, progAiOut, progFinal;
    private int ssboW, ssboP, actA, actB, fbo;
    private final HashMap<String, Integer> uniforms = new HashMap<String, Integer>();

    public GpuEngine(Gl gl, Model model) {
        this(gl, model, DEFAULT_TILE, DEFAULT_HALO);
    }

    public GpuEngine(Gl gl, Model model, int tile, int halo) {
        this.gl = gl;
        this.model = model;
        this.tile = tile;
        this.halo = halo;
        if (tile - 2 * halo < 16) throw new IllegalArgumentException("tile too small");
        maxTextureSize = gl.getInteger(Gl.GL_MAX_TEXTURE_SIZE);
        progPre = program(Shaders.PRE, "pre");
        progResample = program(Shaders.RESAMPLE, "resample");
        progFinal = program(Shaders.FINAL, "final");
        if (model != null) {
            progConv0 = program(Shaders.conv(true), "conv0");
            progConv = program(Shaders.conv(false), "conv");
            progAiOut = program(Shaders.AI_OUT, "aiout");
            model.weights.position(0);
            model.params.position(0);
            ssboW = gl.genBuffer();
            gl.bindBuffer(Gl.GL_SHADER_STORAGE_BUFFER, ssboW);
            gl.bufferData(Gl.GL_SHADER_STORAGE_BUFFER, model.weights, Gl.GL_STATIC_DRAW);
            ssboP = gl.genBuffer();
            gl.bindBuffer(Gl.GL_SHADER_STORAGE_BUFFER, ssboP);
            gl.bufferData(Gl.GL_SHADER_STORAGE_BUFFER, model.params, Gl.GL_STATIC_DRAW);
            gl.bindBuffer(Gl.GL_SHADER_STORAGE_BUFFER, 0);
            int maxLayers = 1;
            for (int i = 0; i < model.layers; i++) maxLayers = Math.max(maxLayers, Model.groups(model.cout[i]));
            actA = newArray(tile, tile, maxLayers);
            actB = newArray(tile, tile, maxLayers);
        }
        fbo = gl.genFramebuffer();
        checkError("init");
    }

    public boolean hasAi() {
        return model != null;
    }

    public void release() {
        int[] progs = {progPre, progResample, progConv0, progConv, progAiOut, progFinal};
        for (int p : progs) if (p != 0) gl.deleteProgram(p);
        if (ssboW != 0) gl.deleteBuffer(ssboW);
        if (ssboP != 0) gl.deleteBuffer(ssboP);
        if (actA != 0) gl.deleteTexture(actA);
        if (actB != 0) gl.deleteTexture(actB);
        if (fbo != 0) gl.deleteFramebuffer(fbo);
        progPre = progResample = progConv0 = progConv = progAiOut = progFinal = 0;
        ssboW = ssboP = actA = actB = fbo = 0;
    }

    /**
     * Process one image.
     * @param rgba  w*h*4 bytes RGBA (Android Bitmap.copyPixelsToBuffer layout), position 0
     */
    public void process(ByteBuffer rgba, int w, int h, Params p, int dstW, int dstH,
                        PixelSink sink, Progress prog) throws Progress.CancelledException {
        if (Math.max(w, h) > maxTextureSize || Math.max(dstW, dstH) > maxTextureSize) {
            throw new IllegalArgumentException("Obrázek je pro GPU příliš velký (max "
                    + maxTextureSize + " px)");
        }
        boolean useAi = p.ai && model != null;
        rgba.position(0);
        int src = newTex2D(w, h);
        gl.pixelStorei(Gl.GL_UNPACK_ALIGNMENT, 4);
        gl.texSubImage2D(Gl.GL_TEXTURE_2D, 0, 0, 0, w, h, Gl.GL_RGBA, Gl.GL_UNSIGNED_BYTE, rgba);
        int pre = newTex2D(w, h);
        int out = 0, fin = 0;
        try {
            runPre(src, pre, w, h, p);
            gl.deleteTexture(src);
            src = 0;
            prog.update(0.03f);
            checkCancel(prog);

            out = newTex2D(dstW, dstH);
            if (useAi) {
                runAi(pre, w, h, out, dstW, dstH, prog);
            } else {
                runResample(pre, w, h, out, dstW, dstH);
            }
            gl.deleteTexture(pre);
            pre = 0;
            checkCancel(prog);

            fin = newTex2D(dstW, dstH);
            runFinal(out, fin, dstW, dstH, p);
            gl.deleteTexture(out);
            out = 0;
            prog.update(0.97f);
            readback(fin, dstW, dstH, sink);
            checkError("process");
            prog.update(1f);
        } finally {
            if (src != 0) gl.deleteTexture(src);
            if (pre != 0) gl.deleteTexture(pre);
            if (out != 0) gl.deleteTexture(out);
            if (fin != 0) gl.deleteTexture(fin);
        }
    }

    // ---------------------------------------------------------------- passes

    private void runPre(int src, int dst, int w, int h, Params p) {
        int pr = progPre;
        gl.useProgram(pr);
        bindSampler(0, Gl.GL_TEXTURE_2D, src);
        gl.bindImageTexture(0, dst, 0, false, 0, Gl.GL_WRITE_ONLY, Gl.GL_RGBA8);
        gl.uniform2i(u(pr, "uSize"), w, h);
        gl.uniform2f(u(pr, "uLevels"), p.levelsLo, p.levelsHi);
        gl.uniform1f(u(pr, "uExpo"), (float) Math.pow(2.0, p.exposureEv));
        gl.uniform1f(u(pr, "uContrast"), p.contrast);
        gl.uniform1f(u(pr, "uShadows"), p.shadows);
        gl.uniform1f(u(pr, "uHighlights"), p.highlights);
        gl.uniform1f(u(pr, "uSat"), p.saturation);
        gl.uniform1f(u(pr, "uDenoise"), p.denoise <= 0f ? 0f : 0.015f + 0.12f * p.denoise);
        bands(pr, w, h);
        gl.memoryBarrier(Gl.GL_ALL_BARRIER_BITS);
    }

    private void runResample(int src, int sw, int sh, int dst, int dw, int dh) {
        int pr = progResample;
        gl.useProgram(pr);
        bindSampler(0, Gl.GL_TEXTURE_2D, src);
        gl.bindImageTexture(0, dst, 0, false, 0, Gl.GL_WRITE_ONLY, Gl.GL_RGBA8);
        gl.uniform2i(u(pr, "uSrcSize"), sw, sh);
        gl.uniform2i(u(pr, "uDstSize"), dw, dh);
        bands(pr, dw, dh);
        gl.memoryBarrier(Gl.GL_ALL_BARRIER_BITS);
    }

    private void runFinal(int src, int dst, int w, int h, Params p) {
        int pr = progFinal;
        gl.useProgram(pr);
        bindSampler(0, Gl.GL_TEXTURE_2D, src);
        gl.bindImageTexture(0, dst, 0, false, 0, Gl.GL_WRITE_ONLY, Gl.GL_RGBA8);
        float sigma = Math.max(0.3f, p.sharpenRadius);
        int radius = p.sharpenAmount > 0f ? Math.min(8, (int) Math.ceil(2.5f * sigma)) : 0;
        gl.uniform2i(u(pr, "uSize"), w, h);
        gl.uniform1f(u(pr, "uAmount"), p.sharpenAmount);
        gl.uniform1f(u(pr, "uSigma"), sigma);
        gl.uniform1i(u(pr, "uRadius"), radius);
        gl.uniform1f(u(pr, "uThreshold"), p.sharpenThreshold);
        gl.uniform1i(u(pr, "uSwapRB"), 1);
        bands(pr, w, h);
        gl.memoryBarrier(Gl.GL_ALL_BARRIER_BITS);
    }

    /** Dispatch a 16x8 full-image pass in horizontal bands (uniform uY0). */
    private void bands(int pr, int w, int h) {
        int locY = u(pr, "uY0");
        for (int y0 = 0; y0 < h; y0 += BAND) {
            int rows = Math.min(BAND, h - y0);
            gl.uniform1i(locY, y0);
            gl.dispatchCompute((w + 15) / 16, (rows + 7) / 8, 1);
            if (h > BAND) gl.finish();
        }
    }

    private void runAi(int pre, int w, int h, int out, int dstW, int dstH, Progress prog)
            throws Progress.CancelledException {
        int core = tile - 2 * halo;
        int nx = (w + core - 1) / core;
        int ny = (h + core - 1) / core;
        int total = nx * ny;
        int done = 0;
        float inv4x = 4f * w / dstW;
        float inv4y = 4f * h / dstH;
        for (int ty = 0; ty < ny; ty++) {
            for (int tx = 0; tx < nx; tx++) {
                int cx0 = tx * core, cx1 = Math.min(w, cx0 + core);
                int cy0 = ty * core, cy1 = Math.min(h, cy0 + core);
                int x0 = Math.max(0, cx0 - halo), x1 = Math.min(w, cx1 + halo);
                int y0 = Math.max(0, cy0 - halo), y1 = Math.min(h, cy1 + halo);
                int tw = x1 - x0, th = y1 - y0;

                int last = runNet(pre, x0, y0, tw, th);

                int dx0 = mapEdge(cx0, w, dstW), dx1 = mapEdge(cx1, w, dstW);
                int dy0 = mapEdge(cy0, h, dstH), dy1 = mapEdge(cy1, h, dstH);
                if (dx1 > dx0 && dy1 > dy0) {
                    int pr = progAiOut;
                    gl.useProgram(pr);
                    bindSampler(0, Gl.GL_TEXTURE_2D_ARRAY, last);
                    bindSampler(1, Gl.GL_TEXTURE_2D, pre);
                    gl.bindImageTexture(0, out, 0, false, 0, Gl.GL_WRITE_ONLY, Gl.GL_RGBA8);
                    gl.uniform2i(u(pr, "uOrigin"), x0, y0);
                    gl.uniform2i(u(pr, "uTile"), tw, th);
                    gl.uniform4i(u(pr, "uRect"), dx0, dy0, dx1, dy1);
                    gl.uniform2f(u(pr, "uInvScale4"), inv4x, inv4y);
                    gl.dispatchCompute((dx1 - dx0 + 15) / 16, (dy1 - dy0 + 7) / 8, 1);
                    gl.memoryBarrier(Gl.GL_ALL_BARRIER_BITS);
                }
                gl.finish();
                done++;
                prog.update(0.03f + 0.92f * done / total);
                checkCancel(prog);
            }
        }
        checkError("ai");
    }

    /** Runs all conv layers on one tile, returns the texture array holding the last layer. */
    private int runNet(int pre, int x0, int y0, int tw, int th) {
        int gx = (tw + 15) / 16, gy = (th + 15) / 16; // 8x8 threads, 2x2 px each
        gl.bindBufferBase(Gl.GL_SHADER_STORAGE_BUFFER, 0, ssboW);
        gl.bindBufferBase(Gl.GL_SHADER_STORAGE_BUFFER, 1, ssboP);
        int srcArr = 0, dstArr = actA;
        for (int l = 0; l < model.layers; l++) {
            boolean first = l == 0;
            int pr = first ? progConv0 : progConv;
            gl.useProgram(pr);
            if (first) {
                bindSampler(0, Gl.GL_TEXTURE_2D, pre);
                gl.uniform2i(u(pr, "uOrigin"), x0, y0);
            } else {
                bindSampler(0, Gl.GL_TEXTURE_2D_ARRAY, srcArr);
            }
            gl.bindImageTexture(0, dstArr, 0, true, 0, Gl.GL_WRITE_ONLY, Gl.GL_RGBA16F);
            gl.uniform2i(u(pr, "uTile"), tw, th);
            gl.uniform1i(u(pr, "uCinG"), Model.groups(model.cin[l]));
            gl.uniform1i(u(pr, "uWOff"), model.wOff[l]);
            gl.uniform1i(u(pr, "uBOff"), model.bOff[l]);
            gl.uniform1i(u(pr, "uSOff"), model.sOff[l]);
            gl.uniform1i(u(pr, "uPrelu"), model.prelu[l]);
            gl.dispatchCompute(gx, gy, Model.groups(model.cout[l]));
            gl.memoryBarrier(Gl.GL_TEXTURE_FETCH_BARRIER_BIT | Gl.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            srcArr = dstArr;
            dstArr = (dstArr == actA) ? actB : actA;
        }
        return srcArr;
    }

    /** Input edge coordinate -> output edge coordinate (exact at 0 and at the image size). */
    static int mapEdge(int c, int srcLen, int dstLen) {
        return (int) ((2L * c * dstLen + srcLen) / (2L * srcLen));
    }

    private void readback(int tex, int w, int h, PixelSink sink) {
        gl.bindFramebuffer(Gl.GL_FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(Gl.GL_FRAMEBUFFER, Gl.GL_COLOR_ATTACHMENT0, Gl.GL_TEXTURE_2D, tex, 0);
        int st = gl.checkFramebufferStatus(Gl.GL_FRAMEBUFFER);
        if (st != Gl.GL_FRAMEBUFFER_COMPLETE) {
            gl.bindFramebuffer(Gl.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("Framebuffer neúplný: 0x" + Integer.toHexString(st));
        }
        gl.pixelStorei(Gl.GL_PACK_ALIGNMENT, 4);
        int rowsPer = Math.max(1, Math.min(h, (4 << 20) / (w * 4)));
        ByteBuffer buf = ByteBuffer.allocateDirect(w * rowsPer * 4).order(ByteOrder.LITTLE_ENDIAN);
        int[] ints = new int[w * rowsPer];
        for (int y = 0; y < h; y += rowsPer) {
            int rows = Math.min(rowsPer, h - y);
            buf.clear();
            gl.readPixels(0, y, w, rows, Gl.GL_RGBA, Gl.GL_UNSIGNED_BYTE, buf);
            buf.position(0);
            buf.asIntBuffer().get(ints, 0, w * rows);
            sink.strip(y, rows, w, ints);
        }
        gl.framebufferTexture2D(Gl.GL_FRAMEBUFFER, Gl.GL_COLOR_ATTACHMENT0, Gl.GL_TEXTURE_2D, 0, 0);
        gl.bindFramebuffer(Gl.GL_FRAMEBUFFER, 0);
    }

    // ---------------------------------------------------------------- helpers

    private static void checkCancel(Progress prog) throws Progress.CancelledException {
        if (prog.isCancelled()) throw new Progress.CancelledException();
    }

    private void bindSampler(int unit, int target, int tex) {
        gl.activeTexture(Gl.GL_TEXTURE0 + unit);
        gl.bindTexture(target, tex);
    }

    private int newTex2D(int w, int h) {
        int t = gl.genTexture();
        gl.bindTexture(Gl.GL_TEXTURE_2D, t);
        gl.texStorage2D(Gl.GL_TEXTURE_2D, 1, Gl.GL_RGBA8, w, h);
        setNearest(Gl.GL_TEXTURE_2D);
        int err = gl.getError();
        if (err != Gl.GL_NO_ERROR) {
            gl.deleteTexture(t);
            throw new RuntimeException("Nedostatek paměti GPU pro " + w + "x" + h
                    + " (GL 0x" + Integer.toHexString(err) + ")");
        }
        return t;
    }

    private int newArray(int w, int h, int layers) {
        int t = gl.genTexture();
        gl.bindTexture(Gl.GL_TEXTURE_2D_ARRAY, t);
        gl.texStorage3D(Gl.GL_TEXTURE_2D_ARRAY, 1, Gl.GL_RGBA16F, w, h, layers);
        setNearest(Gl.GL_TEXTURE_2D_ARRAY);
        return t;
    }

    private void setNearest(int target) {
        gl.texParameteri(target, Gl.GL_TEXTURE_MIN_FILTER, Gl.GL_NEAREST);
        gl.texParameteri(target, Gl.GL_TEXTURE_MAG_FILTER, Gl.GL_NEAREST);
        gl.texParameteri(target, Gl.GL_TEXTURE_WRAP_S, Gl.GL_CLAMP_TO_EDGE);
        gl.texParameteri(target, Gl.GL_TEXTURE_WRAP_T, Gl.GL_CLAMP_TO_EDGE);
    }

    private int u(int program, String name) {
        String key = program + ":" + name;
        Integer v = uniforms.get(key);
        if (v == null) {
            v = gl.getUniformLocation(program, name);
            uniforms.put(key, v);
        }
        return v;
    }

    private int program(String src, String name) {
        int sh = gl.createShader(Gl.GL_COMPUTE_SHADER);
        gl.shaderSource(sh, src);
        gl.compileShader(sh);
        if (gl.getShaderi(sh, Gl.GL_COMPILE_STATUS) == 0) {
            String log = gl.getShaderInfoLog(sh);
            gl.deleteShader(sh);
            throw new RuntimeException("Shader " + name + " se nepřeložil: " + log);
        }
        int pr = gl.createProgram();
        gl.attachShader(pr, sh);
        gl.linkProgram(pr);
        gl.deleteShader(sh);
        if (gl.getProgrami(pr, Gl.GL_LINK_STATUS) == 0) {
            String log = gl.getProgramInfoLog(pr);
            gl.deleteProgram(pr);
            throw new RuntimeException("Program " + name + " nelze slinkovat: " + log);
        }
        return pr;
    }

    private void checkError(String where) {
        int e = gl.getError();
        if (e != Gl.GL_NO_ERROR) {
            throw new RuntimeException("Chyba GPU (" + where + "): 0x" + Integer.toHexString(e));
        }
    }
}
