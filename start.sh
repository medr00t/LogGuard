#!/bin/bash

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="$ROOT_DIR/infrastructure"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[+]${NC} $1"; }
info() { echo -e "${CYAN}[~]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }

echo ""
echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║         LogGuard Launcher            ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"
echo ""

# ── 1. Cleanup any leftover containers ────────────────────────
log "Cleaning up any leftover containers..."
export USERPROFILE="$HOME"
docker compose -f "$INFRA_DIR/docker-compose.yml" down --remove-orphans 2>/dev/null || true
docker compose -f "$ROOT_DIR/docker-compose.yml" down --remove-orphans 2>/dev/null || true

# Kill anything still holding our ports
for PORT in 8080 5000; do
    CID=$(docker ps -q --filter "publish=$PORT")
    if [ -n "$CID" ]; then
        warn "Port $PORT still in use by container $CID — stopping it..."
        docker stop "$CID" && docker rm "$CID"
    fi
done
echo ""

# ── 2. Infrastructure stack (Jenkins + SonarQube) ──────────────
log "Starting infrastructure stack (Jenkins + SonarQube)..."
docker compose -f "$INFRA_DIR/docker-compose.yml" up -d
echo ""

# ── 3. App stack (Postgres + AI Engine + Backend) ──────────────
log "Starting app stack (Postgres + AI Engine + Backend)..."
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d
echo ""

# ── 3. Wait for backend to be healthy ──────────────────────────
info "Waiting for backend to be ready..."
MAX=60
COUNT=0
until curl -sf http://localhost:8080/api/health > /dev/null 2>&1; do
    COUNT=$((COUNT + 1))
    if [ "$COUNT" -ge "$MAX" ]; then
        warn "Backend did not respond after ${MAX}s — check logs: docker compose logs backend"
        break
    fi
    printf "."
    sleep 2
done
echo ""

# ── 4. Wait for AI engine ──────────────────────────────────────
info "Waiting for AI engine to be ready..."
COUNT=0
until curl -sf http://localhost:5000/health > /dev/null 2>&1; do
    COUNT=$((COUNT + 1))
    if [ "$COUNT" -ge "$MAX" ]; then
        warn "AI engine did not respond after ${MAX}s — check logs: docker compose logs ai-engine"
        break
    fi
    printf "."
    sleep 2
done
echo ""
echo ""

# ── 5. Summary ────────────────────────────────────────────────
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo -e "${GREEN}  All services started!${NC}"
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo ""
echo -e "  ${CYAN}Backend API${NC}     →  http://localhost:8080"
echo -e "  ${CYAN}AI Engine${NC}       →  http://localhost:5000"
echo -e "  ${CYAN}Jenkins${NC}         →  http://localhost:8090"
echo -e "  ${CYAN}SonarQube${NC}       →  http://localhost:9000"
echo ""
echo -e "  ${YELLOW}Default login:${NC} admin / admin123"
echo ""
echo -e "  ${CYAN}Run demo alerts:${NC}"
echo -e "  python ai-engine/demo_data.py --url http://localhost:8080"
echo ""
echo -e "  ${CYAN}Stop everything:${NC}"
echo -e "  ./stop.sh"
echo ""
