#!/usr/bin/env python3
"""Reference 16-bit quantiser with TPDF dither (docs/math/dsp.md FR-C7).

PRNG (bit-exact contract with Kotlin): SplitMix64 (Steele, Lea, Flood 2014; Java's
SplittableRandom mixer):
    state += 0x9E3779B97F4A7C15
    z = state; z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
               z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
               z =  z ^ (z >>> 31)
    u = (z >>> 11) * 2^-53                     in [0, 1)
Dither per sample (interleaved order: frame-major, channel-minor): u1 = next, u2 = next,
    d = u1 - u2                                triangular on (-1, 1) LSB, var 1/6 LSB^2
Quantiser:  v = x*32768.0 + d   (double)
    y = clamp(floor(v + 0.5), -32768, 32767)   (round half up)
24 bit: y = clamp(floor(x*8388608.0 + 0.5), -8388608, 8388607), no dither.

Run: python3 dither_ref.py -> noise statistics + writes dither_vectors.json
"""
import math, sys, os, json
import numpy as np

MASK = (1 << 64) - 1


class SplitMix64:
    def __init__(self, seed):
        self.s = seed & MASK

    def next_u64(self):
        self.s = (self.s + 0x9E3779B97F4A7C15) & MASK
        z = self.s
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
        return z ^ (z >> 31)

    def next_double(self):
        return (self.next_u64() >> 11) * (1.0 / (1 << 53))


def splitmix_block(seed, n):
    """Vectorised SplitMix64: returns n uint64 outputs (same sequence as next_u64)."""
    inc = np.uint64(0x9E3779B97F4A7C15)
    s = (np.uint64(seed) + inc * np.arange(1, n + 1, dtype=np.uint64))
    z = s
    with np.errstate(over="ignore"):
        z = (z ^ (z >> np.uint64(30))) * np.uint64(0xBF58476D1CE4E5B9)
        z = (z ^ (z >> np.uint64(27))) * np.uint64(0x94D049BB133111EB)
    return z ^ (z >> np.uint64(31))


def tpdf(seed, n):
    u = (splitmix_block(seed, 2 * n) >> np.uint64(11)).astype(np.float64) * (1.0 / (1 << 53))
    return u[0::2] - u[1::2]


def quantize16(x, seed, dither=True):
    x = np.asarray(x, dtype=np.float64)
    d = tpdf(seed, x.size) if dither else np.zeros(x.size)
    v = x.ravel() * 32768.0 + d
    return np.clip(np.floor(v + 0.5), -32768, 32767).astype(np.int64).reshape(x.shape)


def quantize24(x):
    x = np.asarray(x, dtype=np.float64)
    return np.clip(np.floor(x * 8388608.0 + 0.5), -8388608, 8388607).astype(np.int64)


def main():
    ok_all = True
    # bit-exact check of vectorised vs scalar PRNG
    g = SplitMix64(42)
    sc = [g.next_u64() for _ in range(8)]
    ve = [int(v) for v in splitmix_block(42, 8)]
    assert sc == ve, "PRNG mismatch"
    print("SplitMix64(seed=42) first 4 u64:", [hex(v) for v in sc[:4]])
    theory = 20 * math.log10(0.5 / 32768)
    print(f"theory: RMS error = 0.5 LSB = 1/65536 -> {theory:.3f} dB re 1.0 (= {theory + 3.0103:.3f} dBFS AES17 sine-referenced)")
    N = 1_000_000
    fs = 48000
    for name, x in (("silence", np.zeros(N)),
                    ("sine 997 Hz -20 dBFS", 0.1 * np.sin(2 * np.pi * 997 * np.arange(N) / fs)),
                    ("DC 0.3/32768 (sub-LSB)", np.full(N, 0.3 / 32768))):
        y = quantize16(x, seed=20251110)
        e = y / 32768.0 - x
        rms_db = 20 * math.log10(math.sqrt(np.mean(e * e)))
        mean_lsb = float(np.mean(e) * 32768)
        ok = abs(rms_db - theory) <= 0.05 and abs(mean_lsb) <= 0.01
        ok_all &= ok
        print(f"{name:26}: err RMS {rms_db:8.3f} dB, mean {mean_lsb:+.5f} LSB, var {np.var(e*32768):.5f} LSB^2  {'ok' if ok else 'FAIL'}")
    # without dither: 0.3 LSB DC disappears (truncation distortion) -> shows why dither
    y = quantize16(np.full(1000, 0.3 / 32768), 1, dither=False)
    print("no dither, DC 0.3 LSB -> mean output", y.mean(), "(signal lost)")
    # edge cases
    edge_x = [1.0, -1.0, 0.99999, 1.5, -1.5, 0.5 / 32768, -0.5 / 32768, 1.5 / 32768, -1.5 / 32768, 32767 / 32768]
    print("no-dither 16 bit:", list(zip(edge_x, quantize16(np.array(edge_x), 0, dither=False).tolist())))
    print("24 bit:", list(zip(edge_x, quantize24(np.array(edge_x)).tolist())))
    # vectors
    rng = np.random.default_rng(7)
    xin = (rng.uniform(-1.1, 1.1, 64)).astype(np.float32).astype(np.float64)
    xin[:6] = [0.0, 1.0, -1.0, 0.5 / 32768, -0.5 / 32768, 32767 / 32768]
    seed = 123456789
    g = SplitMix64(seed)
    u = [g.next_double() for _ in range(8)]
    vec = {"note": "x are float32-representable; d_i = u[2i]-u[2i+1] from SplitMix64(seed); y16 = clamp(floor(x*32768+d+0.5))",
           "seed": seed, "first_u64": [str(v) for v in [SplitMix64(seed).next_u64()]],
           "first_doubles": u, "x": xin.tolist(), "d": tpdf(seed, xin.size).tolist(),
           "y16_dither": quantize16(xin, seed).tolist(),
           "y16_nodither": quantize16(xin, seed, dither=False).tolist(),
           "y24": quantize24(xin).tolist(),
           "overflow_count_|x|>1": int(np.sum(np.abs(xin) > 1.0))}
    out = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "test", "resources", "ref", "dither_vectors.json"))
    with open(out, "w") as f:
        json.dump(vec, f, indent=1)
    print("written", out)
    print("ALL", "OK" if ok_all else "FAIL")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
