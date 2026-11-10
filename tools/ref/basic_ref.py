#!/usr/bin/env python3
"""Reference values for FR-C2 (fades), FR-C3 (joint smoothing), FR-C5 (downmix),
FR-20 (gain), FR-22 (normalisation G / G'). See docs/math/dsp.md.
Run: python3 basic_ref.py -> prints values, writes basic_vectors.json"""
import math, json, os

SQRT1_2 = math.sqrt(0.5)  # 0.7071067811865476 (BS.775 prints 0.7071)


def g_lin(x): return x
def g_pow(x): return math.sin(math.pi * x / 2)                       # equal power
def g_log(x): return 0.0 if x <= 0 else 10 ** (-60 * (1 - x) / 20)   # 60 dB, linear in dB
CURVES = {"linear": g_lin, "equal_power": g_pow, "log60": g_log}


def fade_in(n, curve):
    """FR-C2: x_i = i/(N-1); N < 2 -> no-op (gain 1)."""
    if n < 2:
        return [1.0] * n
    return [CURVES[curve](i / (n - 1)) for i in range(n)]


def fade_out(n, curve):
    return fade_in(n, curve)[::-1]          # g(1 - x_i)


def joint_n(d_ms, fs):
    return (d_ms * fs + 500) // 1000        # round_half_up(d*fs), integer arithmetic


def joint_ramp_up(n):
    """FR-C3 (recommended discretisation): x_i = (i + 0.5)/N, g = 0.5(1 - cos(pi x)).
    Ramp-down before the boundary is the mirror: g_down[j] = g_up[N-1-j]."""
    return [0.5 * (1 - math.cos(math.pi * (i + 0.5) / n)) for i in range(n)]


def db_to_gain(db): return 10 ** (db / 20)


# Android AudioFormat.CHANNEL_OUT_* bits
FL, FR, FC, LFE, BL, BR, FLC, FRC, BC, SL, SR = 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000
LFE2 = 0x2000000
# (to L', to R') per bit. ITU-R BS.775 Table 2 (3/2 -> 2/0, 2/2 -> 2/0); Lb/Rb as Ls/Rs (requirement)
DMX = {FL: (1.0, 0.0), FR: (0.0, 1.0), FC: (SQRT1_2, SQRT1_2), LFE: (0.0, 0.0), LFE2: (0.0, 0.0),
       BL: (SQRT1_2, 0.0), BR: (0.0, SQRT1_2), SL: (SQRT1_2, 0.0), SR: (0.0, SQRT1_2),
       FLC: (1.0, 0.0), FRC: (0.0, 1.0), BC: (0.5, 0.5)}
DEFAULT_MASK = {1: FC, 2: FL | FR, 3: FL | FR | FC, 4: FL | FR | BL | BR, 5: FL | FR | FC | BL | BR,
                6: FL | FR | FC | LFE | BL | BR, 7: FL | FR | FC | LFE | BL | BR | BC,
                8: FL | FR | FC | LFE | BL | BR | SL | SR}


def mask_order(mask):
    return [1 << b for b in range(32) if mask & (1 << b)]   # interleave order = ascending bit


def downmix(frame, mask):
    l = r = 0.0
    for v, bit in zip(frame, mask_order(mask)):
        cl, cr = DMX.get(bit, (0.0, 0.0))
        l += cl * v
        r += cr * v
    return l, r


def normalize_lufs(target, measured_I, tp_dbtp, ceiling=-1.0):
    G = target - measured_I
    if tp_dbtp + G > ceiling:
        Gp = ceiling - tp_dbtp
        return Gp, measured_I + Gp, True
    return G, target, False


def fmt_db(v):
    """1 decimal, comma, U+2212 minus, half away from zero, no '-0,0'."""
    a = math.floor(abs(v) * 10 + 0.5 + 1e-9) / 10
    s = f"{a:.1f}".replace(".", ",")
    return ("−" + s) if (v < 0 and a != 0) else s


def main():
    out = {}
    print("FR-C2 at x=0.5:", {k: round(f(0.5), 6) for k, f in CURVES.items()}, " x=1:", {k: f(1.0) for k, f in CURVES.items()},
          " log60 at x=1e-9:", g_log(1e-9))
    out["fade_in_N5"] = {k: fade_in(5, k) for k in CURVES}
    out["fade_out_N5"] = {k: fade_out(5, k) for k in CURVES}
    out["fade_in_N2"] = {k: fade_in(2, k) for k in CURVES}
    for k, v in out["fade_in_N5"].items():
        print(f"  fade_in N=5 {k:12}:", [f"{x:.9f}" for x in v])
    jn = {"5ms@48000": joint_n(5, 48000), "5ms@44100": joint_n(5, 44100), "1ms@8000": joint_n(1, 8000),
          "50ms@192000": joint_n(50, 192000), "1ms@11025": joint_n(1, 11025), "3ms@22050": joint_n(3, 22050)}
    print("FR-C3 N:", jn)
    r = joint_ramp_up(240)
    out["joint_N"] = jn
    out["joint_up_N240_idx"] = {str(i): r[i] for i in (0, 1, 119, 120, 238, 239)}
    out["joint_up_N4"] = joint_ramp_up(4)
    print("  ramp-up N=240 idx 0,119,120,239:", [f"{r[i]:.9e}" for i in (0, 119, 120, 239)], " N=4:", joint_ramp_up(4))
    gains = {str(d): db_to_gain(d) for d in (-40.0, -6.0, -0.1, 0.0, 0.1, 6.0, 20.0)}
    out["gain"] = gains
    print("FR-20 gains:", {k: f"{v:.9f}" for k, v in gains.items()})
    dm = []
    cases = [("5.1 C=1", 0xFC, [0, 0, 1, 0, 0, 0]), ("5.1 BL=0.5", 0xFC, [0, 0, 0, 0, 0.5, 0]),
             ("5.1 LFE=1", 0xFC, [0, 0, 0, 1, 0, 0]), ("5.1 all=1", 0xFC, [1] * 6),
             ("5.1side FL FR FC LFE SL SR, SL=1", FL | FR | FC | LFE | SL | SR, [0, 0, 0, 0, 1, 0]),
             ("7.1 BL=1 SL=1", 0x18FC, [0, 0, 0, 0, 1, 0, 1, 0]), ("7.1 all=1", 0x18FC, [1] * 8),
             ("quad 0xCC L=0.2 BR=1", 0xCC, [0.2, 0, 0, 1]), ("5.0 0xDC C=1 BR=1", 0xDC, [0, 0, 1, 0, 1]),
             ("LCRS 0x41C BC=1", 0x41C, [0, 0, 0, 1])]
    for name, mask, fr in cases:
        l, rr = downmix(fr, mask)
        dm.append({"name": name, "mask": mask, "frame": fr, "L": l, "R": rr})
        print(f"FR-C5 {name:34} mask=0x{mask:X} order={[hex(b) for b in mask_order(mask)]} -> L'={l:.8f} R'={rr:.8f}")
    out["downmix"] = dm
    out["default_mask_by_count"] = DEFAULT_MASK
    nc = []
    for tgt, I, tp in ((-14, -20.0, -8.0), (-14, -20.0, -5.7), (-14, -16.0, -0.5), (-23, -14.0, -0.2), (-16, -30.0, -20.0)):
        Gp, ach, lim = normalize_lufs(tgt, I, tp)
        msg = (f"Громкость {fmt_db(tgt)[:-2] if float(tgt).is_integer() else fmt_db(tgt)} LUFS недостижима без перегрузки. Достигнуто {fmt_db(ach)} LUFS (пик {fmt_db(-1.0)} dBTP)."
               if lim else None)
        nc.append({"target": tgt, "I": I, "TP": tp, "G_applied_dB": Gp, "gain_lin": db_to_gain(Gp), "achieved": ach, "limited": lim, "message": msg})
        print(f"FR-22 target {tgt} I={I} TP={tp}: G={tgt - I:+.2f} applied {Gp:+.2f} dB (x{db_to_gain(Gp):.7f}) -> {ach:.2f} LUFS limited={lim} {msg or ''}")
    out["normalize_lufs"] = nc
    pk = 0.5
    gpk = db_to_gain(-1.0) / pk
    out["normalize_peak"] = {"peak": pk, "gain": gpk, "target_lin": db_to_gain(-1.0)}
    print(f"FR-22 peak mode: 10^(-1/20) = {db_to_gain(-1.0):.10f}, peak 0.5 -> gain {gpk:.10f}")
    print("fmt_db:", [fmt_db(v) for v in (-15.25, -15.35, -0.04, 0.05, -14.0)])
    p = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "test", "resources", "ref", "basic_vectors.json"))
    with open(p, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print("written", p)


if __name__ == "__main__":
    main()
