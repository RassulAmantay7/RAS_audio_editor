#!/usr/bin/env python3
"""
Эталон PMR для T4 (время, пирамида пиков, список правок, FR-7/10/23/25/26).
Спецификация и обоснования: docs/math/timeline.md.

Запуск:
  python3 tools/ref/timeline_ref.py            # самопроверка (assert) + печать сводки
  python3 tools/ref/timeline_ref.py --write    # + запись векторов в core/src/test/resources/ref/
  python3 tools/ref/timeline_ref.py --ffmpeg   # + практическая сверка с ffmpeg (если установлен)

Зависимости: python3 >= 3.8, numpy. Всё целочисленное — без float/double.
"""
import json, math, os, re, struct, subprocess, sys, tempfile
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'core', 'src', 'test', 'resources', 'ref')

# ---------------------------------------------------------------------------
# FR-C1. Время <-> сэмплы. Только целые. n = floor((T_ms*fs + 500)/1000)
# (half-up для T_ms >= 0). Источник: FR-C1/FR-15 ТЗ; округление half-up —
# IEEE 754-2008 roundTiesToAway для положительных чисел совпадает с half-up.
# ---------------------------------------------------------------------------
_TIME_RE = re.compile(r'^\s*(?:(?:(\d+):)?(\d+):)?(\d+)(?:[.,](\d{1,3}))?\s*$')


class TimeParseError(ValueError):
    pass


def parse_time_ms(s: str) -> int:
    """'[[ч:]мм:]сс[.ммм]' -> целые миллисекунды. Ведущее поле не ограничено,
    последующие (мм, сс) < 60. Дробь 1..3 цифры ('.5' = 500 мс). Запятая = точка."""
    m = _TIME_RE.match(s)
    if not m:
        raise TimeParseError('format')
    h, mi, se, fr = m.groups()
    h = int(h) if h is not None else None
    mi = int(mi) if mi is not None else None
    se = int(se)
    if mi is not None and se >= 60:
        raise TimeParseError('seconds>=60')
    if h is not None and mi >= 60:
        raise TimeParseError('minutes>=60')
    ms = int((fr or '0').ljust(3, '0'))
    return (((h or 0) * 60 + (mi or 0)) * 60 + se) * 1000 + ms


def ms_to_samples(t_ms: int, fs: int) -> int:
    assert t_ms >= 0 and fs > 0
    return (t_ms * fs + 500) // 1000


def parse_time_to_samples(s: str, fs: int) -> int:
    return ms_to_samples(parse_time_ms(s), fs)


def samples_to_ms_display(n: int, fs: int) -> int:
    """Показ: round_half_up(n*1000/fs) в целых."""
    assert n >= 0
    return (2 * n * 1000 + fs) // (2 * fs)


def format_ms(t_ms: int) -> str:
    """< 1 ч: 'мм:сс.ммм'; >= 1 ч: 'ч:мм:сс.ммм'. Перенос разрядов — после округления."""
    h, r = divmod(t_ms, 3600000)
    mi, r = divmod(r, 60000)
    se, ms = divmod(r, 1000)
    if h:
        return f'{h}:{mi:02d}:{se:02d}.{ms:03d}'
    return f'{mi:02d}:{se:02d}.{ms:03d}'


def format_samples(n: int, fs: int) -> str:
    return format_ms(samples_to_ms_display(n, fs))


def d_ms_to_samples(d_ms: int, fs: int) -> int:
    """N стыка/fade из целых мс: FR-C3 N = round_half_up(d*fs)."""
    return (d_ms * fs + 500) // 1000


# ---------------------------------------------------------------------------
# FR-C8. Пирамида пиков. Уровень k: блоки B_k = 256*4^k, последний блок
# неполный; min/max ассоциативны -> уровень k+1 = min/max четвёрок блоков k
# (точно, без округления).
# ---------------------------------------------------------------------------
B0 = 256


def pyramid(x: np.ndarray):
    """x: float32 [L] одного канала -> список уровней [(mins, maxs)]."""
    L = len(x)
    levels = []
    nb = -(-L // B0)
    pad = np.full(nb * B0, np.nan, np.float32); pad[:L] = x
    blk = pad.reshape(nb, B0)
    mn, mx = np.nanmin(blk, 1), np.nanmax(blk, 1)
    levels.append((mn, mx))
    while len(mn) > 1:
        n2 = -(-len(mn) // 4)
        a = np.full(n2 * 4, np.nan, np.float32); a[:len(mn)] = mn
        b = np.full(n2 * 4, np.nan, np.float32); b[:len(mx)] = mx
        mn, mx = np.nanmin(a.reshape(n2, 4), 1), np.nanmax(b.reshape(n2, 4), 1)
        levels.append((mn, mx))
    return levels


def n_levels(L: int) -> int:
    k, nb = 1, -(-L // B0)
    while nb > 1:
        nb = -(-nb // 4); k += 1
    return k


def choose_level(spp_num: int, spp_den: int):
    """spp = spp_num/spp_den сэмплов на пиксель. Возвращает -1 (сырые сэмплы)
    или наибольшее k с 256*4^k <= spp."""
    if spp_num < B0 * spp_den:
        return -1
    k = 0
    while B0 * 4 ** (k + 1) * spp_den <= spp_num:
        k += 1
    return k


def first_changed_block(p: int, k: int) -> int:
    return p // (B0 * 4 ** k)


# ---------------------------------------------------------------------------
# Список правок. Каждая операция — в координатах результата всех предыдущих.
# Эталонный рендер — «жадный» (вся последовательность целиком), float32,
# умножения строго по порядку операций (без слияния коэффициентов).
# ---------------------------------------------------------------------------
F32 = np.float32


def raised_cos_in(N: int) -> np.ndarray:
    """FR-C3: g(x)=0.5(1-cos(pi x)), x_i=i/(N-1) (дискретизация как FR-C2).
    Коэффициенты считаются в double и округляются во float32."""
    i = np.arange(N, dtype=np.float64)
    return (0.5 * (1.0 - np.cos(np.pi * i / (N - 1)))).astype(F32)


def fade_curve(N: int, kind: str) -> np.ndarray:
    """FR-C2, fade in; fade out = переворот."""
    x = np.arange(N, dtype=np.float64) / (N - 1)
    if kind == 'linear':
        g = x
    elif kind == 'equal_power':
        g = np.sin(np.pi * x / 2)
    elif kind == 'log60':
        g = np.where(x > 0, 10.0 ** (-60.0 * (1.0 - x) / 20.0), 0.0)
    else:
        raise ValueError(kind)
    return g.astype(F32)


def joint_n(N: int, piece_len: int) -> int:
    """FR-19: если кусок короче 2N, N := floor(len/2). N < 2 -> без сглаживания."""
    n = min(N, piece_len // 2)
    return n if n >= 2 else 0


def _fade_out_tail(y, end, n):          # затухание последних n сэмплов до end
    if n: y[end - n:end] *= raised_cos_in(n)[::-1, None]


def _fade_in_head(y, start, n):         # нарастание первых n сэмплов от start
    if n: y[start:start + n] *= raised_cos_in(n)[:, None]


def apply_op(x: np.ndarray, op: dict) -> np.ndarray:
    """x: float32 [L, C]. Возвращает новый массив."""
    L = x.shape[0]
    t = op['type']
    if t == 'trim':                       # оставить [a,b)
        a, b, N = op['a'], op['b'], op['N']
        assert 0 <= a < b <= L
        y = x[a:b].copy()
        n = joint_n(N, b - a)
        if a > 0: _fade_in_head(y, 0, n)          # новая граница в начале
        if b < L: _fade_out_tail(y, len(y), n)    # новая граница в конце
        return y
    if t == 'delete':                     # удалить [a,b)
        a, b, N = op['a'], op['b'], op['N']
        assert 0 <= a < b <= L and not (a == 0 and b == L)
        left, right = x[:a].copy(), x[b:].copy()
        if a > 0 and b < L:                     # склейка
            _fade_out_tail(left, a, joint_n(N, a))
            _fade_in_head(right, 0, joint_n(N, L - b))
        elif a == 0:                            # фактически обрезка начала
            _fade_in_head(right, 0, joint_n(N, L - b))
        else:                                   # b == L: обрезка конца
            _fade_out_tail(left, a, joint_n(N, a))
        return np.concatenate([left, right])
    if t == 'gain':                       # g хранится как float32 в операции
        a, b = op['a'], op['b']
        y = x.copy(); y[a:b] *= F32(op['g32']); return y
    if t == 'fade':
        a, b = op['a'], op['b']
        g = fade_curve(b - a, op['curve'])
        if op['dir'] == 'out': g = g[::-1]
        y = x.copy(); y[a:b] *= g[:, None]; return y
    if t == 'normalize':                  # G «заморожен» в операции (float32)
        y = x.copy(); y *= F32(op['g32']); return y
    raise ValueError(t)


def render(x0: np.ndarray, ops) -> np.ndarray:
    y = x0
    for op in ops:
        y = apply_op(y, op)
    return y


def gain_db_to_g32(db_tenths: int) -> float:
    """FR-20: g=10^(dB/20); dB задаётся десятыми (шаг 0,1) -> без ошибки ввода."""
    return float(F32(10.0 ** (db_tenths / 200.0)))


def normalize_peak_g32(y: np.ndarray) -> float:
    pk = float(np.max(np.abs(y)))
    if pk == 0.0:
        raise ValueError('silence: peak normalization unavailable')
    return float(F32(10.0 ** (-1.0 / 20.0) / pk))


def first_changed_output_sample(op: dict, L_in: int) -> int:
    """С какого сэмпла результата пересчитывать пирамиду после добавления/отмены op."""
    t = op['type']
    if t == 'trim':
        return 0 if op['a'] > 0 else max(0, op['b'] - joint_n(op['N'], op['b'] - op['a']))
    if t == 'delete':
        return max(0, op['a'] - joint_n(op['N'], op['a'])) if op['a'] > 0 else 0
    if t in ('gain', 'fade'):
        return op['a']
    return 0  # normalize


def out_len(op: dict, L_in: int) -> int:
    t = op['type']
    if t == 'trim': return op['b'] - op['a']
    if t == 'delete': return L_in - (op['b'] - op['a'])
    return L_in


# ---------------------------------------------------------------------------
# FR-7. Заполнение разрывов PTS. Всё в целых: pts в мкс, fs в Гц.
# Ожидаемая позиция считается от начала (без накопления ошибки).
# ---------------------------------------------------------------------------
def gap_fill(frames, fs: int, thr_us: int = 1000):
    """frames: [(pts_us, n_samples)] выходных буферов декодера по порядку.
    Возвращает [(kind, count)] — 'zeros' вставить, 'keep' сэмплов, 'drop' отбросить."""
    out, written = [], 0
    pts0 = frames[0][0]
    for pts, n in frames:
        d = pts - pts0
        # расхождение в мкс*fs (целое): d*fs - written*1e6
        diff = d * fs - written * 1_000_000
        if diff > thr_us * fs:                          # разрыв > 1 мс
            pos = (d * fs + 500_000) // 1_000_000       # round_half_up(d*fs/1e6)
            z = pos - written
            out.append(('zeros', z)); written += z
            out.append(('keep', n)); written += n
        elif diff < -thr_us * fs:                       # перекрытие > 1 мс
            pos = max(0, (d * fs + 500_000) // 1_000_000)
            drop = min(n, written - pos)
            out.append(('drop', drop))
            out.append(('keep', n - drop)); written += n - drop
        else:
            out.append(('keep', n)); written += n
    return out, written


# ---------------------------------------------------------------------------
# FR-10. Требуемое место.
# ---------------------------------------------------------------------------
MB = 1_000_000


def space_required_bytes(duration_us: int, fs: int, ch: int, margin_bytes: int = 200 * MB) -> int:
    n = -(-duration_us * fs // 1_000_000)   # ceil
    return n * ch * 4 + margin_bytes


def cache_bytes(n_samples: int, ch: int) -> int:
    return n_samples * ch * 4


# ---------------------------------------------------------------------------
# FR-23. Длины кадров.
# ---------------------------------------------------------------------------
OPUS_FRAME_48K = [480, 960, 1920, 2880] * 3 + [480, 960] * 2 + [120, 240, 480, 960] * 4


def opus_packet_samples48(pkt: bytes) -> int:
    """RFC 6716 §3.1: TOC -> config, c; длительность пакета в сэмплах 48 кГц."""
    toc = pkt[0]
    fsz = OPUS_FRAME_48K[toc >> 3]
    c = toc & 3
    if c == 0: nf = 1
    elif c in (1, 2): nf = 2
    else: nf = pkt[1] & 0x3F
    n = nf * fsz
    if n > 5760: raise ValueError('opus packet > 120 ms')
    return n


def eac3_samples(numblkscod: int, fscod: int) -> int:
    """ATSC A/52 Annex E: numblks = {1,2,3,6}[numblkscod]; при fscod==3 всегда 6."""
    nb = 6 if fscod == 3 else [1, 2, 3, 6][numblkscod]
    return 256 * nb


def mp3_samples(version_id: int, layer: int) -> int:
    """ISO/IEC 11172-3, 13818-3: Layer III: MPEG-1 1152, MPEG-2/2.5 576; Layer II 1152; Layer I 384."""
    if layer == 1: return 384
    if layer == 2: return 1152
    return 1152 if version_id == 3 else 576


def dts_samples(nblks: int) -> int:
    """ETSI TS 102 114 §5.3: PCM-сэмплов в кадре ядра = (NBLKS+1)*32."""
    return (nblks + 1) * 32


WARMUP = {  # кадров разгона (минимум; для MP3 — см. mp3 bit reservoir в timeline.md)
    'aac': 1, 'he-aac': 1, 'ac3': 1, 'eac3': 1, 'mp3': 2, 'vorbis': 1,
    'flac': 0, 'pcm': 0, 'dts': 1,
}
OPUS_PREROLL_48K = 3840   # RFC 7845 §4.6: не менее 80 мс


def fast_trim_plan(starts, L_user: int, D: int, a: int, b: int, warmup_frames: int = 1,
                   warmup_samples: int = 0):
    """starts: начала пакетов в шкале декодированного потока (вкл. задержку D), длиной K+1
    (последний элемент = конец последнего пакета). Пользовательская шкала: u = s - D.
    Выделение [a,b) в u. Возвращает индексы и фактические границы (u)."""
    import bisect
    ia = bisect.bisect_right(starts, a + D) - 1
    il = bisect.bisect_right(starts, b - 1 + D) - 1
    A = max(0, starts[ia] - D)
    B = min(L_user, starts[il + 1] - D)
    i_s = ia
    if warmup_samples:
        while i_s > 0 and starts[ia] - starts[i_s] < warmup_samples: i_s -= 1
    else:
        i_s = max(0, ia - warmup_frames)
    hidden = (A + D) - starts[i_s]          # сэмплов скрыть в начале нового файла
    return dict(first_pkt=i_s, first_audible_pkt=ia, last_pkt=il, A=A, B=B,
                hidden=hidden, length=B - A)


def fast_trim_message(a, b, A, B, fs) -> str:
    da = samples_to_ms_display(a, fs) - samples_to_ms_display(A, fs)
    db = samples_to_ms_display(B, fs) - samples_to_ms_display(b, fs)
    s1 = f'на {da} мс раньше выделения' if da else 'совпадает с выделением'
    s2 = f'на {db} мс позже' if db else 'совпадает с выделением'
    return (f'Фактическое начало {format_samples(A, fs)} ({s1}), '
            f'конец {format_samples(B, fs)} ({s2}).')


# ---------------------------------------------------------------------------
# FR-25. Выход: MP4 elst, Ogg Opus.
# ---------------------------------------------------------------------------
def mp4_edit(n_out: int, enc_delay: int, fs: int, n_frames: int, frame: int = 1024,
             mvhd_ts: int = None):
    """mdhd.timescale = fs. Рекомендуется mvhd.timescale = fs (точно)."""
    mvhd_ts = mvhd_ts or fs
    seg = (n_out * mvhd_ts + fs // 2) // fs if mvhd_ts != fs else n_out
    total = enc_delay + n_out
    assert n_frames * frame >= total
    last = total - (n_frames - 1) * frame        # stts последнего сэмпла (ffmpeg-конвенция)
    assert 0 < last <= frame
    return dict(mdhd_timescale=fs, mvhd_timescale=mvhd_ts, elst_media_time=enc_delay,
                elst_segment_duration=seg, stts=[[n_frames - 1, frame], [1, last]],
                mdhd_duration=total, roll_distance=-1)


def ogg_opus_final_granule(n_out48: int, pre_skip: int) -> int:
    return pre_skip + n_out48


def opus_packets_needed(n_out48: int, pre_skip: int, frame48: int = 960) -> int:
    return -(-(pre_skip + n_out48) // frame48)


def itunsmpb(delay: int, padding: int, n: int) -> str:
    return ' 00000000 %08X %08X %016X' % (delay, padding, n) + ' 00000000' * 8


def parse_itunsmpb(s: str):
    f = s.split()
    return dict(delay=int(f[1], 16), padding=int(f[2], 16), samples=int(f[3], 16))


# ---------------------------------------------------------------------------
# FR-26. Вход: длины и задержки.
# ---------------------------------------------------------------------------
MP3_DECODER_DELAY = 529


def mp3_lame_trim(frames: int, spf: int, enc_delay: int, enc_padding: int):
    """LAME tech spec (Mp3 Info Tag rev 1), ffmpeg mp3dec.c: пропустить delay+529,
    длина = frames*spf - delay - padding. frames — без кадра Info/Xing."""
    return dict(skip=enc_delay + MP3_DECODER_DELAY,
                length=frames * spf - enc_delay - enc_padding)


def opus_input_trim(pre_skip: int, last_granule: int):
    return dict(skip=pre_skip, length=last_granule - pre_skip)


def mp4_input_trim(media_time: int, seg_dur: int, mvhd_ts: int, mdhd_ts: int,
                   stts_total: int, smpb=None):
    """Приоритет: elst (если mvhd_ts кратна/равна mdhd_ts -> точно) > iTunSMPB > stts."""
    if media_time is not None and media_time >= 0:
        exact = (seg_dur * mdhd_ts) % mvhd_ts == 0
        n = (seg_dur * mdhd_ts + mvhd_ts // 2) // mvhd_ts
        if not exact or mvhd_ts < mdhd_ts:
            # сегмент записан грубо: длина из stts (ffmpeg) или iTunSMPB точнее
            if smpb: n = smpb['samples']
            elif stts_total - media_time <= n + mdhd_ts // mvhd_ts: n = stts_total - media_time
        return dict(skip=media_time, length=n, source='elst')
    if smpb:
        return dict(skip=smpb['delay'], length=smpb['samples'], source='iTunSMPB')
    return dict(skip=0, length=stts_total, source='none')



# ---------------------------------------------------------------------------
# FR-32. Цикл выделения. Поставщик пишет в AudioTrack блоками; позиция рендера
# pos перескакивает на start в момент pos == end ВНУТРИ блока (без тишины и
# сглаживания). Журнал сегментов (w0, p0, n) связывает номер записанного кадра
# AudioTrack с позицией рендера -> курсор без накопленной ошибки.
# ---------------------------------------------------------------------------
def loop_fill(pos: int, start: int, end: int, nframes: int):
    """Возвращает (новый pos, [(p0, n)] — куски рендера, которыми заполнен блок)."""
    assert 0 <= start < end
    if not (start <= pos < end):          # курсор вне выделения -> к началу
        pos = start
    out = []
    while nframes > 0:
        n = min(nframes, end - pos)
        out.append((pos, n)); nframes -= n; pos += n
        if pos == end:
            pos = start
    return pos, out


def loop_stream(start, end, total, block, changes=()):
    """Эталон потока индексов рендера. changes: [(written_frame, new_start, new_end)] —
    применяются перед блоком, начинающимся с written >= written_frame."""
    pos, w, idx, seg_log = start, 0, [], []
    ch = sorted(changes)
    while w < total:
        while ch and ch[0][0] <= w:
            _, start, end = ch.pop(0)
        pos, pieces = loop_fill(pos, start, end, min(block, total - w))
        for p0, n in pieces:
            seg_log.append((w, p0, n)); idx.extend(range(p0, p0 + n)); w += n
    return idx, seg_log


def cursor_from_timestamp(seg_log, frame_pos: int, ts_nanos: int, now_nanos: int, fs: int):
    """AudioTimestamp(framePosition, nanoTime): сейчас звучит кадр
    F = frame_pos + round((now-ts)*fs/1e9). Позиция рендера — по журналу сегментов."""
    F = frame_pos + ((now_nanos - ts_nanos) * fs + 500_000_000) // 1_000_000_000
    for w0, p0, n in reversed(seg_log):
        if w0 <= F < w0 + n:
            return p0 + (F - w0)
    return None


# ---------------------------------------------------------------------------
# FR-33. Автопроверка. FLAC MD5 (RFC 9639 §8.2, STREAMINFO): MD5 от
# знаковых сэмплов, чередование каналов, little-endian, ширина ceil(bps/8) байт.
# ---------------------------------------------------------------------------
import hashlib


def flac_md5(pcm_int: np.ndarray, bps: int) -> str:
    """pcm_int: int [L, C]."""
    w = (bps + 7) // 8
    a = pcm_int.astype('<i4').reshape(-1)
    b = a.view(np.uint8).reshape(-1, 4)[:, :w]
    return hashlib.md5(b.tobytes()).hexdigest()


def float_to_int24_exact(f32: np.ndarray) -> np.ndarray:
    """Обратное к декодеру с выходом float (int24 / 2^23): точно, 24 бита мантиссы."""
    v = f32.astype(np.float64) * 8388608.0
    assert np.all(v == np.round(v))
    return v.astype(np.int32)


def verify_length(kind: str, n_dec: int, n_exp: int, frame: int = 0, fallback=False):
    if kind in ('wav', 'flac'):
        tol = 0
    elif kind in ('aac', 'opus'):
        tol = frame if fallback else 1
    else:                       # как есть / быстрая обрезка: длительность
        tol = frame
    return abs(n_dec - n_exp) <= tol, tol


def bitrate_check(size_bytes: int, n: int, fs: int, target_bps: int, tol=0.15):
    avg = size_bytes * 8 * fs / n
    dev = avg / target_bps - 1.0
    return dict(avg_bps=round(avg), deviation=round(dev, 4), warn=abs(dev) > tol)

# ---------------------------------------------------------------------------
# Самопроверка и векторы
# ---------------------------------------------------------------------------
def test_signal(L, C=2):
    n = np.arange(L, dtype=np.int64)
    ch = [((n * 7919 + 13 * c) % 2001 - 1000).astype(F32) / F32(1024) for c in range(C)]
    return np.stack(ch, 1).astype(F32)


def selfcheck():
    V = {}
    # FR-C1
    assert parse_time_to_samples('1:23.456', 44100) == 3680410
    assert parse_time_to_samples('1:23.456', 48000) == 4005888
    assert parse_time_to_samples('0:00.175', 44100) == 7718      # double даёт 7717
    assert math.floor(0.175 * 44100 + 0.5) == 7717                 # демонстрация ловушки
    c1 = []
    for s, fs in [('1:23.456', 44100), ('1:23.456', 48000), ('0:00.175', 44100),
                  ('0:00.700', 11025), ('0:00.005', 44100), ('1:00:00.000', 192000),
                  ('23:59:59.999', 192000), ('0:00.001', 8000), ('59.5', 44100),
                  ('1:02:03,4', 48000), ('0:00.000', 44100), ('100:00.000', 48000)]:
        c1.append(dict(text=s, fs=fs, ms=parse_time_ms(s), n=parse_time_to_samples(s, fs)))
    bad = ['', '1:60.000', '1:00:60', '1:60:00.000', '1:23.4567', '-1.0', '1::2', 'abc', '1.2.3', ' : ']
    for s in bad:
        try:
            parse_time_ms(s); raise AssertionError(s)
        except TimeParseError:
            pass
    disp = []
    for n, fs in [(3680410, 44100), (4005888, 48000), (44099, 44100), (44078, 44100),
                  (22, 44100), (2646000 * 60, 44100), (158760000 - 1, 44100), (0, 48000),
                  (24, 48000), (23, 48000), (959999976 // 1, 48000)]:
        disp.append(dict(n=n, fs=fs, ms=samples_to_ms_display(n, fs), text=format_samples(n, fs)))
    assert format_samples(3680410, 44100) == '01:23.456'
    assert format_samples(44099, 44100) == '00:01.000'          # 999.977 мс -> перенос
    assert format_samples(158760000 - 1, 44100) == '1:00:00.000'
    assert d_ms_to_samples(5, 48000) == 240 and d_ms_to_samples(5, 44100) == 221
    V['fr_c1_parse'] = c1; V['fr_c1_parse_invalid'] = bad; V['fr_c1_display'] = disp

    # FR-C8
    x = test_signal(256 * 16 + 77, 1)[:, 0]
    lv = pyramid(x)
    assert len(lv) == n_levels(len(x)) == 4
    for k, (mn, mx) in enumerate(lv):
        B = B0 * 4 ** k
        for j in range(len(mn)):
            seg = x[j * B:(j + 1) * B]
            assert mn[j] == seg.min() and mx[j] == seg.max()
    lvls = [dict(spp=f'{p}/{q}', level=choose_level(p, q)) for p, q in
            [(1, 8), (255, 1), (256, 1), (1023, 1), (1024, 1), (4096, 1), (86400000, 1080), (511, 2), (512, 2)]]
    assert [d['level'] for d in lvls] == [-1, -1, 0, 0, 1, 2, 4, -1, 0]
    V['fr_c8_levels'] = dict(L=len(x), n_levels=len(lv), block=[B0 * 4 ** k for k in range(len(lv))],
                             n_blocks=[len(l[0]) for l in lv])
    V['fr_c8_choose_level'] = lvls
    V['fr_c8_n_levels'] = [dict(L=L, n_levels=n_levels(L)) for L in (1, 256, 257, 1024, 1025, 86400000)]

    # Список правок
    fs = 48000; L0 = 4800; x0 = test_signal(L0)
    N5 = d_ms_to_samples(5, fs)
    ops = [
        dict(type='delete', a=1000, b=1500, N=N5),
        dict(type='gain', a=200, b=900, g32=gain_db_to_g32(60)),
        dict(type='trim', a=100, b=4000, N=N5),
        dict(type='fade', a=0, b=300, curve='equal_power', dir='in'),
        dict(type='delete', a=3000, b=3100, N=N5),
    ]
    y = x0; lens = [L0]; fcs = []
    for op in ops:
        fcs.append(first_changed_output_sample(op, len(y)))
        y = apply_op(y, op); lens.append(len(y))
    g = normalize_peak_g32(y)
    ops.append(dict(type='normalize', mode='peak', g32=g)); y = apply_op(y, ops[-1]); lens.append(len(y)); fcs.append(0)
    assert lens == [4800, 4300, 4300, 3900, 3900, 3800, 3800]
    y2 = render(x0, ops)
    assert np.array_equal(y.view(np.uint32), y2.view(np.uint32))   # детерминизм
    # Короткий кусок: удалить так, чтобы справа осталось 100 < 2N=480 -> N=50
    r = apply_op(x0[:1000], dict(type='delete', a=300, b=900, N=240))
    assert len(r) == 400
    assert np.array_equal(r[:300 - 150], x0[:150])       # слева N=min(240,150)=150
    assert r[300, 0] == 0.0 and np.array_equal(r[350:], x0[950:1000])  # справа N=50
    # Выключенное сглаживание (N=0): склейка бит в бит
    r0 = apply_op(x0, dict(type='delete', a=1000, b=1500, N=0))
    assert np.array_equal(r0, np.concatenate([x0[:1000], x0[1500:]]))
    assert np.float32(gain_db_to_g32(60)) == np.float32(1.9952623)
    V['edit_signal'] = 'x[n,c] = float32(((n*7919 + 13*c) mod 2001) - 1000) / 1024, L=4800, C=2, fs=48000'
    V['edit_ops'] = ops
    V['edit_lengths'] = lens
    V['edit_first_changed'] = fcs
    idx = [0, 1, 99, 100, 239, 240, 500, 759, 760, 761, 999, 1000, 2000, 2899, 2900, 2999, 3000, 3239, 3240, 3799]
    V['edit_render_samples'] = dict(index=idx, ch0=[float(y[i, 0]) for i in idx], ch1=[float(y[i, 1]) for i in idx],
                                    tolerance_ulp=2)
    V['edit_render_peak'] = float(np.max(np.abs(y)))
    V['short_piece'] = dict(L=1000, op=dict(type='delete', a=300, b=900, N=240), N_left=150, N_right=50, out_len=400)

    # FR-7
    gf = []
    fr = [(1000000 + i * 21333, 1024) for i in range(3)]   # 48 кГц, ~1024/48000 c, мкс округлены
    o, w = gap_fill(fr, 48000); assert w == 3072 and all(k == 'keep' for k, _ in o)
    gf.append(dict(fs=48000, frames=fr, ops=o, total=w))
    fr = [(0, 4410), (100000, 4410), (300000, 4410)]        # 44,1 кГц: 0..100 мс, затем разрыв 100 мс
    o, w = gap_fill(fr, 44100); assert w == 4410 * 3 + 4410 and o[2] == ('zeros', 4410)
    gf.append(dict(fs=44100, frames=fr, ops=o, total=w))
    fr = [(0, 1024), (21333 + 1000, 1024)]                   # +1,0 мс ровно -> не разрыв (строго >)
    o, w = gap_fill(fr, 48000); assert w == 2048
    gf.append(dict(fs=48000, frames=fr, ops=o, total=w))
    fr = [(0, 1024), (21334 + 1001, 1024)]                   # +1,001 мс... относительно 21333,33
    o, w = gap_fill(fr, 48000)
    gf.append(dict(fs=48000, frames=fr, ops=o, total=w))
    fr = [(0, 1000), (10000, 1000)]                          # 48 кГц: перекрытие 1000-480=520 сэмплов
    o, w = gap_fill(fr, 48000); assert o == [('keep', 1000), ('drop', 520), ('keep', 480)] and w == 1480
    gf.append(dict(fs=48000, frames=fr, ops=o, total=w))
    fr = [(0, 960), (20000 + 12345, 960)]                    # разрыв 12,345 мс -> 592,56 -> 593 сэмпла
    o, w = gap_fill(fr, 48000); assert o[1] == ('zeros', 593) and w == 960 + 593 + 960
    gf.append(dict(fs=48000, frames=fr, ops=o, total=w))
    V['fr7_gap_fill'] = gf

    # FR-10
    assert cache_bytes(1800 * 48000, 2) == 691_200_000
    assert space_required_bytes(1800_000_000, 48000, 2) == 891_200_000
    V['fr10'] = [dict(duration_us=1800_000_000, fs=48000, ch=2, cache_bytes=691_200_000, required_bytes=891_200_000,
                      required_MB_ceil=892, cache_MB=691.2),
                 dict(duration_us=1000, fs=44100, ch=1, cache_bytes=cache_bytes(45, 1),
                      required_bytes=space_required_bytes(1000, 44100, 1)),
                 dict(duration_us=3600_000_000, fs=96000, ch=2, cache_bytes=cache_bytes(3600 * 96000, 2),
                      required_bytes=space_required_bytes(3600_000_000, 96000, 2))]

    # FR-23
    assert opus_packet_samples48(bytes([(16 << 3) | 0])) == 120
    assert opus_packet_samples48(bytes([(3 << 3) | 0])) == 2880
    assert opus_packet_samples48(bytes([(31 << 3) | 3, 6])) == 5760
    assert opus_packet_samples48(bytes([(1 << 3) | 1])) == 1920
    assert eac3_samples(0, 0) == 256 and eac3_samples(3, 0) == 1536 and eac3_samples(0, 3) == 1536
    V['opus_toc'] = [dict(toc=t, b1=b1, samples48=opus_packet_samples48(bytes([t, b1])))
                     for t, b1 in [(0, 0), (8, 0), (16, 0), (24, 0), (96, 0), (104, 0), (128, 0), (136, 0), (144, 0),
                                   (152, 0), (248, 0), (249, 0), (250, 0), (251, 3), (27, 2), (255, 6)]]
    ft = []
    F = 1024; D = 1024; L = 45100; starts = [i * F for i in range(-(-(L + D) // F) + 1)]
    p = fast_trim_plan(starts, L, D, 44100, 88200 // 2 + 1000, 1)  # a=1.000 c, b=1.0227 c (конец файла)
    ft.append(dict(codec='aac', fs=44100, F=F, D=D, L=L, a=44100, b=45100, **p))
    p = fast_trim_plan(starts, L, D, 10000, 20000, 1)
    assert p['A'] == 9216 and p['B'] == 20480 and p['first_pkt'] == 9 and p['hidden'] == 1024
    ft.append(dict(codec='aac', fs=44100, F=F, D=D, L=L, a=10000, b=20000, **p,
                   message=fast_trim_message(10000, 20000, p['A'], p['B'], 44100)))
    p = fast_trim_plan(starts, L, D, 0, 500, 1)
    assert p['A'] == 0 and p['first_pkt'] == 0 and p['hidden'] == 1024
    ft.append(dict(codec='aac', fs=44100, F=F, D=D, L=L, a=0, b=500, **p))
    # Сообщение из ТЗ: выделение 1.000..10.000 c при fs=48000, AAC, D=2112
    F = 1024; D = 2112; L = 48000 * 20; starts = [i * F for i in range(-(-(L + D) // F) + 1)]
    p = fast_trim_plan(starts, L, D, 48000, 480000, 1)
    ft.append(dict(codec='aac', fs=48000, F=F, D=D, L=L, a=48000, b=480000, **p,
                   message=fast_trim_message(48000, 480000, p['A'], p['B'], 48000)))
    # MP3 (ffmpeg/LAME): 41 кадр, D = 576+529 = 1105
    F = 1152; D = 1105; L = 45100; starts = [i * F for i in range(42)]
    p = fast_trim_plan(starts, L, D, 11567 + 3000, 30000, 2)
    ft.append(dict(codec='mp3', fs=44100, F=F, D=D, L=L, a=14567, b=30000, **p))
    # Opus: смешанные пакеты 20/60/10 мс, pre-skip 312, разгон 80 мс
    sizes = [960] * 10 + [2880] * 3 + [480] * 8 + [960] * 20
    starts = [0]
    for s in sizes: starts.append(starts[-1] + s)
    p = fast_trim_plan(starts, starts[-1] - 312 - 500, 312, 16000, 30000, warmup_samples=OPUS_PREROLL_48K)
    ft.append(dict(codec='opus', fs=48000, packet_sizes=sizes, D=312, L=starts[-1] - 812, a=16000, b=30000, **p))
    V['fr23_fast_trim'] = ft

    # FR-25
    e = mp4_edit(45100, 1024, 44100, 46)
    assert e['stts'] == [[45, 1024], [1, 44]] and e['elst_segment_duration'] == 45100
    V['fr25_mp4'] = [dict(n_out=45100, enc_delay=1024, fs=44100, n_frames=46, **e),
                     dict(n_out=45100, enc_delay=1024, fs=44100, n_frames=46,
                          **mp4_edit(45100, 1024, 44100, 46, mvhd_ts=1000)),
                     dict(n_out=86400000, enc_delay=2112, fs=48000, n_frames=-(-(86400000 + 2112) // 1024),
                          **mp4_edit(86400000, 2112, 48000, -(-(86400000 + 2112) // 1024)))]
    assert ogg_opus_final_granule(48100, 312) == 48412
    V['fr25_opus'] = [dict(n_out48=48100, pre_skip=312, final_granule=48412, packets_20ms=opus_packets_needed(48100, 312)),
                      dict(n_out48=49089, pre_skip=312, final_granule=49401, packets_20ms=opus_packets_needed(49089, 312)),
                      dict(n_out48=86400000, pre_skip=312, final_granule=86400312,
                           packets_20ms=opus_packets_needed(86400000, 312))]
    s = itunsmpb(2112, 1, 9_999_999 - 2112 - 1 + 1)
    V['itunsmpb'] = dict(example=s, parsed=parse_itunsmpb(s))

    # FR-26
    m = mp3_lame_trim(41, 1152, 576, 1556)
    assert m == dict(skip=1105, length=45100)
    V['fr26'] = dict(
        mp3_ffmpeg=dict(frames=41, spf=1152, delay=576, padding=1556, **m),
        opus_ffmpeg=dict(pre_skip=312, last_granule=48412, **opus_input_trim(312, 48412)),
        mp4_ffmpeg_ms=dict(media_time=1024, seg=1022, mvhd_ts=1000, mdhd_ts=44100, stts_total=46124,
                           **mp4_input_trim(1024, 1022, 1000, 44100, 46124)),
        mp4_exact=dict(media_time=1024, seg=45100, mvhd_ts=44100, mdhd_ts=44100, stts_total=46124,
                       **mp4_input_trim(1024, 45100, 44100, 44100, 46124)),
        mp4_apple=dict(media_time=2112, seg=441000, mvhd_ts=44100, mdhd_ts=44100, stts_total=444 * 1024,
                       **mp4_input_trim(2112, 441000, 44100, 44100, 444 * 1024)),
    )
    assert V['fr26']['mp4_ffmpeg_ms']['length'] == 45100

    # FR-32
    idx, log = loop_stream(48000, 96000, 5 * 48000, 4096)
    assert len(idx) == 240000
    assert all(idx[i] == 48000 + i % 48000 for i in range(len(idx)))
    i = idx.index(95999); assert idx[i + 1] == 48000
    for blk in (1, 7, 48000, 100000):
        assert loop_stream(48000, 96000, 5 * 48000, blk)[0] == idx
    idx1, _ = loop_stream(10, 11, 5, 3); assert idx1 == [10] * 5          # цикл 1 сэмпл
    idx3, _ = loop_stream(0, 3, 8, 5); assert idx3 == [0, 1, 2, 0, 1, 2, 0, 1]
    # смена конца во время воспроизведения: после 1000 кадров конец 600 -> pos 1000>=600 -> к началу
    idxc, logc = loop_stream(0, 2000, 1600, 500, changes=[(1000, 0, 600)])
    assert idxc[:1000] == list(range(1000)) and idxc[1000:1600] == list(range(600))
    # pos вне нового выделения слева (начало сдвинуто за курсор) -> к новому началу
    idxd, _ = loop_stream(0, 2000, 1200, 500, changes=[(500, 800, 1000)])
    assert idxd[500:700] == list(range(800, 1000)) and idxd[700:900] == list(range(800, 1000))
    # курсор: 60 проходов цикла 1,000 с при 48 кГц, задержка 0 после 59,5 c
    _, log60 = loop_stream(48000, 96000, 61 * 48000, 960)
    c = cursor_from_timestamp(log60, 59 * 48000, 0, 500_000_000, 48000)
    assert c == 48000 + 24000
    V['fr32_loop'] = dict(
        main=dict(start=48000, end=96000, passes=5, total=240000, rule='out[i] = 48000 + i mod 48000',
                  block_sizes_equal=[1, 7, 4096, 48000, 100000]),
        one_sample=dict(start=10, end=11, total=5, block=3, out=idx1),
        three=dict(start=0, end=3, total=8, block=5, out=idx3),
        change_end=dict(start=0, end=2000, total=1600, block=500, change=[1000, 0, 600],
                        rule='out[0:1000]=0..999, out[1000:1600]=0..599'),
        change_start=dict(start=0, end=2000, total=1200, block=500, change=[500, 800, 1000],
                          rule='out[500:700]=800..999, out[700:900]=800..999, out[900:1100]=800..999'),
        cursor=dict(start=48000, end=96000, block=960, frame_pos=59 * 48000, ts_nanos=0,
                    now_nanos=500_000_000, fs=48000, render_pos=72000))

    # FR-33: MD5 FLAC
    q16 = (test_signal(4800) * F32(32767)).astype(np.int32)        # точные целые
    q24 = (test_signal(4800).astype(np.float64) * 8388607).round().astype(np.int32)
    md16, md24 = flac_md5(q16, 16), flac_md5(q24, 24)
    f24 = (q24.astype(np.float64) / 8388608.0).astype(F32)
    assert np.array_equal(float_to_int24_exact(f24), q24)
    V['fr33_flac_md5'] = [
        dict(signal='(int) float32(edit_signal * 32767f) — усечение к нулю, int16, L=4800, C=2', bps=16, md5=md16),
        dict(signal='rint(double(edit_signal) * 8388607) (half-even) -> int24, L=4800, C=2', bps=24, md5=md24),
        dict(signal='тишина int16, L=0, C=2', bps=16, md5=hashlib.md5(b'').hexdigest())]
    V['fr33_tolerances'] = dict(wav=0, flac=0, aac=1, opus=1, aac_fallback='1 кадр (1024; HE 2048)',
                                opus_fallback='1 пакет (обычно 960 @48k)', as_is='пакеты 1:1; длительность ±1 кадр')
    V['fr33_bitrate'] = [dict(size=45_000_000, n=1800 * 48000, fs=48000, target=192000,
                              **bitrate_check(45_000_000, 1800 * 48000, 48000, 192000)),
                         dict(size=32_000_000, n=1800 * 48000, fs=48000, target=192000,
                              **bitrate_check(32_000_000, 1800 * 48000, 48000, 192000))]
    assert V['fr33_bitrate'][0]['warn'] is False and V['fr33_bitrate'][1]['warn'] is True
    return V


def ffmpeg_check():
    """Практическая сверка (ffmpeg 6.1): длины декодирования и метаданные."""
    td = tempfile.mkdtemp()
    def run(*a): subprocess.run(a, check=True, capture_output=True)
    def ndec(f, *pre):
        b = subprocess.run(['ffmpeg', '-v', 'error', *pre, '-i', f, '-f', 's16le', '-ac', '1', '-'],
                           capture_output=True, check=True).stdout
        return len(b) // 2
    res = {}
    for fs, L in ((44100, 45100), (48000, 48100)):
        w = os.path.join(td, f's{fs}.wav')
        run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', f'sine=f=1000:r={fs}', '-af', f'atrim=end_sample={L}', w)
        for name, args in (('aac.m4a', ['-c:a', 'aac']), ('aac_ts.m4a', ['-c:a', 'aac', '-movie_timescale', str(fs)]),
                           ('opus.ogg', ['-c:a', 'libopus']), ('mp3.mp3', ['-c:a', 'libmp3lame'])):
            f = os.path.join(td, f'{fs}_{name}')
            run('ffmpeg', '-v', 'error', '-y', '-i', w, *args, f)
            res[f'{fs}_{name}'] = ndec(f)
    print(json.dumps(res, indent=1))
    print('Ожидание (ffmpeg 6.1): m4a — НЕ обрезан хвост (46080 при 44,1 кГц), ogg 48100 / 49089, mp3 = L.')


def ffmpeg_flac_md5_check():
    """Сверка flac_md5 с STREAMINFO, записанным эталонным энкодером ffmpeg."""
    td = tempfile.mkdtemp(); res = {}
    for bps, scale in ((16, 32767), (24, 8388607)):
        x = test_signal(4800)
        q = (x * F32(32767)).astype(np.int32) if bps == 16 else \
            (x.astype(np.float64) * scale).round().astype(np.int32)
        raw = os.path.join(td, f'q{bps}.raw'); fl = os.path.join(td, f'q{bps}.flac')
        w = bps // 8
        open(raw, 'wb').write(q.astype('<i4').reshape(-1).view(np.uint8).reshape(-1, 4)[:, :w].tobytes())
        fmt = 's16le' if bps == 16 else 's24le'
        sf = 's16' if bps == 16 else 's32'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', fmt, '-ar', '48000', '-ac', '2', '-i', raw,
                        '-c:a', 'flac', '-sample_fmt', sf, '-bits_per_raw_sample', str(bps), fl], check=True)
        d = open(fl, 'rb').read()
        si = d[8:8 + 34]
        tot = ((si[13] & 0x0F) << 32) | struct.unpack('>I', si[14:18])[0]
        res[bps] = dict(streaminfo_md5=si[18:34].hex(), ours=flac_md5(q, bps), total_samples=tot)
        assert res[bps]['streaminfo_md5'] == res[bps]['ours'] and tot == 4800, res
    print('FLAC MD5 == STREAMINFO (ffmpeg):', json.dumps(res))


if __name__ == '__main__':
    V = selfcheck()
    print('selfcheck OK')
    for t in V['fr23_fast_trim']:
        if 'message' in t: print(t['message'])
    if '--write' in sys.argv:
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, 'timeline_vectors.json'), 'w', encoding='utf-8') as f:
            json.dump(V, f, ensure_ascii=False, indent=1, default=lambda o: o.item() if hasattr(o, 'item') else str(o))
        print('written', os.path.join(OUT, 'timeline_vectors.json'))
    if '--ffmpeg' in sys.argv:
        ffmpeg_check()
        ffmpeg_flac_md5_check()
