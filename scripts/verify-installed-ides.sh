#!/usr/bin/env bash
# Runs the IntelliJ Plugin Verifier offline against locally installed IDEs.
# `./gradlew verifyPlugin` resolves `recommended()` IDEs online and often fails to download them;
# this checks the built zip against real installs instead, including ones newer than the build target.
#
# Usage: scripts/verify-installed-ides.sh [IDE.app ...]
#   With no arguments, every IntelliJ IDEA / PyCharm / DataSpell app in ~/Applications and /Applications.
# Build first: ./gradlew buildPlugin
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^pluginVersion *= *//p' gradle.properties)
zip="build/distributions/yadt-$version.zip"
[[ -f "$zip" ]] || { echo "Missing $zip, run ./gradlew buildPlugin first" >&2; exit 1; }

verifier=$(find ~/.gradle/caches -name 'verifier-cli-*-all.jar' 2>/dev/null | sort -V | tail -1)
[[ -n "$verifier" ]] || { echo "No verifier-cli jar in the Gradle cache, run ./gradlew verifyPlugin once online" >&2; exit 1; }

ides=("$@")
if [[ ${#ides[@]} -eq 0 ]]; then
    for dir in "$HOME/Applications" /Applications; do
        for app in "$dir"/{IntelliJ\ IDEA,PyCharm,DataSpell}*.app; do [[ -d "$app" ]] && ides+=("$app"); done
    done
fi
[[ ${#ides[@]} -gt 0 ]] || { echo "No IDEs found; pass .app paths explicitly" >&2; exit 1; }

status=0
for app in "${ides[@]}"; do
    home="$app"; [[ -d "$app/Contents" ]] && home="$app/Contents"
    # The verifier replaces the reports dir's contents, so give each IDE its own.
    reports="build/verifier-reports/$(basename "$app" .app)"
    result=$(java -jar "$verifier" check-plugin "$zip" "$home" -offline -verification-reports-dir "$reports" 2>&1 \
        | grep '^Plugin com.inazr.yadt' || true)
    echo "${result:-$(basename "$app"): no verdict (see $reports)}"
    [[ "$result" == *"Compatible"* ]] || status=1
done
exit $status
