#!/usr/bin/env bash
# Fires the same POST /orders request twice with the same Idempotency-Key.
# The second call must replay the first response - the customer is charged once, not twice.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
KEY="demo-$(date +%s)"
BODY='{"amount":49.99,"currency":"USD"}'

echo "== First request (executes the handler) =="
curl -s -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY"
echo -e "\n"

echo "== Second request, same key + body (replayed - look for Idempotent-Replay: true) =="
curl -s -i -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY"
echo -e "\n"

echo "== Charge count (should be 1) =="
curl -s "$BASE_URL/orders/charge-count"
echo
