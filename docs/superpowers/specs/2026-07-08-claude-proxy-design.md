# Claude Proxy — Design Spec

Дата: 2026-07-08
Статус: утверждён пользователем (строим автономно до launchable)

## Цель

Прокси для Claude Code, который хранит несколько upstream-аккаунтов Anthropic
(OAuth-подписки и API-ключи), маршрутизирует каждый запрос на аккаунт, у которого
ещё не выбран лимит текущего 5-часового окна, с настраиваемым порогом (threshold),
приоритетом (аккаунты идут строго 1 за другим) и fallback-режимом (когда все
достигли порога — работаем игнорируя порог до реального исчерпания). Управление —
через веб-UI (React SPA) + REST API, с логином, ролями и правами. Аккаунты Anthropic
добавляются в UI: по API-ключу или через внешний OAuth-флоу «Login with Claude».

## Терминология

- **User** — юзер сайта/прокси. Логинится в UI, ходит через прокси. Имеет роли/права.
- **Account** — upstream-креды Anthropic (OAuth-подписка или API-ключ). Ротируются.
- **5h-window** — 5-часовое окно лимита подписки.
- **Coefficient (tier)** — относительная ёмкость аккаунта (×1 обычный/ключ, ×5 Max 5x, ×20 Max 20x).

## Архитектура (Подход A — единый Ktor-модульный монолит)

Один backend-процесс на Ktor (Kotlin), внутренне разбит на модули:

- `proxy-core` — горячий путь: приём запроса → выбор аккаунта → форвард на
  `api.anthropic.com` → стриминг (SSE) ответа обратно → снятие usage из хедеров.
- `accounts` — пул аккаунтов, логика выбора (priority/threshold/fallback/coefficient),
  фоновый refresh токенов, health.
- `auth` — юзеры сайта, RBAC (роли/права), логин (сессия/JWT), проксёвые токены.
- `admin-api` — REST CRUD (users, accounts, stats) + OAuth-add (PKCE) флоу.
- `persistence` — SQLite через Exposed; шифрование секретов master-ключом (AES-GCM).
- React SPA (Vite + React + TS) в том же репо, собирается в статику и отдаётся Ktor.

Целевой JVM 21 (запуск на JDK 25), Kotlin 2.x, Gradle. Один fat-jar для деплоя.

## Модель данных (SQLite / Exposed)

- `users` — id, username, password_hash (bcrypt/argon2), enabled, created_at.
- `roles` — id, name; `role_permissions` (role_id, permission); `user_roles` (user_id, role_id).
  Права (enum): `proxy.use`, `accounts.view`, `stats.view`, `accounts.manage`,
  `users.manage`, `admin`.
- `proxy_tokens` — id, user_id, token_hash, name, created_at, last_used_at.
  Токен, который юзер вставляет в Claude Code (`ANTHROPIC_AUTH_TOKEN`).
- `accounts` — id, name, type (`oauth`|`oauth_static`|`api_key`), priority (int),
  threshold (double 0..1, дефолт 0.9), coefficient (double, дефолт 1.0),
  enabled, health (`ok`|`refresh_failed`|`dead`), created_by, created_at.
- `account_secrets` — account_id, cipher_blob (AES-GCM: access_token, refresh_token,
  expires_at, api_key). Ключ шифрования из env `MASTER_KEY`.
- `account_limits` — account_id, window_kind (`5h`|`7d`), remaining (double),
  limit_total (double), reset_at (instant), status (`allowed`|`allowed_warning`|`rejected`),
  rate_limited_until (instant?), updated_at.
- `usage_events` — id, account_id, user_id?, ts, input_tokens, output_tokens,
  http_status, model.
- `oauth_add_sessions` — id, state, pkce_verifier, created_by, expires_at.

## Выбор аккаунта (ядро)

На каждый входящий запрос:
1. Кандидаты = enabled + health=ok, отсортированы по `priority` (меньше = раньше).
2. **Нормальный режим:** идём строго по приоритету, «прилипая» к текущему верхнему
   аккаунту, пока `usage% < threshold` и он не rate-limited. Перешёл threshold →
   следующий по приоритету.
3. **Fallback-режим:** если ВСЕ кандидаты ≥ threshold (или исчерпаны) → игнорируем
   threshold и продолжаем по тому же приоритету, пока каждый не упрётся в реальный
   лимит (429/`rejected`).
4. Аккаунт словил 429 → `rate_limited_until = reset_at`, исключаем до сброса окна.
5. Все жёстко исчерпаны → клиенту 429 + ближайший `reset_at`.

Выбор атомарен (мьютекс/актор на пуле), чтобы параллельные запросы не гоняли счётчики вразнос.

## Умный учёт лимитов + коэффициенты

- Основной источник — хедеры `anthropic-ratelimit-unified-*` из каждого ответа
  (`remaining`, `limit`, `reset`, `status`). **Точный формат хедеров подтверждается
  на живом ответе (шаг калибровки).** `usage% = 1 − remaining/limit`, либо по `status`
  если чистого числа нет.
- Fallback, если хедеров нет: локальный счёт токенов в 5h-окне против бюджета
  `budget = base_budget × coefficient`.
- **Coefficient** даёт абсолютную взвешенную ёмкость: `effective_remaining =
  coefficient × remaining_fraction`. Используется для дашборда (суммарная ёмкость
  пула), fallback-бюджета и сравнения аккаунтов разных тиров.
- Threshold сравнивается с self-normalized usage% самого аккаунта; коэффициент влияет
  на абсолютные оценки/бюджет, не на сам порог.

## Жизненный цикл токенов

- Фоновая корутина: для аккаунтов с refresh_token обновляет access ~за 5 мин до
  `expires_at` через OAuth token endpoint (client_id Claude Code, grant_type=refresh_token).
- Успех → сохраняем новый access+refresh+expires (шифровано). Провал → health=`refresh_failed`,
  видно в UI/логах, аккаунт исключается из выбора.
- `oauth_static` (без refresh) — работает до истечения, потом `dead`.
- `api_key` — статичен, без окна, без refresh.

## OAuth-add флоу (PKCE, «Login with Claude»)

1. UI (право `accounts.manage`) жмёт «Add via Claude login».
2. Backend генерит PKCE verifier/challenge + state, кладёт в `oauth_add_sessions`,
   возвращает authorize URL (claude.ai/console OAuth authorize, client_id Claude Code).
3. Юзер проходит авторизацию, получает code (redirect/paste), вставляет в UI.
4. Backend обменивает code+verifier на access+refresh+expires, создаёт account (шифровано).
   **Endpoints/client_id/redirect подтверждаются на живом флоу (шаг калибровки).**

## Датаплейн прокси

- Ktor route: catch-all под путями Anthropic (`/v1/messages` и т.д.).
- Inbound-auth: bearer из запроса матчится с `proxy_tokens` (hash), проверяется право `proxy.use`.
- Подмена авторизации на выбранный аккаунт: OAuth → `Authorization: Bearer` + бета-хедеры;
  api_key → `x-api-key`. Прокидываем `anthropic-version`, чистим клиентские креды.
- Стриминг SSE прозрачно (Ktor HttpClient → respondBytesWriter, без буферизации).
- После ответа: парсим rate-limit хедеры → обновляем `account_limits`; из тела/`usage`
  события пишем `usage_events`.

## RBAC и auth сайта

- Логин по username/password → сессия (cookie) или JWT для UI.
- Права проверяются на каждом API-роуте и на прокси-пути.
- Дефолтные роли: `admin` (все права), `manager` (accounts.manage+view+stats),
  `viewer` (accounts.view+stats.view), `user` (proxy.use). Роли редактируемы.
- Bootstrap: первый admin из env (`ADMIN_USER`/`ADMIN_PASSWORD`) при пустой БД.

## Admin API + React UI

REST (`/api/...`): auth (login/logout/me), users CRUD + роли, accounts CRUD
(+ threshold/priority/coefficient/enabled), account add (key / oauth-start / oauth-complete),
proxy-tokens CRUD, stats (пул, per-account limits, usage-графики).

React SPA страницы: Login; Dashboard (таблица аккаунтов: % лимита, активный,
reset_at, health, coefficient, суммарная ёмкость); Accounts (CRUD, add-by-key,
add-via-oauth); Users & Roles; Proxy Tokens; Stats (графики usage). Гейтинг по правам.

## Конфиг и деплой

Env/`.env`: `BIND_HOST`, `PORT`, `PUBLIC_DOMAIN`, `DB_PATH`, `MASTER_KEY`,
`ADMIN_USER`, `ADMIN_PASSWORD`, `UPSTREAM_BASE_URL` (дефолт api.anthropic.com),
`SESSION_SECRET`. Запуск: fat-jar + собранная статика SPA. Локально и на сервере
одинаково; на сервере указывается `PUBLIC_DOMAIN` и bind 0.0.0.0.

## Фазы реализации

1. **Скелет + persistence** — Gradle/Ktor проект, Exposed схема, конфиг, миграции, bootstrap admin.
2. **proxy-core** — датаплейн: inbound-auth по proxy_tokens, форвард+стриминг, подмена кредов.
3. **accounts** — выбор (priority/threshold/fallback/coefficient), парсинг лимитов, refresh-луп.
4. **auth + RBAC** — логин, роли/права, guard'ы.
5. **admin-api** — REST CRUD + stats + oauth-add.
6. **React UI** — страницы, гейтинг по правам, дашборд/статистика.
7. **Калибровка live** — реальные хедеры rate-limit + OAuth endpoints/client_id, донастройка.
8. **Упаковка** — fat-jar + SPA-статика, README, запуск, smoke-тест.

## Открытые пункты для live-калибровки

- Точный формат `anthropic-ratelimit-unified-*` (5h/7d, remaining/limit/status).
- OAuth authorize/token endpoints, client_id Claude Code, redirect/PKCE детали.
- Требуемые бета-хедеры для OAuth-запросов к `/v1/messages`.
