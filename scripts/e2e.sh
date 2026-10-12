#!/usr/bin/env bash
# End-to-end check against running services (docker compose, or kubectl port-forward).
# Injects failures into provisioning-service and verifies the enrollment still converges to
# exactly one provision.
set -euo pipefail
ENROLL=${ENROLL_URL:-http://localhost:8080}
PROV=${PROVISIONING_URL:-http://localhost:8081}

json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "== inject faults: 2 rejected calls, then 1 call whose response is lost after commit"
curl -fsS -X PUT "$PROV/internal/faults" -H 'content-type: application/json' \
  -d '{"failNext": 2, "dropResponseNext": 1}'
echo

KEY="e2e-$(date +%s)-$RANDOM"
PARTNER="partner-$RANDOM"
BODY="{\"partnerId\":\"$PARTNER\",\"programCode\":\"GOLD\",\"contactEmail\":\"ops@$PARTNER.example\"}"

echo "== create enrollment twice with the same Idempotency-Key"
FIRST=$(curl -fsS -X POST "$ENROLL/api/v1/enrollments" -H 'content-type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY")
SECOND=$(curl -fsS -X POST "$ENROLL/api/v1/enrollments" -H 'content-type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY")
[ "$FIRST" = "$SECOND" ] || { echo "replay differs"; exit 1; }
ID=$(echo "$FIRST" | json "['id']")
echo "enrollment $ID accepted (replay identical)"

echo "== wait for provisioning through the injected failures"
for _ in $(seq 1 60); do
  STATUS=$(curl -fsS "$ENROLL/api/v1/enrollments/$ID" | json "['status']")
  [ "$STATUS" != "PENDING" ] && break
  sleep 1
done
echo "status: $STATUS"
[ "$STATUS" = "ACTIVE" ] || { curl -fsS "$ENROLL/api/v1/enrollments/$ID"; exit 1; }

PROVISION=$(curl -fsS "$PROV/api/v1/provisions/$ID" | json "['provisionId']")
ENROLLED=$(curl -fsS "$ENROLL/api/v1/enrollments/$ID" | json "['provisionId']")
[ "$PROVISION" = "$ENROLLED" ] || { echo "provision id mismatch: $PROVISION vs $ENROLLED"; exit 1; }
STATS=$(curl -fsS "$PROV/internal/stats")
echo "provisioning stats: $STATS"
DUPES=$(echo "$STATS" | json "['duplicates']")
python3 -c "import sys; sys.exit(0 if float('$DUPES') >= 1 else 1)" || { echo "expected the lost-response retry to hit the idempotent path"; exit 1; }

echo "== metrics exposed"
curl -fsS "$ENROLL/actuator/prometheus" | grep -E '^partnermesh_outbox_dispatch_seconds_count' | head -5
curl -fsS "$ENROLL/actuator/health/readiness"
echo
echo "E2E PASSED: one enrollment, one provision ($PROVISION), despite 3 injected failures"
