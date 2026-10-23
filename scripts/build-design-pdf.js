import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'docs', 'design.md');
const output = path.join(root, 'output', 'pdf', 'ps-003-design.pdf');
const pageWidth = 595.28;
const pageHeight = 841.89;
const margin = 62;
const contentWidth = pageWidth - margin * 2;
const navy = '0.09 0.24 0.38';
const ink = '0.12 0.18 0.24';
const blue = '0.18 0.43 0.62';
const muted = '0.38 0.45 0.54';

function ascii(value) {
  return value
    .replaceAll('→', ' to ').replaceAll('←', ' from ')
    .replace(/[—–‑−]/g, '-')
    .replace(/[“”]/g, '"').replace(/[‘’]/g, "'")
    .replace(/[^\x20-\x7e]/g, '');
}

function cleanInline(value) {
  return ascii(value.replace(/\*\*/g, '').replace(/`/g, '')).replace(/\s+/g, ' ').trim();
}

function wrap(value, size, maxWidth = contentWidth) {
  const words = cleanInline(value).split(' ').filter(Boolean);
  const maxChars = Math.max(24, Math.floor(maxWidth / (size * 0.51)));
  const rows = [];
  let row = '';
  for (const word of words) {
    const candidate = row ? `${row} ${word}` : word;
    if (candidate.length > maxChars && row) {
      rows.push(row);
      row = word;
    } else row = candidate;
  }
  if (row) rows.push(row);
  return rows.length ? rows : [''];
}

const pages = [];
let page = [];
let y = pageHeight - 104;

function nextPage() {
  pages.push(page);
  page = [];
  y = pageHeight - 104;
}

function ensureRoom(height) {
  if (y - height < 56) nextPage();
}

function addText(text, { size = 9.4, leading = 14, color = ink, font = 'F1', indent = 0, before = 0, after = 6 } = {}) {
  const rows = wrap(text, size, contentWidth - indent);
  const total = before + rows.length * leading + after;
  ensureRoom(total);
  y -= before;
  for (const row of rows) {
    const safe = row.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)');
    page.push(`BT /${font} ${size} Tf ${color} rg 1 0 0 1 ${margin + indent} ${y.toFixed(2)} Tm (${safe}) Tj ET`);
    y -= leading;
  }
  y -= after;
}

for (const raw of fs.readFileSync(source, 'utf8').split(/\r?\n/)) {
  const line = raw.trimEnd();
  if (!line.trim()) {
    y -= 3;
    continue;
  }
  if (line.startsWith('# ')) {
    addText(line.slice(2), { size: 25, leading: 30, color: navy, font: 'F2', before: 12, after: 8 });
    addText('Train Reservation with Quotas, RAC and Waitlist', { size: 10, leading: 14, color: muted, after: 14 });
  } else if (line.startsWith('## ')) {
    addText(line.slice(3), { size: 18, leading: 23, color: navy, font: 'F2', before: 9, after: 7 });
  } else if (line.startsWith('### ')) {
    addText(line.slice(4), { size: 12.5, leading: 16, color: blue, font: 'F2', before: 7, after: 4 });
  } else if (line.startsWith('- ')) {
    addText(`* ${line.slice(2)}`, { size: 9.1, leading: 13.2, indent: 12, after: 4 });
  } else addText(line, { after: 6 });
}
pages.push(page);

function streamFor(commands, pageNumber) {
  const parts = [
    'q', `${navy} rg`, '60 788 475 34 re f',
    'BT /F2 8.5 Tf 1 1 1 rg 1 0 0 1 72 805 Tm (SJ03-PS-003  |  RESERVATION ENGINE DESIGN) Tj ET',
    'BT /F1 8 Tf 1 1 1 rg 1 0 0 1 468 805 Tm (Node.js 20+) Tj ET',
    ...commands,
    '0.84 0.88 0.92 RG 0.7 w 60 45 m 535 45 l S',
    `BT /F1 8 Tf ${muted} rg 1 0 0 1 60 31 Tm (Design specification) Tj ET`,
    `BT /F1 8 Tf ${muted} rg 1 0 0 1 493 31 Tm (Page ${pageNumber}) Tj ET`,
    'Q',
  ];
  return Buffer.from(parts.join('\n'), 'ascii');
}

function makePdf() {
  const objects = [];
  const add = (body) => { objects.push(Buffer.isBuffer(body) ? body : Buffer.from(body, 'ascii')); return objects.length; };
  const catalogId = add('');
  const pagesId = add('');
  const regularFontId = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');
  const boldFontId = add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>');
  const pageIds = [];
  pages.forEach((commands, index) => {
    const stream = streamFor(commands, index + 1);
    const contentId = add(Buffer.concat([
      Buffer.from(`<< /Length ${stream.length} >>\nstream\n`, 'ascii'), stream, Buffer.from('\nendstream', 'ascii'),
    ]));
    const pageId = add(`<< /Type /Page /Parent ${pagesId} 0 R /MediaBox [0 0 ${pageWidth} ${pageHeight}] /Resources << /Font << /F1 ${regularFontId} 0 R /F2 ${boldFontId} 0 R >> >> /Contents ${contentId} 0 R >>`);
    pageIds.push(pageId);
  });
  objects[catalogId - 1] = Buffer.from(`<< /Type /Catalog /Pages ${pagesId} 0 R >>`, 'ascii');
  objects[pagesId - 1] = Buffer.from(`<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pageIds.length} >>`, 'ascii');

  const chunks = [Buffer.from('%PDF-1.4\n%PS003\n', 'ascii')];
  const offsets = [0];
  let length = chunks[0].length;
  objects.forEach((object, i) => {
    offsets.push(length);
    const wrapped = Buffer.concat([Buffer.from(`${i + 1} 0 obj\n`, 'ascii'), object, Buffer.from('\nendobj\n', 'ascii')]);
    chunks.push(wrapped);
    length += wrapped.length;
  });
  const xrefOffset = length;
  const xref = [`xref\n0 ${objects.length + 1}\n`, '0000000000 65535 f \n'];
  for (let i = 1; i < offsets.length; i++) xref.push(`${String(offsets[i]).padStart(10, '0')} 00000 n \n`);
  xref.push(`trailer\n<< /Size ${objects.length + 1} /Root ${catalogId} 0 R /Info << /Title (SJ03-PS-003 Design Document) /Author (SJ03-PS-003) >> >>\nstartxref\n${xrefOffset}\n%%EOF\n`);
  chunks.push(Buffer.from(xref.join(''), 'ascii'));
  return Buffer.concat(chunks);
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, makePdf());
console.log(output);

