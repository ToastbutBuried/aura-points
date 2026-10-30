#!/bin/bash
# Publishes an Aura Points jar: GitHub Release vX.Y.Z with the jar, then latest.json pointing at it.
# Usage: ./publish.sh 4.43.0 "release notes"
set -e
V="$1"; NOTES="${2:-Aura Points $1}"
cd "$(dirname "$0")"
JAR=/home/toast/Downloads/aura-points-neoforge/build/libs/aura-points-$V.jar
[ -f "$JAR" ] || { echo "no jar $JAR"; exit 1; }
~/.local/bin/gh release create "v$V" "$JAR" --title "Aura Points $V" --notes "$NOTES"
SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
cat > latest.json <<JSON
{
  "version": "$V",
  "file": "aura-points-$V.jar",
  "url": "https://github.com/ToastbutBuried/aura-points/releases/download/v$V/aura-points-$V.jar",
  "sha256": "$SHA"$( [ -f requires.json ] && printf ',\n  "requires": %s' "$(cat requires.json)" )
}
JSON
git add latest.json
git commit -q -m "Aura Points $V

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -q
echo "published $V ($SHA)"
