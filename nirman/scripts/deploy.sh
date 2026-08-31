#!/usr/bin/env bash
# Deploy Nirman to Fly.io from your machine.
#
#   ./scripts/deploy.sh                 backend, then frontend
#   ./scripts/deploy.sh backend         just one of: backend | frontend
#   ./scripts/deploy.sh --dry-run       show what would run, deploy nothing
#   ./scripts/deploy.sh --no-verify     skip the dirty-tree / branch / build checks
#   ./scripts/deploy.sh --yes           don't ask for confirmation
#   ./scripts/deploy.sh status          machines + health of both apps
#   ./scripts/deploy.sh logs backend    follow a live log (Ctrl-C to detach)
#   ./scripts/deploy.sh rollback backend
#
# The normal path to production is `git push` — .github/workflows/deploy.yml does the
# same thing with a concurrency lock so two deploys can't overlap. Use this script for
# hotfixes, for shipping from a branch, or when Actions is unavailable. Because it has
# no lock, check with whoever else deploys before running it.
#
# Builds happen on Fly's remote builders (--remote-only), so no local Docker is needed.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

BACKEND_APP="shivadri-projects-api"
FRONTEND_APP="shivadri-projects"
BACKEND_URL="https://${BACKEND_APP}.fly.dev"
FRONTEND_URL="https://${FRONTEND_APP}.fly.dev"
HEALTH_PATH="/actuator/health"
DEPLOY_BRANCH="${DEPLOY_BRANCH:-main}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-600}"

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
bold()  { printf "\033[1m%s\033[0m\n" "$1"; }
dim()   { printf "  %s\n" "$1"; }
die()   { red "$1"; exit 1; }

DRY_RUN=0
VERIFY=1
ASSUME_YES=0

run() {                    # echo the command; run it unless --dry-run
  dim "\$ $*"
  [ "$DRY_RUN" = 1 ] && return 0
  "$@"
}

# ---------------------------------------------------------------- preflight

require_flyctl() {
  command -v flyctl >/dev/null 2>&1 || die "flyctl not found — brew install flyctl"
  flyctl auth whoami >/dev/null 2>&1 || die "not logged in to Fly — run: flyctl auth login"
}

check_git() {
  [ "$VERIFY" = 1 ] || return 0
  git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 || return 0

  local branch dirty
  branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
  dirty="$(git -C "$ROOT" status --porcelain)"

  if [ -n "$dirty" ]; then
    red "Working tree is dirty — you would be deploying code that isn't committed:"
    git -C "$ROOT" status --short | head -20
    confirm "Deploy anyway?" || exit 1
  fi

  if [ "$branch" != "$DEPLOY_BRANCH" ]; then
    red "On branch '$branch', not '$DEPLOY_BRANCH'."
    confirm "Deploy this branch to production?" || exit 1
  fi
}

# Catch a broken build here rather than after a five-minute remote build.
build_backend() {
  [ "$VERIFY" = 1 ] || return 0
  bold "Compiling backend locally (skip with --no-verify)"
  local mvn="$ROOT/backend/mvnw"
  [ -x "$mvn" ] || mvn="$(command -v mvn || true)"
  [ -n "$mvn" ] || { dim "no maven wrapper or mvn on PATH — skipping"; return 0; }
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
  ( cd "$ROOT/backend" && "$mvn" -q -DskipTests package ) \
    || die "backend build failed — fix it before deploying"
  green "  backend compiles"
}

build_frontend() {
  [ "$VERIFY" = 1 ] || return 0
  bold "Type-checking and building frontend (skip with --no-verify)"
  local node_bin
  node_bin="$(ls -d "$HOME/.nvm/versions/node"/*/bin 2>/dev/null | sort -V | tail -1)"
  [ -n "$node_bin" ] && export PATH="$node_bin:$PATH"
  command -v npm >/dev/null 2>&1 || { dim "npm not on PATH — skipping"; return 0; }
  [ -d "$ROOT/frontend/node_modules" ] || ( cd "$ROOT/frontend" && npm ci )
  ( cd "$ROOT/frontend" && npm run build ) \
    || die "frontend build failed — fix it before deploying"
  green "  frontend builds"
}

confirm() {                # prompt -> 0 yes / 1 no
  [ "$ASSUME_YES" = 1 ] && return 0
  [ "$DRY_RUN" = 1 ] && return 0
  local reply
  printf "\033[33m%s\033[0m [y/N] " "$1"
  read -r reply </dev/tty || return 1
  case "$reply" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

# ---------------------------------------------------------------- deploy

# Waits for the app to answer 200. The backend runs Flyway on boot and the JVM takes
# ~20s to open the port, so allow a couple of minutes before calling it a failure.
smoke() {                  # url, label, attempts
  local url="$1" label="$2" tries="${3:-20}" code
  [ "$DRY_RUN" = 1 ] && { dim "would smoke test $url"; return 0; }
  bold "Smoke testing $label"
  for i in $(seq 1 "$tries"); do
    code="$(curl -fsS -o /dev/null -w '%{http_code}' --max-time 10 "$url" 2>/dev/null || echo 000)"
    [ "$code" = "200" ] && { green "  $label healthy ($url)"; return 0; }
    dim "attempt $i/$tries: $code"
    sleep 6
  done
  red "  $label did not come up — check: ./scripts/deploy.sh logs $label"
  return 1
}

deploy_backend() {
  bold "Deploying backend → $BACKEND_APP"
  # Flyway migrations run on boot. With one machine, `rolling` means a short gap
  # between the old machine stopping and the new one passing its health check.
  ( cd "$ROOT/backend" && run flyctl deploy --remote-only --wait-timeout "$WAIT_TIMEOUT" ) \
    || die "backend deploy failed — previous release is still serving; see: flyctl releases -a $BACKEND_APP"
  smoke "${BACKEND_URL}${HEALTH_PATH}" backend 20 || exit 1
}

deploy_frontend() {
  bold "Deploying frontend → $FRONTEND_APP"
  ( cd "$ROOT/frontend" && run flyctl deploy --remote-only --wait-timeout "$WAIT_TIMEOUT" ) \
    || die "frontend deploy failed — previous release is still serving; see: flyctl releases -a $FRONTEND_APP"
  smoke "$FRONTEND_URL" frontend 10 || exit 1
}

# ---------------------------------------------------------------- other commands

cmd_status() {
  require_flyctl
  for app in "$BACKEND_APP" "$FRONTEND_APP"; do
    bold "$app"
    flyctl status -a "$app" 2>/dev/null || red "  could not reach Fly"
  done
  bold "Endpoints"
  for url in "${BACKEND_URL}${HEALTH_PATH}" "$FRONTEND_URL"; do
    dim "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" || echo 000)  $url"
  done
}

cmd_logs() {
  require_flyctl
  case "${1:-backend}" in
    backend|api) flyctl logs -a "$BACKEND_APP" ;;
    frontend|web) flyctl logs -a "$FRONTEND_APP" ;;
    *) die "logs: expected 'backend' or 'frontend'" ;;
  esac
}

# Fly has no one-shot rollback for a Docker deploy, so this redeploys the previous
# release's image. A backend rollback does NOT undo Flyway migrations — check that the
# old code can still run against the new schema before you use it.
cmd_rollback() {
  require_flyctl
  local app
  case "${1:-}" in
    backend|api) app="$BACKEND_APP" ;;
    frontend|web) app="$FRONTEND_APP" ;;
    *) die "rollback: expected 'backend' or 'frontend'" ;;
  esac

  bold "Recent releases for $app"
  flyctl releases -a "$app" --image | head -6

  local image
  image="$(flyctl releases -a "$app" --image --json 2>/dev/null \
    | grep -o '"ImageRef"[^,]*' | sed -n '2p' | cut -d'"' -f4)"
  [ -n "$image" ] || die "could not determine the previous image — roll back manually with: flyctl deploy -a $app --image <ref>"

  red "About to redeploy $app from image: $image"
  [ "$app" = "$BACKEND_APP" ] && red "Flyway migrations already applied are NOT reverted."
  confirm "Proceed?" || exit 1
  run flyctl deploy -a "$app" --image "$image" --wait-timeout "$WAIT_TIMEOUT"
}

# ---------------------------------------------------------------- args

TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)     DRY_RUN=1 ;;
    --no-verify)   VERIFY=0 ;;
    --yes|-y)      ASSUME_YES=1 ;;
    -h|--help)     sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    status)        cmd_status; exit $? ;;
    logs)          shift; cmd_logs "${1:-backend}"; exit $? ;;
    rollback)      shift; cmd_rollback "${1:-}"; exit $? ;;
    backend|api)   TARGET="backend" ;;
    frontend|web)  TARGET="frontend" ;;
    both|all)      TARGET="" ;;
    *)             die "unknown argument: $1  (try --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------- main

require_flyctl
check_git

bold "Deploying ${TARGET:-backend + frontend} to Fly.io"
dim "user:   $(flyctl auth whoami 2>/dev/null)"
dim "commit: $(git -C "$ROOT" log -1 --format='%h %s' 2>/dev/null || echo n/a)"
[ "$DRY_RUN" = 1 ] && dim "(dry run — nothing will be deployed)"
echo

confirm "Continue?" || { dim "aborted"; exit 0; }

# Backend first: the frontend proxies /api/ to it, so a frontend expecting a new
# endpoint should never go out ahead of the backend that serves it.
case "$TARGET" in
  backend)  build_backend;  deploy_backend ;;
  frontend) build_frontend; deploy_frontend ;;
  *)        build_backend; build_frontend; deploy_backend; deploy_frontend ;;
esac

echo
green "Deployed."
dim "$FRONTEND_URL"
dim "${BACKEND_URL}${HEALTH_PATH}"
dim "rollback: ./scripts/deploy.sh rollback backend"
