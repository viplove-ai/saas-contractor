# Nirman — Construction Site Management System

Multi-project, multi-site operations for a CPWD contractor: labour attendance and overtime, site inventory on a real stock ledger, expenses with approvals and payments, daily progress reports, and management dashboards. Replaces the spreadsheets, and connects them — every transaction carries a site and, where it matters, a BOQ item, so labour, material and cash roll up against the same work.

Works in a phone browser, installs as a PWA, and keeps accepting entries when the site has no signal.

**Status: Phase 7 complete — all seven phases shipped.** Architecture and schema (Phase 1); a foundation of sign-in with refresh-token rotation, the role and permission matrix, service-layer site scoping, projects, sites, stores, master data, audit trail and attachments (Phase 2); labour — workers, wage history, the muster roll, attendance with hour and overtime calculation, engineer verification, the append-only wage ledger, advances and settlement, the period lock, two reports with Excel export, and a phone-first mark-attendance screen (Phase 3); inventory — an append-only stock ledger with weighted-average valuation, goods receipts, issues, the three-step transfer lifecycle, wastage, physical counts, BOQ items, material estimates with scope-honest variance, five reports and three phone-first screens (Phase 4); expenses and cash — one generic approval engine, duplicate detection that returns its candidates, partial payments, vendor balances, site floats with office-approved settlement, and three financial reports (Phase 5); and daily progress reports and dashboards — a DPR whose figures are derived live from the other three modules rather than retyped, engineer verification that posts measured work to the measurement book, a printed report rendered from the frozen snapshot, and company, site and data-quality dashboards that report material as three separate figures which add up (Phase 6); and offline and hardening — an IndexedDB queue that survives a phone being killed mid-sync, on-device image compression, a conflict prompt with the two answers a supervisor can actually give, a per-source ceiling on the sign-in endpoint, and twelve indexes each justified against a named query (Phase 7). See `docs/08-roadmap.md`.

---

## Stack

| Layer | Choice |
|---|---|
| Frontend | React 18, TypeScript, Vite, MUI, TanStack Query, React Hook Form + Zod, Recharts, Dexie (IndexedDB), vite-plugin-pwa |
| Backend | Java 21, Spring Boot 3.3, Spring Security + JWT, Spring Data JPA, MapStruct, Flyway, springdoc-openapi, Maven |
| Data | PostgreSQL 16, MinIO (S3-compatible) for bills, challans and site photos |
| Infra | Docker Compose, Nginx, environment-based config |

Modular monolith. No microservices, no Kubernetes.

---

## Run it locally

Two ways. **Native is the default** — it works everywhere, and it is the only option on
macOS 12 or any machine without a Docker daemon (see the note at the end of this section).

### 1. Install the toolchain

```bash
brew install --cask temurin@21
```

```bash
brew install postgresql@16 node
```

Maven is not in that list on purpose: `backend/mvnw` downloads a checksum-pinned Maven 3.9.9
on first use, so a JDK is the only Java prerequisite.

### 2. Verify the install

```bash
./scripts/dev-doctor.sh
```

Reports every prerequisite as OK or MISS with the command that fixes it, and changes nothing.
Exit code 0 means you are ready.

### 3. Create the development database

```bash
./scripts/dev-db.sh
```

Idempotent: starts postgres, creates the `nirman` role and database, enables `pgcrypto`, then
verifies the connection. `./scripts/dev-db.sh reset` drops and recreates it so Flyway replays
from the baseline; `./scripts/dev-db.sh psql` opens a shell on it.

### 4. Run the backend

```bash
cd backend && JWT_SECRET=$(openssl rand -base64 48) ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway applies the baseline schema, the permission catalogue, the document-number counters
and — under the `dev` profile only — the sample data. Expect 69 tables afterwards, 50
permissions, 4 roles, the 3 logins listed below and 6 workers across the two Kausani sites.

### 5. Run the frontend

```bash
cd frontend && npm install && npm run dev
```

### 6. Check it is up

```bash
curl -s http://localhost:8080/actuator/health
```

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

MinIO is only needed if you are uploading bills, challans or photos. The storage client
connects lazily, so everything else runs without it and only `/attachments` returns 503.
Add it with `brew install minio` and `brew services start minio`.

### With Docker, on a machine that supports it

```bash
cp .env.example .env && openssl rand -base64 48   # paste into JWT_SECRET
```

```bash
docker compose up -d postgres minio minio-init && docker compose up -d backend && docker compose --profile dev up -d frontend
```

> **macOS 12 (Monterey) cannot run this stack in Docker.** Docker Desktop requires Sonoma,
> Colima's `vz` backend requires macOS 13, and building QEMU requires Clang 15 while Xcode 14
> ships Clang 14. Use the native path above. `./mvnw test` still works there — it falls back
> to the local PostgreSQL when no Docker daemon is present (see Tests below).

### Day-to-day: start, stop, reload

Once set up, `dev.sh` runs the whole stack. It finds processes by the port they listen on
rather than a stored pid, because `mvn spring-boot:run` forks a separate JVM — killing the
wrapper alone would leave the real server holding 8080.

```bash
./scripts/dev.sh start
```

```bash
./scripts/dev.sh status
```

```bash
./scripts/dev.sh stop
```

Each accepts a target: `db`, `backend`, `frontend`, or `all` (the default).

**Picking up changes:**

| You changed | What to do |
|---|---|
| React / TS under `frontend/src` | nothing — Vite hot-reloads |
| Java under `backend/src` | `./scripts/dev.sh restart backend` |
| `application*.yml` | `./scripts/dev.sh restart backend` |
| The migration, before it is applied anywhere | `./scripts/dev.sh reset-db` |
| The migration, after it is applied | never edit it — add `V2__…` and restart the backend |

`reset-db` drops the schema, runs `mvnw clean` (stale migration copies in `target/classes`
otherwise collide with the renamed ones) and restarts the backend so Flyway replays.

```bash
./scripts/dev.sh logs backend
```

Follows a log; `frontend` works too. The dev JWT signing key is generated once into `.dev/`
and reused, so a backend restart does not invalidate the token in your browser.

### Tests

```bash
cd backend && ./mvnw test
```

JUnit 5 against a real PostgreSQL, never an in-memory stand-in — the schema leans on partial
unique indexes, generated columns and `jsonb`, none of which H2 models faithfully. It finds a
database one of two ways and needs no configuration either way:

| Docker daemon | What happens |
|---|---|
| present | one Testcontainers PostgreSQL for the whole suite |
| absent (macOS 12) | the local `postgresql@16` from `dev-db.sh`, in a separate `nirman_test` database whose schema is dropped and recreated first |

The dev database is never touched by the suite. Flyway applies the migrations **and the dev
seed**, so a broken migration or a broken seed fails the build rather than a deployment.

```bash
cd frontend && npm run test
```

```bash
cd frontend && npm run e2e
```

`npm run e2e` builds the app first and runs Playwright against the production output, because
the PWA manifest, service worker and icons only exist after `vite build`.

> **Playwright does not run on macOS 12** — `npx playwright install` refuses with
> `Playwright does not support chromium on mac12`, for every browser it ships. Driving the
> Chrome installed on the machine instead (`PLAYWRIGHT_CHANNEL=chrome npm run e2e`) gets one
> step further and then fails in the same place, because current Chrome builds do not load on
> macOS 12 either. The e2e suite therefore runs in CI and on macOS 13+ only; the channel
> override is there because it will work on any machine whose Chrome does. Vitest, the backend
> suite and everything else are unaffected.
>
> This matters more from Phase 7 than it did before: the offline sync journeys — mark
> attendance and add an expense with no signal, then reconnect — are e2e tests and nowhere
> else, which is why CI gained a dedicated `e2e` job in that phase.

### Database

```bash
./scripts/dev-db.sh psql
```

Then `\dt` to list tables — expect **69** (67 from the baseline, `document_number_counters`
from V3, and `flyway_schema_history`). To wipe and let
Flyway replay the baseline:

```bash
./scripts/dev-db.sh reset
```

---

## Development logins

Every seeded login uses the password **`Nirman@123`**. These exist **for local development
only**: they live in `db/seed/V900__dev_seed.sql`, which is loaded by the `dev` profile's
Flyway locations and by nothing else — the `prod` profile never sees the folder.

One login per person, not one per role — in a firm this size the same people wear several
hats, and `user_roles` is many-to-many so that is expressible. It also keeps
`audit_logs.username` meaningful: every row traces to one human being.

| Username | Name | Roles | Posted to | Sees |
|---|---|---|---|---|
| `viplove` | Viplove Chaudhary | Admin, Accountant, Supervisor | KSN-B | everything |
| `uttam` | Uttam Rana | Admin, Engineer, Accountant | KSN-A, KSN-B | everything |
| `vivek` | Vivek Aggarwal | Supervisor | KSN-A | **KSN-A only** |

Vivek is the one login whose token is genuinely site-scoped, because he is the only person
here who does not also hold Admin — and that is the fastest way to see the scoping work by
hand. Sign in as `vivek`: KSN-B is absent from the site list and returns 403 if you ask for
it by id. Sign in as anyone else and both sites are there.

That asymmetry is deliberate, and it is the fixture the site-scope integration test asserts
against. Note that "posted to" is an operational fact — who answers for the site — and only
narrows access for someone whose roles are all site-scoped.

The seed mirrors the Kausani field data reviewed in `docs/09-design-decisions.md`: a 7-hour
standard shift rather than 8, quintal as a stocked unit, the two-level expense taxonomy with
its `is_labour_payment` and `is_material_purchase` flags, and the CPWD cement coefficients.

**Never reuse these anywhere real.** Passwords for a deployment come from `.env`.

---

## Design documents

Read `docs/00-assumptions.md` first — it records every decision made where the requirements were silent, and those are the ones worth arguing with early.

| Document | What's in it |
|---|---|
| [00 Assumptions](docs/00-assumptions.md) | Shift hours, wage rules, approval limits, tenancy, what's out of scope |
| [01 Architecture](docs/01-architecture.md) | Functional and technical architecture, module boundaries, visual direction |
| [02 ER model](docs/02-er-model.md) | Entity relationships and the rule that keeps the modules connected |
| [03 Database design](docs/03-database-design.md) | Table inventory and the five constraints carrying the business rules |
| [04 Permission matrix](docs/04-permission-matrix.md) | Every permission by role, and the four enforcement layers |
| [05 API specification](docs/05-api-spec.md) | Endpoints, conventions, error format, idempotency |
| [06 Frontend screens](docs/06-frontend-screens.md) | Routes and screens for both shells |
| [07 Repository structure](docs/07-repository-structure.md) | Folder and package layout, module internals |
| [08 Roadmap](docs/08-roadmap.md) | Phase plan with exit criteria |

---

## Rules this system holds to

**Stock is a ledger, not a number.** `stock_transactions` is append-only. Nobody types a balance. Closing stock is opening + receipts + transfers in + returns − issues − transfers out − wastage ± approved adjustments, and the balance table is a cache that the ledger overrules.

**Purchase is not consumption.** Buy ₹5,00,000 of cement and use ₹2,00,000 of it, and the dashboard shows ₹5,00,000 purchased, ₹2,00,000 consumed, ₹3,00,000 in inventory. Cost incurred counts consumption. Material cost is never double-counted.

**History does not move.** A wage revision cannot change last month's labour cost — attendance freezes the rate it was verified against. A daily report freezes its figures when it is sent, so a correction underneath shows up as a difference rather than by rewriting what an engineer signed. Approved financial records are voided with a reason, never deleted.

**A rolled-up figure is derived, not stored.** The DPR's prefill and every dashboard tile are computed from the underlying records on each call, through each module's own read API. A cached total would be a second version of the truth, and the version people notice is whichever one is wrong.

**Claiming work is its own act.** Entering a contract line, doing the work and claiming it against the contract are three different things. A quantity on a daily report reaches the measurement book only when the engineer verifies the report, and only once — verifying twice cannot bill the client twice.

**The backend decides who sees what.** Site scoping is enforced in the service layer on every read and write. The frontend hides what a role cannot use, but hiding is not security. The JWT's `sites` claim can only ever say *no*: a *yes* is re-checked against `user_site_assignments` on the way through, because access revoked at 10:00 must not survive in a token issued at 09:58.

**A rotated refresh token is single-use.** Presenting one twice is the signature of a stolen token, so it revokes the entire family and raises an audit event rather than quietly issuing another pair.

**Offline entry is safe.** The device generates the record id, so the same draft sent three times over a flaky connection creates one row. The queue does not rely on that: a record that has gone out is marked as gone out and is not sent again. Both halves are tested, because a guarantee that only holds on the server is one nobody can see holding.

**A conflict is a question, not a retry.** With no answer at all, the queue waits and tries again on its own. Everything else — a refusal, a duplicate, a locked month — is the server having an opinion, and an opinion is put in front of a person with the two answers they can act on: keep mine with a stated reason, or drop mine and leave what is there.

---

## Contributing

Additive migrations only — an applied migration is never edited. Business logic lives in services, not controllers. JPA entities never cross the controller boundary. New permissions are seeded rows, not constants scattered through the code.
