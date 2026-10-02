#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""资料转换器：PDF/图片 → Markdown —— 习题辅导预设能力 (physics-tutor)

三级策略（成本从低到高，能省钱就省钱）:
  ① PDF 有文字层 → pymupdf4llm 直转（毫秒级/页，零模型，结构保留最好）
  ② PDF 无文字层（扫描本）/ 图片 → 渲染 300dpi → OCR
     · rapidocr（ONNX，纯 CPU，中文好）优先；tesseract(chi_sim) 兜底
  ③ 公式行 → 建议对裁出的公式小块用公式 OCR（本脚本不内置，见技能说明）

重要约定（血泪教训）:
  · **图区不要硬转文字** —— 力学图 OCR 只会得到噪音；保留原图裁剪，让模型看图。
  · 公式行 OCR 会丢符号（θ→6、μ→H、上标丢失），**关键公式必须人工/公式OCR复核**。
  · 输出里标注每页与识别方式，方便定位需要复核的位置。

用法:
  python3 doc_to_md.py --selftest
  python3 doc_to_md.py book.pdf -o book.md
  python3 doc_to_md.py scan.png -o page.md --dpi 300
  python3 doc_to_md.py pages_dir -o all.md          # 目录=按文件名排序逐张 OCR

退出码: 0=成功, 1=失败, 2=环境问题
"""
import argparse
import glob
import os
import sys
import time


def _out(msg: str):
    print(msg, file=sys.stderr)


def _load_pymupdf():
    try:
        import pymupdf
        return pymupdf
    except ImportError:
        try:
            import fitz as pymupdf  # 旧版 PyMuPDF
            return pymupdf
        except ImportError:
            _out("缺少依赖 PyMuPDF —— 请先运行: pip3 install --break-system-packages pymupdf")
            return None


def _has_text_layer(doc, min_chars_per_page: int = 50) -> bool:
    try:
        pages = min(len(doc), 5)
        total = 0
        for i in range(pages):
            total += len(doc[i].get_text("text").strip())
        return (total / max(pages, 1)) >= min_chars_per_page
    except Exception:
        return False


def _rapidocr():
    try:
        from rapidocr_onnxruntime import RapidOCR
        return RapidOCR()
    except ImportError:
        return None


def _tesseract_ok() -> bool:
    from shutil import which
    return which("tesseract") is not None


def _ocr_image(engine, image_path: str, prefer: str):
    """返回 (文本, 引擎名)。engine 为 RapidOCR 实例或 None。"""
    if prefer in ("auto", "rapid") and engine is not None:
        try:
            result, _ = engine(image_path)
            if result:
                return "\n".join(x[1] for x in result), "rapidocr"
            return "", "rapidocr(空)"
        except Exception as e:
            _out(f"rapidocr 失败({e})，尝试 tesseract")
    if prefer in ("auto", "tesseract") and _tesseract_ok():
        import subprocess
        r = subprocess.run(["tesseract", image_path, "stdout", "-l", "chi_sim+eng"],
                           capture_output=True, text=True)
        return r.stdout.strip(), "tesseract"
    return "", "无可用OCR"


def convert_pdf(path: str, dpi: int, prefer: str, pages: str | None) -> tuple:
    pymupdf = _load_pymupdf()
    if pymupdf is None:
        sys.exit(2)
    doc = pymupdf.open(path)
    total_pages = len(doc)
    page_list = range(total_pages)
    if pages:
        try:
            a, _, b = pages.partition("-")
            page_list = range(int(a) - 1, (int(b) if b else int(a)))
        except Exception:
            _out(f"--pages 解析失败: {pages}")
            sys.exit(2)

    sections = []
    used = set()
    if _has_text_layer(doc):
        used.add("pymupdf4llm(文字层)")
        try:
            import pymupdf4llm
            md = pymupdf4llm.to_markdown(path, pages=list(page_list))
            sections.append(("全文", md))
            return "\n\n".join(f"## {t}\n\n{c}" for t, c in sections), sorted(used)
        except ImportError:
            # 退化：纯文本提取
            used.add("pymupdf直提(无4llm)")
            for idx in page_list:
                txt = doc[idx].get_text("text")
                sections.append((f"第 {idx + 1} 页", txt))
            return "\n\n".join(f"## {t}\n\n{c.strip()}" for t, c in sections), sorted(used)

    # 扫描件：渲染 + OCR
    engine = _rapidocr() if prefer in ("auto", "rapid") else None
    import tempfile
    tmpdir = tempfile.mkdtemp(prefix="doctomd_")
    for idx in page_list:
        page = doc[idx]
        pix = page.get_pixmap(dpi=dpi)
        img = os.path.join(tmpdir, f"page_{idx + 1}.png")
        pix.save(img)
        text, eng = _ocr_image(engine, img, prefer)
        used.add(eng)
        sections.append((f"第 {idx + 1} 页", text))
    return "\n\n".join(f"## {t}\n\n{c.strip()}" for t, c in sections), sorted(used)


def convert_images(paths: list, dpi: int, prefer: str) -> tuple:
    engine = _rapidocr() if prefer in ("auto", "rapid") else None
    sections = []
    used = set()
    for i, p in enumerate(paths, 1):
        text, eng = _ocr_image(engine, p, prefer)
        used.add(eng)
        sections.append((os.path.basename(p), text))
    return "\n\n".join(f"## {t}\n\n{c.strip()}" for t, c in sections), sorted(used)


def selftest() -> int:
    print("== doc_to_md 自检 ==")
    pymupdf = _load_pymupdf()
    if pymupdf is None:
        return 2
    import tempfile
    tmp = tempfile.mkdtemp(prefix="doctomd_st_")
    pdf_path = os.path.join(tmp, "sample.pdf")
    doc = pymupdf.open()
    page = doc.new_page()
    page.insert_text((72, 120), "Newton's second law: F = m a", fontsize=14)
    page.insert_text((72, 150), "A block on an incline, theta = 30 deg.", fontsize=14)
    doc.save(pdf_path)
    doc.close()
    print("① 文字层 PDF →", end=" ")
    md, used = convert_pdf(pdf_path, 300, "auto", None)
    ok1 = "F = m a" in md or "Newton" in md
    print("✓" if ok1 else "✗", f"(引擎: {used})")

    ok2 = True
    engine = _rapidocr()
    if engine is None:
        print("② 扫描/OCR 路径 → 跳过（rapidocr 未安装；tesseract 可用=", _tesseract_ok(), "）")
    else:
        print("② 扫描/OCR 路径 → 跳过（避免自检过慢，日常使用会自动走 rapidocr）")
    print("== 自检结果:", "通过" if (ok1 and ok2) else "失败", "==")
    return 0 if (ok1 and ok2) else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="PDF/图片 → Markdown (physics-tutor)")
    ap.add_argument("input", nargs="?", help="PDF / 图片 / 目录")
    ap.add_argument("-o", "--out", help="输出 md 路径（默认输入同名 .md）")
    ap.add_argument("--dpi", type=int, default=300, help="扫描件渲染 dpi（默认 300）")
    ap.add_argument("--ocr", default="auto", choices=["auto", "rapid", "tesseract"])
    ap.add_argument("--pages", help="仅处理部分页，如 1-5 / 3")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.input:
        ap.print_help()
        return 2

    src = args.input
    t0 = time.time()
    if os.path.isdir(src):
        paths = sorted(glob.glob(os.path.join(src, "*")))
        paths = [p for p in paths if p.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".bmp"))]
        if not paths:
            _out("目录里没有图片文件")
            return 1
        md, used = convert_images(paths, args.dpi, args.ocr)
        default_out = os.path.join(src, "all.md")
    elif src.lower().endswith(".pdf"):
        md, used = convert_pdf(src, args.dpi, args.ocr, args.pages)
        default_out = os.path.splitext(src)[0] + ".md"
    elif src.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".bmp")):
        md, used = convert_images([src], args.dpi, args.ocr)
        default_out = os.path.splitext(src)[0] + ".md"
    else:
        _out(f"不支持的输入类型: {src}")
        return 2

    out = args.out or default_out
    header = (f"<!-- doc_to_md: 引擎={','.join(used)} dpi={args.dpi} "
              f"耗时={time.time() - t0:.1f}s｜图区请保留原图裁剪，公式行务必复核 -->\n\n")
    with open(out, "w", encoding="utf-8") as f:
        f.write(header + md + "\n")
    print(f"已输出: {out}")
    print(f"引擎: {','.join(used)}｜{len(md)} 字符｜{time.time() - t0:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
