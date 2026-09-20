#!/usr/bin/env bash
set -euo pipefail
root="${MVS_ROOT:-$HOME/mvs-demo}"
mkdir -p "$root/downloads" "$root/evidence"
sudo -n apt-get update
sudo -n apt-get install -y build-essential autoconf automake flex m4 \
  cmake gawk libtool-bin libltdl-dev libregina3-dev zlib1g-dev libbz2-dev \
  curl unzip iproute2 git ripgrep python3 util-linux
if [[ ! -f "$root/downloads/mvs-tk5.zip" ]]; then
  curl -fL --retry 3 https://www.prince-webdesign.nl/images/downloads/mvs-tk5.zip \
    -o "$root/downloads/mvs-tk5.zip"
fi
printf '%s  %s\n' \
  710d002843631322810a276dd42c793fda458548dc64d86e2914a62db7425f84 \
  "$root/downloads/mvs-tk5.zip" | sha256sum -c -
if [[ ! -d "$root/mvs-tk5" ]]; then
  unzip -q "$root/downloads/mvs-tk5.zip" -d "$root"
fi
if [[ ! -d "$root/hyperion" ]]; then
  git clone --branch Release_4.9.1 \
    https://github.com/SDL-Hercules-390/hyperion.git "$root/hyperion"
fi
test "$(git -C "$root/hyperion" rev-parse HEAD)" = \
  ee86c4de066fcdb2ec898a157ff4c687c5f01ce2
if [[ ! -x "$root/hercules-install/bin/hercules" ]]; then
  (
    cd "$root/hyperion"
    ./autogen.sh
    ./configure --prefix="$root/hercules-install"
    make -j6
    make install
  ) >"$root/evidence/setup-build.log" 2>&1
fi
"$root/hercules-install/bin/hercules" --version
