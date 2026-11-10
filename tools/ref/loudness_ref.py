#!/usr/bin/env python3
"""Reference BS.1770-5 / EBU R128 integrated loudness and true peak (float64).

Implements exactly the rules fixed in docs/math/dsp.md (section FR-C4):
  * K-filter: tools/ref/kfilter.py (bilinear + prewarp, any fs)
  * blocks: segment b(i) = floor(i*fs/10); block j = [b(j), b(j+4)), only complete
    blocks (b(j+4) <= N); z_j = sum(y^2)/(b(j+4)-b(j)) (float64)
  * l_j = -0.691 + 10 log10(sum_i G_i z_ij), G = 1.0 for L/R/C, 1.41 Ls/Rs
  * gates: l_j > -70 (strict), then l_j > Gamma_r (strict), Gamma_r = L(abs-gated) - 10
  * result None if no complete block or no block above -70 LUFS
  * true peak: BS.1770-5 Annex 2 48-tap 4-phase FIR, x4 for fs < 96 kHz,
    x2 (phases 0 and 2 of the same FIR) for fs >= 96 kHz; zero initial state,
    11 zeros flushed at the end; TP = max(filter peak, sample peak)
Cross-checks: pyloudnorm (filter_class='DeMan' == BS.1770 coefs, and default RBJ
'K-weighting' for information), ffmpeg ebur128=peak=true (if ffmpeg in PATH).

Run: python3 loudness_ref.py  [--no-ffmpeg]   -> table of cases; writes loudness_cases.json
"""
import math, sys, os, json, subprocess, tempfile, wave, struct
import numpy as np
from scipy.signal import lfilter, resample_poly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kfilter import kfilter_coeffs

# ITU-R BS.1770-5 Annex 2, Table (48-tap, 4-phase interpolator). Phase 2 = reversed
# phase 1, phase 3 = reversed phase 0. All values are multiples of 2^-13.
# !! Auditor: verify digit-by-digit against BS.1770-5 Annex 2 (pp. 18-19) !!
TP_PHASE0 = [0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000,
             -0.0594482421875, 0.1373291015625, 0.9721679687500, -0.1022949218750,
             0.0476074218750, -0.0266113281250, 0.0148925781250, -0.0083007812500]
TP_PHASE1 = [-0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250,
             -0.1665039062500, 0.4650878906250, 0.7797851562500, -0.2003173828125,
             0.1015625000000, -0.0582275390625, 0.0330810546875, -0.0189208984375]
TP_PHASES = [TP_PHASE0, TP_PHASE1, TP_PHASE1[::-1], TP_PHASE0[::-1]]

ABS_GATE = -70.0
REL_GATE = -10.0


def kfilter(x, fs):
    c = kfilter_coeffs(fs)
    y = lfilter(c["s1_b"], c["s1_a"], x)
    return lfilter(c["s2_b"], c["s2_a"], y)


def block_bounds(n, fs):
    """Block j = samples [b(j), b(j+4)), b(i) = floor(i*fs/10) (integer arithmetic).
    = 100-ms segments; a block is 4 consecutive segments. Only complete blocks."""
    out = []
    j = 0
    while True:
        s, e = (j * fs) // 10, ((j + 4) * fs) // 10
        if e > n:
            break
        out.append((s, e - s))
        j += 1
    return out


def integrated_loudness(x, fs, weights=None):
    """x: ndarray (N,) or (N, ch) float64. Returns (LUFS or None, n_blocks, info)."""
    x = np.asarray(x, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    n, ch = x.shape
    if weights is None:
        weights = [1.0] * ch
    blocks = block_bounds(n, fs)
    if not blocks:
        return None, 0, "shorter than 400 ms"
    y = np.stack([kfilter(x[:, c], fs) for c in range(ch)], axis=1)
    csum = np.concatenate([np.zeros((1, ch)), np.cumsum(y * y, axis=0)])  # float64
    z = np.array([(csum[s + ng] - csum[s]) / ng for s, ng in blocks])     # (J, ch)
    p = z @ np.asarray(weights, dtype=np.float64)
    with np.errstate(divide="ignore"):
        l = -0.691 + 10.0 * np.log10(p)
    ga = l > ABS_GATE
    if not ga.any():
        return None, len(blocks), "below absolute gate"
    gamma_r = -0.691 + 10.0 * math.log10(p[ga].mean()) + REL_GATE
    gr = ga & (l > gamma_r)
    return -0.691 + 10.0 * math.log10(p[gr].mean()), len(blocks), f"Gamma_r={gamma_r:.4f}"


def true_peak_lin(x, fs):
    x = np.asarray(x, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    phases = TP_PHASES if fs < 96000 else [TP_PHASES[0], TP_PHASES[2]]
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    for c in range(x.shape[1]):
        xp = np.concatenate([x[:, c], np.zeros(11)])
        for h in phases:
            peak = max(peak, float(np.max(np.abs(lfilter(h, [1.0], xp)))))
    return peak


def db(v):
    return 20.0 * math.log10(v) if v > 0 else float("-inf")


# ---------------- signals ----------------
def sine(fs, f, amp_dbfs, dur, ch=2, phase=0.0):
    n = int(round(dur * fs))
    t = np.arange(n) / fs
    s = 10 ** (amp_dbfs / 20) * np.sin(2 * np.pi * f * t + phase)
    return np.tile(s[:, None], (1, ch)) if ch > 1 else s


def segments(fs, f, spec, ch=2):
    return np.concatenate([sine(fs, f, a, d, ch) for a, d in spec], axis=0)


def tp_sine(fs, frac, amp, phase_deg, dur=1.0, ramp_s=0.01):
    """Tech 3341 tests 15-19. A 10 ms raised-cosine ramp at both ends removes the
    onset transient: an abrupt start legitimately produces inter-sample overshoot
    (up to +0.7 dB for test 18) that is not part of the steady-state test."""
    n = int(fs * dur)
    k = np.arange(n)
    s = amp * np.sin(2 * np.pi * frac * k + math.radians(phase_deg))
    nr = int(ramp_s * fs)
    if nr > 0:
        r = 0.5 * (1 - np.cos(np.pi * (np.arange(nr) + 0.5) / nr))
        s[:nr] *= r
        s[-nr:] *= r[::-1]
    return np.stack([s, s], axis=1)


def tp_test20(fs, offset, dur=1.0):
    """Tech 3341 tests 20-23 (approximation of the EBU synthesis): at 4*fs, sine fs/6
    amp 0.5 with one period of fs/4 amp 1.0 inserted phase-continuously, low-passed and
    decimated by 4 starting at `offset`. Expected 0.0 dBTP (+0.2/-0.4)."""
    F = 4 * fs
    n6 = int(F / (fs / 6))  # samples per fs/6 period at 4fs = 24
    n4 = int(F / (fs / 4))  # 16
    reps = int(dur * fs / 6)
    a = 0.5 * np.sin(2 * np.pi * np.arange(n6 * (reps // 2)) / n6)
    b = 1.0 * np.sin(2 * np.pi * np.arange(n4) / n4)
    s = np.concatenate([a, b, a])
    y = resample_poly(np.concatenate([np.zeros(offset), s]), 1, 4)
    return np.stack([y, y], axis=1)


def ffmpeg_measure(x, fs):
    """Integrated loudness and true peak by ffmpeg ebur128=peak=true (float WAV in)."""
    x = np.asarray(x, dtype=np.float32)
    if x.ndim == 1:
        x = x[:, None]
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
        path = tf.name
    try:
        import scipy.io.wavfile as wf
        wf.write(path, fs, x)
        r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af",
                            "ebur128=peak=true:framelog=quiet", "-f", "null", "-"],
                           capture_output=True, text=True)
        txt = r.stderr
        i_val = tp_val = None
        sec = txt[txt.rfind("Summary:"):]
        for line in sec.splitlines():
            line = line.strip()
            if line.startswith("I:"):
                i_val = float(line.split()[1])
            if line.startswith("Peak:"):
                tp_val = float(line.split()[1])
        return i_val, tp_val
    finally:
        os.unlink(path)


def pyln(x, fs, cls):
    try:
        import pyloudnorm as pl
        m = pl.Meter(fs, filter_class=cls)
        return float(m.integrated_loudness(np.asarray(x, dtype=np.float64)))
    except Exception as e:
        return None


def main():
    use_ff = "--no-ffmpeg" not in sys.argv
    cases = []
    def add(name, x, fs, expect_I=None, tol_I=None, expect_TP=None, tp_tol=None):
        I, nb, info = integrated_loudness(x, fs)
        tp = db(true_peak_lin(x, fs))
        sp = db(float(np.max(np.abs(x)))) if np.size(x) else float("-inf")
        r = {"name": name, "fs": fs, "n": int(np.shape(x)[0]), "channels": 1 if np.ndim(x) == 1 else int(np.shape(x)[1]),
             "I_ref": I, "blocks": nb, "TP_ref_dBTP": tp, "SP_dBFS": sp,
             "expect_I": expect_I, "tol_I": tol_I, "expect_TP": expect_TP, "tol_TP": tp_tol}
        if np.shape(x)[0] >= int(0.4 * fs) + 1:
            r["pyln_DeMan"] = pyln(x, fs, "DeMan")
            r["pyln_RBJ"] = pyln(x, fs, "K-weighting")
        if use_ff:
            r["ffmpeg_I"], r["ffmpeg_TP"] = ffmpeg_measure(x, fs)
        cases.append(r)

    fs = 48000
    add("T3341-1 sine1k -23dBFS stereo 20s", sine(fs, 1000, -23, 20), fs, -23.0, 0.1)
    add("T3341-2 sine1k -33dBFS stereo 20s", sine(fs, 1000, -33, 20), fs, -33.0, 0.1)
    add("T3341-3 -36/-23/-36 10/60/10s", segments(fs, 1000, [(-36, 10), (-23, 60), (-36, 10)]), fs, -23.0, 0.1)
    add("T3341-4 -72/-36/-23/-36/-72", segments(fs, 1000, [(-72, 10), (-36, 10), (-23, 60), (-36, 10), (-72, 10)]), fs, -23.0, 0.1)
    add("T3341-5 -26/-20/-26 20/20.1/20s", segments(fs, 1000, [(-26, 20), (-20, 20.1), (-26, 20)]), fs, -23.0, 0.1)
    for f0 in (997, 1000):
        for fsx in (48000, 44100):
            add(f"mono sine {f0}Hz 0dBFS 10s @{fsx}", sine(fsx, f0, 0, 10, ch=1), fsx, -3.01, 0.01)
    for fsx in (8000, 11025, 22050, 32000, 96000, 192000):
        add(f"T3341-1 at {fsx} Hz", sine(fsx, 1000, -23, 20), fsx, -23.0, 0.1)
    # true peak (Tech 3341 tests 15-23), tolerance +0.2/-0.4 dB
    add("T3341-15 fs/4 0.5 0deg", tp_sine(fs, 1 / 4, 0.5, 0), fs, None, None, -6.0, (0.2, -0.4))
    add("T3341-16 fs/4 0.5 45deg", tp_sine(fs, 1 / 4, 0.5, 45), fs, None, None, -6.0, (0.2, -0.4))
    add("T3341-17 fs/6 0.5 60deg", tp_sine(fs, 1 / 6, 0.5, 60), fs, None, None, -6.0, (0.2, -0.4))
    add("T3341-18 fs/8 0.5 67.5deg", tp_sine(fs, 1 / 8, 0.5, 67.5), fs, None, None, -6.0, (0.2, -0.4))
    add("T3341-19 fs/4 1.41 45deg", tp_sine(fs, 1 / 4, 1.41, 45), fs, None, None, 3.0, (0.2, -0.4))
    add("onset (no ramp) fs/8 0.5 67.5deg: info only", tp_sine(fs, 1 / 8, 0.5, 67.5, ramp_s=0), fs, None, None, -5.318, (0.01, -0.01))
    for off in range(4):
        add(f"T3341-{20+off} (approx synth) offset {off}", tp_test20(fs, off), fs, None, None, 0.0, (0.2, -0.4))
    for fsx in (44100, 96000, 192000):
        add(f"TP fs/4 0.5 45deg @{fsx}", tp_sine(fsx, 1 / 4, 0.5, 45), fsx, None, None, -6.0, (0.2, -0.4))
    # edge cases
    add("399.98 ms sine (19199 samples) -> None", sine(fs, 1000, -23, 19199 / fs), fs, None)
    add("exactly 400 ms sine -> 1 block", sine(fs, 1000, -23, 0.4), fs, -23.0, 0.1)
    add("digital silence 5 s -> None", np.zeros((5 * fs, 2)), fs, None)
    add("sine -80 dBFS 5 s -> None (below -70)", sine(fs, 1000, -80, 5), fs, None)

    print(f"{'case':44} {'I_ref':>9} {'DeMan':>9} {'RBJ':>9} {'ff_I':>7} {'TP_ref':>8} {'ff_TP':>7} {'SP':>8}  check")
    ok_all = True
    for r in cases:
        def f(v, w=9, p=4):
            return f"{v:{w}.{p}f}" if isinstance(v, (int, float)) and v is not None and math.isfinite(v) else f"{str(v):>{w}}"
        chk = ""
        if r["expect_I"] is not None and r["tol_I"]:
            ok = r["I_ref"] is not None and abs(r["I_ref"] - r["expect_I"]) <= r["tol_I"]
            chk += "I:" + ("ok " if ok else "FAIL ")
            ok_all &= ok
        elif r["expect_I"] is None and r["expect_TP"] is None:
            ok = r["I_ref"] is None
            chk += "None:" + ("ok " if ok else "FAIL ")
            ok_all &= ok
        if r["expect_TP"] is not None:
            d = r["TP_ref_dBTP"] - r["expect_TP"]
            ok = r["tol_TP"][1] <= d <= r["tol_TP"][0]
            chk += "TP:" + ("ok" if ok else "FAIL")
            ok_all &= ok
        print(f"{r['name'][:44]:44} {f(r['I_ref'])} {f(r.get('pyln_DeMan'))} {f(r.get('pyln_RBJ'))} {f(r.get('ffmpeg_I'),7,1)} {f(r['TP_ref_dBTP'],8,3)} {f(r.get('ffmpeg_TP'),7,1)} {f(r['SP_dBFS'],8,3)}  {chk}")
    out = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "test", "resources", "ref", "loudness_cases.json"))
    with open(out, "w") as fo:
        json.dump(cases, fo, indent=1, default=lambda v: None)
    print("written", out, "| ALL", "OK" if ok_all else "FAIL")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
