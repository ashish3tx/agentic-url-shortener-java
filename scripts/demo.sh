#!/usr/bin/env bash
# End-to-end demo against a running server (local profile): ./mvnw spring-boot:run, then ./scripts/demo.sh
# Local users (password "password"): alice=ENGINEER, bob=APPROVER, carol=ENGINEER+APPROVER, admin=ADMIN+OPS
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
JSON='Content-Type: application/json'
as() { local who=$1; shift; curl -s -u "$who:password" "$@"; }

pretty() { if command -v jq >/dev/null; then jq "$@"; else cat; echo; fi; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }  # first occurrence
wait_state() { # run-id, expected state
  for _ in $(seq 1 60); do
    state=$(as bob "$BASE/orchestrator/runs/$1" | field state)
    [[ "$state" == "$2" ]] && return 0
    sleep 0.25
  done
  echo "run $1 did not reach $2 (last: $state)" >&2; return 1
}

echo "== Product API =="
echo "anonymous create -> $(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/urls" -H "$JSON" -d '{"url":"https://example.com"}')"
LINK=$(as alice -X POST "$BASE/api/v1/urls" -H "$JSON" \
  -d "{\"url\":\"https://example.com/docs\",\"alias\":\"docs-$RANDOM\",\"ttlSeconds\":86400}")
echo "$LINK" | pretty
CODE=$(echo "$LINK" | field code)
curl -s -o /dev/null -w "GET /$CODE (public) -> %{http_code} Location: %{redirect_url}\n" "$BASE/$CODE"
echo "bob reads alice's analytics -> $(as bob -o /dev/null -w '%{http_code}' "$BASE/api/v1/urls/$CODE/analytics")"
as alice "$BASE/api/v1/urls/$CODE/analytics" | pretty
as alice -X POST "$BASE/api/v1/urls" -H "$JSON" -d '{"url":"http://169.254.169.254/latest"}' | pretty

echo "== Greenfield run (alice) with a transient test failure, four-eyes approval (bob) =="
RUN=$(as alice -X POST "$BASE/orchestrator/runs" -H "$JSON" -d '{"scenario":"GREENFIELD","failureMode":"TRANSIENT"}' | field id)
echo "run id: $RUN"
wait_state "$RUN" WAITING_APPROVAL
echo "-- alice (no APPROVER role) tries to approve -> $(as alice -o /dev/null -w '%{http_code}' -X POST "$BASE/orchestrator/runs/$RUN/approve" -H "$JSON" -d '{}')"
echo "-- bob approves -> $(as bob -o /dev/null -w '%{http_code}' -X POST "$BASE/orchestrator/runs/$RUN/approve" -H "$JSON" -d '{"comment":"CHG-1001"}')"
wait_state "$RUN" SUCCEEDED && echo "greenfield SUCCEEDED"

echo "== Brownfield run by carol (engineer AND approver): separation of duties =="
RUN=$(as carol -X POST "$BASE/orchestrator/runs" -H "$JSON" -d '{"scenario":"BROWNFIELD"}' | field id)
wait_state "$RUN" WAITING_APPROVAL
as carol -X POST "$BASE/orchestrator/runs/$RUN/approve" -H "$JSON" -d '{}' | pretty
as bob -o /dev/null -w "bob rejects -> %{http_code}\n" -X POST "$BASE/orchestrator/runs/$RUN/reject" -H "$JSON" -d '{"comment":"breaking change"}'
wait_state "$RUN" FAILED && echo "brownfield rolled back (NO_GO)"

echo "== Ambiguous run: assumptions accepted, then scope amended (re-plan) =="
RUN=$(as alice -X POST "$BASE/orchestrator/runs" -H "$JSON" -d '{"scenario":"AMBIGUOUS"}' | field id)
wait_state "$RUN" WAITING_APPROVAL
as bob -o /dev/null -w "bob accepts assumptions -> %{http_code}\n" -X POST "$BASE/orchestrator/runs/$RUN/approve" -H "$JSON" \
  -d '{"stageId":"normalize","comment":"bounded scope accepted"}'
sleep 1
as alice -o /dev/null -w "alice amends requirements -> %{http_code}\n" -X POST "$BASE/orchestrator/runs/$RUN/amend" -H "$JSON" \
  -d '{"artifact":"requirements","changes":{"assumptions":["Click retention is 90 days"]},"reason":"compliance input"}'
wait_state "$RUN" WAITING_APPROVAL
echo "normalize checkpoint is pending again after re-plan; stage states:"
as bob "$BASE/orchestrator/runs/$RUN" | grep -oE '"id":"(normalize|architecture)","name":"[^"]*"|"state":"[A-Z_]+"' | sed -n '1,2p'
as alice -o /dev/null -w "alice safe-stops -> %{http_code}\n" -X POST "$BASE/orchestrator/runs/$RUN/stop"
wait_state "$RUN" STOPPED && echo "ambiguous run STOPPED safely"

echo "== Reliability metrics (bob) and Prometheus (admin) =="
as bob "$BASE/orchestrator/metrics" | pretty
as admin "$BASE/actuator/prometheus" | grep -E '^orchestrator_runs_finished_total' || true
