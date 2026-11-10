#!/usr/bin/env python3
"""Reference polyphase windowed-sinc (Kaiser) resampler, as fixed in docs/math/dsp.md FR-C6.

  L/M = fs_out/fs_in reduced by gcd;  rho = min(1, L/M)
  cutoff  fcn = 0.475*rho  [cycles per INPUT sample]  (middle of 0.45..0.50 fs_min)
  transition dfn = 0.05*rho
  Kaiser (Oppenheim & Schafer, 3rd ed., eq. 7.62-7.63):  A = 100 dB design (spec >= 90),
      beta = 0.1102*(A - 8.7) = 10.0613,  span = (A - 7.95)/(2.285*2*pi*dfn) input samples
  taps per phase Nt = even ceil(span);  T = Nt/2
  kernel h(tau) = 2 fcn sinc(2 fcn tau) * I0(beta*sqrt(1-(tau/T)^2))/I0(beta),  |tau| <= T
  output m: P = m*M (Long), n0 = P div L, phase p = P mod L, mu = p/L
      y[m] = sum_{k=-(T-1)}^{T} x[n0+k] * h(mu - k)       (x = 0 outside [0, N_in))
  -> zero-phase: output sample 0 is exactly at input time 0 (no delay to compensate)
  N_out = round_half_up(N_in*L/M) = (2*N_in*L + M) div (2*M)

Run: python3 resampler_ref.py  -> spec table for rate pairs, end-to-end tests, vectors.
"""
import math, sys, os, json
import numpy as np
from math import gcd

A_DESIGN = 100.0
STD_RATES = [8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 176400, 192000]


def design(fs_in, fs_out, A=A_DESIGN):
    g = gcd(fs_in, fs_out)
    L, M = fs_out // g, fs_in // g
    rho = min(1.0, L / M)
    fcn, dfn = 0.475 * rho, 0.05 * rho
    beta = 0.1102 * (A - 8.7)
    span = (A - 7.95) / (2.285 * 2 * math.pi * dfn)
    Nt = int(math.ceil(span))
    Nt += Nt % 2
    return dict(L=L, M=M, rho=rho, fcn=fcn, beta=beta, Nt=Nt, T=Nt // 2)


def kernel(tau, d):
    T, fcn, beta = d["T"], d["fcn"], d["beta"]
    tau = np.asarray(tau, dtype=np.float64)
    r = np.clip(1.0 - (tau / T) ** 2, 0.0, None)
    w = np.i0(beta * np.sqrt(r)) / np.i0(beta)
    h = 2 * fcn * np.sinc(2 * fcn * tau) * w
    return np.where(np.abs(tau) <= T, h, 0.0)


def phase_table(d):
    L, T = d["L"], d["T"]
    k = np.arange(-(T - 1), T + 1)                 # Nt taps
    mu = np.arange(L)[:, None] / L
    return kernel(mu - k[None, :], d), k           # (L, Nt)


def out_len(n_in, L, M):
    return (2 * n_in * L + M) // (2 * M)


def resample(x, fs_in, fs_out, table=None):
    x = np.asarray(x, dtype=np.float64)
    if fs_in == fs_out:
        return x.copy()                            # bypass: bit-exact
    d = design(fs_in, fs_out)
    L, M, T = d["L"], d["M"], d["T"]
    H, k = phase_table(d) if table is None else table
    n_in = len(x)
    n_out = out_len(n_in, L, M)
    xp = np.concatenate([np.zeros(T), x, np.zeros(T + 1)])
    m = np.arange(n_out, dtype=np.int64)
    P = m * M
    n0 = P // L
    p = P % L
    idx = (n0[:, None] + k[None, :]) + T            # index into padded
    return np.einsum("ij,ij->i", xp[idx], H[p])


def proto_response(d, freqs_rel_in):
    """|H(f)| of the continuous kernel sampled at L*fs_in, f in cycles/input sample."""
    L, T = d["L"], d["T"]
    j = np.arange(-T * L, T * L + 1)
    hp = kernel(j / L, d) / L
    out = []
    for f in freqs_rel_in:
        out.append(abs(np.sum(hp * np.exp(-2j * np.pi * f * j / L))))
    return np.array(out)


def spec(fs_in, fs_out, npts=400):
    d = design(fs_in, fs_out)
    fmin = min(fs_in, fs_out)
    pb = np.linspace(0, 0.45 * fmin, npts) / fs_in
    sb = np.linspace(0.5 * fmin, min(0.5 * d["L"] * fs_in, 4 * max(fs_in, fs_out)), npts) / fs_in
    Hp = 20 * np.log10(proto_response(d, pb))
    Hs = 20 * np.log10(proto_response(d, sb) + 1e-300)
    return d, float(Hp.max() - Hp.min()), float(Hp.max()), float(Hp.min()), float(Hs.max())


def fit_sine(y, fs, f):
    t = np.arange(len(y)) / fs
    Amat = np.stack([np.sin(2 * np.pi * f * t), np.cos(2 * np.pi * f * t), np.ones_like(t)], axis=1)
    c, *_ = np.linalg.lstsq(Amat, y, rcond=None)
    res = y - Amat @ c
    return math.hypot(c[0], c[1]), math.sqrt(np.mean(res ** 2))


def main():
    ok_all = True
    print("Design / spec (A_design=100 dB, beta=%.4f):" % (0.1102 * (A_DESIGN - 8.7)))
    print(f"{'in':>7}->{'out':<7} {'L':>5} {'M':>5} {'Nt':>5} {'coefs':>8} {'ripple dB':>10} {'stop max dB':>12}")
    pairs = [(44100, 48000), (48000, 44100), (8000, 48000), (48000, 8000), (192000, 48000),
             (11025, 48000), (22050, 44100), (96000, 44100), (44100, 192000), (192000, 8000)]
    for a, b in pairs:
        d, rip, hi, lo, stop = spec(a, b, 300)
        ok = rip <= 0.1 and stop <= -90
        ok_all &= ok
        print(f"{a:7d}->{b:<7d} {d['L']:5d} {d['M']:5d} {d['Nt']:5d} {d['L']*d['Nt']:8d} {rip:10.2e} {stop:12.2f} {'ok' if ok else 'FAIL'}")
    mx = max(((design(a, b)["L"] * design(a, b)["Nt"], a, b) for a in STD_RATES for b in STD_RATES if a != b))
    mxL = max(((design(a, b)["L"], a, b) for a in STD_RATES for b in STD_RATES if a != b))
    print("max table size over standard rates: %d coefs (%d->%d); max L = %d (%d->%d)" % (mx[0], mx[1], mx[2], mxL[0], mxL[1], mxL[2]))

    print("\nEnd-to-end:")
    fs_i, fs_o = 44100, 48000
    x = 10 ** (-1 / 20) * np.sin(2 * np.pi * 1000 * np.arange(2 * fs_i) / fs_i)
    y = resample(x, fs_i, fs_o)
    T = design(fs_i, fs_o)["T"]
    cut = int(T * fs_o / fs_i) + 2
    amp, rms_res = fit_sine(y[cut:-cut], fs_o, 1000.0)
    amp_err = 20 * math.log10(amp / 10 ** (-1 / 20))
    thdn = 20 * math.log10(rms_res / (amp / math.sqrt(2)))
    ok = abs(amp_err) <= 0.01 and thdn <= -90
    ok_all &= ok
    print(f"1 kHz -1 dBFS 44.1->48: amp err {amp_err:+.2e} dB, THD+N {thdn:.1f} dB  {'ok' if ok else 'FAIL'}")

    x = np.sin(2 * np.pi * 23000 * np.arange(2 * 48000) / 48000)
    y = resample(x, 48000, 44100)
    T = design(48000, 44100)["T"]
    cut = int(T * 44100 / 48000) + 2
    res = 20 * math.log10(math.sqrt(np.mean(y[cut:-cut] ** 2)) + 1e-300)
    res_pk = 20 * math.log10(np.max(np.abs(y[cut:-cut])) + 1e-300)
    edge = 20 * math.log10(np.max(np.abs(y[:cut])) + 1e-300)
    ok = res_pk <= -90
    ok_all &= ok
    print(f"23 kHz 0 dBFS 48->44.1: residual RMS {res:.1f} dBFS, peak {res_pk:.1f} dBFS (edges excluded {cut} smp; edge transient peak {edge:.1f} dBFS) {'ok' if ok else 'FAIL'}")

    n30 = 30 * 60 * 44100
    print(f"30 min 44.1->48: N_out = {out_len(n30, 160, 147)} (expect 86400000)")
    ok_all &= out_len(n30, 160, 147) == 86400000

    # delay: impulse at n0 -> peak at round(n0*L/M) for upsampling, energy centroid check
    for a, b in ((44100, 48000), (48000, 44100), (8000, 48000), (192000, 48000)):
        n_in = 4000
        n0 = 1234
        x = np.zeros(n_in); x[n0] = 1.0
        # band-limited impulse avoids ambiguity: use a Gaussian-windowed pulse
        t = np.arange(n_in) - n0
        sig = 0.2 * min(1.0, b / a)
        x = np.exp(-0.5 * (t * sig) ** 2)
        y = resample(x, a, b)
        tt = np.arange(len(y))
        centroid_out = np.sum(tt * y ** 2) / np.sum(y ** 2)
        expect = n0 * b / a
        err = centroid_out - expect
        ok = abs(err) <= 1.0
        ok_all &= ok
        print(f"delay {a}->{b}: centroid {centroid_out:.4f}, expected {expect:.4f}, err {err:+.2e} smp {'ok' if ok else 'FAIL'}")

    # length formula cases
    lens = [(1, 44100, 48000), (147, 44100, 48000), (100, 44100, 48000), (1000, 48000, 44100), (7, 48000, 8000),
            (3, 48000, 8000), (441, 44100, 8000), (12345, 11025, 48000), (n30, 44100, 48000)]
    len_cases = []
    for n, a, b in lens:
        d = design(a, b)
        len_cases.append({"n_in": n, "fs_in": a, "fs_out": b, "n_out": out_len(n, d["L"], d["M"])})
    print("length cases:", [(c["n_in"], c["fs_in"], c["fs_out"], c["n_out"]) for c in len_cases])

    # small vector for JUnit (float64 reference); input deterministic
    rng = np.random.default_rng(12345)
    vec = []
    for a, b in ((44100, 48000), (48000, 44100), (8000, 11025)):
        n = 300
        x = 0.5 * np.sin(2 * np.pi * 997 * np.arange(n) / a) + 0.1 * rng.uniform(-1, 1, n)
        x = x.astype(np.float32).astype(np.float64)
        y = resample(x, a, b)
        d = design(a, b)
        vec.append({"fs_in": a, "fs_out": b, "L": d["L"], "M": d["M"], "Nt": d["Nt"], "beta": d["beta"],
                    "fcn": d["fcn"], "x": [float(v) for v in x], "y": [float(v) for v in y]})
    ref = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "test", "resources", "ref"))
    with open(os.path.join(ref, "resampler_vectors.json"), "w") as f:
        json.dump({"note": "y = float64 reference of FR-C6 algorithm; Kotlin float32 tolerance |dy| <= 2e-6",
                   "length_cases": len_cases, "vectors": vec}, f)
    print("written", os.path.join(ref, "resampler_vectors.json"))

    # sanity vs scipy resample_poly (different design; info only)
    try:
        from scipy.signal import resample_poly
        x = 10 ** (-1 / 20) * np.sin(2 * np.pi * 1000 * np.arange(fs_i) / fs_i)
        ys = resample_poly(x, 160, 147, window=("kaiser", 10.0613))
        yr = resample(x, 44100, 48000)
        n = min(len(ys), len(yr))
        dd = np.max(np.abs(ys[500:n - 500] - yr[500:n - 500]))
        print(f"scipy.resample_poly (cutoff 0.5 fs_min, own length rule: {len(ys)} vs ours {len(yr)}): max |diff| mid = {dd:.2e}")
    except Exception as e:
        print("scipy check skipped:", e)
    print("ALL", "OK" if ok_all else "FAIL")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
