"""HTML-модель ключевых экранов (токены и пути иконок читаются из Kotlin) + скриншот Chromium (Playwright).
Запуск: python3 docs/design/model.py  ->  docs/design/screens.png. Это модель, а не снимок устройства."""
import sys, math, pathlib, asyncio
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from tokens_kt import palettes, icons, icon_svg
from contrast import blend
P = palettes()['dark']; IC = icons()
I = lambda n, s=24, c=None: icon_svg(n, s, c or P['text'], IC)
pc = blend(P['primary'], P['surfaceHigh'], .14); wc = blend(P['warning'], P['surface'], .14); dc = blend(P['danger'], P['surface'], .14)
EDGE = 'rgba(255,255,255,.08)'

def tile(icon, title, sub, primary=False, chev=False, dim=False):
    fg = P['onPrimary'] if primary else P['text']; sc = 'rgba(4,32,26,.85)' if primary else P['textSecondary']
    bg = P['primary'] if primary else P['surface']; bd = '' if primary else 'border:1px solid %s;' % EDGE
    badge = 'rgba(4,32,26,.12)' if primary else pc; ic = P['onPrimary'] if primary else P['primary']
    sh = 'box-shadow:0 6px 16px rgba(0,0,0,.45);' if primary else ''
    return ('<div style="display:flex;align-items:center;min-height:80px;padding:14px 18px;border-radius:20px;background:%s;%s%smargin-bottom:12px;%s">'
            '<div style="width:52px;height:52px;border-radius:50%%;background:%s;display:flex;align-items:center;justify-content:center;margin-right:16px;flex:none">%s</div>'
            '<div style="flex:1"><div style="font-size:18px;font-weight:500;color:%s">%s</div><div style="font-size:13px;color:%s;margin-top:2px">%s</div></div>%s</div>'
            % (bg, bd, sh, 'opacity:.4;' if dim else '', badge, I(icon, 26, ic), fg, title, sc, sub, I('CHEVRON_RIGHT', 24, sc) if chev else ''))

def notice(kind, text, boxed=True):
    ic, col, bg = {'info': ('INFO', P['primary'], pc), 'warn': ('WARNING', P['warning'], wc), 'err': ('ERROR', P['danger'], dc)}[kind]
    b = 'background:%s;border-radius:12px;padding:10px 14px 10px 12px;' % bg if boxed else ''
    return '<div style="display:flex;gap:10px;align-items:flex-start;%s"><div style="margin-top:1px">%s</div><div style="font-size:13px;line-height:1.3;color:%s">%s</div></div>' % (b, I(ic, 20, col), P['text'], text)

def heading(t, pad='16px 4px 8px'): return '<div style="font-size:13px;font-weight:500;letter-spacing:.04em;color:%s;padding:%s">%s</div>' % (P['primary'], pad, t)

def topbar(title, sub, actions=''):
    return ('<div style="display:flex;align-items:center;min-height:64px;padding:0 4px"><div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">%s</div>'
            '<div style="flex:1;padding:0 8px 0 4px;min-width:0"><div style="font-size:18px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">%s</div><div style="font-size:13px;color:%s">%s</div></div>%s</div>'
            % (I('BACK'), title, P['textSecondary'], sub, actions))

def phone(inner, extra=''):
    return ('<div class=phone style="background:%s;color:%s">'
            '<div style="height:36px;display:flex;justify-content:space-between;align-items:center;padding:0 24px;font-size:14px;font-weight:500"><span>14:32</span><span>▂▄▆ ◔ ▮</span></div>%s%s'
            '<div style="position:absolute;bottom:8px;left:152px;width:108px;height:4px;border-radius:2px;background:%s;opacity:.5"></div></div>' % (P['bg'], P['text'], inner, extra, P['text']))

def hero():
    bars = []
    for i in range(54):
        t = i / 54; a = .18 + .82 * abs(math.sin(t * 9) * math.sin(t * 2.3 + .7)) * (.55 + .45 * math.sin(t * 31 + 1))
        h = 88 * a; bars.append('<rect x="%.1f" y="%.1f" width="4" height="%.1f" rx="2" fill="url(#g)"/>' % (i * 7 + 1.5, 48 - h / 2, max(h, 4)))
    return ('<svg width="380" height="96" style="margin:8px 0 20px"><defs><linearGradient id="g" x1="0" x2="1"><stop offset="0" stop-color="%s" stop-opacity=".25"/><stop offset=".5" stop-color="%s"/><stop offset="1" stop-color="%s" stop-opacity=".25"/></linearGradient></defs>%s</svg>' % (P['primary'], P['primary'], P['primary'], ''.join(bars)))

def home():
    jobs = ('<div style="position:absolute;left:12px;right:12px;bottom:36px;background:%s;border-radius:20px;border:1px solid %s;box-shadow:0 8px 24px rgba(0,0,0,.5);padding:12px 6px 8px 14px">'
            '<div style="display:flex;gap:10px;align-items:center">%s<div style="flex:1"><div style="font-size:15px;font-weight:500">Отпуск_Турция_2026_audio.m4a</div><div style="font-size:13px;color:%s">Проверка файла…</div></div></div>'
            '<div style="height:6px;border-radius:3px;background:%s;margin:8px 8px 4px 0;position:relative"><div style="position:absolute;left:30%%;width:30%%;height:6px;border-radius:3px;background:%s"></div></div>'
            '<div style="text-align:right;color:%s;font-weight:500;font-size:15px;padding:8px 8px 4px">Пропустить проверку &nbsp;&nbsp; Отменить</div></div>'
            % (P['surfaceHigh'], EDGE, I('STETHOSCOPE', 22, P['primary']), P['textSecondary'], P['outline'], P['primary'], P['primary']))
    body = ('<div style="padding:0 16px"><div style="display:flex;align-items:center;padding:16px 0 8px 4px"><div style="flex:1"><div style="font-size:28px;font-weight:650">Аудиоредактор</div><div style="color:%s;font-size:15px">Звук из видео и правка записей</div></div>'
            '<div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">%s</div></div>%s%s%s%s<div style="margin-top:20px">%s</div></div>'
            % (P['textSecondary'], I('SETTINGS'), hero(), tile('VIDEO', 'Видео из галереи', 'Достать звук из ролика или отредактировать его', True),
               tile('FOLDER', 'Файл', 'Аудио или видео из «Файлов»'), tile('LIBRARY', 'Сохранённые', '4 файла', chev=True),
               notice('info', 'Можно и так: в галерее нажмите «Поделиться» и выберите это приложение.')))
    return phone(body, jobs)

def track(title, sel, extra=''):
    bg = 'background:%s;border:1.5px solid %s' % (pc, P['primary']) if sel else 'background:%s;border:1px solid %s' % (P['surface'], EDGE)
    dot = ('<svg width="24" height="24"><circle cx="12" cy="12" r="9" fill="none" stroke="%s" stroke-width="2"/>%s</svg>' % (P['primary'] if sel else P['textSecondary'], '<circle cx="12" cy="12" r="5" fill="%s"/>' % P['primary'] if sel else ''))
    return '<div style="display:flex;gap:14px;padding:12px 14px;border-radius:20px;margin-bottom:8px;%s"><div style="margin-top:2px">%s</div><div><div style="font-size:15px;font-weight:500;line-height:1.15">%s</div>%s</div></div>' % (bg, dot, title, extra)

def extract():
    body = ('<div style="padding:0 16px">%s%s%s%s%s%s%s%s%s</div>' % (
        heading('ЗВУКОВАЯ ДОРОЖКА', '8px 4px 8px'),
        track('Дорожка 1 · рус · AAC · стерео · 48 кГц · 192 кбит/с', True, '<div style="font-size:12px;color:%s;margin-top:2px">по умолчанию</div>' % P['textSecondary']),
        track('Дорожка 2 · eng · AC-3 · 5.1 · 48 кГц · 384 кбит/с', False, '<div style="margin-top:8px">%s</div>' % notice('warn', 'Этот телефон не умеет раскодировать звук Dolby Digital (AC-3). Его можно сохранить без изменений, но нельзя редактировать или перекодировать.', False)),
        heading('СОХРАНИТЬ ЗВУК'), tile('EXPORT', 'Сохранить в M4A', 'Без потерь — копирование AAC', True),
        tile('MORE', 'Другой формат…', 'Как есть, M4A, Opus, FLAC или WAV', chev=True),
        heading('РЕДАКТИРОВАНИЕ', '8px 4px 8px'), tile('OPEN_EDITOR', 'Открыть в редакторе', 'Обрезать, убрать лишнее, выровнять громкость', chev=True), ''))
    return phone(topbar('Отпуск_Турция_2026.mp4', 'Видео') + body)

def env(t): return .08 + .7 * abs(math.sin(math.pi * t / 3.1)) ** 1.5 * (.6 + .4 * math.sin(2 * math.pi * t / 37))

def wave(W=388, H=300):
    top = 28; t0, span = 40.0, 36.0; lanes = 2; lh = (H - top) / lanes; sc = lh / 2 / 1.15
    s0, s1 = 46.0, 56.0; dim = blend(P['waveform'], P['waveBg'], .45)
    out = []; groups = {'n': [], 'd': [], 'o': []}; strip = []
    for c in range(W):
        t = t0 + (c + .5) * span / W
        for l in range(lanes):
            g = 3.0 if 56.5 <= t <= 58.5 else 1.0
            e = env(t) * g; h = (.72 + .28 * abs(math.sin(c * 12.9898 + l * 7.1))) ; mx = e * h; mn = -e * (.72 + .28 * abs(math.sin(c * 78.23 + l * 3.3)))
            cy = top + lh * l + lh / 2; y1, y2 = cy - mx * sc, cy - mn * sc
            k = 'o' if mx > 1 or mn < -1 else ('n' if s0 <= t < s1 else 'd')
            if k == 'o': strip.append('M%d.5 %d v3' % (c, top))
            groups[k].append('M%d.5 %.1fV%.1f' % (c, y1, y2))
    for l in range(lanes):
        cy = top + lh * l + lh / 2
        out.append('<line x1=0 x2=%d y1=%.1f y2=%.1f stroke="%s" stroke-opacity=".45"/>' % (W, cy, cy, P['textSecondary']))
        for s in (-1, 1): out.append('<line x1=0 x2=%d y1=%.1f y2=%.1f stroke="%s" stroke-opacity=".35" stroke-dasharray="4 4"/>' % (W, cy + s * sc, cy + s * sc, P['textSecondary']))
    out.append('<line x1=0 x2=%d y1=%d y2=%d stroke="%s" stroke-opacity=".3"/>' % (W, top + lh, top + lh, P['textSecondary']))
    xs, xe = (s0 - t0) / span * W, (s1 - t0) / span * W; xp = (50.6 - t0) / span * W
    ruler = ['<rect width=%d height=%d fill="%s"/>' % (W, top, blend(P['text'], P['waveBg'], .05))]
    for j in range(0, 9):
        t = 40 + j * 5; x = (t - t0) / span * W
        if x < W: ruler.append('<line x1=%.1f x2=%.1f y1=%d y2=%d stroke="%s"/><text x=%.1f y=16 font-size=11 font-family=monospace fill="%s">%d:%02d</text>' % (x, x, top - 10, top, P['textSecondary'], x + 4, P['textSecondary'], t // 60, t % 60))
    for pre, col in (('d', dim), ('n', P['waveform']), ('o', P['overload'])):
        out.append('<path d="%s" stroke="%s" stroke-width=1 fill="none" shape-rendering="crispEdges"/>' % (''.join(groups[pre]), col))
    out.append('<path d="%s" stroke="%s" stroke-width="3" fill="none"/>' % (''.join(strip), P['overload']))
    sel = ('<rect x=%.1f y=%d width=%.1f height=%d fill="%s" fill-opacity=".2"/>' % (xs, top, xe - xs, H - top, P['primary']) +
           ''.join('<line x1=%.1f x2=%.1f y1=%d y2=%d stroke="%s" stroke-width="2"/>' % (x, x, top, H, P['primary']) for x in (xs, xe)) +
           '<circle cx=%.1f cy=%d r=9 fill="%s"/><circle cx=%.1f cy=%d r=9 fill="%s"/>' % (xs, top + 22, P['primary'], xe, H - 22, P['primary']) +
           ''.join('<line x1=%.1f x2=%.1f y1=%d y2=%d stroke="%s" stroke-width="2" stroke-linecap="round"/>' % (x + d, x + d, y - 4, y + 4, P['onPrimary']) for x, y in ((xs, top + 22), (xe, H - 22)) for d in (-2.5, 2.5)))
    head = '<line x1=%.1f x2=%.1f y1=0 y2=%d stroke="%s" stroke-width="2"/><circle cx=%.1f cy=5 r=5 fill="%s"/>' % (xp, xp, H, P['text'], xp, P['text'])
    pill = '<rect x=%d y=%d width=128 height=20 rx=10 fill="%s"/><text x=%d y=%d font-size=11 font-weight=500 fill="%s">Перегрузка &gt; 0 дБ</text>' % (W - 136, top + 8, dc, W - 128, top + 22, P['text'])
    return '<svg width=%d height=%d style="display:block;border-radius:20px;background:%s">%s%s%s%s%s<text x=4 y=%.1f font-size=11 font-family=monospace fill="%s">0 дБ</text></svg>' % (W, H, P['waveBg'], ''.join(out), ''.join(ruler), sel, head, pill, top + lh / 2 - lh / 2 / 1.15 - 3, P['textSecondary'])

def field(label, val, err=None):
    b = P['danger'] if err else P['outline']
    return ('<div style="flex:1"><div style="font-size:13px;font-weight:500;color:%s;padding:0 4px 6px">%s</div><div style="border:%s solid %s;border-radius:12px;background:%s;padding:11px 14px;font-family:monospace;font-size:16px">%s</div>%s</div>'
            % (P['textSecondary'], label, '2px' if err else '1px', b, P['bg'], val, '<div style="color:%s;font-size:13px;padding:6px 4px 0">⚠ %s</div>' % (P['danger'], err) if err else ''))

def tool(icon, label, on=True):
    return '<div style="flex:none;min-width:76px;height:64px;border-radius:12px;background:%s;border:1px solid %s;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;padding:0 10px;opacity:%s">%s<div style="font-size:12px;font-weight:500">%s</div></div>' % (P['surface'], EDGE, 1 if on else .38, I(icon, 24), label)

def editor(sheet=''):
    ab = '<div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center;opacity:%s">%s</div>'
    pad = ''.join([ab % (1, I('UNDO')), ab % (.38, I('REDO'))])
    transport = ('<div style="display:flex;align-items:center;margin-top:8px"><div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">%s</div>'
                 '<div style="width:56px;height:56px;border-radius:50%%;background:%s;display:flex;align-items:center;justify-content:center;margin:0 4px;box-shadow:0 6px 16px rgba(0,0,0,.45)">%s</div>'
                 '<div style="width:48px;height:48px;border-radius:14px;background:%s;display:flex;align-items:center;justify-content:center">%s</div><div style="flex:1"></div>%s</div>'
                 % (I('SKIP_START'), P['primary'], I('PLAY', 24, P['onPrimary']), pc, I('LOOP', 24, P['primary']),
                    ''.join('<div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">%s</div>' % I(n) for n in ('ZOOM_OUT', 'ZOOM_IN', 'FIT_ALL'))))
    body = ('<div style="padding:0 12px">%s<div style="margin-top:4px">%s</div><div style="padding:8px 0 4px"><div style="display:flex;align-items:baseline;padding:0 4px 4px"><span style="font-family:monospace;font-size:18px;font-weight:500">0:50.640 / 30:00.000</span><span style="flex:1"></span><span style="font-size:13px;color:%s">Выделено: 0:10.000</span></div>'
            '<div style="display:flex;gap:12px">%s%s</div>%s<div style="display:flex;gap:8px;margin-top:4px;overflow:hidden">%s</div>'
            '<div style="margin-top:12px;height:52px;border-radius:26px;background:%s;color:%s;display:flex;align-items:center;justify-content:center;gap:10px;font-size:15px;font-weight:500">%s Экспорт</div></div></div>'
            % (notice('warn', 'Громкость −14 LUFS недостижима без перегрузки. Достигнуто −15,3 LUFS (пик −1,0 dBTP).'), wave(), P['textSecondary'],
               field('Начало', '0:46.000'), field('Конец', '0:56.000'), transport,
               ''.join([tool('KEEP', 'Оставить'), tool('DELETE', 'Удалить'), tool('VOLUME', 'Громкость'), tool('FADE_IN', 'Fade'), tool('NORMALIZE', 'Нормализация')]),
               P['primary'], P['onPrimary'], I('EXPORT', 24, P['onPrimary'])))
    return phone(topbar('lecture_2026-11-10.m4a', '48 кГц · стерео · 30:00.000', pad) + '<div style="height:12px"></div>' + body, sheet)

def fmt(name, sub, sel=False):
    bg = 'background:%s;border:1.5px solid %s' % (pc, P['primary']) if sel else 'background:%s;border:1px solid %s' % (P['bg'], P['outline'])
    return '<div style="display:flex;align-items:center;min-height:56px;padding:8px 14px;border-radius:12px;margin-bottom:8px;%s"><div style="flex:1"><div style="font-size:15px;font-weight:500;color:%s">%s</div><div style="font-size:12px;color:%s">%s</div></div>%s</div>' % (bg, P['primary'] if sel else P['text'], name, P['textSecondary'], sub, I('CHECK', 24, P['primary']) if sel else '')

def export_sheet():
    chips = ''.join('<div style="min-height:44px;min-width:56px;padding:0 14px;display:flex;align-items:center;justify-content:center;border-radius:12px;font-size:14px;font-weight:500;%s">%s</div>' % (
        ('background:%s;border:1.5px solid %s;color:%s' % (pc, P['primary'], P['primary'])) if s else 'background:%s;border:1px solid %s' % (P['bg'], P['outline']), t) for t, s in (('64', 0), ('128', 0), ('192 · по умолч.', 1), ('256', 0)))
    sheet = ('<div style="position:absolute;inset:0;background:rgba(0,0,0,.6)"></div><div style="position:absolute;left:0;right:0;bottom:0;border-radius:28px 28px 0 0;background:%s;border:1px solid %s;box-shadow:0 -8px 32px rgba(0,0,0,.5);padding:0 24px 28px">'
             '<div style="width:36px;height:4px;border-radius:2px;background:%s;opacity:.5;margin:9px auto 11px"></div><div style="font-size:20px;font-weight:500;padding:4px 0 12px">Экспорт</div>%s%s%s'
             '<div style="display:flex;align-items:center;min-height:48px;padding:0 4px;font-size:15px;font-weight:500"><span style="flex:1">Дополнительно</span>%s</div>'
             '<div style="font-size:13px;font-weight:500;color:%s;padding:4px 4px 8px">Битрейт, кбит/с</div><div style="display:flex;gap:8px">%s</div>'
             '%s<div style="display:flex;flex-direction:column;gap:8px">%s%s%s</div>'
             '<div style="margin-top:16px;height:52px;border-radius:26px;background:%s;color:%s;display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:500">Сохранить</div></div>'
             % (P['surfaceHigh'], EDGE, P['textSecondary'], heading('ФОРМАТ', '0 4px 8px'),
                fmt('M4A (AAC)  ·  по умолчанию', '.m4a, AAC-LC 192 кбит/с — открывается везде', True) + fmt('Opus', '.ogg, мало места, хорошо для речи'),
                fmt('FLAC (без потерь)', '.flac, без потерь, для архива') + fmt('WAV (без сжатия)', '.wav, без сжатия, для студии'),
                I('CHEVRON_DOWN', 24, P['textSecondary']), P['textSecondary'], chips, heading('ЧТО БУДЕТ СОХРАНЕНО', '16px 4px 8px'),
                notice('info', 'AAC-LC, 192 кбит/с, 48 кГц, стерео'), notice('info', 'Примерный размер: 41,2 МБ'),
                notice('warn', 'Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV.'), P['primary'], P['onPrimary']))
    return sheet

HTML = ('<html><head><meta charset=utf-8><style>*{box-sizing:border-box}body{margin:0;padding:24px;font-family:Roboto,"Noto Sans","Segoe UI",sans-serif;background:#2a2d33;display:flex;gap:28px;flex-wrap:wrap}'
        '.phone{width:412px;height:892px;border-radius:28px;overflow:hidden;position:relative;flex:none}</style></head><body>%s%s%s%s</body></html>')

async def shot(html, out):
    from playwright.async_api import async_playwright
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page(viewport={'width': 1800, 'height': 1000}, device_scale_factor=1)
        await pg.goto('file://' + html); await pg.wait_for_timeout(300); await pg.screenshot(path=out, full_page=True); await b.close()

if __name__ == '__main__':
    d = pathlib.Path(__file__).parent
    (d / 'model.html').write_text(HTML % (home(), extract(), editor(), editor(export_sheet())), encoding='utf-8')
    asyncio.run(shot(str(d / 'model.html'), str(d / 'screens.png')))
    print('ok')
