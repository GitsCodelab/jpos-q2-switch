#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Ensure DB + switch dependencies are up before running settlement jobs.
docker compose up -d jpos-postgresql switch >/dev/null

mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.qswitch.settlement.FullSettlementRunner
