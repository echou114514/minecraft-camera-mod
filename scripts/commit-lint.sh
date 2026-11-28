#!/usr/bin/env bash
# 拦截会让 GitHub Actions 走慢速镜像的构建配置。
#   scripts/commit-lint.sh --staged   # pre-commit：检查暂存区
#   scripts/commit-lint.sh --all      # CI / 手动：检查工作区
# 镜像配置只应留在本地；运行 scripts/disable-cn-mirrors.sh 恢复官方源。
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mode="${1:---staged}"
case "$mode" in
    --staged|--all) ;;
    *)
        echo "用法: $0 [--staged|--all]" >&2
        exit 2
        ;;
esac

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
failed=0

read_file() {
    local path="$1"
    if [[ "$mode" == "--staged" ]]; then
        git show ":$path" 2>/dev/null || true
    else
        [[ -f "$path" ]] && cat "$path" || true
    fi
}

check_gradle_properties() {
    local path="gradle.properties"
    read_file "$path" > "$tmp"

    # 注释里的镜像示例是允许的；只拦截真正生效的 loom_* 属性。
    if grep -nE '^[[:space:]]*loom_(libraries_base|resources_base|version_manifests|fabric_repository)[[:space:]]*=' "$tmp" >/dev/null; then
        echo "commit-lint: $path 含有启用的 loom 镜像属性：" >&2
        grep -nE '^[[:space:]]*loom_(libraries_base|resources_base|version_manifests|fabric_repository)[[:space:]]*=' "$tmp" >&2
        failed=1
    fi
}

check_wrapper_properties() {
    local path="gradle/wrapper/gradle-wrapper.properties"
    read_file "$path" > "$tmp"

    local url
    url="$(grep -E '^[[:space:]]*distributionUrl[[:space:]]*=' "$tmp" | tail -n1 || true)"
    if [[ -z "$url" ]]; then
        echo "commit-lint: $path 缺少 distributionUrl。" >&2
        failed=1
        return
    fi

    if [[ "$url" != *"services.gradle.org/distributions/"* ]]; then
        echo "commit-lint: Gradle 发行包必须使用官方源，CI 才能稳定命中缓存：" >&2
        echo "  $url" >&2
        failed=1
    fi
}

check_mirror_backups() {
    local files=""
    if [[ "$mode" == "--staged" ]]; then
        files="$(git diff --cached --name-only --diff-filter=ACMR | grep -E '(\.cn-backup$|^gradle\.local\.properties$)' || true)"
    else
        files="$(git ls-files | grep -E '(\.cn-backup$|^gradle\.local\.properties$)' || true)"
    fi

    if [[ -n "$files" ]]; then
        echo "commit-lint: 以下本地镜像文件不应进入版本库：" >&2
        echo "$files" >&2
        failed=1
    fi
}

check_gradle_properties
check_wrapper_properties
check_mirror_backups

if (( failed )); then
    echo >&2
    echo "提交被拦截：中国镜像配置会让 GitHub Actions 变慢。" >&2
    echo "运行 scripts/disable-cn-mirrors.sh 恢复官方源后再提交。" >&2
    exit 1
fi

echo "commit-lint: OK"
