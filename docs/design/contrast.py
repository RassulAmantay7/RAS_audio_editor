"""Контраст WCAG 2.1 для палитр из Tokens.kt. Запуск: python3 docs/design/contrast.py  (печатает таблицы Markdown).
Относительная яркость: L = 0,2126 R + 0,7152 G + 0,0722 B, где канал c ≤ 0,03928 -> c/12,92, иначе ((c+0,055)/1,055)^2,4.
Контраст: (L1 + 0,05)/(L2 + 0,05), L1 ≥ L2. Пороги AA: текст 4,5:1 (SC 1.4.3), элементы интерфейса и иконки 3:1 (SC 1.4.11)."""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from tokens_kt import palettes

def lum(h):
    h = h.lstrip('#'); r, g, b = [int(h[i:i+2], 16) / 255 for i in (0, 2, 4)]
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def cr(a, b):
    la, lb = sorted([lum(a), lum(b)], reverse=True); return (la + 0.05) / (lb + 0.05)

def blend(fg, bg, a):  # как Tokens.blend: каналы усекаются
    f = fg.lstrip('#'); b = bg.lstrip('#')
    return '#' + ''.join('%02X' % int(int(f[i:i+2], 16) * a + int(b[i:i+2], 16) * (1 - a)) for i in (0, 2, 4))

def rows(name, p):
    dark = name == 'dark'
    pc = blend(p['primary'], p['surfaceHigh'], 0.14)
    wc = blend(p['warning'], p['surface'], 0.14)
    dc = blend(p['danger'], p['surface'], 0.14)
    field_bg = p['bg'] if dark else p['surface']
    wave_dim = blend(p['waveform'], p['waveBg'], 0.45 if dark else 0.7)
    sel_tint = blend(p['primary'], p['waveBg'], 0.20 if dark else 0.16)
    ruler = blend(p['text'], p['waveBg'], 0.05 if dark else 0.06)
    snack = ('#E6E8F0', '#161922', palettes()['light']['primary']) if dark else ('#2A2E38', '#ECEEF4', palettes()['dark']['primary'])
    seg_bg = p['bg'] if dark else blend(p['text'], p['surfaceHigh'], 0.06)
    return [
        ('Основной текст', 'text', 'bg', p['text'], p['bg'], 4.5),
        ('Основной текст на карточке', 'text', 'surface', p['text'], p['surface'], 4.5),
        ('Основной текст в листе/диалоге', 'text', 'surfaceHigh', p['text'], p['surfaceHigh'], 4.5),
        ('Вторичный текст', 'textSecondary', 'bg', p['textSecondary'], p['bg'], 4.5),
        ('Вторичный текст на карточке', 'textSecondary', 'surface', p['textSecondary'], p['surface'], 4.5),
        ('Вторичный текст в листе', 'textSecondary', 'surfaceHigh', p['textSecondary'], p['surfaceHigh'], 4.5),
        ('Акцент: текстовые кнопки, заголовки разделов', 'primary', 'surfaceHigh', p['primary'], p['surfaceHigh'], 4.5),
        ('Акцент на фоне экрана', 'primary', 'bg', p['primary'], p['bg'], 4.5),
        ('Текст на акцентной кнопке', 'onPrimary', 'primary', p['onPrimary'], p['primary'], 4.5),
        ('Ошибка / опасное действие (текст)', 'danger', 'surfaceHigh', p['danger'], p['surfaceHigh'], 4.5),
        ('Ошибка под полем ввода', 'danger', 'поле ' + field_bg, p['danger'], field_bg, 4.5),
        ('Выбранный сегмент / инструмент / формат', 'primary', 'primaryContainer ' + pc, p['primary'], pc, 4.5),
        ('Текст на подложке акцента (busy, выбранные карточки)', 'text', 'primaryContainer ' + pc, p['text'], pc, 4.5),
        ('Невыбранный сегмент', 'textSecondary', 'segmentBg ' + seg_bg, p['textSecondary'], seg_bg, 4.5),
        ('Текст заметки (notice) на подложке предупреждения', 'text', 'warningContainer ' + wc, p['text'], wc, 4.5),
        ('Иконка предупреждения (UI ≥3:1)', 'warning', 'warningContainer ' + wc, p['warning'], wc, 3),
        ('Текст на подложке ошибки', 'text', 'dangerContainer ' + dc, p['text'], dc, 4.5),
        ('Иконка ошибки (UI ≥3:1)', 'danger', 'dangerContainer ' + dc, p['danger'], dc, 3),
        ('Snackbar: текст', 'snackFg ' + snack[1], 'snackBg ' + snack[0], snack[1], snack[0], 4.5),
        ('Snackbar: действие', 'snackAction ' + snack[2], 'snackBg ' + snack[0], snack[2], snack[0], 4.5),
        ('Иконка (UI ≥3:1)', 'text', 'surfaceHigh', p['text'], p['surfaceHigh'], 3),
        ('Прогресс: заливка vs дорожка (UI ≥3:1)', 'primary', 'outline', p['primary'], p['outline'], 3),
        ('Рамка поля ввода vs фон (UI ≥3:1)', 'textSecondary', 'поле ' + field_bg, p['textSecondary'], field_bg, 3),
        ('Волна на подложке (UI ≥3:1)', 'waveform', 'waveBg', p['waveform'], p['waveBg'], 3),
        ('Волна вне выделения на подложке (UI ≥3:1)', 'waveform 45 % (светлая 70 %)', 'waveBg', wave_dim, p['waveBg'], 3),
        ('Волна внутри выделения (UI ≥3:1)', 'waveform', 'selection ' + sel_tint, p['waveform'], sel_tint, 3),
        ('Перегрузка на подложке (UI ≥3:1)', 'overload', 'waveBg', p['overload'], p['waveBg'], 3),
        ('Перегрузка vs обычная волна (информативно: отличимость обеспечена полосой-маркером и подписью, не цветом)', 'overload', 'waveform', p['overload'], p['waveform'], 1.0),
        ('Подписи шкалы времени', 'textSecondary', 'ruler ' + ruler, p['textSecondary'], ruler, 4.5),
        ('Курсор воспроизведения на волне (UI ≥3:1)', 'text', 'waveBg', p['text'], p['waveBg'], 3),
        ('Переключатель выкл.: бегунок vs дорожка (UI ≥3:1)', 'textSecondary', 'track ' + seg_bg, p['textSecondary'], seg_bg, 3),
        ('Переключатель вкл.: дорожка vs фон (UI ≥3:1)', 'primary', 'surfaceHigh', p['primary'], p['surfaceHigh'], 3),
    ]

def main():
    P = palettes(); bad = 0; out = []
    for name in ('dark', 'light'):
        out.append('\n#### %s тема\n\n| Пара | Передний план | Фон | Контраст | Порог | Итог |\n|---|---|---|---|---|---|' % ('Тёмная' if name == 'dark' else 'Светлая'))
        for t, a, b, fa, fb, thr in rows(name, P[name]):
            c = cr(fa, fb); ok = c >= thr; bad += not ok
            fgt = '`%s` %s' % (a.split(' ')[0], fa) if ' ' not in a or a.startswith('snack') else a
            out.append('| %s | `%s` %s | `%s` | **%.2f:1** | %s:1 | %s |' % (t, a.split(' ')[0] if not a.startswith('snack') else a.split(' ')[0], fa, b, c, thr, 'OK' if ok else 'НИЖЕ ПОРОГА'))
    print('\n'.join(out)); print('\nНиже порога: %d' % bad, file=sys.stderr)
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main())
