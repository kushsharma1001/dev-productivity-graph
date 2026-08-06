#!/usr/bin/env bash
# ============================================================================
#  load.sh — populate a Neo4j database with the demo graph, without the app.
# ----------------------------------------------------------------------------
#  Runs cypher/schema.cypher then cypher/seed.cypher against the target DB via
#  cypher-shell (bundled with Neo4j; also installable standalone).
#
#  Connection details come from the SAME environment variables the application
#  uses — nothing is hard-coded or committed:
#
#      NEO4J_URI       (default: neo4j://localhost:7687)
#      NEO4J_USER      (default: neo4j)
#      NEO4J_PASSWORD  (required — no default)
#
#  Usage:
#      export NEO4J_URI='neo4j+s://<your-db>.databases.neo4j.io'
#      export NEO4J_USER='neo4j'
#      export NEO4J_PASSWORD='********'
#      ./scripts/load.sh
#
#      # start from a clean graph first:
#      ./scripts/load.sh --reset
# ============================================================================
set -euo pipefail

# Resolve paths relative to this script, so it runs from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CYPHER_DIR="${SCRIPT_DIR}/../cypher"

URI="${NEO4J_URI:-neo4j://localhost:7687}"
USER="${NEO4J_USER:-neo4j}"

if [[ -z "${NEO4J_PASSWORD:-}" ]]; then
  echo "ERROR: NEO4J_PASSWORD is not set. Export it before running:" >&2
  echo "  export NEO4J_PASSWORD='********'" >&2
  exit 1
fi

if ! command -v cypher-shell >/dev/null 2>&1; then
  echo "ERROR: cypher-shell not found on PATH." >&2
  echo "It ships with Neo4j (bin/cypher-shell) or can be installed standalone." >&2
  exit 1
fi

run() {
  # -a URI, -u user, -p password; read Cypher from stdin.
  cypher-shell -a "$URI" -u "$USER" -p "$NEO4J_PASSWORD" --format plain
}

echo "==> Target: $URI  (user: $USER)"

if [[ "${1:-}" == "--reset" ]]; then
  echo "==> Resetting graph (DETACH DELETE all nodes)…"
  echo "MATCH (n) DETACH DELETE n;" | run
fi

echo "==> Applying schema (constraints/indexes)…"
run < "${CYPHER_DIR}/schema.cypher"

echo "==> Seeding demo graph…"
run < "${CYPHER_DIR}/seed.cypher"

echo "==> Verifying node/relationship counts…"
echo "MATCH (n) WITH count(n) AS nodes MATCH ()-[r]->() RETURN nodes, count(r) AS relationships;" | run

echo "==> Done. Try:  ./scripts/load.sh   then open the app or the Neo4j Browser."
