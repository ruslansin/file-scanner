#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
echo "==> File Scanner — building & launching..."
mvn -q compile javafx:run
