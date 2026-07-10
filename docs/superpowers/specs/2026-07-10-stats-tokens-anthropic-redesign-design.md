# Statistics tokens chart + Anthropic redesign — design

Date: 2026-07-10. Status: approved (user: "делай и деплой, без вопросов ко мне").

## Goal

Three things, all in the React admin UI (`frontend/`) plus one backend endpoint:

1. **New token-breakdown chart** on Statistics: per-day stacked bars of
   `input / output / cache-read / cache-write` where the stack height = **sum of all four**
   (covers both "sum(in out cache rw)" and "в сумме все токены"). Filterable by **model**
   (dropdown) and viewable **per account** via a selector (not a vertical wall of charts).
2. **Layout / UX**: the three chart types (spend · tokens · window utilization) sit **in one
   row**, not stacked vertically. A single **date-range control drives all charts at once**,
   default = last week. Same row-layout treatment for other charts.
3. **Full redesign** to Anthropic's warm "Claude" identity, **dual theme (light + dark),
   default = system** (`prefers-color-scheme`) with a manual toggle. Proper **mobile
   adaptation** and a **collapsible sidebar** (desktop rail + mobile off-canvas drawer).

## Backend (one new endpoint)

`GET /api/stats/tokens?days=<Int, default 7, clamp 1..90>&end=<ISO LocalDate, default today UTC>`
— mirrors `/stats/daily` conventions, requires `STATS_VIEW`.

Response:
```
{ days:[…],
  total:{ input:[Long], output:[Long], cacheRead:[Long], cacheWrite:[Long] },
  perModel:[ { model, input:[], output:[], cacheRead:[], cacheWrite:[] } ],
  perAccount:[ { accountId, accountName, input:[], output:[], cacheRead:[], cacheWrite:[] } ],
  models:[…], canViewAccounts }
```
- All series arrays have length == `days.length`, gaps = 0, bucketed by UTC date.
- `perModel`: one per distinct DB `model` value (null → `"unknown"`), sorted; `models` = same
  sorted labels. `perAccount` gated behind `canAccounts()` (empty + `canViewAccounts=false`
  otherwise), exactly like `/stats/daily`.
- New `UsageRepo.tokenBuckets(start, end)` aggregation (template: existing `dailyBuckets`) keeps
  the four token kinds separate, keyed by (accountId, model, date). Full-scan is acceptable
  (matches existing code); `ts` index noted as optional, out of scope.
- One fetch; the client filters by model/account locally (a week of data is small).

## Frontend

### Design system (`styles.css`)
- Dual-theme CSS variables. Light `:root` default; `@media (prefers-color-scheme: dark)`
  supplies dark; `html[data-theme="light|dark"]` overrides system when the user picks. Choice
  persisted in `localStorage` (`auto|light|dark`).
- Warm palette. Light: bg `#f5f4ee`, card `#fff/#faf9f5`, ink `#1a1a17`, muted `#6b6b63`,
  border `#e6e3da`, accent clay `#c96442`. Dark: bg `#14120f`, card `#1e1b17`, text `#ece7de`,
  muted `#a39e93`, border `#2c2820`, accent clay `#d97757`. Status hues warmed.
- Serif display headings via system stack `ui-serif, Georgia, 'Times New Roman', serif`
  (zero dependency — no web font, per CLAUDE.md). Body = existing system sans. `tabular-nums`
  on data. Radii 12/8px. Soft shadows in light theme only. Reduced-motion respected.

### App shell (`App.tsx` + `ui.tsx`)
- Collapsible sidebar: desktop full ↔ icon-rail (chevron toggle, state in `localStorage`);
  mobile (<900px) off-canvas drawer + top bar (hamburger, brand, theme toggle) + scrim,
  closes on nav-select / outside-click / Esc. Inline SVG icons per nav item (no lib).
- Theme toggle control (Auto / Light / Dark) in the sidebar footer.

### Reusable components (`ui.tsx`)
- `Select` (styled dropdown for model + account), `Segmented` (range presets), `Card`,
  `IconButton`, `ThemeToggle`. Keep the existing class-based approach (no Tailwind/shadcn).

### Charts (`Chart.tsx`)
- Reuse `StackedBarChart` for the token chart (it already takes arbitrary series + `fmt`).
- Add optional **interactive legend** (click to toggle a series) shared by spend + tokens.
- Charts already read CSS variables, so theming reflows automatically. Ensure axis/grid stay
  legible in both themes; add empty-state text.

### Statistics page (`Stats.tsx`)
- Top **control bar** over everything: range presets **7 / 30 / 90d** (default 7) + existing
  prev/next date shifter; drives daily, windows, and tokens fetches together.
- **Row 1 — three charts side by side** (CSS grid, `repeat(3,…)` → 2 → 1 by breakpoint):
  Spend/day · Tokens (new) · Window utilization.
- Token chart has a **model dropdown** (All + each model) filtering it; legend toggles kinds;
  tooltip shows each kind + total.
- **Per-account section**: an **account `Select`** (default "All accounts") renders that
  account's spend + tokens + utilization charts in a row — replaces the vertical per-account
  wall.
- Tables (per-account 24h, recent requests) restyled; wrapped for horizontal scroll on mobile.

### Responsive
- Breakpoints ~1200 / 900 / 600. Chart grid 3→2→1. KPI cards reflow (existing auto-fit grid).
  Tables scroll inside `.tablewrap`. Sidebar becomes drawer <900px. No horizontal page scroll.

## Non-goals / YAGNI
- No custom date picker (presets + prev/next suffice). No `ts` DB index in this pass. No
  per-model×per-account cross-filter. No chart library — keep hand-rolled SVG.

## Rollout
Backend endpoint + `api.ts` first, then `styles.css` (cascades the new look to all pages via
shared classes), shell, components, charts, Stats. Build UI → `./gradlew bundle` → deploy per
`docs/DEPLOY.md` (source-only rsync, `--no-deps` app rebuild; never `--delete`).
