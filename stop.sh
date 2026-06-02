#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="$ROOT_DIR/infrastructure"

CYAN='\033[0;36m'
GREEN='\033[0;32m'
NC='\033[0m'

echo ""
echo -e "${CYAN}[~]${NC} Stopping app stack..."
docker compose -f "$ROOT_DIR/docker-compose.yml" down

echo -e "${CYAN}[~]${NC} Stopping infrastructure stack..."
export USERPROFILE="$HOME"
docker compose -f "$INFRA_DIR/docker-compose.yml" down

echo ""
echo -e "${GREEN}[+]${NC} All services stopped."
echo ""
