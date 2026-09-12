#!/bin/sh
# Deploy LIQUIDO backend to GISMO's "integration" Docker environment.
# Run this from the liquido-backend-quarkus repo root, directly on GISMO
# (Claude Code sessions in this project already run there - see [[gismo-deployment-setup]]).
#
# This is the ONE instance on GISMO. Postgres is NOT part of this stack - it's the native
# install on GISMO (see docker/docker-compose.yml comment for why), already migrated to a
# single "liquido-int" database. Secrets/env (DB password, hash secret, mailer password, ...)
# live in docker/.env, which is gitignored - see that file for where each value came from.
#
# Caddy (native, unchanged) reverse-proxies localhost:8080 -> this container, which uses
# network_mode: host, so no port publishing/mapping is involved.

set -e

echo
echo "Building LIQUIDO backend Docker image..."
docker compose -f docker/docker-compose.yml build

echo
echo "Starting/recreating the container..."
docker compose -f docker/docker-compose.yml up -d

LIQUIDO_API_URL="https://liquido.dynv6.net/api/v2/graphql"
LIQUIDO_FRONTEND_URL="https://liquido.dynv6.net/"

echo
check_attempt=1
max_attempts=15
while [ "$check_attempt" -le "$max_attempts" ]; do
  printf "\rChecking backend at %s (%s/%s)" "$LIQUIDO_API_URL" "$check_attempt" "$max_attempts"
  response="$(curl -k --silent --show-error -X POST "$LIQUIDO_API_URL" -H "Content-Type: application/json" -d '{"query":"{ ping }"}' || true)"
  if printf '%s' "$response" | grep -q 'LIQUIDO'; then
    echo " => ✅ Backend is alive."
    break
  fi
  if [ "$check_attempt" -eq "$max_attempts" ]; then
    echo " => ❌ FAILED! (${check_attempt}/${max_attempts}): $response"
    echo "Recent container logs:"
    docker compose -f docker/docker-compose.yml logs --tail 40 backend
    exit 1
  fi
  check_attempt=$((check_attempt + 1))
  sleep 1
done

printf "Checking frontend at %s" "$LIQUIDO_FRONTEND_URL"
response="$(curl -k --silent --show-error "$LIQUIDO_FRONTEND_URL" || true)"
if printf '%s' "$response" | grep -qi '^<html' \
  && printf '%s' "$response" | grep -q 'LIQUIDO is loading'; then
  echo " => Frontend is OK ✅"
else
  echo " => FAILED! Frontend did not return valid HTML. (Deploy the frontend separately via"
  echo "    liquido-mobile-pwa-vue3/deploy/build-and-deploy-local.sh - Caddy serves it as static"
  echo "    files, it is not part of this Docker stack.)"
  echo "$response"
  exit 1
fi

echo
echo "Deployed successfully ✅"
