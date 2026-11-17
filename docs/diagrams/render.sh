#!/bin/sh
# Render the architecture diagram sources in this directory to PNG and SVG.
# Needs Graphviz (`dot`): apt-get install graphviz fonts-dejavu / brew install graphviz
set -e
cd "$(dirname "$0")"
for f in *.dot; do
    dot -Tpng -Gdpi=110 "$f" -o "${f%.dot}.png"
    dot -Tsvg "$f" -o "${f%.dot}.svg"
done
