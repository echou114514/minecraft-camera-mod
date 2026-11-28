#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if [[ -z "${MP_PATH:-}" ]]; then
    echo "Error: MP_PATH is not set (path to the modpack instance)." >&2
    exit 1
fi

if [[ ! -d "$MP_PATH" ]]; then
    echo "Error: MP_PATH does not exist or is not a directory: $MP_PATH" >&2
    exit 1
fi

if [[ -z "${MP_RUNNER:-}" ]]; then
    echo "Error: MP_RUNNER is not set (path to the modpack launch script)." >&2
    exit 1
fi

if [[ ! -f "$MP_RUNNER" || ! -r "$MP_RUNNER" ]]; then
    echo "Error: MP_RUNNER is not a readable file: $MP_RUNNER" >&2
    exit 1
fi

if [[ "${MP_RUNNER##*.}" != "sh" ]]; then
    echo "Error: MP_RUNNER must be a .sh file: $MP_RUNNER" >&2
    exit 1
fi

mapfile -t jars < <(find "$project_dir/build/libs" -maxdepth 1 -type f -name 'camera-mod-*.jar' ! -name '*-sources.jar' -print | sort)
if (( ${#jars[@]} == 0 )); then
    echo "Error: no built camera-mod jar in $project_dir/build/libs. Run ./gradlew build first." >&2
    exit 1
fi

# Versions are derived from git (commit count + short hash), so `build/libs` keeps
# jars from earlier commits. Pick the newest one instead of requiring a single jar.
latest="${jars[0]}"
for jar in "${jars[@]}"; do
    if [[ "$jar" -nt "$latest" ]]; then
        latest="$jar"
    fi
done

if (( ${#jars[@]} > 1 )); then
    echo "提示：build/libs 有 ${#jars[@]} 个 camera-mod jar，使用最新的 $(basename -- "$latest")。"
fi

mods_dir="$MP_PATH/mods"
mkdir -p -- "$mods_dir"

# Remove every other camera-mod jar first: version bumps would otherwise leave
# two copies of the mod in the pack.
find "$mods_dir" -maxdepth 1 -type f -name 'camera-mod-*.jar' ! -name '*-sources.jar' -delete
install -m 0644 -- "$latest" "$mods_dir/$(basename -- "$latest")"

echo "Installed $(basename -- "$latest") into $mods_dir"
echo "Launching modpack (output is also written to /tmp/latest.log)..."
exec bash "$MP_RUNNER" "$@" 2>&1 | tee /tmp/latest.log
