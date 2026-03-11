#!/usr/bin/env bash

set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/home/ubuntu/vlainter}"
IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG is required}"
DOCKERHUB_USERNAME="${DOCKERHUB_USERNAME:?DOCKERHUB_USERNAME is required}"
DOCKERHUB_TOKEN="${DOCKERHUB_TOKEN:?DOCKERHUB_TOKEN is required}"
HEALTH_PATH="${HEALTH_PATH:-/actuator/health}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-3}"

cd "$DEPLOY_DIR"

if [ ! -f .env ]; then
  echo "[ERROR] $DEPLOY_DIR/.env 가 없습니다."
  exit 1
fi

mkdir -p deploy/runtime

if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  echo "[ERROR] Docker Compose가 설치되어 있지 않습니다."
  exit 1
fi

echo "$DOCKERHUB_TOKEN" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin

active_color=""
if [ -f deploy/runtime/active-color ]; then
  active_color="$(cat deploy/runtime/active-color)"
fi

if [ "$active_color" = "blue" ]; then
  target_color="green"
  target_port="18081"
  previous_color="blue"
elif [ "$active_color" = "green" ]; then
  target_color="blue"
  target_port="18080"
  previous_color="green"
else
  target_color="blue"
  target_port="18080"
  previous_color=""
fi

export IMAGE_TAG

echo "[INFO] 현재 활성 색상: ${active_color}"
echo "[INFO] 새 버전 배포 대상: ${target_color} (${IMAGE_TAG})"

$DC -f deploy/docker-compose.bluegreen.yml pull proxy "app-${target_color}"
$DC -f deploy/docker-compose.bluegreen.yml up -d "app-${target_color}"

deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
health_url="http://127.0.0.1:${target_port}${HEALTH_PATH}"

echo "[INFO] 헬스체크 시작: ${health_url}"
until curl -fsS "$health_url" >/dev/null 2>&1; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "[ERROR] 새 컨테이너 헬스체크 실패: ${health_url}"
    docker logs "vlainter-app-${target_color}" --tail 200 || true
    exit 1
  fi
  sleep "$HEALTH_INTERVAL_SECONDS"
done

cat > deploy/runtime/active-upstream.conf <<EOF
upstream vlainter_backend {
  server app-${target_color}:${target_port};
  keepalive 32;
}
EOF
printf '%s' "$target_color" > deploy/runtime/active-color

if docker ps --format '{{.Names}}' | grep -q '^vlainter-proxy$'; then
  docker exec vlainter-proxy nginx -s reload
else
  if docker ps -a --format '{{.Names}}' | grep -q '^vlainter-app$'; then
    echo "[INFO] 레거시 단일 컨테이너 정리: vlainter-app"
    docker rm -f vlainter-app >/dev/null 2>&1 || true
  fi
  $DC -f deploy/docker-compose.bluegreen.yml up -d proxy
fi

echo "[INFO] 프록시 스위칭 완료. 프록시 헬스체크 확인 중"
curl -fsS "http://127.0.0.1:8080${HEALTH_PATH}" >/dev/null

if [ -n "$previous_color" ] && docker ps --format '{{.Names}}' | grep -q "^vlainter-app-${previous_color}\$"; then
  echo "[INFO] 이전 컨테이너 중지: ${previous_color}"
  $DC -f deploy/docker-compose.bluegreen.yml stop "app-${previous_color}"
fi

$DC -f deploy/docker-compose.bluegreen.yml ps
echo "[INFO] 무중단 배포 완료. 활성 색상: ${target_color}"
