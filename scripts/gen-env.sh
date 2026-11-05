#!/usr/bin/env bash
# Creates .env with random secrets (does not overwrite an existing file).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo ".env already exists - leaving it alone"; exit 0; fi
rand() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex 32; else head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi; }
cat > .env <<EOF
DATABASE_PASSWORD=$(rand)
JWT_SECRET=$(rand)
INTERNAL_SECRET=$(rand)
FAILURE_POLICY=fail-closed
GRAFANA_PASSWORD=$(rand)
EOF
echo "Created .env with random secrets (Grafana login: admin / see GRAFANA_PASSWORD in .env)"
