package cz.gsprep.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * SRVGGNetCompact weights (Real-ESRGAN realesr-general-x4v3) in the "GSM1" layout written by
 * tools/convert_model.py: per layer a block of mat4 (column = input channel, row = output
 * channel) for each [outGroup][inGroup][tap], then bias vec4[outGroups], slope vec4[outGroups].
 */
public final class Model {
    public final int layers;
    public final int[] cin, cout, prelu;
    /** Offsets in mat4 units (weights) and vec4 units (bias / slope). */
    public final int[] wOff, bOff, sOff;
    public final ByteBuffer weights; // float32, native order
    public final ByteBuffer params;  // float32, native order

    private Model(int layers, int[] cin, int[] cout, int[] prelu, int[] wOff, int[] bOff, int[] sOff,
                  ByteBuffer weights, ByteBuffer params) {
        this.layers = layers; this.cin = cin; this.cout = cout; this.prelu = prelu;
        this.wOff = wOff; this.bOff = bOff; this.sOff = sOff;
        this.weights = weights; this.params = params;
    }

    public static int groups(int ch) { return (ch + 3) / 4; }

    public static Model load(InputStream in) throws IOException {
        byte[] all = readAll(in);
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'G' || b.get() != 'S' || b.get() != 'M' || b.get() != '1') {
            throw new IOException("Neplatný soubor modelu");
        }
        int version = b.getInt();
        int n = b.getInt();
        if (version != 1 || n <= 0 || n > 256) throw new IOException("Nepodporovaná verze modelu");
        int[] cin = new int[n], cout = new int[n], prelu = new int[n];
        for (int i = 0; i < n; i++) {
            cin[i] = b.getInt(); cout[i] = b.getInt(); prelu[i] = b.getInt();
        }
        int totalMat = 0, totalVec = 0;
        int[] wOff = new int[n], bOff = new int[n], sOff = new int[n];
        for (int i = 0; i < n; i++) {
            int og = groups(cout[i]), ig = groups(cin[i]);
            wOff[i] = totalMat; totalMat += og * ig * 9;
            bOff[i] = totalVec; totalVec += og;
            sOff[i] = totalVec; totalVec += og;
        }
        ByteBuffer w = ByteBuffer.allocateDirect(totalMat * 64).order(ByteOrder.nativeOrder());
        ByteBuffer p = ByteBuffer.allocateDirect(totalVec * 16).order(ByteOrder.nativeOrder());
        FloatBuffer wf = w.asFloatBuffer();
        FloatBuffer pf = p.asFloatBuffer();
        for (int i = 0; i < n; i++) {
            int og = groups(cout[i]), ig = groups(cin[i]);
            int count = og * ig * 9 * 16;
            for (int k = 0; k < count; k++) wf.put(halfToFloat(b.getShort()));
            for (int k = 0; k < og * 8; k++) pf.put(b.getFloat()); // bias then slope
        }
        if (b.remaining() != 0) throw new IOException("Poškozený soubor modelu");
        return new Model(n, cin, cout, prelu, wOff, bOff, sOff, w, p);
    }

    static float halfToFloat(short hs) {
        int h = hs & 0xffff;
        int sign = (h & 0x8000) << 16;
        int exp = (h >>> 10) & 0x1f;
        int mant = h & 0x3ff;
        int bits;
        if (exp == 0) {
            if (mant == 0) {
                bits = sign;
            } else {
                // subnormal: normalise
                exp = 1;
                while ((mant & 0x400) == 0) { mant <<= 1; exp--; }
                mant &= 0x3ff;
                bits = sign | ((exp + 127 - 15) << 23) | (mant << 13);
            }
        } else if (exp == 31) {
            bits = sign | 0x7f800000 | (mant << 13);
        } else {
            bits = sign | ((exp + 127 - 15) << 23) | (mant << 13);
        }
        return Float.intBitsToFloat(bits);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 22);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }
}
