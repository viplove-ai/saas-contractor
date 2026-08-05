# Deployment

Production runs on **Fly.io** as two apps, with managed Postgres and S3-compatible
object storage from third parties. GitHub Actions builds and deploys on every push
to `main`.

```
browser ──https──► nirman-constructions (nginx + SPA)
                        │  /api/ proxied over Fly's private network (6PN)
                        ▼
                   nirman-constructions-api (Spring Boot)
                        ├──► Neon Postgres   (managed, TLS)
                        └──► Tigris          (S3 API, presigned URLs)
```

The browser only ever sees one origin, so CORS never comes into play in production.

## Why this stack

| Option | ~Monthly | Notes |
|---|---|---|
| **Fly.io + Neon + Tigris** | **$6–9** | Runs the existing Dockerfiles unchanged |
| Render / Railway | $14–25 | Similar ergonomics, higher price at equal specs |
| AWS ECS Fargate + RDS + ALB + S3 | $45–60 | The ALB alone is ~$18/mo even when idle |
| Azure Container Apps + Flexible Postgres | $30–40 | Cheaper than AWS, still several times Fly |

Cost breakdown for the Fly setup: API machine (shared-cpu-1x, 1 GB, always on)
~$5.70, web machine (256 MB, suspends when idle) ~$0–1, Neon free tier $0,
Tigris ~$0.02/GB stored. Neon's free tier is 0.5 GB — move to their $19 Launch
plan, or to Fly Postgres, when you outgrow it.

---

## One-time setup

### 1. Fly account and apps

```bash
brew install flyctl && flyctl auth login
```

```bash
flyctl apps create nirman-constructions-api && flyctl apps create nirman-constructions
```

If you pick different names, update `app =` in `backend/fly.toml` and
`frontend/fly.toml`, `API_UPSTREAM` in `frontend/fly.toml`, and the smoke-test
URLs in `.github/workflows/deploy.yml`.

### 2. Postgres (Neon)

Create a project at neon.tech in the same region as `primary_region` — ap-southeast-1
(Singapore), matching `sin`. Copy the connection string and rewrite it into JDBC form:
prefix `jdbc:`, and drop the `user:password@` part, since `DB_USER` and `DB_PASSWORD`
are separate secrets.

`postgresql://neondb_owner:npg_xxx@ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`
becomes `jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require`.

Take the direct endpoint, not the `-pooler` one: Hikari already holds a persistent pool,
and stacking it on PgBouncer in transaction mode breaks prepared statements.

Colocation is the point — a request makes several DB round trips and one trip to the
user, so if you move the database, move `primary_region` in both fly.toml files with it.

### 3. Object storage (Tigris)

```bash
flyctl storage create --app nirman-constructions-api --name nirman-files
```

This provisions a bucket and sets `AWS_*` env vars on the app. Copy the printed
access key and secret into the `STORAGE_*` secrets below — the backend reads those
names, not the `AWS_*` ones.

### 4. Backend secrets

```bash
flyctl secrets set --app nirman-constructions-api DATABASE_URL='jdbc:postgresql://ep-xxx.ap-south-1.aws.neon.tech/nirman?sslmode=require' DB_USER='neondb_owner' DB_PASSWORD='...' JWT_SECRET="$(openssl rand -base64 48)" STORAGE_ACCESS_KEY='tid_...' STORAGE_SECRET_KEY='tsec_...' STORAGE_BUCKET='nirman-files' CORS_ALLOWED_ORIGINS='https://nirman-constructions.fly.dev'
```

Never put these in `fly.toml` — that file is committed.

### 5. GitHub Actions token

```bash
flyctl tokens create deploy --name github-actions --expiry 8760h
```

Add the output as the repository secret `FLY_API_TOKEN` under
Settings → Secrets and variables → Actions.

Optionally create a `production` environment (Settings → Environments) with
required reviewers — both deploy jobs already reference it, so that turns every
production deploy into a one-click approval.

### 6. First deploy

Deploy from inside each app's directory, not from the repo root with `--config`: both
Dockerfiles copy `pom.xml` / `package.json` from the context root, so the build context
has to be that directory. This is the same form `.github/workflows/deploy.yml` uses.

```bash
cd backend && flyctl deploy --remote-only
```

```bash
cd frontend && flyctl deploy --remote-only
```

(If flyctl reports "the config for your app is missing an app name", it did not find a
fly.toml at all — check which directory you are in. It reads a missing `--config` path
as an empty config rather than reporting the missing file.)

After this, pushing to `main` does it automatically.

---

## The pipeline

**`.github/workflows/ci.yml`** — runs on every PR and push to `main`.
Backend: `./mvnw verify` (Testcontainers spins up real Postgres on the runner).
Frontend: lint, typecheck, Vitest, production build.

**`.github/workflows/deploy.yml`** — runs on push to `main`, or manually via
*Run workflow* with a `backend` / `frontend` / `both` target.

- A `changes` job diffs paths so an untouched app is not redeployed.
- Backend deploys first (Flyway migrations run on boot), then the frontend.
- Each deploy is followed by a health smoke test; a failure fails the job.
- `concurrency` serializes deploys and never cancels one in flight — a
  half-applied migration is worse than a queued job.

CI is not a hard gate on deploy (both trigger on the same push). If you want it
to be, make `main` a protected branch requiring the CI checks — merges then can't
land unless CI is green.

## Migrations

Flyway runs at application startup with `baseline-on-migrate: false` and
`ddl-auto: validate`, so a schema/entity mismatch fails the boot and the health
check, and Fly keeps the old machine serving. Because `min_machines_running = 1`,
a deploy is a brief in-place restart — expect a few seconds of 502s.

Write migrations to be backward-compatible (add columns nullable, drop them in a
later release) and you can go zero-downtime by scaling to two machines:

```bash
flyctl scale count 2 --app nirman-constructions-api
```

That roughly doubles the API cost.

## Rollback

```bash
flyctl releases --app nirman-constructions-api
```

```bash
flyctl deploy --app nirman-constructions-api --image registry.fly.io/nirman-constructions-api:deployment-XXXX
```

Rolling back the image does **not** roll back the database. Reverting a migration
is a manual, deliberate operation — write a forward migration instead wherever
possible.

## What the prod profile changes about security

These come from the Phase 7 review and are on by default under `SPRING_PROFILES_ACTIVE=prod`;
they are listed because each one is something that works differently from development and would
otherwise be found by surprise.

| Setting | Development | Production | Why |
|---|---|---|---|
| `app.api-docs.public` | `true` | `false` | Swagger and `/v3/api-docs` are a complete map of the API — every route, field and enum, and which are anonymous. Nobody on a production deployment needs that map who is not already holding the source. The paths do not 404; they fall through to `authenticated()`. |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | `health` | Liveness for Fly's checks, and nothing else. |
| `management.endpoint.health.show-details` | `when-authorized` | `never` | A health body naming which dependency is down is a map of the deployment. |

Two settings are the same in both and worth knowing about before somebody reports them as bugs:

- **`app.security.login-attempts-per-window` (60 per 5 minutes, per source address).** This is
  not the account lockout — that is `max-failed-logins`, and it stops somebody guessing at one
  password. This stops one password being tried against every username, which the lockout cannot
  see because each attempt is the first failure on a different account. It counts *attempts*, not
  failures, so a genuine burst can hit it: raise it if a site's whole crew shares one gateway
  address and signs in at seven every morning.
- **`app.security.refresh-attempts-per-window` (600).** Ten times larger on purpose. A refresh is
  an app rotating a token every fifteen minutes per open phone, not a human typing, and a whole
  site behind one 4G gateway shares a source address. Setting them equal would lock a company out
  over a morning with nothing wrong.

Both counters are **per instance**, held in memory. With N machines the real ceiling is N times
the limit. That is a deliberate trade — a shared counter means a round trip on the one endpoint
reachable without a token, which is the endpoint an attacker would then use to make round trips —
and it is fine at one or two machines. Revisit it before scaling `nirman-constructions-api` past that.

## Operations

```bash
flyctl logs --app nirman-constructions-api
```

```bash
flyctl ssh console --app nirman-constructions-api
```

```bash
flyctl status --app nirman-constructions-api
```

## Custom domain

```bash
flyctl certs add app.yourdomain.com --app nirman-constructions
```

Add the CNAME/A records it prints, then update `CORS_ALLOWED_ORIGINS` on
`nirman-constructions-api` to the new origin.

## Local production check

The same images run locally, since the nginx config is now a template driven by
`API_UPSTREAM` / `DNS_RESOLVER`:

```bash
docker compose --profile prod up --build
```
