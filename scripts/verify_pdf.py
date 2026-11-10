#!/usr/bin/env python3
"""Внешняя проверка PDF после PdfAnnotationWriter.append.

Использование: scripts/verify_pdf.py FILE.pdf [FILE2.pdf ...] [--render DIR]

Для каждого файла:
  * pypdf (strict=True): печатает аннотации каждой страницы — тип, Rect, QuadPoints, наличие /AP;
  * pikepdf (libqpdf): pdf.check() — структурные ошибки xref/объектов;
  * --render DIR: PyMuPDF рендерит страницы с аннотациями в PNG (если pymupdf установлен).
Код возврата 1, если хоть один файл не прошёл.
"""
import sys
import os


def fmt(v):
    try:
        return "[" + " ".join(f"{float(x):g}" for x in v) + "]"
    except TypeError:
        return str(v)


def check_pypdf(path):
    from pypdf import PdfReader
    r = PdfReader(path, strict=True)
    print(f"  pypdf: страниц {len(r.pages)}, оглавление: {len(r.outline)} верхних пунктов")
    for i, p in enumerate(r.pages):
        annots = p.get("/Annots")
        if annots is None:
            continue
        annots = annots.get_object()
        print(f"  стр. {i + 1} (/Rotate {p.get('/Rotate', 0)}):")
        for a in annots:
            a = a.get_object()
            qp = a.get("/QuadPoints")
            ap = a.get("/AP")
            has_ap = ap is not None and "/N" in ap.get_object()
            line = f"    {a.get('/Subtype')} Rect={fmt(a.get('/Rect'))} AP={'да' if has_ap else 'НЕТ'}"
            if qp is not None:
                line += f" QuadPoints={fmt(qp)}"
            if "/Contents" in a:
                line += f" Contents={a['/Contents']!r}"
            print(line)


def check_pikepdf(path):
    import pikepdf
    with pikepdf.open(path) as pdf:
        # pikepdf ≥ 9: check_pdf_syntax(); раньше — check()
        problems = pdf.check_pdf_syntax() if hasattr(pdf, "check_pdf_syntax") else pdf.check()
        if problems:
            print("  pikepdf.check: ПРОБЛЕМЫ:")
            for p in problems:
                print("    ", p)
            return False
        print(f"  pikepdf (libqpdf {pikepdf.__libqpdf_version__}): OK, страниц {len(pdf.pages)}")
    return True


def render(path, outdir):
    try:
        import pymupdf
    except ImportError:
        print("  pymupdf не установлен — рендер пропущен")
        return
    os.makedirs(outdir, exist_ok=True)
    doc = pymupdf.open(path)
    base = os.path.splitext(os.path.basename(path))[0]
    for i, page in enumerate(doc):
        if not list(page.annots()):
            continue
        pix = page.get_pixmap(dpi=50, annots=True)
        out = os.path.join(outdir, f"{base}-p{i + 1}.png")
        pix.save(out)
        print(f"  рендер: {out} ({pix.width}x{pix.height}, rotation={page.rotation})")


def main(argv):
    render_dir = None
    if "--render" in argv:
        k = argv.index("--render")
        render_dir = argv[k + 1]
        argv = argv[:k] + argv[k + 2:]
    ok = True
    for path in argv:
        print(f"== {path}")
        try:
            check_pypdf(path)
            ok = check_pikepdf(path) and ok
            if render_dir:
                render(path, render_dir)
        except Exception as e:  # noqa: BLE001
            print(f"  ОШИБКА: {type(e).__name__}: {e}")
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
