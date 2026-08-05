#!/usr/bin/env bash
# Checks every prerequisite for running Nirman without Docker and reports what is missing.
# Never changes anything. Exit code 0 = ready to develop.
#
#   ./scripts/dev-doctor.sh

PASS=0; FAIL=0
ok()   { printf "  \033[32mOK\033[0m    %-22s %s\n" "$1" "$2"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31mMISS\033[0m  %-22s %s\n" "$1" "$2"; FAIL=$((FAIL+1)); }
note() { printf "  ----  %-22s %s\n" "$1" "$2"; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_PREFIX="$(brew --prefix postgresql@16 2>/dev/null || echo /usr/local/opt/postgresql@16)"
PG_BIN="$PG_PREFIX/bin"

echo ""
echo "Nirman local development check"
echo "------------------------------------------------------------------"

# --- Java 21: the backend targets 21 and will not compile on anything older.
if command -v java >/dev/null 2>&1; then
  V="$(java -version 2>&1 | head -1)"
  MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  if [ "${MAJOR:-0}" -ge 21 ] 2>/dev/null; then ok "java" "$V"; else bad "java" "need 21+, found: $V"; fi
else
  bad "java" "not installed -> brew install --cask temurin@21"
fi

# --- Maven comes from the wrapper; no system install needed.
if [ -x "$ROOT/backend/mvnw" ]; then ok "maven wrapper" "backend/mvnw (downloads Maven 3.9.9 on first use)"
else bad "maven wrapper" "backend/mvnw missing"; fi

# --- Node for the frontend.
if command -v node >/dev/null 2>&1; then
  MAJOR="$(node --version | sed -E 's/v([0-9]+).*/\1/')"
  if [ "${MAJOR:-0}" -ge 20 ] 2>/dev/null; then ok "node" "$(node --version)"; else bad "node" "need 20+, found $(node --version)"; fi
else bad "node" "not installed -> brew install node"; fi
if command -v npm >/dev/null 2>&1; then ok "npm" "$(npm --version)"; else bad "npm" "not installed -> brew install node"; fi

# --- The prod Docker stage runs `npm ci`, which requires a committed lockfile.
if [ -f "$ROOT/frontend/package-lock.json" ]; then ok "lockfile" "frontend/package-lock.json"
else bad "lockfile" "run: cd frontend && npm install"; fi

# --- PostgreSQL 16.
if [ -x "$PG_BIN/psql" ]; then
  ok "postgresql@16" "$("$PG_BIN/psql" --version)"
  if "$PG_BIN/pg_isready" -q -p "${DB_PORT:-5432}" 2>/dev/null; then
    ok "postgres running" "port ${DB_PORT:-5432}"
    DBS=$("$PG_BIN/psql" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME:-nirman}'" 2>/dev/null)
    if [ "$DBS" = "1" ]; then
      TABLES=$("$PG_BIN/psql" -d "${DB_NAME:-nirman}" -tAc \
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null)
      ok "database" "${DB_NAME:-nirman} exists, $TABLES tables"
      [ "${TABLES:-0}" = "0" ] && note "" "0 tables is expected until the backend runs Flyway"
    else
      bad "database" "'${DB_NAME:-nirman}' missing -> ./scripts/dev-db.sh"
    fi
  else
    bad "postgres running" "not accepting connections -> ./scripts/dev-db.sh"
  fi
else
  bad "postgresql@16" "not installed -> brew install postgresql@16"
fi

# --- Docker is genuinely unavailable on macOS 12; say so rather than let it be discovered.
echo ""
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ok "docker" "available - docker compose up also works"
else
  note "docker" "unavailable: expected on macOS 12 (Docker Desktop needs Sonoma,"
  note "" "Colima's vz backend needs macOS 13, QEMU needs Clang 15)."
  note "" "Use this native setup. ./mvnw test also needs Docker (Testcontainers)."
fi

echo "------------------------------------------------------------------"
printf "  %d ready, %d missing\n\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
