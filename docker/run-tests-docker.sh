#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="jpos-q2-switch-test:latest"
UID_GID="$(id -u):$(id -g)"

echo "[docker-test] Building test image: ${IMAGE_TAG}"
docker build -f "${ROOT_DIR}/docker/Dockerfile.test" -t "${IMAGE_TAG}" "${ROOT_DIR}"

echo "[docker-test] Running tests as UID:GID ${UID_GID}"
docker run --rm \
  --user "${UID_GID}" \
  -e HOME=/tmp \
  -v "${ROOT_DIR}:/work" \
  -w /work \
  "${IMAGE_TAG}" \
  bash -lc "mvn -q clean test && python3 -m pytest -q python_tests"

echo "[docker-test] PASS"
