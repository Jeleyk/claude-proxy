// Thin fetch wrapper for the management API. Cookies carry the session.

export interface UserDto {
  id: number;
  username: string;
  enabled: boolean;
  roles: string[];
  permissions: string[];
  allowedGroups: number[];
  allGroups: boolean;
  dailyCostLimit: number | null;
  dailyRoutingCostLimit: number | null;
  preferGlobalPool: boolean;
  todayCost: number;
  todayRoutingCost: number;
  todayInputTokens: number;
  todayOutputTokens: number;
}

export interface ModelPrice {
  pattern: string;
  inputPrice: number;
  outputPrice: number;
  cacheReadPrice: number;
  cacheWritePrice: number;
}

export interface WindowLimitDto {
  usageFraction: number | null;
  remaining: number | null;
  limitTotal: number | null;
  resetAt: string | null;
  status: string | null;
  updatedAt: string | null;
}

export interface AccountDto {
  id: number;
  name: string;
  type: string;
  groupId: number | null;
  ownerId: number | null;
  priority: number;
  threshold: number;
  coefficient: number;
  enabled: boolean;
  overThreshold: boolean;
  health: string;
  fiveHour: WindowLimitDto | null;
  weekly: WindowLimitDto | null;
  usageFraction: number | null;
  rateLimitedUntil: string | null;
  effectiveRemaining: number | null;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheReadTokens: number;
  totalCacheWriteTokens: number;
  totalCost: number;
  totalRequests: number;
  deviceId: string | null;
  createdAt: string;
}

export interface PoolStats {
  totalAccounts: number;
  healthyAccounts: number;
  activeAccountId: number | null;
  totalEffectiveRemaining: number;
  totalEffectiveCapacity: number;
  totalWeeklyRemaining: number;
  totalWeeklyCapacity: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheReadTokens: number;
  totalCacheWriteTokens: number;
  totalCost: number;
  totalRequests: number;
  nextFiveHourReset: string | null;
  nextWeeklyReset: string | null;
  // requests streaming from Anthropic right now — pool-wide on /api/accounts, own on /api/my/accounts
  activeProxySessions: number;
  activeRoutingSessions: number;
  accounts: AccountDto[];
}

export interface GroupDto {
  id: number;
  name: string;
  accountCount: number;
  createdAt: string;
}

export interface ProxyTokenDto {
  id: number;
  name: string;
  userId: number;
  createdAt: string;
  lastUsedAt: string | null;
  token?: string | null;
  // routing tokens only: static system prompt injected ahead of client system content
  systemPrompt?: string | null;
  // off = the token stops authenticating (clients get 401) without being deleted
  enabled: boolean;
}

export interface RoleDto {
  id: number;
  name: string;
  permissions: string[];
}

/** Datapath filter for the stats views: undefined = both sources. */
export type StatsSource = 'proxy' | 'routing' | undefined;

/** Own stats live under /api/stats/mine; the admin view of another user under /api/users/{id}/stats. */
function statsBase(uid: number | null): string {
  return uid == null ? '/api/stats/mine' : `/api/users/${uid}/stats`;
}

function qs(params: Record<string, string | number | undefined>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * The viewer's IANA timezone, sent with every time-series request so the server slices days on
 * their clock instead of UTC. Falls back to UTC when the browser won't say (the server does the
 * same for a missing/unparseable value, so behaviour matches).
 */
export function localTz(): string {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch { return 'UTC'; }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
  return data as T;
}

export const api = {
  config: () => req<{ publicBaseUrl: string }>('GET', '/api/config'),
  modelPrices: () => req<ModelPrice[]>('GET', '/api/model-prices'),
  setModelPrice: (b: ModelPrice) => req<ModelPrice[]>('POST', '/api/model-prices', b),
  deleteModelPrice: (pattern: string) => req<ModelPrice[]>('DELETE', `/api/model-prices/${encodeURIComponent(pattern)}`),

  login: (username: string, password: string) =>
    req<UserDto>('POST', '/api/auth/login', { username, password }),
  logout: () => req<unknown>('POST', '/api/auth/logout'),
  me: () => req<UserDto>('GET', '/api/auth/me'),
  updateProfile: (b: { currentPassword: string; username?: string; password?: string }) =>
    req<UserDto>('PATCH', '/api/account', b),

  accounts: () => req<PoolStats>('GET', '/api/accounts'),
  createAccount: (b: unknown) => req<PoolStats>('POST', '/api/accounts', b),
  updateAccount: (id: number, b: unknown) => req<PoolStats>('PATCH', `/api/accounts/${id}`, b),
  deleteAccount: (id: number) => req<unknown>('DELETE', `/api/accounts/${id}`),
  refreshOne: (id: number) => req<PoolStats>('POST', `/api/accounts/${id}/refresh-limits`),
  refreshAll: () => req<PoolStats>('POST', '/api/accounts/refresh-limits'),
  oauthStart: () => req<{ authorizeUrl: string; state: string }>('POST', '/api/accounts/oauth/start'),
  oauthComplete: (b: unknown) => req<PoolStats>('POST', '/api/accounts/oauth/complete', b),

  // personal (per-user) accounts — tried before the global pool, excluded from global stats
  myAccounts: () => req<PoolStats>('GET', '/api/my/accounts'),
  globalPool: () => req<PoolStats>('GET', '/api/my/global-pool'),
  createMyAccount: (b: unknown) => req<PoolStats>('POST', '/api/my/accounts', b),
  updateMyAccount: (id: number, b: unknown) => req<PoolStats>('PATCH', `/api/my/accounts/${id}`, b),
  deleteMyAccount: (id: number) => req<unknown>('DELETE', `/api/my/accounts/${id}`),
  refreshMyOne: (id: number) => req<PoolStats>('POST', `/api/my/accounts/${id}/refresh-limits`),
  refreshMyAll: () => req<PoolStats>('POST', '/api/my/accounts/refresh-limits'),
  myOauthStart: () => req<{ authorizeUrl: string; state: string }>('POST', '/api/my/accounts/oauth/start'),
  myOauthComplete: (b: unknown) => req<PoolStats>('POST', '/api/my/accounts/oauth/complete', b),

  // admin oversight of a user's personal accounts
  userAccounts: (uid: number) => req<PoolStats>('GET', `/api/users/${uid}/accounts`),
  updateUserAccount: (uid: number, id: number, b: unknown) => req<PoolStats>('PATCH', `/api/users/${uid}/accounts/${id}`, b),
  deleteUserAccount: (uid: number, id: number) => req<PoolStats>('DELETE', `/api/users/${uid}/accounts/${id}`),

  groups: () => req<GroupDto[]>('GET', '/api/groups'),
  createGroup: (name: string) => req<GroupDto[]>('POST', '/api/groups', { name }),
  renameGroup: (id: number, name: string) => req<GroupDto[]>('PATCH', `/api/groups/${id}`, { name }),
  deleteGroup: (id: number) => req<unknown>('DELETE', `/api/groups/${id}`),

  users: () => req<UserDto[]>('GET', '/api/users'),
  createUser: (b: unknown) => req<UserDto>('POST', '/api/users', b),
  updateUser: (id: number, b: unknown) => req<UserDto>('PATCH', `/api/users/${id}`, b),
  deleteUser: (id: number) => req<unknown>('DELETE', `/api/users/${id}`),

  roles: () => req<{ roles: RoleDto[]; allPermissions: string[] }>('GET', '/api/roles'),
  createRole: (b: unknown) => req<unknown>('POST', '/api/roles', b),
  updateRole: (id: number, b: unknown) => req<unknown>('PATCH', `/api/roles/${id}`, b),
  deleteRole: (id: number) => req<unknown>('DELETE', `/api/roles/${id}`),

  tokens: () => req<ProxyTokenDto[]>('GET', '/api/proxy-tokens'),
  createToken: (name: string) => req<ProxyTokenDto>('POST', '/api/proxy-tokens', { name }),
  deleteToken: (id: number) => req<unknown>('DELETE', `/api/proxy-tokens/${id}`),
  // Disable/enable a token without revoking it; returns the refreshed list.
  setTokenEnabled: (id: number, enabled: boolean) =>
    req<ProxyTokenDto[]>('PATCH', `/api/proxy-tokens/${id}/enabled`, { enabled }),

  // Routing tokens (cxr_...) for the OpenAI/Anthropic API gateways. Same shape as proxy tokens.
  routingTokens: () => req<ProxyTokenDto[]>('GET', '/api/routing-tokens'),
  createRoutingToken: (name: string, systemPrompt?: string) =>
    req<ProxyTokenDto>('POST', '/api/routing-tokens', { name, systemPrompt: systemPrompt || null }),
  deleteRoutingToken: (id: number) => req<unknown>('DELETE', `/api/routing-tokens/${id}`),
  setRoutingTokenEnabled: (id: number, enabled: boolean) =>
    req<ProxyTokenDto[]>('PATCH', `/api/routing-tokens/${id}/enabled`, { enabled }),
  // Set (non-blank) or clear (null) a routing token's static system prompt.
  updateRoutingTokenPrompt: (id: number, systemPrompt: string | null) =>
    req<ProxyTokenDto[]>('PATCH', `/api/routing-tokens/${id}`, { systemPrompt }),

  statsSummary: () => req<UsageSummary[]>('GET', '/api/stats/summary'),
  statsRecent: () => req<UsageEvent[]>('GET', '/api/stats/recent'),
  // `tz` makes the "today" half of the breakdown the viewer's day, matching the charts.
  statsModels: () => req<ModelBreakdown>('GET', `/api/stats/models${qs({ tz: localTz() })}`),
  statsDaily: (days: number, end?: string) => req<DailyStats>('GET', `/api/stats/daily${qs({ days, end, tz: localTz() })}`),
  statsWindows: (days: number, end?: string) => req<WindowStats>('GET', `/api/stats/windows${qs({ days, end, tz: localTz() })}`),
  statsTokens: (days: number, end?: string) => req<TokenStats>('GET', `/api/stats/tokens${qs({ days, end, tz: localTz() })}`),
  // Per-day window burn (5h + weekly), reset-aware: how much of each window was spent per day.
  statsWindowDaily: (days: number, end?: string) =>
    req<WindowDaily>('GET', `/api/stats/window-daily${qs({ days, end, tz: localTz() })}`),
  // Per-user statistics: uid=null → the caller's own ("My Stats"), a number → admin view of that
  // user (USERS_MANAGE). `source` filters to one datapath ('proxy' | 'routing'); undefined = both.
  userStats: (uid: number | null, source?: StatsSource) =>
    req<MyStats>('GET', `${statsBase(uid)}${qs({ source, tz: localTz() })}`),
  userStatsDaily: (uid: number | null, days: number, end?: string, source?: StatsSource) =>
    req<DailyStats>('GET', `${statsBase(uid)}/daily${qs({ days, end, source, tz: localTz() })}`),
  userStatsWindows: (uid: number | null, days: number, end?: string) =>
    req<WindowStats>('GET', `${statsBase(uid)}/windows${qs({ days, end, tz: localTz() })}`),
  userStatsWindowDaily: (uid: number | null, days: number, end?: string) =>
    req<WindowDaily>('GET', `${statsBase(uid)}/window-daily${qs({ days, end, tz: localTz() })}`),
  userStatsTokens: (uid: number | null, days: number, end?: string, source?: StatsSource) =>
    req<TokenStats>('GET', `${statsBase(uid)}/tokens${qs({ days, end, source, tz: localTz() })}`),
  // Per-inbound-token usage (Tokens / API Routing pages + the stats views): all-time totals + daily series.
  userTokenUsage: (uid: number | null, source: 'proxy' | 'routing', days: number, end?: string) =>
    req<TokenUsage>('GET', `${statsBase(uid)}/token-usage${qs({ source, days, end, tz: localTz() })}`),
  // Per-MCP-tool call counts (Claude Code datapath): daily series + range totals per tool.
  userMcpUsage: (uid: number | null, days: number, end?: string) =>
    req<McpUsage>('GET', `${statsBase(uid)}/mcp${qs({ days, end, tz: localTz() })}`),
  usersOverview: () => req<UserStatsOverview[]>('GET', `/api/users/stats/overview${qs({ tz: localTz() })}`),
  myStats: () => req<MyStats>('GET', `/api/stats/mine${qs({ tz: localTz() })}`),
  tokenUsage: (source: 'proxy' | 'routing', days: number, end?: string) =>
    req<TokenUsage>('GET', `/api/stats/mine/token-usage${qs({ source, days, end, tz: localTz() })}`),
  setAccountOrder: (preferGlobalPool: boolean) => req<UserDto>('PATCH', '/api/my/account-order', { preferGlobalPool }),
  resetAllStats: () => req<{ message: string }>('POST', '/api/stats/reset'),
  resetUserStats: (id: number) => req<{ message: string }>('POST', `/api/users/${id}/stats/reset`),
  resetMyStats: () => req<{ message: string }>('POST', '/api/stats/mine/reset'),
};

export interface UsageSummary { accountId: number; accountName: string | null; requests: number; inputTokens: number; outputTokens: number; cost: number; }
export interface UsageEvent { id: number; accountId: number; accountName: string | null; ts: string; inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; cost: number; httpStatus: number; model: string | null; source: string; }
export interface ModelUsage { model: string | null; requests: number; cleanTokens: number; cost: number; }
export interface ModelBreakdown { today: ModelUsage[]; allTime: ModelUsage[]; }
export interface MyStats {
  todayCost: number; todayClean: number; todayRequests: number;
  totalCost: number; totalClean: number; totalRequests: number;
  dailyCostLimit: number | null;
  proxyTodayCost: number;             // spend counted against dailyCostLimit today
  dailyRoutingCostLimit: number | null;
  routingTodayCost: number;           // spend counted against dailyRoutingCostLimit today
  activeProxySessions: number;        // this user's in-flight requests, by datapath
  activeRoutingSessions: number;
  perModel: ModelUsage[]; perModelToday: ModelUsage[]; recent: UsageEvent[];
}
export interface UserStatsOverview {
  userId: number | null;              // null = usage left by since-deleted users
  username: string | null;
  enabled: boolean;
  dailyCostLimit: number | null;
  dailyRoutingCostLimit: number | null;
  todayProxyCost: number; todayRoutingCost: number; todayCost: number;
  todayRequests: number; todayTokens: number;
  totalProxyCost: number; totalRoutingCost: number; totalCost: number;
  totalRequests: number; totalTokens: number;
  lastActivity: string | null;
}
export interface AccountSeries { accountId: number; accountName: string | null; cost: number[]; requests: number[]; }
export interface DailyStats {
  days: string[];
  totalCost: number[];
  totalRequests: number[];
  perAccount: AccountSeries[];
  canViewAccounts: boolean;
}
export interface WindowSeries {
  accountId: number; accountName: string | null;
  fiveHour: (number | null)[]; weekly: (number | null)[];
  // coefficient × utilization (may exceed 1) — the ×coef toggle switches to these
  fiveHourWeighted: (number | null)[]; weeklyWeighted: (number | null)[];
}
export interface WindowStats {
  buckets: string[];
  totalFiveHour: (number | null)[];          // Σ raw utilization across accounts (may exceed 1)
  totalWeekly: (number | null)[];
  totalFiveHourWeighted: (number | null)[];  // Σ coefficient × utilization across accounts
  totalWeeklyWeighted: (number | null)[];
  perAccount: WindowSeries[];
  canViewAccounts: boolean;
}

export interface WindowDailySeries {
  accountId: number; accountName: string | null;
  // window-fractions burned that day: 1.0 = one whole window. Several resets in a day stack past 1.
  fiveHour: number[]; weekly: number[];
  // the 5h burn restated in base-subscription windows (each step ×the account's coefficient)
  fiveHourWeighted: number[];
}
export interface WindowDaily {
  days: string[];
  totalFiveHour: number[];
  totalWeekly: number[];
  totalFiveHourWeighted: number[];
  perAccount: WindowDailySeries[];
  canViewAccounts: boolean;
}

export interface TokenKindSeries { input: number[]; output: number[]; cacheRead: number[]; cacheWrite: number[]; }
export interface ModelTokenSeries extends TokenKindSeries { model: string; }
export interface AccountTokenSeries extends TokenKindSeries { accountId: number; accountName: string | null; }
export interface TokenStats {
  days: string[];
  total: TokenKindSeries;
  perModel: ModelTokenSeries[];
  perAccount: AccountTokenSeries[];
  models: string[];
  canViewAccounts: boolean;
}

export interface TokenUsageSeries {
  tokenId: number | null;      // null = unattributed (pre-migration) rows
  name: string | null;         // null for deleted tokens
  cost: number[];              // per-day USD, aligned with `days`
  tokens: number[];            // per-day total tokens (all four kinds)
  requests: number[];
  totalCost: number;           // all-time totals (not range-scoped)
  totalTokens: number;
  totalRequests: number;
}
export interface TokenUsage { days: string[]; perToken: TokenUsageSeries[]; }

export interface McpToolSeries {
  name: string;                // full tool name, e.g. "mcp__github__get_issue"
  calls: number[];             // per-day call counts, aligned with `days`
  totalCalls: number;          // sum over the range
}
export interface McpUsage { days: string[]; tools: McpToolSeries[]; }

export function has(user: UserDto | null, perm: string): boolean {
  return !!user && user.permissions.includes(perm);
}

export function fmtUsd(n: number): string {
  if (n === 0) return '$0';
  if (n < 0.01) return `$${n.toFixed(4)}`;
  if (n < 1) return `$${n.toFixed(3)}`;
  return `$${n.toFixed(2)}`;
}

export function fmtTokens(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** Window burn as a percentage of one window: 2.78 → "278%". */
export function fmtWindowPct(n: number): string {
  if (n === 0) return '0%';
  if (n < 0.01) return `${(n * 100).toFixed(2)}%`;
  if (n < 0.1) return `${(n * 100).toFixed(1)}%`;
  return `${Math.round(n * 100)}%`;
}

/**
 * Time left until the daily-cost limit rolls over. The limit is enforced on UTC days (see
 * DatapathService), so this counts down to the next 00:00 UTC regardless of the viewer's zone —
 * which is the whole reason it's spelled out in the UI.
 */
export function fmtUntilUtcMidnight(now: Date = new Date()): string {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  const diff = next - now.getTime();
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export function fmtReset(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const diff = d.getTime() - Date.now();
  if (diff <= 0) return 'now';
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
