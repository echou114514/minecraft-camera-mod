#!/usr/bin/env bash
# 关闭 scripts/enable-cn-mirrors.sh 启用的项目级中国镜像，恢复官方源。
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

# 删除 enable 脚本追加的镜像块，并清掉文件末尾多余空行。
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
cat "$tmp" > "$gradle_props"

# 优先用 enable 时保存的原文件精确恢复；没有备份则把已知镜像域名换回官方。
if [[ -f "$wrapper_backup" ]]; then
    cat "$wrapper_backup" > "$wrapper_props"
    rm -f "$wrapper_backup"
else
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
    ' "$wrapper_props" > "$tmp"
    cat "$tmp" > "$wrapper_props"
fi

if ! grep -q 'services\.gradle\.org/distributions/' "$wrapper_props"; then
    echo "警告：未能确认官方 distributionUrl，请检查 $wrapper_props" >&2
fi

legacy_props="${HOME}/.gradle/gradle.properties"
if [[ -f "$legacy_props" ]] && grep -qE '^loom_(libraries_base|resources_base|version_manifests)=' "$legacy_props"; then
    echo "提示：$legacy_props 仍有旧的用户级 loom_* 镜像配置。"
    echo "      本脚本只处理项目文件；建议手动删除这些行，以免影响其他项目。"
fi

echo "已恢复官方源："
echo "  - $gradle_props"
echo "  - $wrapper_props"
