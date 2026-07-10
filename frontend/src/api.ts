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
  todayCost: number;
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
  priority: number;
  threshold: number;
  coefficient: number;
  enabled: boolean;
  health: string;
  fiveHour: WindowLimitDto | null;
  weekly: WindowLimitDto | null;
  usageFraction: number | null;
  rateLimitedUntil: string | null;
  effectiveRemaining: number | null;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCost: number;
  totalRequests: number;
  clientId: string | null;
  createdAt: string;
}

export interface PoolStats {
  totalAccounts: number;
  healthyAccounts: number;
  activeAccountId: number | null;
  totalEffectiveRemaining: number;
  totalEffectiveCapacity: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCost: number;
  totalRequests: number;
  nextFiveHourReset: string | null;
  nextWeeklyReset: string | null;
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
}

export interface RoleDto {
  id: number;
  name: string;
  permissions: string[];
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

  accounts: () => req<PoolStats>('GET', '/api/accounts'),
  createAccount: (b: unknown) => req<PoolStats>('POST', '/api/accounts', b),
  updateAccount: (id: number, b: unknown) => req<PoolStats>('PATCH', `/api/accounts/${id}`, b),
  deleteAccount: (id: number) => req<unknown>('DELETE', `/api/accounts/${id}`),
  refreshOne: (id: number) => req<PoolStats>('POST', `/api/accounts/${id}/refresh-limits`),
  refreshAll: () => req<PoolStats>('POST', '/api/accounts/refresh-limits'),
  oauthStart: () => req<{ authorizeUrl: string; state: string }>('POST', '/api/accounts/oauth/start'),
  oauthComplete: (b: unknown) => req<PoolStats>('POST', '/api/accounts/oauth/complete', b),

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

  statsSummary: () => req<UsageSummary[]>('GET', '/api/stats/summary'),
  statsRecent: () => req<UsageEvent[]>('GET', '/api/stats/recent'),
  statsDaily: (days: number, end?: string) => req<DailyStats>('GET', `/api/stats/daily?days=${days}${end ? `&end=${end}` : ''}`),
  statsWindows: (days: number, end?: string) => req<WindowStats>('GET', `/api/stats/windows?days=${days}${end ? `&end=${end}` : ''}`),
  myStats: () => req<MyStats>('GET', '/api/stats/mine'),
  resetAllStats: () => req<{ message: string }>('POST', '/api/stats/reset'),
  resetUserStats: (id: number) => req<{ message: string }>('POST', `/api/users/${id}/stats/reset`),
  resetMyStats: () => req<{ message: string }>('POST', '/api/stats/mine/reset'),
};

export interface UsageSummary { accountId: number; accountName: string | null; requests: number; inputTokens: number; outputTokens: number; cost: number; }
export interface UsageEvent { id: number; accountId: number; accountName: string | null; ts: string; inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; cost: number; httpStatus: number; model: string | null; }
export interface ModelUsage { model: string | null; requests: number; cleanTokens: number; cost: number; }
export interface MyStats {
  todayCost: number; todayClean: number; todayRequests: number;
  totalCost: number; totalClean: number; totalRequests: number;
  dailyCostLimit: number | null;
  perModel: ModelUsage[]; recent: UsageEvent[];
}
export interface AccountSeries { accountId: number; accountName: string | null; cost: number[]; requests: number[]; }
export interface DailyStats {
  days: string[];
  totalCost: number[];
  totalRequests: number[];
  perAccount: AccountSeries[];
  canViewAccounts: boolean;
}
export interface WindowSeries { accountId: number; accountName: string | null; fiveHour: (number | null)[]; weekly: (number | null)[]; }
export interface WindowStats {
  buckets: string[];
  totalFiveHour: (number | null)[];
  totalWeekly: (number | null)[];
  perAccount: WindowSeries[];
  canViewAccounts: boolean;
}

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
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
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
