import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { marked } = require('marked');
const {
  AlignmentType, BorderStyle, Document, Footer, Header, HeadingLevel, ImageRun,
  Packer, PageNumber, Paragraph, ShadingType, Table, TableCell, TableOfContents,
  TableRow, TextRun, WidthType,
} = require('docx');

const folder = path.dirname(fileURLToPath(import.meta.url));
const source = path.join(folder, 'databridge-thesis-draft.md');
const output = path.join(folder, 'databridge-thesis-content-draft.docx');
const markdown = fs.readFileSync(source, 'utf8');
const tokens = marked.lexer(markdown);
const title = tokens.find((token) => token.type === 'heading' && token.depth === 1)?.text;
if (!title) throw new Error('A thesis title is required');

function inline(tokens, options = {}) {
  const runs = [];
  for (const token of tokens ?? []) {
    if (token.type === 'text' || token.type === 'escape') {
      const segments = token.text.split(/(\*\*[^*]+\*\*)/g);
      for (const segment of segments) {
        if (!segment) continue;
        if (segment.startsWith('**') && segment.endsWith('**')) {
          runs.push(new TextRun({ text: segment.slice(2, -2), ...options, bold: true }));
        } else {
          runs.push(new TextRun({ text: segment, ...options }));
        }
      }
    } else if (token.type === 'strong') {
      runs.push(...inline(token.tokens, { ...options, bold: true }));
    } else if (token.type === 'em') {
      runs.push(...inline(token.tokens, { ...options, italics: true }));
    } else if (token.type === 'codespan') {
      runs.push(new TextRun({ text: token.text, font: 'Consolas', color: '234F70', ...options }));
    } else if (token.type === 'link') {
      runs.push(...inline(token.tokens, { ...options, color: '1B4B80' }));
    } else if (token.type === 'br') {
      runs.push(new TextRun({ break: 1 }));
    } else if (token.tokens) {
      runs.push(...inline(token.tokens, options));
    } else if (token.text) {
      runs.push(new TextRun({ text: token.text, ...options }));
    }
  }
  return runs;
}

function pngParagraph(token) {
  const file = path.resolve(folder, token.href);
  const repo = path.resolve(folder, '..', '..');
  if (!file.startsWith(repo + path.sep) || path.extname(file).toLowerCase() !== '.png') {
    throw new Error(`Invalid thesis image path: ${token.href}`);
  }
  const data = fs.readFileSync(file);
  if (data.toString('hex', 0, 8) !== '89504e470d0a1a0a') throw new Error(`Invalid PNG: ${file}`);
  const pixelWidth = data.readUInt32BE(16);
  const pixelHeight = data.readUInt32BE(20);
  const scale = Math.min(580 / pixelWidth, 720 / pixelHeight, 1);
  return [
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 150, after: 60 },
      children: [new ImageRun({ data, type: 'png', transformation: {
        width: Math.round(pixelWidth * scale), height: Math.round(pixelHeight * scale),
      } })] }),
    new Paragraph({ alignment: AlignmentType.CENTER, style: 'Caption',
      children: [new TextRun(token.text)] }),
  ];
}

function table(token) {
  const allRows = [token.header, ...token.rows];
  const columns = token.header.length;
  const totalWidth = 9100;
  const widths = Array(columns).fill(Math.floor(totalWidth / columns));
  widths[columns - 1] += totalWidth - widths.reduce((a, b) => a + b, 0);
  return new Table({
    width: { size: totalWidth, type: WidthType.DXA }, columnWidths: widths,
    rows: allRows.map((row, rowIndex) => new TableRow({
      tableHeader: rowIndex === 0,
      children: row.map((cell, column) => new TableCell({
        width: { size: widths[column], type: WidthType.DXA },
        shading: rowIndex === 0 ? { type: ShadingType.CLEAR, fill: 'E8EDF3' } : undefined,
        margins: { top: 90, bottom: 90, left: 110, right: 110 },
        children: [new Paragraph({
          alignment: cell.align === 'right' ? AlignmentType.RIGHT : AlignmentType.LEFT,
          children: inline(cell.tokens, rowIndex === 0 ? { bold: true } : {}),
          spacing: { after: 0, line: 240 },
        })],
      })),
    })),
    borders: { top: { style: BorderStyle.SINGLE, size: 4, color: '8494A8' },
      bottom: { style: BorderStyle.SINGLE, size: 4, color: '8494A8' },
      insideHorizontal: { style: BorderStyle.SINGLE, size: 3, color: 'CCD4DE' },
      insideVertical: { style: BorderStyle.SINGLE, size: 3, color: 'CCD4DE' } },
  });
}

const children = [
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 3200, after: 500 },
    children: [new TextRun({ text: title, bold: true, size: 36, font: 'SimHei' })] }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 300 },
    children: [new TextRun({ text: '毕业设计技术内容稿', size: 24, font: 'SimHei' })] }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 800 },
    children: [new TextRun({ text: '待按本人的学校正式模板填写封面、声明、作者与导师信息', size: 20 })] }),
  new Paragraph({ pageBreakBefore: true, children: [new TextRun({
    text: '目录（在 Word 中更新目录域）', bold: true, size: 30, font: 'SimHei',
  })] }),
  new TableOfContents('目录', { hyperlink: true, headingStyleRange: '1-3' }),
  new Paragraph({ pageBreakBefore: true }),
];

let firstTitleSkipped = false;
for (const token of tokens) {
  if (token.type === 'heading') {
    if (token.depth === 1 && !firstTitleSkipped) { firstTitleSkipped = true; continue; }
    const heading = token.depth === 1 ? HeadingLevel.HEADING_1
      : token.depth === 2 ? HeadingLevel.HEADING_1 : HeadingLevel.HEADING_2;
    children.push(new Paragraph({ heading, pageBreakBefore: token.text.startsWith('参考资料'),
      children: inline(token.tokens),
      spacing: { before: token.depth <= 2 ? 340 : 220, after: 150 } }));
  } else if (token.type === 'paragraph') {
    if (token.tokens?.length === 1 && token.tokens[0].type === 'image') {
      children.push(...pngParagraph(token.tokens[0]));
    } else {
      children.push(new Paragraph({ style: 'Normal', children: inline(token.tokens),
        indent: { firstLine: 420 }, spacing: { after: 130, line: 360 } }));
    }
  } else if (token.type === 'blockquote') {
    for (const paragraph of token.tokens ?? []) {
      if (paragraph.type === 'paragraph') {
        children.push(new Paragraph({ style: 'Quote', children: inline(paragraph.tokens) }));
      }
    }
  } else if (token.type === 'table') {
    children.push(table(token));
    children.push(new Paragraph({ spacing: { after: 120 } }));
  } else if (token.type === 'code') {
    for (const line of token.text.split(/\r?\n/)) {
      children.push(new Paragraph({ style: 'Code', children: [new TextRun(line || ' ')] }));
    }
  } else if (token.type === 'list') {
    for (const item of token.items) {
      const paragraph = item.tokens.find((part) => part.type === 'text' || part.type === 'paragraph');
      children.push(new Paragraph({ style: 'Normal',
        numbering: { reference: token.ordered ? 'ordered' : 'bullet', level: 0 },
        children: inline(paragraph?.tokens ?? marked.lexer(paragraph?.text ?? '')[0]?.tokens),
        spacing: { after: 90, line: 340 } }));
    }
  }
}

const doc = new Document({
  creator: 'DataBridge project', title, subject: 'Graduation project technical content draft',
  numbering: { config: [
    { reference: 'bullet', levels: [{ level: 0, format: 'bullet', text: '•', alignment: AlignmentType.LEFT }] },
    { reference: 'ordered', levels: [{ level: 0, format: 'decimal', text: '%1.', alignment: AlignmentType.LEFT }] },
  ] },
  styles: {
    default: { document: { run: { font: 'SimSun', size: 22 }, paragraph: { spacing: { line: 360 } } } },
    paragraphStyles: [
      { id: 'Normal', name: 'Normal', run: { font: 'SimSun', size: 22 },
        paragraph: { spacing: { line: 360, after: 130 } } },
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal',
        run: { font: 'SimHei', size: 30, bold: true },
        paragraph: { spacing: { before: 340, after: 160 }, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal',
        run: { font: 'SimHei', size: 26, bold: true },
        paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 1 } },
      { id: 'Caption', name: 'Caption', basedOn: 'Normal', run: { font: 'SimSun', size: 20 },
        paragraph: { spacing: { after: 200 } } },
      { id: 'Quote', name: 'Quote', basedOn: 'Normal', run: { font: 'SimSun', size: 20, color: '526171' },
        paragraph: { indent: { left: 360, right: 360 }, spacing: { after: 140, line: 320 } } },
      { id: 'Code', name: 'Code', run: { font: 'Consolas', size: 17 },
        paragraph: { indent: { left: 240 }, spacing: { line: 230, after: 0 } } },
    ],
  },
  sections: [{
    properties: { page: { margin: { top: 1250, bottom: 1250, left: 1400, right: 1200 } } },
    headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT,
      children: [new TextRun({ text: 'DataBridge · 技术内容稿', size: 16, color: '7A8795' })] })] }) },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
      children: [new TextRun({ children: [PageNumber.CURRENT], size: 18 })] })] }) },
    children,
  }],
});

const buffer = await Packer.toBuffer(doc);
fs.writeFileSync(output, buffer);
console.log(`Wrote ${output}; ${children.length} document blocks`);
