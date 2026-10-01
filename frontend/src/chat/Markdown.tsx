import { Fragment, ReactNode, useMemo, useState } from 'react';
import { highlight, langLabel } from './highlight';

/* ============================================================================
   Markdown renderer for assistant messages.

   Hand-rolled, like the charts and the icon set: the alternative is pulling in
   a parser plus a sanitizer plus a highlighter for one view. Everything is
   built as React nodes (never dangerouslySetInnerHTML), so model output cannot
   inject markup no matter what it contains.

   Covers what Claude actually emits: fenced code, headings, lists (nested),
   tables, blockquotes, rules, images, links, inline code, emphasis, strikethrough
   and LaTeX spans.
   ========================================================================== */

type Block =
  | { k: 'code'; lang?: string; code: string; open: boolean }
  | { k: 'head'; level: number; text: string }
  | { k: 'hr' }
  | { k: 'quote'; lines: string[] }
  | { k: 'list'; ordered: boolean; start: number; items: string[][] }
  | { k: 'table'; header: string[]; align: Align[]; rows: string[][] }
  | { k: 'para'; lines: string[] };

type Align = 'left' | 'center' | 'right';

const FENCE = /^(\s*)(`{3,}|~{3,})\s*([\w+#-]*)\s*$/;
const HEADING = /^(#{1,6})\s+(.*)$/;
const RULE = /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/;
const QUOTE = /^\s{0,3}>\s?(.*)$/;
const BULLET = /^(\s*)([-*+])\s+(.*)$/;
const NUMBERED = /^(\s*)(\d{1,9})[.)]\s+(.*)$/;

/** Split source into blocks. Tolerates an unterminated fence — that's every streaming answer. */
function parseBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    const fence = line.match(FENCE);
    if (fence) {
      const marker = fence[2][0];
      const body: string[] = [];
      let open = true;
      i++;
      while (i < lines.length) {
        const m = lines[i].match(FENCE);
        if (m && m[2][0] === marker) { open = false; i++; break; }
        body.push(lines[i]);
        i++;
      }
      blocks.push({ k: 'code', lang: fence[3] || undefined, code: body.join('\n'), open });
      continue;
    }

    if (!line.trim()) { i++; continue; }

    if (RULE.test(line)) { blocks.push({ k: 'hr' }); i++; continue; }

    const head = line.match(HEADING);
    if (head) { blocks.push({ k: 'head', level: head[1].length, text: head[2] }); i++; continue; }

    if (QUOTE.test(line)) {
      const body: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) {
        body.push(lines[i].match(QUOTE)![1]);
        i++;
      }
      blocks.push({ k: 'quote', lines: body });
      continue;
    }

    if (BULLET.test(line) || NUMBERED.test(line)) {
      const [list, next] = parseList(lines, i);
      blocks.push(list);
      i = next;
      continue;
    }

    const table = parseTable(lines, i);
    if (table) { blocks.push(table[0]); i = table[1]; continue; }

    const para: string[] = [];
    while (
      i < lines.length && lines[i].trim() &&
      !FENCE.test(lines[i]) && !HEADING.test(lines[i]) && !RULE.test(lines[i]) &&
      !QUOTE.test(lines[i]) && !BULLET.test(lines[i]) && !NUMBERED.test(lines[i])
    ) {
      para.push(lines[i]);
      i++;
    }
    if (para.length) blocks.push({ k: 'para', lines: para });
    else i++;   // a line that starts a construct we just rejected — don't spin on it
  }
  return blocks;
}

/**
 * One list run. Each item keeps its continuation lines de-indented, so nested lists and code
 * blocks inside an item are just markdown again — the renderer recurses.
 */
function parseList(lines: string[], start: number): [Block, number] {
  const first = lines[start].match(BULLET) ?? lines[start].match(NUMBERED)!;
  const ordered = !BULLET.test(lines[start]);
  const baseIndent = first[1].length;
  const items: string[][] = [];
  let i = start;
  let current: string[] | null = null;

  while (i < lines.length) {
    const line = lines[i];
    const bullet = line.match(BULLET);
    const numbered = line.match(NUMBERED);
    const marker = bullet ?? numbered;
    const isSameKind = bullet ? !ordered : numbered ? ordered : false;

    if (marker && marker[1].length <= baseIndent && isSameKind) {
      current = [marker[3]];
      items.push(current);
      i++;
      continue;
    }
    if (!line.trim()) {
      // A blank line ends the list unless the next line continues the current item.
      const next = lines[i + 1];
      if (!next || (!next.startsWith(' '.repeat(baseIndent + 2)) && !BULLET.test(next) && !NUMBERED.test(next))) break;
      current?.push('');
      i++;
      continue;
    }
    if (current && (line.startsWith(' '.repeat(baseIndent + 1)) || marker)) {
      current.push(line.slice(baseIndent + 2 > line.length ? 0 : baseIndent + 2));
      i++;
      continue;
    }
    break;
  }
  const startNum = ordered ? parseInt(first[2], 10) || 1 : 1;
  return [{ k: 'list', ordered, start: startNum, items }, i];
}

/** A pipe table needs a header row and a `---|---` separator directly under it. */
function parseTable(lines: string[], start: number): [Block, number] | null {
  const header = lines[start];
  const sep = lines[start + 1];
  if (!header?.includes('|') || !sep) return null;
  if (!/^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(sep) || !sep.includes('-')) return null;

  const cells = (row: string) =>
    row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
  const head = cells(header);
  const align: Align[] = cells(sep).map((c) => {
    const left = c.startsWith(':');
    const right = c.endsWith(':');
    return left && right ? 'center' : right ? 'right' : 'left';
  });
  const rows: string[][] = [];
  let i = start + 2;
  while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
    rows.push(cells(lines[i]));
    i++;
  }
  return [{ k: 'table', header: head, align, rows }, i];
}

/* ---------------------------------------------------------------- inline */

interface InlineRule {
  re: RegExp;
  node: (m: RegExpExecArray, key: number) => ReactNode;
}

/** Ordered: code and math bind tightest, so their contents are never re-parsed as emphasis. */
const INLINE: InlineRule[] = [
  {
    re: /`([^`]+)`/,
    node: (m, key) => <code key={key} className="md-code">{m[1]}</code>,
  },
  {
    re: /\$\$([\s\S]+?)\$\$/,
    node: (m, key) => <span key={key} className="md-math block">{m[1].trim()}</span>,
  },
  {
    // Single-dollar math, but not a currency amount ("$5 and $7").
    re: /\$(?!\s)((?:[^$\n\\]|\\.)+?)(?<!\s)\$(?!\d)/,
    node: (m, key) => <span key={key} className="md-math">{m[1]}</span>,
  },
  {
    re: /!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/,
    node: (m, key) => <img key={key} className="md-img" src={m[2]} alt={m[1]} loading="lazy" />,
  },
  {
    re: /\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/,
    node: (m, key) => (
      <a key={key} href={safeHref(m[2])} target="_blank" rel="noopener noreferrer">{inline(m[1])}</a>
    ),
  },
  {
    re: /(https?:\/\/[^\s<>()[\]]+[^\s<>()[\].,;:!?])/,
    node: (m, key) => <a key={key} href={safeHref(m[1])} target="_blank" rel="noopener noreferrer">{m[1]}</a>,
  },
  {
    re: /\*\*([\s\S]+?)\*\*|__([\s\S]+?)__/,
    node: (m, key) => <strong key={key}>{inline(m[1] ?? m[2])}</strong>,
  },
  {
    re: /~~([\s\S]+?)~~/,
    node: (m, key) => <del key={key}>{inline(m[1])}</del>,
  },
  {
    // `_x_` only at a word boundary, so snake_case_identifiers survive.
    re: /\*([^\s*][\s\S]*?)\*|(?<![\w\\])_([^\s_][\s\S]*?)_(?![\w])/,
    node: (m, key) => <em key={key}>{inline(m[1] ?? m[2])}</em>,
  },
];

/** Only http(s) and mailto survive — a `javascript:` href from model output must not render. */
function safeHref(url: string): string {
  const trimmed = url.trim();
  return /^(https?:|mailto:|#|\/)/i.test(trimmed) ? trimmed : '#';
}

let keySeq = 0;

/** Render inline markdown, honouring single newlines as line breaks. */
export function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let rest = text;

  while (rest) {
    let best: { rule: InlineRule; m: RegExpExecArray } | null = null;
    for (const rule of INLINE) {
      const m = rule.re.exec(rest);
      if (m && (!best || m.index < best.m.index)) best = { rule, m };
      if (best && best.m.index === 0) break;
    }
    if (!best) { out.push(...withBreaks(rest)); break; }
    if (best.m.index > 0) out.push(...withBreaks(rest.slice(0, best.m.index)));
    out.push(best.rule.node(best.m, keySeq++));
    rest = rest.slice(best.m.index + best.m[0].length);
  }
  return out;
}

function withBreaks(text: string): ReactNode[] {
  const parts = text.split('\n');
  return parts.flatMap((part, i) =>
    i === 0 ? [part] : [<br key={`br${keySeq++}`} />, part],
  );
}

/* ---------------------------------------------------------------- render */

function CodeBlock({ code, lang, open }: { code: string; lang?: string; open: boolean }) {
  const [copied, setCopied] = useState(false);
  const tokens = useMemo(() => highlight(code, lang), [code, lang]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = code; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); document.body.removeChild(ta);
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  }

  return (
    <div className={'md-pre' + (open ? ' streaming' : '')}>
      <div className="md-pre-head">
        <span className="lang">{langLabel(lang)}</span>
        <button type="button" className="ghost sm" onClick={copy}>{copied ? '✓ Copied' : 'Copy'}</button>
      </div>
      <pre><code>{tokens.map((t, i) => (
        t.cls ? <span key={i} className={`hl-${t.cls}`}>{t.text}</span> : <Fragment key={i}>{t.text}</Fragment>
      ))}</code></pre>
    </div>
  );
}

function renderBlock(block: Block, key: number): ReactNode {
  switch (block.k) {
    case 'code':
      return <CodeBlock key={key} code={block.code} lang={block.lang} open={block.open} />;
    case 'head': {
      const Tag = `h${Math.min(block.level + 1, 6)}` as 'h2';
      return <Tag key={key} className={`md-h md-h${block.level}`}>{inline(block.text)}</Tag>;
    }
    case 'hr':
      return <hr key={key} className="md-hr" />;
    case 'quote':
      return <blockquote key={key} className="md-quote">{parseBlocks(block.lines.join('\n')).map(renderBlock)}</blockquote>;
    case 'list': {
      const items = block.items.map((lines, i) => {
        const inner = parseBlocks(lines.join('\n'));
        // A one-paragraph item renders inline, so simple lists stay tight.
        const body = inner.length === 1 && inner[0].k === 'para'
          ? inline(inner[0].lines.join('\n'))
          : inner.map(renderBlock);
        return <li key={i}>{body}</li>;
      });
      return block.ordered
        ? <ol key={key} className="md-list" start={block.start}>{items}</ol>
        : <ul key={key} className="md-list">{items}</ul>;
    }
    case 'table':
      return (
        <div key={key} className="md-tablewrap">
          <table className="md-table">
            <thead>
              <tr>{block.header.map((h, i) => (
                <th key={i} style={{ textAlign: block.align[i] ?? 'left' }}>{inline(h)}</th>
              ))}</tr>
            </thead>
            <tbody>
              {block.rows.map((row, r) => (
                <tr key={r}>{row.map((cell, c) => (
                  <td key={c} style={{ textAlign: block.align[c] ?? 'left' }}>{inline(cell)}</td>
                ))}</tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'para':
      return <p key={key} className="md-p">{inline(block.lines.join('\n'))}</p>;
  }
}

export function Markdown({ text }: { text: string }) {
  const blocks = useMemo(() => parseBlocks(text), [text]);
  return <div className="md">{blocks.map(renderBlock)}</div>;
}
