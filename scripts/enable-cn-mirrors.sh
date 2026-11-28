#!/usr/bin/env bash
# 项目级中国镜像开关：只改本仓库，不影响 ~/.gradle 或其他项目。
#   - gradle.properties: BMCLAPI 的 loom 镜像（Minecraft 库 / 资源 / 版本清单）
#   - gradle/wrapper/gradle-wrapper.properties: Gradle 发行包走腾讯云镜像
# 恢复官方源：scripts/disable-cn-mirrors.sh
# 注意：镜像配置不要提交；pre-commit / CI 的 commit lint 会拦截。
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
gradle_props="$project_dir/gradle.properties"
wrapper_props="$project_dir/gradle/wrapper/gradle-wrapper.properties"
wrapper_backup="${wrapper_props}.cn-backup"

if [[ ! -f "$gradle_props" ]]; then
    echo "错误：找不到 $gradle_props" >&2
    exit 1
fi
if [[ ! -f "$wrapper_props" ]]; then
    echo "错误：找不到 $wrapper_props" >&2
    exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

# 重写 gradle.properties 末尾的镜像块（可重复执行，不会重复追加）。
awk '
    { sub(/\r$/, "") }
    /^# --- BEGIN CN mirrors \(enable-cn-mirrors\.sh\) ---$/ { skip = 1; next }
    /^# --- END CN mirrors \(enable-cn-mirrors\.sh\) ---$/   { skip = 0; next }
    !skip { lines[++n] = $0 }
    END {
        last = n
        while (last > 0 && lines[last] ~ /^[[:space:]]*$/) last--
        for (i = 1; i <= last; i++) print lines[i]
    }
' "$gradle_props" > "$tmp"
cat >> "$tmp" <<'PROPS'

# --- BEGIN CN mirrors (enable-cn-mirrors.sh) ---
loom_libraries_base=https://bmclapi2.bangbang93.com/maven/
loom_resources_base=https://bmclapi2.bangbang93.com/assets/
loom_version_manifests=https://bmclapi2.bangbang93.com/mc/game/version_manifest.json
# --- END CN mirrors (enable-cn-mirrors.sh) ---
PROPS
cat "$tmp" > "$gradle_props"

# 保留一份官方 wrapper 配置，disable 时精确恢复。
if grep -q 'services\.gradle\.org/distributions/' "$wrapper_props"; then
    cp "$wrapper_props" "$wrapper_backup"
elif [[ ! -f "$wrapper_backup" ]]; then
    # 当前已经是已知镜像（例如按旧 README 手动改过）时，反推官方 URL 作为备份。
    awk '
        { sub(/\r$/, "") }
        /^[[:space:]]*distributionUrl[[:space:]]*=/ {
            line = $0
            gsub(/mirrors\.cloud\.tencent\.com\/gradle\//, "services.gradle.org/distributions/", line)
            gsub(/mirrors\.huaweicloud\.com\/gradle\//, "services.gradle.org/distributions/", line)
            gsub(/mirrors\.aliyun\.com\/macports\/distfiles\/gradle\//, "services.gradle.org/distributions/", line)
            print line
            next
        }
        { print }
    ' "$wrapper_props" > "$wrapper_backup"
fi

dist_name="$(grep -oE 'gradle-[0-9][^/]*-(bin|all)\.zip' "$wrapper_props" | head -n1 || true)"
if [[ -z "$dist_name" ]]; then
    echo "错误：无法从 $wrapper_props 解析 Gradle 发行包文件名。" >&2
    exit 1
fi

awk -v dist="$dist_name" '
    { sub(/\r$/, "") }
    /^[[:space:]]*distributionUrl[[:space:]]*=/ {
        print "distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/" dist
        next
    }
    { print }
' "$wrapper_props" > "$tmp"
cat "$tmp" > "$wrapper_props"

# 启用镜像后自动装上 pre-commit 拦截器，避免误提交。
if git -C "$project_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    hooks_path="$(git -C "$project_dir" config --get core.hooksPath || true)"
    if [[ -z "$hooks_path" || "$hooks_path" == ".githooks" ]]; then
        git -C "$project_dir" config core.hooksPath .githooks
        echo "已启用 commit lint（core.hooksPath=.githooks）。"
    else
        echo "提示：core.hooksPath 已是 $hooks_path，未覆盖；"
        echo "      请把 .githooks/pre-commit 合并进去，或手动运行 scripts/commit-lint.sh。"
    fi
fi

legacy_props="${HOME}/.gradle/gradle.properties"
if [[ -f "$legacy_props" ]] && grep -qE '^loom_(libraries_base|resources_base|version_manifests)=' "$legacy_props"; then
    echo "提示：$legacy_props 仍有旧的用户级 loom_* 镜像配置。"
    echo "      项目级配置会覆盖它；建议删除这些行，以免影响其他项目。"
fi

echo "已启用项目级中国镜像："
echo "  - $gradle_props"
echo "  - $wrapper_props（腾讯云 Gradle ${dist_name}）"
echo "恢复官方源：scripts/disable-cn-mirrors.sh"
