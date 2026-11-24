"""Convert ncnn SRVGGNetCompact (realesr-general-x4v3) into the GSM1 asset used by the app,
and provide a numpy reference implementation of the network."""
import struct, sys
import numpy as np

FP16_TAG = 0x01306B47


def parse_ncnn(param_path, bin_path):
    lines = open(param_path).read().split("\n")
    layers = []
    for ln in lines[2:]:
        p = ln.split()
        if not p:
            continue
        typ = p[0]
        nin, nout = int(p[2]), int(p[3])
        kv = {}
        for t in p[4 + nin + nout:]:
            k, v = t.split("=")
            kv[int(k)] = v
        layers.append((typ, kv))
    data = open(bin_path, "rb").read()
    off = 0
    net = []  # list of dicts {w, b, slope}
    for typ, kv in layers:
        if typ == "Convolution":
            cout = int(kv[0]); k = int(kv[1]); nw = int(kv[6])
            cin = nw // (cout * k * k)
            tag = struct.unpack_from("<I", data, off)[0]; off += 4
            assert tag == FP16_TAG, hex(tag)
            w = np.frombuffer(data, dtype="<f2", count=nw, offset=off).astype(np.float32)
            off += (nw * 2 + 3) // 4 * 4
            b = np.frombuffer(data, dtype="<f4", count=cout, offset=off).copy(); off += cout * 4
            net.append({"w": w.reshape(cout, cin, k, k), "b": b, "slope": None})
        elif typ == "PReLU":
            n = int(kv[0])
            s = np.frombuffer(data, dtype="<f4", count=n, offset=off).copy(); off += n * 4
            net[-1]["slope"] = s
    assert off == len(data), (off, len(data))
    return net


def ref_forward(net, img):
    """img: HxWx3 float32 in [0,1]. Zero padding at borders. Returns 4H x 4W x 3 clamped."""
    x = img.transpose(2, 0, 1).astype(np.float32)
    inp = x
    for L in net:
        w, b = L["w"], L["b"]
        cout, cin = w.shape[:2]
        H, W = x.shape[1:]
        xp = np.zeros((cin, H + 2, W + 2), np.float32); xp[:, 1:-1, 1:-1] = x
        y = np.zeros((cout, H, W), np.float32)
        for ky in range(3):
            for kx in range(3):
                y += np.einsum("oc,chw->ohw", w[:, :, ky, kx], xp[:, ky:ky + H, kx:kx + W], optimize=True)
        y += b[:, None, None]
        if L["slope"] is not None:
            s = L["slope"][:, None, None]
            y = np.where(y >= 0, y, y * s)
        x = y
    C, H, W = x.shape  # 48
    r = 4
    out = x.reshape(3, r, r, H, W).transpose(0, 3, 1, 4, 2).reshape(3, H * r, W * r)
    up = np.repeat(np.repeat(inp, r, axis=1), r, axis=2)
    out = np.clip(out + up, 0, 1)
    return out.transpose(1, 2, 0)


def write_gsm(net, path):
    with open(path, "wb") as f:
        f.write(b"GSM1"); f.write(struct.pack("<ii", 1, len(net)))
        for L in net:
            cout, cin = L["w"].shape[:2]
            f.write(struct.pack("<iii", cin, cout, 1 if L["slope"] is not None else 0))
        for L in net:
            w = L["w"]; cout, cin = w.shape[:2]
            og, ig = (cout + 3) // 4, (cin + 3) // 4
            wp = np.zeros((og * 4, ig * 4, 3, 3), np.float32); wp[:cout, :cin] = w
            # block[og][ig][tap] = mat4 column-major: col c (input ch in group), row j (output ch in group)
            blk = wp.reshape(og, 4, ig, 4, 9)          # o_g, j, i_g, c, tap
            blk = blk.transpose(0, 2, 4, 3, 1)           # o_g, i_g, tap, c, j
            f.write(blk.astype("<f2").tobytes())
            bb = np.zeros(og * 4, np.float32); bb[:cout] = L["b"]
            ss = np.zeros(og * 4, np.float32)
            if L["slope"] is not None:
                ss[:cout] = L["slope"]
            f.write(bb.astype("<f4").tobytes()); f.write(ss.astype("<f4").tobytes())


if __name__ == "__main__":
    net = parse_ncnn(sys.argv[1], sys.argv[2])
    print("layers", len(net), [L["w"].shape for L in (net[0], net[1], net[-1])])
    write_gsm(net, sys.argv[3])
    print("wrote", sys.argv[3])
