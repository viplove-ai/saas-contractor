#!/usr/bin/env bash
# Bootstraps the local PostgreSQL 16 used for development on machines where Docker is not
# available (macOS 12 cannot run Docker Desktop, Colima's vz backend or a QEMU build).
#
# Idempotent: safe to run repeatedly. Creates the role and database the backend expects,
# then leaves the server running.
#
#   ./scripts/dev-db.sh          bootstrap and verify
#   ./scripts/dev-db.sh reset    DROP the database and recreate it empty (Flyway re-applies)
#   ./scripts/dev-db.sh psql     open a shell on the dev database
set -euo pipefail

DB_NAME="${DB_NAME:-nirman}"
DB_USER="${DB_USER:-nirman}"
DB_PASSWORD="${DB_PASSWORD:-nirman_dev_password}"
DB_PORT="${DB_PORT:-5432}"

# postgresql@16 is keg-only, so its binaries are not on PATH by default.
PG_PREFIX="$(brew --prefix postgresql@16 2>/dev/null || echo /usr/local/opt/postgresql@16)"
PG_BIN="$PG_PREFIX/bin"

if [ ! -x "$PG_BIN/psql" ]; then
  echo "postgresql@16 is not installed. Run:  brew install postgresql@16" >&2
  exit 1
fi

# Connect as the OS user, which brew's initdb makes a superuser of the local cluster.
export PGPORT="$DB_PORT"
SU_PSQL=("$PG_BIN/psql" -v ON_ERROR_STOP=1 -d postgres)

start_server() {
  if "$PG_BIN/pg_isready" -q -p "$DB_PORT" 2>/dev/null; then
    echo "  postgres already running on port $DB_PORT"
    return
  fi
  echo "  starting postgresql@16 ..."
  brew services start postgresql@16 >/dev/null
  for _ in $(seq 1 30); do
    "$PG_BIN/pg_isready" -q -p "$DB_PORT" 2>/dev/null && break
    sleep 1
  done
  "$PG_BIN/pg_isready" -q -p "$DB_PORT" || { echo "postgres did not come up" >&2; exit 1; }
  echo "  started"
}

ensure_role() {
  if "${SU_PSQL[@]}" -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
    echo "  role '$DB_USER' exists"
  else
    "${SU_PSQL[@]}" -c "CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASSWORD' CREATEDB" >/dev/null
    echo "  role '$DB_USER' created"
  fi
}

ensure_db() {
  if "${SU_PSQL[@]}" -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
    echo "  database '$DB_NAME' exists"
  else
    "${SU_PSQL[@]}" -c "CREATE DATABASE $DB_NAME OWNER $DB_USER" >/dev/null
    echo "  database '$DB_NAME' created"
  fi
  # V1 creates the pgcrypto extension, which needs privileges the app role may not hold.
  "${SU_PSQL[@]}" -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto" >/dev/null 2>&1 \
    || "$PG_BIN/psql" -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto" >/dev/null
}

verify() {
  echo ""
  echo "verifying:"
  "$PG_BIN/psql" --version | sed 's/^/  /'
  PGPASSWORD="$DB_PASSWORD" "$PG_BIN/psql" -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -tAc "SELECT 'connected as ' || current_user || ' to ' || current_database()" | sed 's/^/  /'
  local tables
  tables=$(PGPASSWORD="$DB_PASSWORD" "$PG_BIN/psql" -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")
  echo "  tables: $tables  (0 until the backend starts and Flyway runs; 68 after the baseline + flyway_schema_history)"
}

case "${1:-setup}" in
  setup)
    echo "bootstrapping local dev database:"
    start_server; ensure_role; ensure_db; verify
    echo ""
    echo "next:  cd backend && JWT_SECRET=\$(openssl rand -base64 48) ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev"
    ;;
  reset)
    start_server
    echo "  dropping '$DB_NAME' ..."
    "${SU_PSQL[@]}" -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)" >/dev/null
    ensure_db; verify
    ;;
  psql)
    exec env PGPASSWORD="$DB_PASSWORD" "$PG_BIN/psql" -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
    ;;
  *)
    echo "usage: $0 [setup|reset|psql]" >&2; exit 2
    ;;
esac
