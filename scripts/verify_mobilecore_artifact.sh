#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly AAR="${1:-$ROOT/app/libs/mobilecore.aar}"
readonly EXPECTED_OLCRTC_VERSION="v0.0.0-20261023184934-6c55df536574"
readonly EXPECTED_OLCRTC_REPLACE="github.com/Oleglog/Olcrtc_manager"
readonly EXPECTED_J_VERSION="v0.0.0-20260813164759-98b35e399132"
readonly -a REQUIRED_LIBRARIES=(
  "jni/arm64-v8a/libgojni.so"
  "jni/armeabi-v7a/libgojni.so"
)

if [[ ! -s "$AAR" ]]; then
  printf 'mobilecore AAR is missing or empty: %s\n' "$AAR" >&2
  exit 1
fi

mapfile -t libraries < <(unzip -Z1 "$AAR" | grep -E '^jni/[^/]+/libgojni\.so$' | sort)
if ((${#libraries[@]} == 0)); then
  printf 'mobilecore AAR contains no libgojni.so libraries: %s\n' "$AAR" >&2
  exit 1
fi

for required in "${REQUIRED_LIBRARIES[@]}"; do
  if ! printf '%s\n' "${libraries[@]}" | grep -Fx "$required" >/dev/null; then
    printf 'mobilecore AAR is missing required library: %s\n' "$required" >&2
    exit 1
  fi
done

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TEMP_DIR:?}"' EXIT

for library in "${libraries[@]}"; do
  abi="${library#jni/}"
  abi="${abi%%/*}"
  binary="$TEMP_DIR/$abi-libgojni.so"
  metadata="$TEMP_DIR/$abi-buildinfo.txt"

  unzip -p "$AAR" "$library" >"$binary"
  go version -m "$binary" | tee "$metadata"

  if ! awk -v expected="$EXPECTED_OLCRTC_VERSION" \
    '$1 == "dep" && $2 == "github.com/openlibrecommunity/olcrtc" && $3 == expected { found = 1 } END { exit !found }' \
    "$metadata"; then
    printf 'mobilecore %s does not contain the required olcRTC version %s\n' \
      "$abi" "$EXPECTED_OLCRTC_VERSION" >&2
    exit 1
  fi
  # The olcRTC module resolves to the Oleglog/Olcrtc_manager fork via a
  # go.mod replace (fork-only UDP relay commits do not exist upstream).
  # awk splits on any whitespace, so tabs vs spaces in `go version -m`
  # output do not matter here.
  if ! awk -v path="$EXPECTED_OLCRTC_REPLACE" -v expected="$EXPECTED_OLCRTC_VERSION" \
    '$1 == "=>" && $2 == path && $3 == expected { found = 1 } END { exit !found }' \
    "$metadata"; then
    printf 'mobilecore %s does not resolve olcRTC %s to the pinned fork\n' \
      "$abi" "$EXPECTED_OLCRTC_VERSION" >&2
    exit 1
  fi
  if ! awk -v expected="$EXPECTED_J_VERSION" \
    '$1 == "=>" && $2 == "github.com/Oleglog/j" && $3 == expected { found = 1 } END { exit !found }' \
    "$metadata"; then
    printf 'mobilecore %s does not contain the required Oleglog/j version %s\n' \
      "$abi" "$EXPECTED_J_VERSION" >&2
    exit 1
  fi
done

printf 'Verified mobilecore AAR for %d ABIs with olcRTC %s\n' \
  "${#libraries[@]}" "$EXPECTED_OLCRTC_VERSION"
