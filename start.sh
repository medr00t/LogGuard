#!/bin/bash

set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[+]${NC} $1"; }
info() { echo -e "${CYAN}[~]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }

export USERPROFILE="$HOME"

echo ""
echo -e "${CYAN}╔══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║         LogGuard Launcher            ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════╝${NC}"
echo ""

# ── 1. Cleanup ────────────────────────────────────────────────
log "Tearing down any running containers..."
docker compose -f "$ROOT_DIR/docker-compose.yml" down --remove-orphans 2>/dev/null || true

for PORT in 8080 8090 9000 5000; do
    CID=$(docker ps -q --filter "publish=$PORT")
    if [ -n "$CID" ]; then
        warn "Port $PORT still held by $CID — stopping..."
        docker stop "$CID" && docker rm "$CID" 2>/dev/null || true
    fi
done
echo ""

# ── 2. Build backend JAR ──────────────────────────────────────
log "Building backend JAR..."
cd "$BACKEND_DIR"
if ! ./mvnw package -DskipTests -q; then
    err "Maven build failed — fix compilation errors and retry."
    exit 1
fi
cd "$ROOT_DIR"
log "JAR built."
echo ""

# ── 3. Build Docker images ────────────────────────────────────
log "Building Docker images (backend + jenkins)..."
docker compose -f "$ROOT_DIR/docker-compose.yml" build backend jenkins
echo ""

# ── 4. Start everything ───────────────────────────────────────
log "Starting all services (Postgres, SonarQube, Jenkins, AI Engine, Backend)..."
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d
echo ""

# ── 5. Wait for backend ───────────────────────────────────────
info "Waiting for backend to be ready..."
MAX=60; COUNT=0
until curl -sf http://localhost:8080/api/health > /dev/null 2>&1; do
    COUNT=$((COUNT + 1))
    [ "$COUNT" -ge "$MAX" ] && { warn "Backend did not respond — docker compose logs backend"; break; }
    printf "."; sleep 2
done
echo ""

# ── 6. Wait for AI engine ─────────────────────────────────────
info "Waiting for AI engine to be ready..."
COUNT=0
until curl -sf http://localhost:5000/health > /dev/null 2>&1; do
    COUNT=$((COUNT + 1))
    [ "$COUNT" -ge "$MAX" ] && { warn "AI engine did not respond — docker compose logs ai-engine"; break; }
    printf "."; sleep 2
done
echo ""
echo ""

# ── 7. Summary ────────────────────────────────────────────────
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo -e "${GREEN}  All services started!${NC}"
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo ""
echo -e "  ${CYAN}Backend API${NC}     →  http://localhost:8080"
echo -e "  ${CYAN}AI Engine${NC}       →  http://localhost:5000"
echo -e "  ${CYAN}Jenkins${NC}         →  http://localhost:8090  (admin / admin123)"
echo -e "  ${CYAN}SonarQube${NC}       →  http://localhost:9000  (admin / admin)"
echo -e "  ${CYAN}Prometheus${NC}      →  http://localhost:9090"
echo -e "  ${CYAN}Grafana${NC}         →  http://localhost:3300  (admin / admin123)"
echo ""
echo -e "  ${YELLOW}Note:${NC} Jenkins needs ~2 min to finish plugin setup on first boot."
echo -e "  ${YELLOW}Note:${NC} The logguard-deploy pipeline is auto-created by the backend."
echo ""
echo -e "  ${CYAN}Default login${NC}   →  admin / admin123  (same for LogGuard + Jenkins)"
echo ""
echo -e "  ${CYAN}Demo data:${NC}"
echo -e "  python ai-engine/demo_data.py --url http://localhost:8080"
echo ""
echo -e "  ${CYAN}Stop everything:${NC}"
echo -e "  ./stop.sh"
echo ""
