#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ -d "$workspace_dir/work/tooling/jdk-17.0.20.1+1/Contents/Home" ]; then
    export JAVA_HOME="$workspace_dir/work/tooling/jdk-17.0.20.1+1/Contents/Home"
fi
cd "$project_dir"
if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
exec ./gradlew "$@"
