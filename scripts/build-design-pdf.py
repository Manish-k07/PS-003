from pathlib import Path
import re
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "design.md"
OUTPUT = ROOT / "output" / "pdf" / "ps-003-design.pdf"
NAVY = colors.HexColor("#173c62")
BLUE = colors.HexColor("#2e6d9f")
INK = colors.HexColor("#1e2d3d")
MUTED = colors.HexColor("#60748a")
PALE = colors.HexColor("#edf5fc")


def register_fonts():
    regular = Path("C:/Windows/Fonts/arial.ttf")
    bold = Path("C:/Windows/Fonts/arialbd.ttf")
    italic = Path("C:/Windows/Fonts/ariali.ttf")
    if regular.exists() and bold.exists():
        pdfmetrics.registerFont(TTFont("ProjectSans", str(regular)))
        pdfmetrics.registerFont(TTFont("ProjectSans-Bold", str(bold)))
        if italic.exists():
            pdfmetrics.registerFont(TTFont("ProjectSans-Italic", str(italic)))
        pdfmetrics.registerFontFamily("ProjectSans", normal="ProjectSans", bold="ProjectSans-Bold", italic="ProjectSans-Italic" if italic.exists() else "ProjectSans", boldItalic="ProjectSans-Bold")
        return "ProjectSans", "ProjectSans-Bold"
    return "Helvetica", "Helvetica-Bold"


def inline_markup(value):
    value = value.replace("—", "-").replace("–", "-").replace("‑", "-").replace("−", "-")
    value = value.replace("→", " to ")
    value = escape(value)
    value = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", value)
    value = re.sub(r"`(.+?)`", r'<font name="Courier">\1</font>', value)
    return value


def draw_page(canvas, doc):
    width, height = A4
    canvas.saveState()
    canvas.setFillColor(NAVY)
    canvas.roundRect(18 * mm, height - 23 * mm, width - 36 * mm, 12 * mm, 3 * mm, fill=1, stroke=0)
    canvas.setFillColor(colors.white)
    canvas.setFont("Helvetica-Bold", 8.5)
    canvas.drawString(23 * mm, height - 18.5 * mm, "SJ03-PS-003  |  RESERVATION ENGINE DESIGN")
    canvas.setFont("Helvetica", 8)
    canvas.drawRightString(width - 23 * mm, height - 18.5 * mm, "Node.js 20+")
    canvas.setStrokeColor(colors.HexColor("#d7e1eb"))
    canvas.line(18 * mm, 16 * mm, width - 18 * mm, 16 * mm)
    canvas.setFillColor(MUTED)
    canvas.setFont("Helvetica", 8)
    canvas.drawString(18 * mm, 10.5 * mm, "Design specification")
    canvas.drawRightString(width - 18 * mm, 10.5 * mm, f"Page {doc.page}")
    canvas.restoreState()


def make_styles():
    regular, bold = register_fonts()
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle("ProjectTitle", parent=base["Title"], fontName=bold, fontSize=25, leading=30, textColor=NAVY, alignment=TA_LEFT, spaceAfter=9),
        "h1": ParagraphStyle("ProjectH1", parent=base["Heading1"], fontName=bold, fontSize=18, leading=23, textColor=NAVY, spaceBefore=15, spaceAfter=7, keepWithNext=True),
        "h2": ParagraphStyle("ProjectH2", parent=base["Heading2"], fontName=bold, fontSize=12.5, leading=16, textColor=BLUE, spaceBefore=10, spaceAfter=4, keepWithNext=True),
        "body": ParagraphStyle("ProjectBody", parent=base["BodyText"], fontName=regular, fontSize=9.4, leading=14, textColor=INK, alignment=TA_LEFT, spaceAfter=6),
        "bullet": ParagraphStyle("ProjectBullet", parent=base["BodyText"], fontName=regular, fontSize=9.1, leading=13.2, textColor=INK, leftIndent=13, firstLineIndent=-9, bulletIndent=1, spaceAfter=4),
        "kicker": ParagraphStyle("ProjectKicker", parent=base["BodyText"], fontName=regular, fontSize=9.5, leading=14, textColor=MUTED, spaceAfter=13),
        "callout": ParagraphStyle("ProjectCallout", parent=base["BodyText"], fontName=regular, fontSize=9.3, leading=14, textColor=NAVY, backColor=PALE, borderColor=colors.HexColor("#bed4e8"), borderWidth=0.6, borderPadding=8, spaceBefore=4, spaceAfter=10),
    }


def parse_source(styles):
    text = SOURCE.read_text(encoding="utf-8")
    story = []
    paragraph_lines = []

    def flush_paragraph():
        if paragraph_lines:
            joined = " ".join(line.strip() for line in paragraph_lines)
            story.append(Paragraph(inline_markup(joined), styles["body"]))
            paragraph_lines.clear()

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            flush_paragraph()
            continue
        if line.startswith("# "):
            flush_paragraph()
            story.append(Spacer(1, 8))
            story.append(Paragraph(inline_markup(line[2:]), styles["title"]))
            story.append(Paragraph("Train Reservation with Quotas, RAC and Waitlist", styles["kicker"]))
        elif line.startswith("## "):
            flush_paragraph()
            story.append(Paragraph(inline_markup(line[3:]), styles["h1"]))
        elif line.startswith("### "):
            flush_paragraph()
            story.append(Paragraph(inline_markup(line[4:]), styles["h2"]))
        elif line.startswith("- "):
            flush_paragraph()
            story.append(Paragraph(inline_markup(line[2:]), styles["bullet"], bulletText="•"))
        else:
            paragraph_lines.append(line)
    flush_paragraph()
    return story


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    styles = make_styles()
    doc = BaseDocTemplate(str(OUTPUT), pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=36 * mm, bottomMargin=22 * mm, title="SJ03-PS-003 Design Document", author="SJ03-PS-003")
    frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="main", leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
    doc.addPageTemplates([PageTemplate(id="project", frames=[frame], onPage=draw_page)])
    doc.build(parse_source(styles))
    print(OUTPUT)


if __name__ == "__main__":
    main()

