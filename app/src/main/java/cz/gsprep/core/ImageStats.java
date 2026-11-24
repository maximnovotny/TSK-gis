package cz.gsprep.core;

/** CPU statistics on small (analysis-size) images: sharpness score and colour histograms. */
public final class ImageStats {
    private ImageStats() {}

    /** Variance of the 4-neighbour Laplacian of luma. Higher = sharper. Compare only within one set. */
    public static double sharpness(int[] argb, int w, int h) {
        if (w < 3 || h < 3) return 0;
        int[] y = new int[w * h];
        for (int i = 0; i < y.length; i++) {
            int c = argb[i];
            y[i] = (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
        }
        double sum = 0, sum2 = 0;
        long n = 0;
        for (int yy = 1; yy < h - 1; yy++) {
            int row = yy * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                int lap = 4 * y[i] - y[i - 1] - y[i + 1] - y[i - w] - y[i + w];
                sum += lap;
                sum2 += (double) lap * lap;
                n++;
            }
        }
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    /** Adds the image to a 3x256 histogram (R, G, B). */
    public static void accumulate(int[] argb, int count, long[] hist) {
        for (int i = 0; i < count; i++) {
            int c = argb[i];
            hist[(c >> 16) & 255]++;
            hist[256 + ((c >> 8) & 255)]++;
            hist[512 + (c & 255)]++;
        }
    }

    /**
     * Linked auto levels for a whole set: the same black/white point for all channels (keeps
     * white balance), from per-channel percentiles. Returns {lo, hi} in 0..1.
     */
    public static float[] levels(long[] hist, double loFrac, double hiFrac) {
        int lo = 255, hi = 0;
        for (int ch = 0; ch < 3; ch++) {
            long total = 0;
            for (int i = 0; i < 256; i++) total += hist[ch * 256 + i];
            if (total == 0) return new float[] {0f, 1f};
            long acc = 0;
            int l = 0;
            for (int i = 0; i < 256; i++) {
                acc += hist[ch * 256 + i];
                if (acc > total * loFrac) { l = i; break; }
            }
            acc = 0;
            int hgh = 255;
            for (int i = 255; i >= 0; i--) {
                acc += hist[ch * 256 + i];
                if (acc > total * (1.0 - hiFrac)) { hgh = i; break; }
            }
            lo = Math.min(lo, l);
            hi = Math.max(hi, hgh);
        }
        float flo = Math.min(lo / 255f, 0.25f);
        float fhi = Math.max(hi / 255f, 0.75f);
        return new float[] {flo, fhi};
    }
}
