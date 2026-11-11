"""Чтение токенов и путей иконок прямо из Kotlin-кода (Tokens.kt, Icons.kt): HTML-модель и расчёт контраста
всегда совпадают с приложением."""
import re, pathlib
UI = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/java/com/rassul/audioeditor/ui"

def palettes():
    src = (UI / "Tokens.kt").read_text(encoding="utf-8")
    out = {}
    for name in ("DARK", "LIGHT"):
        m = re.search(r"val %s = Palette\((.*?)\n    \)" % name, src, re.S)
        d = {}
        for k, v in re.findall(r"(\w+) = 0x([0-9A-Fa-f]{8})", m.group(1)):
            d[k] = "#" + v[2:].upper()
        d["scrim_alpha"] = int(re.search(r"scrim = 0x([0-9A-Fa-f]{2})", m.group(1)).group(1), 16) / 255
        out[name.lower()] = d
    return out

def icons():
    src = (UI / "Icons.kt").read_text(encoding="utf-8")
    consts = {}
    for name, body in re.findall(r"private const val (\w+) =\s*((?:\"[^\"]*\"\s*\+?\s*)+)", src):
        consts[name] = "".join(re.findall(r"\"([^\"]*)\"", body))
    res = {}
    for name, body in re.findall(r"Icon\.(\w+) to listOf\((.*?)\n?\s*\),?\n(?=\s*Icon\.|\s*\)\n)", src, re.S):
        items = []
        for tok in re.findall(r"\"([^\"]*)\"|\b([A-Z_]{3,})\b", body):
            items.append(tok[0] if tok[0] else consts.get(tok[1], ""))
        res[name] = [i for i in items if i]
    return res

def icon_svg(name, size=24, color="#fff", ic=None):
    ic = ic or icons()
    parts = []
    for s in ic[name]:
        fill, stroke = False, True
        if s.startswith("F:"): s, fill, stroke = s[2:], True, False
        elif s.startswith("B:"): s, fill = s[2:], True
        parts.append('<path d="%s" fill="%s" stroke="%s" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
                     % (s, color if fill else "none", color if stroke else "none"))
    return '<svg width="%d" height="%d" viewBox="0 0 24 24" style="flex:none">%s</svg>' % (size, size, "".join(parts))
