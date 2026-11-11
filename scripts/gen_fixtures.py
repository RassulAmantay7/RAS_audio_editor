#!/usr/bin/env python3
"""Генератор тестовых файлов (T5, FR-2, 3, 5, 6, 7, 26; NFR-11).

Запуск: python3 scripts/gen_fixtures.py  (нужен ffmpeg 6.x с aac, libmp3lame, libopus, ac3, eac3, dca).
Детерминированно: сигналы считаются здесь (целочисленно), ffmpeg вызывается с -bitexact и без метаданных.
Длина в сэмплах — в имени файла (последнее число перед расширением, у импульсов — imp<позиция>).

Выход:
  core/src/test/resources/fixtures/   — мелкие файлы для JUnit (:core)
  app/src/main/assets/selftest/       — набор для самопроверки на телефоне (≤ 3 МБ) + manifest.json
"""
import json, math, os, shutil, struct, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, 'core/src/test/resources/fixtures')
SELF = os.path.join(ROOT, 'app/src/main/assets/selftest')
BX = ['-fflags', '+bitexact', '-flags:a', '+bitexact', '-map_metadata', '-1', '-threads', '1']


def ff(*args):
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *args], check=True)


def signal(n, ch, fs, imp=None, amp=0.25):
    """Детерминированный тестовый сигнал int16: синус 440·(c+1) Гц, амплитуда amp; опционально импульс 0,8 в позиции imp."""
    out = []
    for i in range(n):
        for c in range(ch):
            v = amp * math.sin(2 * math.pi * 440 * (c + 1) * i / fs)
            if imp is not None:
                v = 0.03 * v / amp
                if i == imp:
                    v = 0.8
            out.append(int(round(v * 32767)))
    return out


def wav_bytes(samples, ch, fs, bits=16, fmt_tag=1, extra_chunks=b'', declared=None):
    """Простой WAV (RIFF, WAVE_FORMAT_PCM); extra_chunks вставляются перед data."""
    if bits == 16:
        data = struct.pack('<%dh' % len(samples), *samples)
    elif bits == 8:
        data = bytes((s >> 8) + 128 for s in samples)
    else:
        raise ValueError(bits)
    ba = ch * bits // 8
    fmt = struct.pack('<HHIIHH', fmt_tag, ch, fs, fs * ba, ba, bits)
    size = len(data) if declared is None else declared
    body = b'WAVE' + b'fmt ' + struct.pack('<I', len(fmt)) + fmt + extra_chunks + b'data' + struct.pack('<I', size) + data
    if len(data) % 2:
        body += b'\0'
    return b'RIFF' + struct.pack('<I', len(body)) + body


def write(path, b):
    with open(path, 'wb') as f:
        f.write(b)


def main():
    os.makedirs(FIX, exist_ok=True)
    os.makedirs(SELF, exist_ok=True)
    tmp = tempfile.mkdtemp()
    try:
        gen_core(tmp)
        gen_selftest(tmp)
    finally:
        shutil.rmtree(tmp)
    tot = sum(os.path.getsize(os.path.join(FIX, f)) for f in os.listdir(FIX))
    tot2 = sum(os.path.getsize(os.path.join(SELF, f)) for f in os.listdir(SELF))
    print(f'fixtures: {len(os.listdir(FIX))} файлов, {tot} Б; selftest: {len(os.listdir(SELF))} файлов, {tot2} Б')


def gen_core(tmp):
    F = lambda n: os.path.join(FIX, n)
    # Исходник 44,1 кГц стерео, 45 100 сэмплов (длины из timeline.md).
    src = os.path.join(tmp, 'src44.wav')
    write(src, wav_bytes(signal(45100, 2, 44100), 2, 44100))
    src48 = os.path.join(tmp, 'src48.wav')
    write(src48, wav_bytes(signal(48000, 2, 48000), 2, 48000))
    src48_100 = os.path.join(tmp, 'src48_100.wav')
    write(src48_100, wav_bytes(signal(48100, 2, 48000), 2, 48000))

    # WAV: разные разрядности (ffmpeg пишет EXTENSIBLE при > 16 бит или > 2 каналах).
    write(F('wav_s16_44100_2ch_45100.wav'), wav_bytes(signal(45100, 2, 44100), 2, 44100))
    write(F('wav_u8_8000_1ch_801.wav'), wav_bytes(signal(801, 1, 8000), 1, 8000, bits=8))
    for codec, name in [('pcm_s24le', 's24'), ('pcm_s32le', 's32'), ('pcm_f32le', 'f32'), ('pcm_f64le', 'f64')]:
        ff('-i', src48, '-t', '0.1', '-c:a', codec, *BX, F(f'wav_{name}_48000_2ch_4800.wav'))
    ff('-i', src48, '-t', '0.1', '-af', 'pan=5.1|FL=c0|FR=c1|FC=c0|LFE=c1|BL=c0|BR=c1', '-c:a', 'pcm_s16le', *BX,
       F('wav_ext_s16_48000_6ch_4800.wav'))
    # Нечётный чанк + LIST перед data (RIFF pad byte).
    odd = b'junk' + struct.pack('<I', 3) + b'abc\0' + b'LIST' + struct.pack('<I', 12) + b'INFOISFT\0\0\0\0'
    write(F('wav_oddchunks_s16_8000_1ch_801.wav'), wav_bytes(signal(801, 1, 8000), 1, 8000, extra_chunks=odd))
    # Усечён на 10 %: заявлено 4410 кадров, есть 3969 (FR-33).
    full = wav_bytes(signal(4410, 2, 44100), 2, 44100)
    write(F('wav_truncated_s16_44100_2ch_declared4410_3969.wav'), full[:44 + 3969 * 4])

    # AAC в MP4 (ffmpeg aac: delay 1024, mvhd 1000), ADTS, 5.1.
    ff('-i', src, '-c:a', 'aac', '-b:a', '128k', *BX, F('aac_44100_2ch_45100.m4a'))
    ff('-i', src, '-c:a', 'aac', '-b:a', '128k', *BX, '-f', 'adts', F('adts_44100_2ch_45100.aac'))
    ff('-i', src48, '-af', 'pan=5.1|FL=c0|FR=c1|FC=c0|LFE=c1|BL=c0|BR=c1', '-c:a', 'aac', '-b:a', '192k', *BX,
       F('aac51_48000_6ch_48000.m4a'))
    # Усечённый m4a (moov в конце → не читается).
    b = open(F('aac_44100_2ch_45100.m4a'), 'rb').read()
    write(F('aac_truncated_44100_2ch_45100.m4a'), b[:len(b) * 9 // 10])
    # MP3 с тегом LAME (delay 576, padding 1556, 41 кадр), Opus (pre-skip 312, гранула 48 412).
    ff('-i', src, '-c:a', 'libmp3lame', '-b:a', '128k', *BX, F('mp3_lame_44100_2ch_45100.mp3'))
    ff('-i', src48_100, '-c:a', 'libopus', '-b:a', '96k', *BX, F('opus_48000_2ch_48100.ogg'))
    # AC-3 / E-AC-3 / DTS: сырые потоки и в MKV.
    ff('-i', src48, '-c:a', 'ac3', '-b:a', '192k', *BX, '-f', 'ac3', F('ac3_48000_2ch_48000.ac3'))
    ff('-i', src48, '-c:a', 'eac3', '-b:a', '192k', *BX, '-f', 'eac3', F('eac3_48000_2ch_48000.eac3'))
    ff('-i', src48, '-c:a', 'dca', '-strict', '-2', *BX, '-f', 'dts', F('dts_48000_2ch_48000.dts'))
    ff('-i', src48, '-c:a', 'ac3', '-b:a', '192k', *BX, F('ac3_48000_2ch_48000.mkv'))
    # MKV с двумя дорожками (AAC + Opus), вторая — «по умолчанию».
    ff('-i', src48, '-i', src48, '-map', '0:a', '-map', '1:a', '-c:a:0', 'aac', '-c:a:1', 'libopus',
       '-disposition:a:0', '0', '-disposition:a:1', 'default', '-metadata:s:a:0', 'language=rus',
       '-metadata:s:a:1', 'language=eng', *BX, F('mkv_2tracks_48000.mkv'))
    # Разрыв PTS 100 мс посередине (FR-7): PCM в MKV, после 0,5 с метки сдвинуты на +0,1 с.
    ff('-i', src48, '-af', 'asetpts=PTS+if(gte(T\\,0.5)\\,0.1/TB\\,0)', '-c:a', 'pcm_s16le', *BX,
       F('ptsgap100ms_pcm_48000_2ch_48000.mkv'))
    # Неподдерживаемые контейнеры (FR-2) и видео без звука.
    ff('-i', src, '-t', '0.2', '-c:a', 'pcm_s16le', *BX, F('container_avi_44100.avi'))
    ff('-i', src, '-t', '0.2', '-c:a', 'libmp3lame', '-ar', '44100', *BX, F('container_flv_44100.flv'))
    ff('-i', src, '-t', '0.2', '-c:a', 'wmav2', *BX, F('container_asf_44100.wma'))
    ff('-f', 'lavfi', '-i', 'color=black:s=16x16:r=5:d=0.4', '-c:v', 'mpeg4', '-an', *BX, F('video_noaudio.mp4'))


def gen_selftest(tmp):
    """Самопроверка на телефоне: тихий тон + импульс в известной позиции, длина 88 200 @ 44,1 кГц / 96 000 @ 48 кГц."""
    S = lambda n: os.path.join(SELF, n)
    man = []
    for fs, n, imp in [(44100, 88200, 22050), (48000, 96000, 24000)]:
        w = os.path.join(tmp, f'st{fs}.wav')
        write(w, wav_bytes(signal(n, 2, fs, imp=imp), 2, fs))
        items = [
            ('wav', ['-c:a', 'pcm_s16le'], '.wav'),
            ('flac', ['-c:a', 'flac'], '.flac'),
            ('aac', ['-c:a', 'aac', '-b:a', '128k'], '.m4a'),
            ('mp3', ['-c:a', 'libmp3lame', '-b:a', '128k'], '.mp3'),
        ]
        if fs == 48000:
            items.append(('opus', ['-c:a', 'libopus', '-b:a', '96k'], '.ogg'))
        for codec, args, ext in items:
            name = f'selftest_{codec}_{fs}_2ch_{n}_imp{imp}{ext}'
            ff('-i', w, *args, *BX, S(name))
            man.append(dict(file=name, codec=codec, sampleRate=fs, channels=2, lengthFrames=n, impulseFrame=imp))
    with open(S('manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(dict(note='Сгенерировано scripts/gen_fixtures.py; длина и позиция импульса — в сэмплах исходной частоты',
                       files=man), f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
