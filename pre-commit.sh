#!/bin/sh
set -eu

# Run from the project root, even when invoked from another directory.
cd "$(dirname "$0")"

docker compose up -d --wait --wait-timeout 60 postgres mailpit
exec ./gradlew :server:run
