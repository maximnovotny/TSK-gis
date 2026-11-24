package cz.gsprep.core;

/** Processing parameters. The same instance is applied to every image of a set (consistency for GS). */
public final class Params {
    /** Upscale factor requested by the user: 1, 2 or 4. */
    public int scale = 2;
    /** true = Real-ESRGAN on GPU, false = classic bicubic. */
    public boolean ai = true;
    /** Maximum long side of the output in pixels. */
    public int maxSide = 4096;

    /** Bilateral denoise strength 0..1 (0 = off). */
    public float denoise = 0f;
    /** Linked levels (black / white point) shared by the whole set. */
    public float levelsLo = 0f, levelsHi = 1f;
    public float exposureEv = 0f;   // -2..2
    public float contrast = 0f;     // -0.5..0.5
    public float shadows = 0f;      // 0..1
    public float highlights = 0f;   // 0..1
    public float saturation = 0f;   // -0.5..0.5

    /** Luma unsharp mask. amount 0..2, radius (sigma) px 0.5..2.5, threshold 0..0.05. */
    public float sharpenAmount = 0.6f;
    public float sharpenRadius = 1.0f;
    public float sharpenThreshold = 0.01f;

    public Params copy() {
        Params p = new Params();
        p.scale = scale; p.ai = ai; p.maxSide = maxSide; p.denoise = denoise;
        p.levelsLo = levelsLo; p.levelsHi = levelsHi; p.exposureEv = exposureEv;
        p.contrast = contrast; p.shadows = shadows; p.highlights = highlights;
        p.saturation = saturation; p.sharpenAmount = sharpenAmount;
        p.sharpenRadius = sharpenRadius; p.sharpenThreshold = sharpenThreshold;
        return p;
    }

    /** Output size for an input of w x h: w*scale capped to maxSide on the long side. Never below 1. */
    public static int[] targetSize(int w, int h, int scale, int maxSide) {
        int longSide = Math.max(w, h);
        double e = scale;
        if (longSide * e > maxSide) e = maxSide / (double) longSide;
        int tw = Math.max(1, (int) Math.round(w * e));
        int th = Math.max(1, (int) Math.round(h * e));
        return new int[] {tw, th};
    }

    /** Decode cap: there is no point decoding beyond what the output can hold. */
    public int decodeCap() {
        return maxSide;
    }
}
