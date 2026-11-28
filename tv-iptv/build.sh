#!/usr/bin/env bash
# Sestaveni a instalace balicku .wgt na Samsung TV v rezimu vyvojare.
#
#   ./build.sh                 jen sestavi balicek do .buildResult/
#   TV_IP=192.168.1.50 ./build.sh install
#
# Promenne:
#   PROFILE   nazev bezpecnostniho profilu z Certificate Manageru (vychozi: samsung)
#   TV_IP     IP adresa televize v domaci siti
#   TIZEN     cesta k prikazu tizen, pokud neni v PATH
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${PROFILE:-samsung}"
TIZEN="${TIZEN:-tizen}"
SDB="${SDB:-sdb}"
OUT="$HERE/.buildResult"

have() { command -v "$1" >/dev/null 2>&1; }

if ! have "$TIZEN"; then
  cat >&2 <<'MSG'
Prikaz "tizen" nenalezen.

Nainstalujte Tizen Studio (https://developer.tizen.org/development/tizen-studio/download)
vcetne "Samsung Certificate Extension" a "TV Extensions-5.0", pak pridejte do PATH:

  export PATH="$HOME/tizen-studio/tools/ide/bin:$HOME/tizen-studio/tools:$PATH"
MSG
  exit 1
fi

echo "==> kontrola projektu"
test -f "$HERE/config.xml" || { echo "chybi config.xml" >&2; exit 1; }
test -f "$HERE/index.html" || { echo "chybi index.html" >&2; exit 1; }

echo "==> build-web"
rm -rf "$OUT"
"$TIZEN" build-web -out .buildResult \
  -e "dev/*" -e "*.sh" -e "*.md" -e ".git*" -e ".buildResult/*" \
  -- "$HERE"

echo "==> package (profil: $PROFILE)"
"$TIZEN" package -t wgt -s "$PROFILE" -- "$OUT"

WGT="$(ls -1 "$OUT"/*.wgt 2>/dev/null | head -1 || true)"
test -n "$WGT" || { echo "balicek se nevytvoril" >&2; exit 1; }
echo "==> hotovo: $WGT"

if [ "${1:-}" != "install" ]; then
  echo
  echo "Instalace na televizi:  TV_IP=<adresa> $0 install"
  exit 0
fi

: "${TV_IP:?nastavte TV_IP, napriklad TV_IP=192.168.1.50 $0 install}"

echo "==> sdb connect $TV_IP:26101"
"$SDB" connect "$TV_IP:26101"
DEVICE="$("$SDB" devices | awk 'NR>1 && NF {print $1; exit}')"
test -n "$DEVICE" || { echo "televize se nepripojila - je zapnuty Developer mode a je TV ve stejne siti?" >&2; exit 1; }
echo "==> zarizeni: $DEVICE"

"$TIZEN" install -n "$(basename "$WGT")" -t "$DEVICE" -- "$OUT"
echo "==> nainstalovano. Aplikace je v nabidce Apps jako \"IPTV Hrac\"."
