# Nirman — Construction Site Management System

Multi-project, multi-site operations for a CPWD contractor: labour attendance and overtime, site inventory on a real stock ledger, expenses with approvals and payments, daily progress reports, and management dashboards. Replaces the spreadsheets, and connects them — every transaction carries a site and, where it matters, a BOQ item, so labour, material and cash roll up against the same work.

Works in a phone browser, installs as a PWA, and keeps accepting entries when the site has no signal.

**Status: Phase 1 complete** — architecture, database schema, API design and scaffolding. Business modules land phase by phase (see `docs/08-roadmap.md`).

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

```bash
cp .env.example .env
# generate a real signing key
openssl rand -base64 48   # paste into JWT_SECRET in .env

docker compose up -d postgres minio minio-init
docker compose up -d backend
docker compose --profile dev up -d frontend
```

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| MinIO console | http://localhost:9001 |
| Health | http://localhost:8080/actuator/health |

### Without Docker

```bash
# backend
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# frontend
cd frontend && npm install && npm run dev
```

### Tests

```bash
cd backend  && ./mvnw test        # JUnit 5 + Testcontainers, real PostgreSQL
cd frontend && npm run test       # Vitest + React Testing Library
cd frontend && npm run e2e        # Playwright
```

### Database

```bash
docker compose exec postgres psql -U nirman -d nirman -c '\dt'   # 61 tables after V1–V5
docker compose down -v                                        # wipe and start clean
```

---

## Development logins

Seed data arrives with Phase 2. Credentials will be listed here **for local development only** and the seed migration never loads under the `prod` profile. Change every password in `.env` before deploying anywhere.

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

**History does not move.** A wage revision cannot change last month's labour cost — attendance freezes the rate it was verified against. Approved financial records are voided with a reason, never deleted.

**The backend decides who sees what.** Site scoping is enforced in the service layer on every read and write. The frontend hides what a role cannot use, but hiding is not security.

**Offline entry is safe.** The device generates the record id, so the same draft sent three times over a flaky connection creates one row.

---

## Contributing

Additive migrations only — an applied migration is never edited. Business logic lives in services, not controllers. JPA entities never cross the controller boundary. New permissions are seeded rows, not constants scattered through the code.
