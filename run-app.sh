#!/usr/bin/env bash
# run-app.sh — starts GitSolve AI and tees output to logs/app.log for Promtail
set -euo pipefail

cd "$(dirname "$0")"
source .env 2>/dev/null || true

mkdir -p logs

DATABASE_URL=jdbc:postgresql://localhost:5433/gitsolve \
  mvn spring-boot:run -q 2>&1 | tee logs/app.log
