#!/usr/bin/env bash
# 启动开发环境客户端（默认不含 Iris；光影调试用 -PwithIrisDev，见 README）。
set -euo pipefail
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec ./gradlew runClient "$@"
