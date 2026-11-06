#!/usr/bin/env bash
# Sets up repository git hooks so CI test verification runs on pre-commit and pre-push.
set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

chmod +x .githooks/pre-commit .githooks/pre-push
git config core.hooksPath .githooks

echo "✅ Git hooks configured to '.githooks'. Pre-commit and pre-push test checks are active."
