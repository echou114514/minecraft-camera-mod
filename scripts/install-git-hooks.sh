#!/usr/bin/env bash
# 安装仓库自带的 git hooks（目前是拦截镜像配置的 pre-commit）。
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if ! git -C "$project_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "错误：$project_dir 不是 git 仓库。" >&2
    exit 1
fi

hooks_path="$(git -C "$project_dir" config --get core.hooksPath || true)"
if [[ -n "$hooks_path" && "$hooks_path" != ".githooks" ]]; then
    echo "错误：core.hooksPath 已设置为 $hooks_path，未覆盖。" >&2
    echo "请把 .githooks/pre-commit 合并到该目录，或手动运行 scripts/commit-lint.sh --staged。" >&2
    exit 1
fi

git -C "$project_dir" config core.hooksPath .githooks
chmod +x "$project_dir/.githooks/pre-commit" "$project_dir/scripts/commit-lint.sh"

echo "已安装 git hooks：core.hooksPath=.githooks"
echo "pre-commit 会运行 scripts/commit-lint.sh --staged，阻止镜像配置进入提交。"
