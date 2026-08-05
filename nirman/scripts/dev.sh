#!/usr/bin/env bash
# Start, stop and inspect the local Nirman stack.
#
#   ./scripts/dev.sh start            postgres + backend + frontend
#   ./scripts/dev.sh start backend    just one of: db | backend | frontend
#   ./scripts/dev.sh stop [what]
#   ./scripts/dev.sh restart [what]   after a Java change; the frontend hot-reloads itself
#   ./scripts/dev.sh status
#   ./scripts/dev.sh logs backend     follow a log (Ctrl-C to detach)
#   ./scripts/dev.sh reset-db         drop the schema, replay the migration, restart backend
#
# Processes are found by the port they listen on, not by a stored pid: `mvn spring-boot:run`
# forks a separate JVM, so killing the wrapper would leave the real server holding 8080.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT/.dev"
LOG_DIR="$RUN_DIR/logs"
mkdir -p "$LOG_DIR"

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
DB_PORT="${DB_PORT:-5432}"

PG_PREFIX="$(brew --prefix postgresql@16 2>/dev/null || echo /usr/local/opt/postgresql@16)"

# nvm keeps node outside the default PATH; pick the newest installed version.
NODE_BIN="$(ls -d "$HOME/.nvm/versions/node"/*/bin 2>/dev/null | sort -V | tail -1)"
[ -n "$NODE_BIN" ] && export PATH="$NODE_BIN:$PATH"
command -v java >/dev/null 2>&1 || true
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"

# A stable dev signing key: regenerating it on every restart would invalidate the token in
# your browser and make you log in again after each backend change.
SECRET_FILE="$RUN_DIR/jwt_secret"
if [ ! -f "$SECRET_FILE" ]; then
  openssl rand -base64 48 > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
fi

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
dim()   { printf "  %s\n" "$1"; }

port_pid() { lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null | head -1; }

wait_for_port() {          # port, seconds
  for _ in $(seq 1 "${2:-60}"); do
    [ -n "$(port_pid "$1")" ] && return 0
    sleep 1
  done
  return 1
}

kill_port() {              # port, label
  local pid; pid="$(port_pid "$1")"
  if [ -z "$pid" ]; then dim "$2 not running"; return; fi
  kill "$pid" 2>/dev/null
  for _ in $(seq 1 15); do
    [ -z "$(port_pid "$1")" ] && { green "  stopped $2"; return; }
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null && green "  force-stopped $2"
}

# ------------------------------------------------------------------ db
start_db() {
  if "$PG_PREFIX/bin/pg_isready" -q -p "$DB_PORT" 2>/dev/null; then
    dim "postgres already running on $DB_PORT"
  else
    brew services start postgresql@16 >/dev/null 2>&1
    "$PG_PREFIX/bin/pg_isready" -q -p "$DB_PORT" 2>/dev/null || sleep 3
    green "  started postgres"
  fi
}
stop_db() { brew services stop postgresql@16 >/dev/null 2>&1 && green "  stopped postgres"; }

# ------------------------------------------------------------------ backend
start_backend() {
  if [ -n "$(port_pid "$BACKEND_PORT")" ]; then dim "backend already on $BACKEND_PORT"; return; fi
  ( cd "$ROOT/backend" && \
    nohup env JWT_SECRET="$(cat "$SECRET_FILE")" \
      ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=dev \
      > "$LOG_DIR/backend.log" 2>&1 & )
  printf "  starting backend"
  for _ in $(seq 1 90); do
    grep -q "Started NirmanApplication" "$LOG_DIR/backend.log" 2>/dev/null && break
    grep -qE "Application run failed|BUILD FAILURE" "$LOG_DIR/backend.log" 2>/dev/null && break
    printf "."; sleep 1
  done
  echo ""
  if grep -q "Started NirmanApplication" "$LOG_DIR/backend.log" 2>/dev/null; then
    green "  backend up on $BACKEND_PORT"
  else
    red   "  backend failed — see ./scripts/dev.sh logs backend"
    tail -15 "$LOG_DIR/backend.log" | sed 's/^/      /'
  fi
}
stop_backend() { kill_port "$BACKEND_PORT" "backend"; }

# ------------------------------------------------------------------ frontend
start_frontend() {
  if [ -n "$(port_pid "$FRONTEND_PORT")" ]; then dim "frontend already on $FRONTEND_PORT"; return; fi
  if [ ! -d "$ROOT/frontend/node_modules" ]; then
    dim "installing frontend dependencies (first run)"
    ( cd "$ROOT/frontend" && npm install --no-audit --no-fund > "$LOG_DIR/npm-install.log" 2>&1 )
  fi
  ( cd "$ROOT/frontend" && nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 & )
  printf "  starting frontend"
  wait_for_port "$FRONTEND_PORT" 60 && echo "" && green "  frontend up on $FRONTEND_PORT" \
    || { echo ""; red "  frontend failed — see ./scripts/dev.sh logs frontend"; }
}
stop_frontend() { kill_port "$FRONTEND_PORT" "frontend"; }

# ------------------------------------------------------------------ status
status() {
  echo ""
  echo "Nirman dev stack"
  echo "--------------------------------------------------------------"
  local p
  p="$(port_pid "$DB_PORT")";       [ -n "$p" ] && green "  UP    postgres  :$DB_PORT       pid $p" || red "  DOWN  postgres  :$DB_PORT"
  p="$(port_pid "$BACKEND_PORT")";  [ -n "$p" ] && green "  UP    backend   :$BACKEND_PORT       pid $p" || red "  DOWN  backend   :$BACKEND_PORT"
  p="$(port_pid "$FRONTEND_PORT")"; [ -n "$p" ] && green "  UP    frontend  :$FRONTEND_PORT       pid $p" || red "  DOWN  frontend  :$FRONTEND_PORT"
  echo ""
  if [ -n "$(port_pid "$BACKEND_PORT")" ]; then
    dim "health:  $(curl -s --max-time 3 http://localhost:$BACKEND_PORT/actuator/health || echo unreachable)"
  fi
  if [ -n "$(port_pid "$DB_PORT")" ]; then
    local t
    t=$(PGPASSWORD=nirman_dev_password "$PG_PREFIX/bin/psql" -h localhost -U nirman -d nirman -tAc \
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null)
    dim "tables:  ${t:-unreachable}"
  fi
  echo ""
  dim "frontend  http://localhost:$FRONTEND_PORT"
  dim "swagger   http://localhost:$BACKEND_PORT/swagger-ui.html"
  echo ""
}

# ------------------------------------------------------------------ dispatch
WHAT="${2:-all}"
case "${1:-status}" in
  start)
    case "$WHAT" in
      all)      start_db; start_backend; start_frontend; status ;;
      db)       start_db ;;
      backend)  start_backend ;;
      frontend) start_frontend ;;
      *) red "unknown: $WHAT"; exit 2 ;;
    esac ;;
  stop)
    case "$WHAT" in
      all)      stop_frontend; stop_backend; stop_db ;;
      db)       stop_db ;;
      backend)  stop_backend ;;
      frontend) stop_frontend ;;
      *) red "unknown: $WHAT"; exit 2 ;;
    esac ;;
  restart)
    case "$WHAT" in
      all)      stop_frontend; stop_backend; start_backend; start_frontend; status ;;
      db)       stop_db; start_db ;;
      backend)  stop_backend; start_backend ;;
      frontend) stop_frontend; start_frontend ;;
      *) red "unknown: $WHAT"; exit 2 ;;
    esac ;;
  reset-db)
    stop_backend
    "$ROOT/scripts/dev-db.sh" reset
    # Migration files are copied into target/classes; a rename leaves the old ones behind
    # and Flyway then sees two migrations with the same version.
    ( cd "$ROOT/backend" && ./mvnw -B -q clean )
    start_backend ;;
  logs)
    case "$WHAT" in
      backend|frontend) tail -f "$LOG_DIR/$WHAT.log" ;;
      *) red "usage: $0 logs [backend|frontend]"; exit 2 ;;
    esac ;;
  status) status ;;
  *)
    echo "usage: $0 {start|stop|restart} [all|db|backend|frontend]"
    echo "       $0 {status|reset-db}"
    echo "       $0 logs {backend|frontend}"
    exit 2 ;;
esac
