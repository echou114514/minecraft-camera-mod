#!/usr/bin/env bash
# 为 Gradle 启用中国镜像（Gradle 用户级配置，写入 ~/.gradle/gradle.properties）。
# 仓库内的构建文件保持官方源；本脚本只影响当前机器的所有 Gradle 构建。
# 恢复官方源：手动删除 ~/.gradle/gradle.properties 中的 loom_* 行。
set -euo pipefail

user_props="${HOME}/.gradle/gradle.properties"
touch "$user_props"

if grep -q "^loom_libraries_base=" "$user_props"; then
    sed -i 's|^loom_libraries_base=.*|loom_libraries_base=https://bmclapi2.bangbang93.com/maven/|' "$user_props"
    sed -i 's|^loom_resources_base=.*|loom_resources_base=https://bmclapi2.bangbang93.com/assets/|' "$user_props"
    sed -i 's|^loom_version_manifests=.*|loom_version_manifests=https://bmclapi2.bangbang93.com/mc/game/version_manifest.json|' "$user_props"
else
    cat >> "$user_props" <<'PROPS'

# --- camera-mod CN mirrors (scripts/enable-cn-mirrors.sh) ---
loom_libraries_base=https://bmclapi2.bangbang93.com/maven/
loom_resources_base=https://bmclapi2.bangbang93.com/assets/
loom_version_manifests=https://bmclapi2.bangbang93.com/mc/game/version_manifest.json
PROPS
fi

echo "已启用中国镜像（写入 $user_props）。"
echo "如需覆盖 Gradle 发行包下载源，可另行修改项目 gradle-wrapper.properties 或使用本地代理。"
