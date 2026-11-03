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
if (( ${#jars[@]} != 1 )); then
    echo "Error: expected exactly one built camera-mod jar in $project_dir/build/libs; found ${#jars[@]}. Run ./gradlew build first." >&2
    exit 1
fi

mods_dir="$MP_PATH/mods"
mkdir -p -- "$mods_dir"
install -m 0644 -- "${jars[0]}" "$mods_dir/$(basename -- "${jars[0]}")"

echo "Installed $(basename -- "${jars[0]}") into $mods_dir"
exec bash "$MP_RUNNER" "$@" > /tmp/latest.log 2>&1
