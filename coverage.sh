#!/usr/bin/env bash
set -euo pipefail

# Colored Output
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

USAGE="Usage: $0 [--unit|--android|--all]
  --unit     (default) unit tests + JaCoCo HTML report
  --android  instrumented tests on a connected device + report
  --all      unit + instrumented, then unified createCoverageReport

Set COVERAGE_OPEN=1 to open the generated report in a desktop session."

MODE="unit"
case "${1:-}" in
  --unit|-u|"") MODE="unit" ;;
  --android|-a) MODE="android" ;;
  --all) MODE="all" ;;
  -h|--help)
    echo "$USAGE"
    exit 0
    ;;
  *)
    echo -e "${RED}Unknown option: $1${NC}"
    echo "$USAGE"
    exit 1
    ;;
esac

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo -e "${RED}No ./gradlew ni gradle en PATH.${NC}" >&2
  exit 1
fi

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   Bestia Pop - JaCoCo coverage (${MODE})           ${NC}"
echo -e "${CYAN}====================================================${NC}"

UNIT_HTML="app/build/reports/coverage/test/debug/index.html"
ANDROID_HTML="app/build/reports/coverage/androidTest/debug/connected/index.html"
COMBINED_HTML="app/build/reports/coverage/debug/index.html"
# AGP 9.3+ experimental aggregation layout
COMBINED_HTML_AGP="app/build/reports/code_coverage_html_report/global/index.html"

case "$MODE" in
  unit)
    "${GRADLE[@]}" :app:createDebugUnitTestCoverageReport
    REPORT="$UNIT_HTML"
    ;;
  android)
    if ! adb get-state >/dev/null 2>&1; then
      echo -e "${RED}No hay dispositivo/emulador adb conectado.${NC}" >&2
      exit 1
    fi
    "${GRADLE[@]}" :app:createDebugAndroidTestCoverageReport
    REPORT="$ANDROID_HTML"
    ;;
  all)
    if ! adb get-state >/dev/null 2>&1; then
      echo -e "${RED}--all necesita dispositivo/emulador adb (androidTest).${NC}" >&2
      exit 1
    fi
    "${GRADLE[@]}" :app:createCoverageReport
    REPORT="$COMBINED_HTML"
    # Fallback paths if AGP lays out the unified report differently.
    if [[ ! -f "$REPORT" ]]; then
      if [[ -f "$COMBINED_HTML_AGP" ]]; then REPORT="$COMBINED_HTML_AGP"; fi
    fi
    ;;
esac

if [[ -f "$REPORT" ]]; then
  ABS="$(cd "$(dirname "$REPORT")" && pwd)/$(basename "$REPORT")"
  echo -e "${GREEN}Report: ${ABS}${NC}"
  if [[ "${COVERAGE_OPEN:-0}" == "1" ]] && command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$ABS" >/dev/null 2>&1 || true
  fi
else
  echo -e "${RED}Build finished but the requested HTML report was not generated.${NC}" >&2
  echo -e "${YELLOW}Expected: ${REPORT}${NC}" >&2
  exit 1
fi
