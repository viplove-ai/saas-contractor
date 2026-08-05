# 07 — Repository and package structure

```
nirman/
├─ docker-compose.yml          Postgres, MinIO, backend, frontend / nginx
├─ .env.example                every configurable value, no secrets committed
├─ README.md
├─ docs/                       00 assumptions … 08 roadmap
├─ .github/workflows/         ci.yml (test on PR), deploy.yml (Fly.io on main)
├─ backend/
│  ├─ pom.xml
│  ├─ mvnw, mvnw.cmd           Apache Maven Wrapper 3.3.2, script-only (no jar vendored)
│  ├─ .mvn/wrapper/            maven-wrapper.properties: pinned Maven + SHA-256
│  ├─ Dockerfile               multi-stage, non-root runtime
│  └─ src/
│     ├─ main/java/in/nirman/
│     │  ├─ NirmanApplication.java
│     │  ├─ common/            BaseEntity, ApiError, GlobalExceptionHandler,
│     │  │                     BusinessException, PageResponse, CorrelationIdFilter,
│     │  │                     PeriodLockGuard
│     │  ├─ config/            OpenApiConfig, JpaAuditingConfig
│     │  ├─ security/          SecurityConfig, CurrentUserProvider, SiteAccessGuard
│     │  └─ modules/           identity, project, masterdata, labour, inventory,
│     │                        expense, dpr, approval, attachment, audit,
│     │                        reporting, dashboard, imports
│     ├─ main/resources/
│     │  ├─ application.yml    plus application-dev.yml
│     │  ├─ db/migration/      V1 baseline (67 tables), V2 permissions and system
│     │  │                     roles, V3 document-number counters
│     │  └─ db/seed/           V900__dev_seed.sql — dev profile only, never under prod
│     └─ test/java/…           AbstractIntegrationTest (Testcontainers, or local
│                              PostgreSQL where no Docker daemon exists), plus one
│                              test class per exit criterion
└─ frontend/
   ├─ package.json, package-lock.json   lockfile is required: the prod image runs `npm ci`
   ├─ vite.config.ts, tsconfig.json, Dockerfile
   ├─ fly.toml               Fly app config; backend/fly.toml is the API's
   ├─ nginx.default.conf.template   SPA fallback, security headers, /api/ proxy.
   │                         Baked into the prod image; ${API_UPSTREAM} and
   │                         ${DNS_RESOLVER} are substituted at container start,
   │                         which is what lets compose and Fly share one file.
   ├─ .eslintrc.cjs          eslintrc format, matches the pinned eslint 8
   ├─ playwright.config.ts   e2e runs against a production build, not the dev server
   ├─ e2e/                   Playwright specs (Vitest is scoped to src/, so they never collide)
   ├─ public/                favicon.svg, icon-192/512, maskable, apple-touch-icon
   └─ src/
      ├─ app/                  App, router, RootLayout, theme (design tokens)
      ├─ features/<module>/    api hooks, zod schemas, components, routes
      ├─ shared/               apiClient, StatusChip, OfflineBanner, formatters
      ├─ offline/              Dexie schema, sync queue, image compression
      └─ test/                 vitest setup
```

## Inside a backend module
Every module folder follows the same five-part layout, so any developer opening an unfamiliar module already knows where things are.

```
modules/labour/
├─ api/          controllers, request and response DTOs
├─ domain/       JPA entities and enums
├─ repository/   Spring Data interfaces
├─ service/      business logic, @Transactional, @PreAuthorize
└─ mapper/       MapStruct entity ↔ DTO
```

Not every module carries all five folders. `audit` is a service and an interface with no
entity, because `audit_logs` is append-only, `bigserial`-keyed and `jsonb`-typed — nothing
should ever load or mutate those rows through the ORM, so it writes by plain JDBC and no
mapping layer exists to misuse. `masterdata` has one service across eight aggregates,
because they share exactly one behaviour (org-scoped, code-unique reference rows) and none
has business logic yet; the first one that grows real rules moves out on its own.

Rules that make the modularity real rather than decorative:
- A controller in module A never imports a repository or entity from module B.
- Cross-module reads go through the other module's public service interface.
- Entities never appear in a controller signature.
- Anything shared by three or more modules moves to `common`, and nothing else does.

The cross-cutting security code — `SiteAccessGuard`, `PeriodLockGuard` — lives under
`security` and `common` and queries by JDBC rather than through the owning module's
repositories. It is called by nearly every module, so routing it through them would create
exactly the dependency cycles the boundaries above exist to prevent.

## Inside a frontend feature
```
features/attendance/
├─ api.ts          TanStack Query hooks, one per endpoint
├─ schema.ts       Zod schemas, reused by the form and the offline draft
├─ routes.tsx      route objects contributed to the router
└─ components/     screens and pieces used only by this feature
```
