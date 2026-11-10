#!/usr/bin/env python3
"""K-filter (ITU-R BS.1770-5, Annex 1, Tables 1-2) for any sample rate.

Method: analog prototypes (high shelf + RLB high-pass) with parameters fitted to the
48 kHz coefficients of BS.1770 (B. De Man; same constants as libebur128 ebur128.c),
discretised by the bilinear transform with frequency pre-warping K = tan(pi*f0/fs).

  Stage 1 (shelf):  K=tan(pi f0/fs), Vh=10^(G/20), Vb=Vh^0.4996667741545416
     a0' = 1 + K/Q + K^2
     b = [(Vh + Vb K/Q + K^2), 2(K^2 - Vh), (Vh - Vb K/Q + K^2)] / a0'
     a = [1, 2(K^2 - 1)/a0', (1 - K/Q + K^2)/a0']
     f0=1681.974450955533 Hz, G=3.999843853973347 dB, Q=0.7071752369554196
  Stage 2 (RLB HP): b = [1, -2, 1] (NOT normalised, as in Table 2)
     a = [1, 2(K^2 - 1)/(1+K/Q+K^2), (1 - K/Q + K^2)/(1+K/Q+K^2)]
     f0=38.13547087602444 Hz, Q=0.5003270373238773

Run: python3 kfilter.py  -> prints coefficients for standard rates and the max
deviation from the published 48 kHz table (must be < 1e-6). Writes kfilter_coeffs.csv.
"""
import math, sys, os, csv

BS1770_48K = {
    "s1_b": (1.53512485958697, -2.69169618940638, 1.19839281085285),
    "s1_a": (1.0, -1.69065929318241, 0.73248077421585),
    "s2_b": (1.0, -2.0, 1.0),
    "s2_a": (1.0, -1.99004745483398, 0.99007225036621),
}

SHELF_F0, SHELF_G, SHELF_Q, SHELF_VB_EXP = 1681.974450955533, 3.999843853973347, 0.7071752369554196, 0.4996667741545416
HP_F0, HP_Q = 38.13547087602444, 0.5003270373238773

RATES = [8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000,
         88200, 96000, 176400, 192000]


def kfilter_coeffs(fs):
    K = math.tan(math.pi * SHELF_F0 / fs)
    Vh = 10.0 ** (SHELF_G / 20.0)
    Vb = Vh ** SHELF_VB_EXP
    a0 = 1.0 + K / SHELF_Q + K * K
    s1_b = ((Vh + Vb * K / SHELF_Q + K * K) / a0, 2.0 * (K * K - Vh) / a0, (Vh - Vb * K / SHELF_Q + K * K) / a0)
    s1_a = (1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / SHELF_Q + K * K) / a0)
    K = math.tan(math.pi * HP_F0 / fs)
    d = 1.0 + K / HP_Q + K * K
    s2_b = (1.0, -2.0, 1.0)
    s2_a = (1.0, 2.0 * (K * K - 1.0) / d, (1.0 - K / HP_Q + K * K) / d)
    return {"s1_b": s1_b, "s1_a": s1_a, "s2_b": s2_b, "s2_a": s2_a}


def response_db(c, f, fs):
    import cmath
    z = cmath.exp(-2j * math.pi * f / fs)
    h = 1.0
    for st in ("s1", "s2"):
        b, a = c[st + "_b"], c[st + "_a"]
        h *= (b[0] + b[1] * z + b[2] * z * z) / (a[0] + a[1] * z + a[2] * z * z)
    return 20 * math.log10(abs(h))


def main():
    c48 = kfilter_coeffs(48000)
    dev = max(abs(c48[k][i] - BS1770_48K[k][i]) for k in c48 for i in range(3))
    print(f"48 kHz max |coef - BS.1770 table| = {dev:.3e}  ({'OK' if dev < 1e-6 else 'FAIL'} < 1e-6)")
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "test", "resources", "ref", "kfilter_coeffs.csv")
    rows = []
    print(f"{'fs':>7} {'s1_b0':>18} {'s1_b1':>18} {'s1_b2':>18} {'s1_a1':>18} {'s1_a2':>18} {'s2_a1':>18} {'s2_a2':>18} {'H(997)dB':>9} {'H(fs/2-)dB':>9}")
    for fs in RATES:
        c = kfilter_coeffs(fs)
        r997 = response_db(c, 997.0, fs)
        rhi = response_db(c, min(20000.0, 0.45 * fs), fs)
        rows.append([fs, *c["s1_b"], *c["s1_a"][1:], *c["s2_a"][1:], r997])
        print(f"{fs:7d} {c['s1_b'][0]:18.14f} {c['s1_b'][1]:18.14f} {c['s1_b'][2]:18.14f} {c['s1_a'][1]:18.14f} {c['s1_a'][2]:18.14f} {c['s2_a'][1]:18.14f} {c['s2_a'][2]:18.14f} {r997:9.5f} {rhi:9.4f}")
    with open(os.path.normpath(out), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["fs", "s1_b0", "s1_b1", "s1_b2", "s1_a1", "s1_a2", "s2_a1", "s2_a2", "gain_997Hz_dB"])
        for r in rows:
            w.writerow([r[0]] + [repr(float(x)) for x in r[1:]])
    print("written", os.path.normpath(out))
    return 0 if dev < 1e-6 else 1


if __name__ == "__main__":
    sys.exit(main())
