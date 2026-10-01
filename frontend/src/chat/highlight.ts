/* ============================================================================
   Tiny syntax highlighter for chat code blocks.

   Deliberately hand-rolled: a real grammar-based highlighter is ~500 kB of
   dependency for something that only has to make a fenced block readable. One
   pass of an ordered alternation regex per language family, emitting flat
   tokens the renderer wraps in spans — good enough to read, impossible to
   break the page with.
   ========================================================================== */

export interface Token {
  text: string;
  /** css class under `.hl-`; absent = plain text */
  cls?: 'com' | 'str' | 'num' | 'kw' | 'fn' | 'typ' | 'op' | 'tag' | 'atr' | 'var';
}

type Family = 'c' | 'python' | 'shell' | 'sql' | 'html' | 'css' | 'json' | 'plain';

const FAMILY: Record<string, Family> = {
  js: 'c', jsx: 'c', ts: 'c', tsx: 'c', javascript: 'c', typescript: 'c',
  java: 'c', kotlin: 'c', kt: 'c', kts: 'c', swift: 'c', scala: 'c', dart: 'c',
  go: 'c', rust: 'c', rs: 'c', c: 'c', h: 'c', cpp: 'c', 'c++': 'c', cs: 'c', csharp: 'c',
  php: 'c', groovy: 'c',
  python: 'python', py: 'python', ruby: 'python', rb: 'python', r: 'python',
  sh: 'shell', bash: 'shell', zsh: 'shell', shell: 'shell', console: 'shell', fish: 'shell',
  yaml: 'shell', yml: 'shell', toml: 'shell', ini: 'shell', dockerfile: 'shell', makefile: 'shell',
  sql: 'sql', postgres: 'sql', postgresql: 'sql', mysql: 'sql',
  html: 'html', xml: 'html', svg: 'html', vue: 'html', svelte: 'html',
  css: 'css', scss: 'css', sass: 'css', less: 'css',
  json: 'json', jsonc: 'json',
};

const KEYWORDS: Record<Family, string[]> = {
  c: ('abstract as async await break case catch class const constructor continue debugger default defer delete do ' +
    'else enum export extends false finally fn for from func function get go if impl implements import in ' +
    'instanceof interface internal is let match mod mut new null nil object of open operator override package ' +
    'private protected public readonly return sealed set static struct super suspend switch this throw throws ' +
    'trait true try type typeof union unsafe use val var void when where while yield').split(' '),
  python: ('and as assert async await begin break class continue def del elif else end except False finally for ' +
    'from global if import in is lambda module never new nil none None not or pass raise require return self ' +
    'True try unless until while with yield').split(' '),
  shell: ('if then else elif fi for while do done case esac function return export local readonly source alias ' +
    'set unset echo cd exit trap shift eval exec sudo apt npm pnpm yarn docker git curl true false null').split(' '),
  sql: ('select from where group by order having limit offset insert into values update set delete create table ' +
    'alter drop index view join left right inner outer full on as and or not null is distinct union all ' +
    'primary key foreign references default cascade returning with case when then else end').split(' '),
  html: [],
  css: [],
  json: ['true', 'false', 'null'],
  plain: [],
};

function familyOf(lang?: string): Family {
  if (!lang) return 'plain';
  return FAMILY[lang.toLowerCase().trim()] ?? 'plain';
}

/** Ordered alternation: whatever matches first wins, so a keyword inside a string stays a string. */
function patternFor(family: Family): RegExp {
  const string = `"(?:[^"\\\\\\n]|\\\\.)*"|'(?:[^'\\\\\\n]|\\\\.)*'|\`(?:[^\`\\\\]|\\\\.)*\``;
  const number = `\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b0[xX][0-9a-fA-F]+\\b`;
  const ident = `[A-Za-z_$][\\w$]*`;
  switch (family) {
    case 'html':
      return new RegExp(`(<!--[\\s\\S]*?-->)|(<[/!]?${ident}(?:[-:]\\w+)*)|(${ident}(?:[-:]\\w+)*)(?==)|(${string})|(>)`, 'g');
    case 'css':
      return new RegExp(`(/\\*[\\s\\S]*?\\*/)|(${string})|(@${ident}|[.#]${ident}[\\w-]*)|(${ident}[\\w-]*)(?=\\s*:)|(${number}(?:px|em|rem|%|vh|vw|s|ms)?)`, 'g');
    case 'shell':
      return new RegExp(`(#[^\\n]*)|(${string})|(\\$\\{?${ident}\\}?)|(${number})|\\b(${KEYWORDS.shell.join('|')})\\b`, 'g');
    case 'sql':
      return new RegExp(`(--[^\\n]*|/\\*[\\s\\S]*?\\*/)|(${string})|(${number})|\\b(${KEYWORDS.sql.join('|')})\\b`, 'gi');
    case 'json':
      return new RegExp(`(${string})(?=\\s*:)|(${string})|(${number})|\\b(true|false|null)\\b`, 'g');
    case 'python':
      return new RegExp(`(#[^\\n]*)|("""[\\s\\S]*?"""|'''[\\s\\S]*?'''|${string})|(${number})|\\b(${KEYWORDS.python.join('|')})\\b|\\b(${ident})(?=\\s*\\()|\\b([A-Z]${ident.slice(0, -1)}*)\\b`, 'g');
    case 'c':
      return new RegExp(`(//[^\\n]*|/\\*[\\s\\S]*?\\*/)|(${string})|(${number})|\\b(${KEYWORDS.c.join('|')})\\b|\\b(${ident})(?=\\s*\\()|\\b([A-Z][\\w$]*)\\b`, 'g');
    default:
      return new RegExp(`(${string})|(${number})`, 'g');
  }
}

/** Which capture group maps to which token class, per family. */
const CLASSES: Record<Family, Token['cls'][]> = {
  c: ['com', 'str', 'num', 'kw', 'fn', 'typ'],
  python: ['com', 'str', 'num', 'kw', 'fn', 'typ'],
  shell: ['com', 'str', 'var', 'num', 'kw'],
  sql: ['com', 'str', 'num', 'kw'],
  html: ['com', 'tag', 'atr', 'str', 'tag'],
  css: ['com', 'str', 'kw', 'atr', 'num'],
  json: ['atr', 'str', 'num', 'kw'],
  plain: ['str', 'num'],
};

/** Split [code] into flat tokens. Unknown languages still get strings and numbers. */
export function highlight(code: string, lang?: string): Token[] {
  const family = familyOf(lang);
  if (family === 'plain' && !lang) return [{ text: code }];
  const re = patternFor(family);
  const classes = CLASSES[family];
  const out: Token[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(code))) {
    // A zero-length match would spin forever; step past it.
    if (m.index === re.lastIndex) { re.lastIndex++; continue; }
    if (m.index > last) out.push({ text: code.slice(last, m.index) });
    const groupIndex = m.slice(1).findIndex((g) => g !== undefined);
    out.push({ text: m[0], cls: groupIndex >= 0 ? classes[groupIndex] : undefined });
    last = m.index + m[0].length;
  }
  if (last < code.length) out.push({ text: code.slice(last) });
  return out;
}

/** Human label for the fence's language tag ("tsx" → "TSX"). */
export function langLabel(lang?: string): string {
  if (!lang) return 'text';
  const l = lang.toLowerCase();
  const pretty: Record<string, string> = {
    js: 'JavaScript', jsx: 'JSX', ts: 'TypeScript', tsx: 'TSX', py: 'Python', rb: 'Ruby',
    rs: 'Rust', kt: 'Kotlin', kts: 'Kotlin', sh: 'Shell', yml: 'YAML', md: 'Markdown',
    cs: 'C#', cpp: 'C++', go: 'Go', sql: 'SQL', json: 'JSON', html: 'HTML', css: 'CSS',
  };
  return pretty[l] ?? l.charAt(0).toUpperCase() + l.slice(1);
}
