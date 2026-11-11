#!/usr/bin/env python3
"""Случайные контрольные примеры для аудита кода форматов (code-formula-auditor, T14/T15).

Все ожидаемые значения считаются функциями эталона tools/ref/timeline_ref.py (PMR) — без
повторной реализации формул. Запуск: python3 tools/ref/audit_fmt_gen.py --write
→ core/src/test/resources/ref/audit_fmt_vectors.json (детерминированно, seed фиксирован).
"""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import timeline_ref as R  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'core', 'src', 'test', 'resources', 'ref',
                   'audit_fmt_vectors.json')


def decision_format(n, fs):
    """Формат по решению главной сессии (timeline.md, «Решения»): < 1 ч — «м:сс.ммм»."""
    s = R.format_samples(n, fs)
    if s.count(':') == 1 and s.startswith('0') and len(s) == 9:
        s = s[1:]
    return s


def decision_message(a, b, A, B, fs):
    da = R.samples_to_ms_display(a, fs) - R.samples_to_ms_display(A, fs)
    db = R.samples_to_ms_display(B, fs) - R.samples_to_ms_display(b, fs)
    s1 = f'на {da} мс раньше выделения' if da else 'совпадает с выделением'
    s2 = f'на {db} мс позже' if db else 'совпадает с выделением'
    return (f'Фактическое начало {decision_format(A, fs)} ({s1}), '
            f'конец {decision_format(B, fs)} ({s2}).')


def gen_fast_trim(rng, n):
    out = []
    for _ in range(n):
        codec = rng.choice(['aac', 'aac', 'mp3', 'ac3', 'opus', 'opus', 'flac'])
        if codec == 'aac':
            fs = rng.choice([44100, 48000]); D = rng.choice([0, 1024, 2112]); sizes = None; F = 1024
        elif codec == 'mp3':
            fs = 44100; D = rng.choice([1105, 529]); F = 1152; sizes = None
        elif codec == 'ac3':
            fs = 48000; D = 0; F = 1536; sizes = None
        elif codec == 'opus':
            fs = 48000; D = 312; F = 0
        else:
            fs = 44100; D = 0; F = 0
        L = rng.randint(20000, 400000)
        if codec in ('opus', 'flac'):
            choices = [120, 240, 480, 960, 1920, 2880] if codec == 'opus' else [576, 1152, 4096, 4608]
            sizes = []
            tot = 0
            while tot < L + D:
                s = rng.choice(choices); sizes.append(s); tot += s
        else:
            K = -(-(L + D) // F)
            sizes = [F] * K
        starts = [0]
        for s in sizes:
            starts.append(starts[-1] + s)
        a = rng.randint(0, L - 1)
        b = rng.randint(a + 1, min(L, a + rng.choice([1, 50, 5000, 100000, L])))
        if codec == 'opus':
            W, Ws = 0, R.OPUS_PREROLL_48K
        elif codec == 'mp3':
            W, Ws = rng.choice([2, 2, 3, 4]), 0
        else:
            W, Ws = R.WARMUP[codec], 0
        p = R.fast_trim_plan(starts, L, D, a, b, warmup_frames=W, warmup_samples=Ws)
        out.append(dict(codec=codec, fs=fs, D=D, L=L, sizes=sizes, a=a, b=b, W=W, Ws=Ws,
                        message=decision_message(a, b, p['A'], p['B'], fs), **p))
    return out


def gen_gap_fill(rng, n):
    out = []
    for _ in range(n):
        fs = rng.choice([8000, 22050, 44100, 48000, 96000])
        blk = rng.choice([256, 1024, 1152, 2048, 4410])
        pts = rng.randint(0, 10**7)
        frames = []
        for _ in range(rng.randint(2, 25)):
            frames.append([pts, blk])
            nominal = blk * 1_000_000 // fs
            kind = rng.random()
            if kind < 0.5:
                pts += nominal + rng.randint(-1000, 1000)       # дрожание вокруг порога 1 мс (вкл. ровно ±1000)
            elif kind < 0.7:
                pts += nominal + rng.randint(1001, 300000)      # разрыв
            elif kind < 0.85:
                pts += max(0, nominal - rng.randint(1001, 40000))  # перекрытие
            else:
                pts += nominal + rng.choice([1000, -1000, 999, 1001])
        ops, total = R.gap_fill([tuple(f) for f in frames], fs)
        out.append(dict(fs=fs, frames=frames, ops=[list(o) for o in ops], total=total))
    return out


def gen_mp4(rng, n):
    out = []
    for i in range(n):
        fs = rng.choice([8000, 16000, 22050, 32000, 44100, 48000])
        delay = rng.choice([0, 1024, 2112, 960])
        nn = rng.randint(1, 3_000_000)
        if i % 4 == 0:
            nn = rng.randint(1, 3000) * 1024 - delay if rng.random() < 0.5 else rng.randint(1, 3000) * 1024
            nn = max(1, nn)
        K = -(-(delay + nn) // 1024)
        mv = rng.choice([fs, fs, 1000, 600, 90000])
        e = R.mp4_edit(nn, delay, fs, K, 1024, mv)
        out.append(dict(n_out=nn, delay=delay, fs=fs, K=K, mvhd_ts=mv, **e))
    return out


def gen_mp4_input(rng, n):
    out = []
    for _ in range(n):
        fs = rng.choice([22050, 44100, 48000])
        mt = rng.choice([1024, 2112, 0])
        N = rng.randint(1000, 5_000_000)
        mv = rng.choice([1000, fs, 600])
        seg = N if mv == fs else (N * mv) // fs          # ffmpeg пишет сегмент с усечением (timeline.md ф. 10)
        K = -(-(mt + N) // 1024)
        stts_total = mt + N                              # конвенция ffmpeg
        smpb = None
        if rng.random() < 0.3:
            smpb = dict(delay=mt, padding=K * 1024 - mt - N, samples=N)
        r = R.mp4_input_trim(mt, seg, mv, fs, stts_total, smpb)
        out.append(dict(fs=fs, media_time=mt, seg=seg, mvhd_ts=mv, K=K, stts_total=stts_total,
                        smpb=R.itunsmpb(smpb['delay'], smpb['padding'], smpb['samples']) if smpb else None,
                        skip=r['skip'], length=r['length'], true_length=N))
    return out


def gen_space(rng, n):
    out = []
    for _ in range(n):
        d = rng.randint(1, 24 * 3600 * 10**6); fs = rng.choice([8000, 44100, 48000, 96000, 192000]); ch = rng.randint(1, 8)
        out.append(dict(duration_us=d, fs=fs, ch=ch, required_bytes=R.space_required_bytes(d, fs, ch)))
    return out


def gen_display(rng, n):
    out = []
    for _ in range(n):
        fs = rng.choice([8000, 11025, 22050, 44100, 48000, 96000, 192000])
        nn = rng.choice([rng.randint(0, 10**5), rng.randint(0, 3600 * fs + 10**4), rng.randint(0, 30 * 3600 * fs)])
        out.append(dict(n=nn, fs=fs, text=decision_format(nn, fs)))
    for fs in (44100, 48000):  # граница часа
        for nn in (3600 * fs - 1, 3600 * fs - fs // 2000, 3600 * fs - fs // 2000 - 1, 3600 * fs):
            out.append(dict(n=nn, fs=fs, text=decision_format(nn, fs)))
    return out


def main():
    rng = random.Random(20261111)
    data = dict(
        source='tools/ref/audit_fmt_gen.py (функции timeline_ref.py), seed 20261111',
        fast_trim=gen_fast_trim(rng, 300),
        gap_fill=gen_gap_fill(rng, 300),
        mp4_edit=gen_mp4(rng, 200),
        mp4_input=gen_mp4_input(rng, 200),
        space=gen_space(rng, 100),
        display=gen_display(rng, 400),
    )
    if '--write' in sys.argv:
        with open(OUT, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
        print('written', os.path.normpath(OUT))
    else:
        print({k: len(v) if isinstance(v, list) else v for k, v in data.items()})


if __name__ == '__main__':
    main()
