package cz.gsprep.core;

/** GLSL ES 3.10 compute shaders. Texture row 0 = top image row everywhere. */
final class Shaders {
    private Shaders() {}

    static final String HEADER =
            "#version 310 es\n"
            + "precision highp float;\n"
            + "precision highp int;\n"
            + "precision highp sampler2D;\n"
            + "precision highp sampler2DArray;\n";

    /** Denoise (bilateral) + uniform tone adjustments. src: RGBA8 input, dst: RGBA8. */
    static final String PRE = HEADER
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "uniform int uY0;\n"
            + "layout(binding = 0) uniform sampler2D uSrc;\n"
            + "layout(rgba8, binding = 0) writeonly uniform highp image2D uDst;\n"
            + "uniform ivec2 uSize;\n"
            + "uniform vec2 uLevels;\n"      // black, white (linked for all channels)
            + "uniform float uExpo;\n"       // linear gain (2^EV)
            + "uniform float uContrast;\n"   // -1..1
            + "uniform float uShadows;\n"    // 0..1
            + "uniform float uHighlights;\n" // 0..1
            + "uniform float uSat;\n"        // -1..1
            + "uniform float uDenoise;\n"    // 0 = off, else range sigma
            + "const vec3 LW = vec3(0.2126, 0.7152, 0.0722);\n"
            + "vec3 fetch(ivec2 p) { return texelFetch(uSrc, clamp(p, ivec2(0), uSize - 1), 0).rgb; }\n"
            + "void main() {\n"
            + "  ivec2 p = ivec2(gl_GlobalInvocationID.xy) + ivec2(0, uY0);\n"
            + "  if (p.x >= uSize.x || p.y >= uSize.y) return;\n"
            + "  vec3 c = fetch(p);\n"
            + "  if (uDenoise > 0.0) {\n"
            + "    vec3 acc = vec3(0.0); float ws = 0.0;\n"
            + "    float inv2r = 1.0 / (2.0 * uDenoise * uDenoise);\n"
            + "    for (int j = -2; j <= 2; j++) {\n"
            + "      for (int i = -2; i <= 2; i++) {\n"
            + "        vec3 t = fetch(p + ivec2(i, j));\n"
            + "        vec3 dc = t - c;\n"
            + "        float w = exp(-float(i * i + j * j) * 0.2222 - dot(dc, dc) * inv2r);\n"
            + "        acc += t * w; ws += w;\n"
            + "      }\n"
            + "    }\n"
            + "    c = acc / ws;\n"
            + "  }\n"
            + "  c = clamp((c - uLevels.x) / max(uLevels.y - uLevels.x, 1e-4), 0.0, 1.0);\n"
            + "  if (uExpo != 1.0) {\n"
            + "    c = pow(clamp(pow(c, vec3(2.2)) * uExpo, 0.0, 1.0), vec3(1.0 / 2.2));\n"
            + "  }\n"
            + "  if (uShadows > 0.0 || uHighlights > 0.0) {\n"
            + "    float y = dot(c, LW);\n"
            + "    float y2 = y + uShadows * 0.8 * y * (1.0 - y) * (1.0 - y) - uHighlights * 0.8 * y * y * (1.0 - y);\n"
            + "    c = (y > 1e-4) ? c * (y2 / y) : c;\n"
            + "  }\n"
            + "  if (uContrast != 0.0) c = (c - 0.5) * (1.0 + uContrast) + 0.5;\n"
            + "  if (uSat != 0.0) { float l = dot(c, LW); c = mix(vec3(l), c, 1.0 + uSat); }\n"
            + "  imageStore(uDst, p, vec4(clamp(c, 0.0, 1.0), 1.0));\n"
            + "}\n";

    /** Classic resampling: Catmull-Rom bicubic when enlarging, area average when shrinking. */
    static final String RESAMPLE = HEADER
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "uniform int uY0;\n"
            + "layout(binding = 0) uniform sampler2D uSrc;\n"
            + "layout(rgba8, binding = 0) writeonly uniform highp image2D uDst;\n"
            + "uniform ivec2 uSrcSize;\n"
            + "uniform ivec2 uDstSize;\n"
            + "vec3 fetch(ivec2 p) { return texelFetch(uSrc, clamp(p, ivec2(0), uSrcSize - 1), 0).rgb; }\n"
            + "vec4 cr(float f) {\n"
            + "  return vec4(f * (-0.5 + f * (1.0 - 0.5 * f)), 1.0 + f * f * (-2.5 + 1.5 * f),\n"
            + "              f * (0.5 + f * (2.0 - 1.5 * f)), f * f * (-0.5 + 0.5 * f));\n"
            + "}\n"
            + "void main() {\n"
            + "  ivec2 d = ivec2(gl_GlobalInvocationID.xy) + ivec2(0, uY0);\n"
            + "  if (d.x >= uDstSize.x || d.y >= uDstSize.y) return;\n"
            + "  vec2 sc = vec2(uDstSize) / vec2(uSrcSize);\n"
            + "  vec3 c;\n"
            + "  if (uDstSize == uSrcSize) {\n"
            + "    c = fetch(d);\n"
            + "  } else if (sc.x >= 1.0 && sc.y >= 1.0) {\n"
            + "    vec2 s = (vec2(d) + 0.5) / sc - 0.5;\n"
            + "    vec2 fl = floor(s); vec2 f = s - fl; ivec2 i0 = ivec2(fl);\n"
            + "    vec4 wx = cr(f.x); vec4 wy = cr(f.y);\n"
            + "    c = vec3(0.0);\n"
            + "    for (int j = 0; j < 4; j++) {\n"
            + "      vec3 row = vec3(0.0);\n"
            + "      for (int i = 0; i < 4; i++) row += fetch(i0 + ivec2(i - 1, j - 1)) * wx[i];\n"
            + "      c += row * wy[j];\n"
            + "    }\n"
            + "  } else {\n"
            + "    vec2 f0 = vec2(d) / sc; vec2 f1 = vec2(d + 1) / sc;\n"
            + "    ivec2 a = ivec2(floor(f0)); ivec2 b = ivec2(ceil(f1)) - 1;\n"
            + "    c = vec3(0.0); float ws = 0.0;\n"
            + "    for (int y = a.y; y <= b.y; y++) {\n"
            + "      float wy = min(f1.y, float(y + 1)) - max(f0.y, float(y));\n"
            + "      if (wy <= 0.0) continue;\n"
            + "      for (int x = a.x; x <= b.x; x++) {\n"
            + "        float wx = min(f1.x, float(x + 1)) - max(f0.x, float(x));\n"
            + "        if (wx <= 0.0) continue;\n"
            + "        c += fetch(ivec2(x, y)) * (wx * wy); ws += wx * wy;\n"
            + "      }\n"
            + "    }\n"
            + "    c /= max(ws, 1e-6);\n"
            + "  }\n"
            + "  imageStore(uDst, d, vec4(clamp(c, 0.0, 1.0), 1.0));\n"
            + "}\n";

    /**
     * 3x3 convolution (+bias, optional PReLU) for SRVGGNetCompact. Each invocation computes a
     * 2x2 pixel block for 4 output channels (one RGBA16F layer). Zero padding outside the tile.
     */
    static String conv(boolean first) {
        return HEADER
                + "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n"
                + "layout(std430, binding = 0) readonly buffer Weights { mat4 w[]; };\n"
                + "layout(std430, binding = 1) readonly buffer Params { vec4 prm[]; };\n"
                + (first
                    ? "layout(binding = 0) uniform sampler2D uSrc;\n"
                    : "layout(binding = 0) uniform sampler2DArray uSrc;\n")
                + "layout(rgba16f, binding = 0) writeonly uniform highp image2DArray uDst;\n"
                + "uniform ivec2 uTile;\n"     // tile width, height
                + "uniform ivec2 uOrigin;\n"   // tile origin in the image (first layer only)
                + "uniform int uCinG;\n"
                + "uniform int uWOff;\n"
                + "uniform int uBOff;\n"
                + "uniform int uSOff;\n"
                + "uniform int uPrelu;\n"
                + "vec4 ld(ivec2 q, int g) {\n"
                + "  if (q.x < 0 || q.y < 0 || q.x >= uTile.x || q.y >= uTile.y) return vec4(0.0);\n"
                + (first
                    ? "  return vec4(texelFetch(uSrc, uOrigin + q, 0).rgb, 0.0);\n"
                    : "  return texelFetch(uSrc, ivec3(q, g), 0);\n")
                + "}\n"
                + "void main() {\n"
                + "  ivec2 base = ivec2(gl_GlobalInvocationID.xy) * 2;\n"
                + "  int og = int(gl_GlobalInvocationID.z);\n"
                + "  if (base.x >= uTile.x || base.y >= uTile.y) return;\n"
                + "  vec4 b = prm[uBOff + og];\n"
                + "  vec4 a00 = b; vec4 a10 = b; vec4 a01 = b; vec4 a11 = b;\n"
                + "  for (int ig = 0; ig < uCinG; ig++) {\n"
                + "    vec4 v[16];\n"
                + "    for (int j = 0; j < 4; j++)\n"
                + "      for (int i = 0; i < 4; i++)\n"
                + "        v[j * 4 + i] = ld(base + ivec2(i - 1, j - 1), ig);\n"
                + "    int wb = uWOff + (og * uCinG + ig) * 9;\n"
                + "    for (int ky = 0; ky < 3; ky++) {\n"
                + "      for (int kx = 0; kx < 3; kx++) {\n"
                + "        mat4 m = w[wb + ky * 3 + kx];\n"
                + "        int k = ky * 4 + kx;\n"
                + "        a00 += m * v[k];\n"
                + "        a10 += m * v[k + 1];\n"
                + "        a01 += m * v[k + 4];\n"
                + "        a11 += m * v[k + 5];\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "  if (uPrelu != 0) {\n"
                + "    vec4 s = prm[uSOff + og];\n"
                + "    a00 = max(a00, 0.0) + s * min(a00, 0.0);\n"
                + "    a10 = max(a10, 0.0) + s * min(a10, 0.0);\n"
                + "    a01 = max(a01, 0.0) + s * min(a01, 0.0);\n"
                + "    a11 = max(a11, 0.0) + s * min(a11, 0.0);\n"
                + "  }\n"
                + "  imageStore(uDst, ivec3(base, og), a00);\n"
                + "  if (base.x + 1 < uTile.x) imageStore(uDst, ivec3(base + ivec2(1, 0), og), a10);\n"
                + "  if (base.y + 1 < uTile.y) {\n"
                + "    imageStore(uDst, ivec3(base + ivec2(0, 1), og), a01);\n"
                + "    if (base.x + 1 < uTile.x) imageStore(uDst, ivec3(base + ivec2(1, 1), og), a11);\n"
                + "  }\n"
                + "}\n";
    }

    /**
     * PixelShuffle(4) + nearest-upsampled input residual, then area-resampled from the virtual 4x
     * grid straight into the output image (so 2x / 1x / any scale <= 4 costs no extra memory).
     */
    static final String AI_OUT = HEADER
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "layout(binding = 0) uniform sampler2DArray uConv;\n"
            + "layout(binding = 1) uniform sampler2D uSrc;\n"
            + "layout(rgba8, binding = 0) writeonly uniform highp image2D uDst;\n"
            + "uniform ivec2 uOrigin;\n"   // tile origin in input image
            + "uniform ivec2 uTile;\n"     // tile size
            + "uniform ivec4 uRect;\n"     // destination rect x0,y0,x1,y1 (exclusive)
            + "uniform vec2 uInvScale4;\n" // 4 * src / dst per axis
            + "vec3 px4(int X, int Y) {\n"
            + "  ivec2 s = ivec2(X >> 2, Y >> 2);\n"
            + "  ivec2 l = clamp(s - uOrigin, ivec2(0), uTile - 1);\n"
            + "  int sub = (Y & 3) * 4 + (X & 3);\n"
            + "  int ly = sub >> 2; int lc = sub & 3;\n"
            + "  float r = texelFetch(uConv, ivec3(l, ly), 0)[lc];\n"
            + "  float g = texelFetch(uConv, ivec3(l, ly + 4), 0)[lc];\n"
            + "  float b = texelFetch(uConv, ivec3(l, ly + 8), 0)[lc];\n"
            + "  vec3 inp = texelFetch(uSrc, uOrigin + l, 0).rgb;\n"
            + "  return clamp(vec3(r, g, b) + inp, 0.0, 1.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  ivec2 d = uRect.xy + ivec2(gl_GlobalInvocationID.xy);\n"
            + "  if (d.x >= uRect.z || d.y >= uRect.w) return;\n"
            + "  vec2 f0 = vec2(d) * uInvScale4; vec2 f1 = vec2(d + 1) * uInvScale4;\n"
            + "  ivec2 a = ivec2(floor(f0 + 1e-4)); ivec2 b = ivec2(ceil(f1 - 1e-4)) - 1;\n"
            + "  vec3 c = vec3(0.0); float ws = 0.0;\n"
            + "  for (int y = a.y; y <= b.y; y++) {\n"
            + "    float wy = min(f1.y, float(y + 1)) - max(f0.y, float(y));\n"
            + "    if (wy <= 0.0) continue;\n"
            + "    for (int x = a.x; x <= b.x; x++) {\n"
            + "      float wx = min(f1.x, float(x + 1)) - max(f0.x, float(x));\n"
            + "      if (wx <= 0.0) continue;\n"
            + "      c += px4(x, y) * (wx * wy); ws += wx * wy;\n"
            + "    }\n"
            + "  }\n"
            + "  imageStore(uDst, d, vec4(c / max(ws, 1e-6), 1.0));\n"
            + "}\n";

    /** Luma unsharp mask + optional R/B swap so that glReadPixels yields Android ARGB ints. */
    static final String FINAL = HEADER
            + "layout(local_size_x = 16, local_size_y = 8) in;\n"
            + "uniform int uY0;\n"
            + "layout(binding = 0) uniform sampler2D uSrc;\n"
            + "layout(rgba8, binding = 0) writeonly uniform highp image2D uDst;\n"
            + "uniform ivec2 uSize;\n"
            + "uniform float uAmount;\n"
            + "uniform float uSigma;\n"
            + "uniform int uRadius;\n"
            + "uniform float uThreshold;\n"
            + "uniform int uSwapRB;\n"
            + "const vec3 LW = vec3(0.2126, 0.7152, 0.0722);\n"
            + "void main() {\n"
            + "  ivec2 p = ivec2(gl_GlobalInvocationID.xy) + ivec2(0, uY0);\n"
            + "  if (p.x >= uSize.x || p.y >= uSize.y) return;\n"
            + "  vec3 c = texelFetch(uSrc, p, 0).rgb;\n"
            + "  if (uAmount > 0.0 && uRadius > 0) {\n"
            + "    float k = 1.0 / (2.0 * uSigma * uSigma);\n"
            + "    float sum = 0.0; float ws = 0.0;\n"
            + "    for (int j = -uRadius; j <= uRadius; j++) {\n"
            + "      for (int i = -uRadius; i <= uRadius; i++) {\n"
            + "        float w = exp(-float(i * i + j * j) * k);\n"
            + "        sum += w * dot(texelFetch(uSrc, clamp(p + ivec2(i, j), ivec2(0), uSize - 1), 0).rgb, LW);\n"
            + "        ws += w;\n"
            + "      }\n"
            + "    }\n"
            + "    float dlt = dot(c, LW) - sum / ws;\n"
            + "    dlt = sign(dlt) * max(abs(dlt) - uThreshold, 0.0);\n"
            + "    c = clamp(c + uAmount * dlt, 0.0, 1.0);\n"
            + "  }\n"
            + "  vec4 o = vec4(c, 1.0);\n"
            + "  if (uSwapRB != 0) o = o.bgra;\n"
            + "  imageStore(uDst, p, o);\n"
            + "}\n";
}
