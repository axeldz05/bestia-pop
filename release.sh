#!/usr/bin/env bash
# BestiaPop — APK firmado + GitHub Release (amigos / sideload + update in-app).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PACKAGE="com.bestiapop.android"
VERSION_FILE="version.properties"
GITHUB_PROPS="github-release.properties"
KEYSTORE_PROPS="keystore.properties"
GOOGLE_SERVICES="app/google-services.json"
APK_GRADLE_OUT="app/build/outputs/apk/release/app-release.apk"
DIST_DIR="dist"

# shellcheck source=scripts/version.sh
source "$ROOT/scripts/version.sh"

USAGE="Usage: $0 [options]
  (default)     bump VERSION_CODE + último dígito de VERSION_NAME, assembleRelease, gh release create
  --no-bump     no tocar version.properties
  --version-name X   fijar VERSION_NAME (igual incrementa VERSION_CODE salvo --no-bump)
  --notes TEXT  cuerpo del release
  --notes-file FILE  notas desde archivo
  --no-upload   buildear APK en dist/; no crear release
  --dry-run     chequear requisitos y mostrar próxima versión; no escribe ni buildea
  -h, --help

Notas (prioridad): --notes-file → --notes → CHANGELOG.release-notes.md (si existe) → plantilla mínima.
Changelog local user-facing: CHANGELOG.pending.md (gitignored; skill bestiapop-release-changelog).
Habitual: preparar CHANGELOG.release-notes.md y correr $0
Después: compartí https://github.com/OWNER/REPO/releases/latest
Requiere: keystore.properties, gh auth login, GITHUB_REPOSITORY en ${GITHUB_PROPS}"

PENDING_RELEASE_NOTES="CHANGELOG.release-notes.md"

DO_BUMP=1
DO_UPLOAD=1
DRY_RUN=0
VERSION_NAME_OVERRIDE=""
NOTES_TEXT=""
NOTES_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-bump) DO_BUMP=0; shift ;;
        --version-name) VERSION_NAME_OVERRIDE="${2:?}"; shift 2 ;;
        --notes) NOTES_TEXT="${2:?}"; shift 2 ;;
        --notes-file) NOTES_FILE="${2:?}"; shift 2 ;;
        --no-upload) DO_UPLOAD=0; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) echo "$USAGE"; exit 0 ;;
        *)
            echo -e "${RED}Opción desconocida: $1${NC}"
            echo "$USAGE"
            exit 1
            ;;
    esac
done

gradle_cmd() {
    if [[ -x ./gradlew ]]; then
        echo ./gradlew
    elif command -v gradle >/dev/null 2>&1; then
        echo gradle
    else
        echo -e "${RED}No hay ./gradlew ni gradle en PATH.${NC}" >&2
        exit 1
    fi
}

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   BestiaPop — GitHub Release (APK)                  ${NC}"
echo -e "${CYAN}====================================================${NC}"

if [[ ! -f "$VERSION_FILE" ]]; then
    echo -e "${RED}Falta ${VERSION_FILE}.${NC}"
    exit 1
fi

CURRENT_CODE="$(read_prop "$VERSION_FILE" VERSION_CODE)"
CURRENT_NAME="$(read_prop "$VERSION_FILE" VERSION_NAME)"
if ! [[ "$CURRENT_CODE" =~ ^[0-9]+$ ]]; then
    echo -e "${RED}VERSION_CODE inválido: ${CURRENT_CODE}${NC}"
    exit 1
fi

if [[ ! -f "$GITHUB_PROPS" ]]; then
    echo -e "${RED}Falta ${GITHUB_PROPS}.${NC}"
    exit 1
fi
GITHUB_REPOSITORY="$(read_prop "$GITHUB_PROPS" GITHUB_REPOSITORY)"
if [[ -z "$GITHUB_REPOSITORY" || ! "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo -e "${RED}GITHUB_REPOSITORY vacío o inválido en ${GITHUB_PROPS} (owner/repo).${NC}"
    exit 1
fi
LATEST_URL="https://github.com/${GITHUB_REPOSITORY}/releases/latest"
echo -e "${GREEN}Repo:${NC} ${GITHUB_REPOSITORY}"

if [[ ! -f "$KEYSTORE_PROPS" ]]; then
    echo -e "${RED}Falta ${KEYSTORE_PROPS} — el APK para amigos tiene que ir firmado con el mismo keystore.${NC}"
    echo -e "${YELLOW}Copiá keystore.properties.example → keystore.properties y generá el .jks.${NC}"
    exit 1
fi

if [[ ! -f "$GOOGLE_SERVICES" ]]; then
    if [[ -f "app/google-services.json.example" ]]; then
        echo -e "${YELLOW}No hay ${GOOGLE_SERVICES} — copiando example (Crashlytics real requiere el JSON de Firebase).${NC}"
        cp app/google-services.json.example "$GOOGLE_SERVICES"
    else
        echo -e "${RED}Missing ${GOOGLE_SERVICES} (and no example).${NC}"
        exit 1
    fi
fi

if [[ "$DO_UPLOAD" -eq 1 ]]; then
    if ! command -v gh >/dev/null 2>&1; then
        echo -e "${RED}No está gh (GitHub CLI). Instalalo o usá --no-upload.${NC}"
        exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo -e "${RED}gh no está autenticado. Corré: gh auth login${NC}"
        exit 1
    fi
    echo -e "${GREEN}gh auth OK${NC}"
fi

if [[ -n "$NOTES_FILE" && ! -f "$NOTES_FILE" ]]; then
    echo -e "${RED}--notes-file no existe: ${NOTES_FILE}${NC}"
    exit 1
fi

NEXT_CODE="$CURRENT_CODE"
NEXT_NAME="$CURRENT_NAME"
if [[ "$DO_BUMP" -eq 1 ]]; then
    NEXT_CODE=$((CURRENT_CODE + 1))
    if [[ -n "$VERSION_NAME_OVERRIDE" ]]; then
        NEXT_NAME="$VERSION_NAME_OVERRIDE"
    else
        NEXT_NAME="$(bump_last_numeric "$CURRENT_NAME")"
    fi
elif [[ -n "$VERSION_NAME_OVERRIDE" ]]; then
    NEXT_NAME="$VERSION_NAME_OVERRIDE"
fi

echo -e "${CYAN}Versión actual:${NC} ${CURRENT_NAME} (${CURRENT_CODE})"
echo -e "${CYAN}Versión release:${NC} ${NEXT_NAME} (${NEXT_CODE})"

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo -e "\n${GREEN}Dry-run OK. No se escribió ni compiló nada.${NC}"
    echo "Tag: v${NEXT_NAME}"
    echo "URL: ${LATEST_URL}"
    echo "Para publicar: $0"
    exit 0
fi

if [[ "$DO_BUMP" -eq 1 || -n "$VERSION_NAME_OVERRIDE" ]]; then
    write_version_file "$NEXT_CODE" "$NEXT_NAME"
    echo -e "${GREEN}Actualizado ${VERSION_FILE}${NC}  ${NEXT_NAME} (${NEXT_CODE})"
fi

GRADLE="$(gradle_cmd)"
echo -e "\n${YELLOW}assembleRelease (APK firmado)…${NC}"
"$GRADLE" assembleRelease

if [[ ! -f "$APK_GRADLE_OUT" ]]; then
    echo -e "${RED}No se generó ${APK_GRADLE_OUT}${NC}"
    exit 1
fi

mkdir -p "$DIST_DIR"
APK_DIST="${DIST_DIR}/BestiaPop-${NEXT_NAME}.apk"
cp "$APK_GRADLE_OUT" "$APK_DIST"
echo -e "${GREEN}APK:${NC} ${APK_DIST}"

if [[ "$DO_UPLOAD" -eq 0 ]]; then
    echo -e "\n${YELLOW}--no-upload: release no creado.${NC}"
    echo "Link cuando publiques: ${LATEST_URL}"
    exit 0
fi

TAG="v${NEXT_NAME}"
NOTES_TMP=""
cleanup_notes() {
    [[ -n "$NOTES_TMP" && -f "$NOTES_TMP" ]] && rm -f "$NOTES_TMP"
}
trap cleanup_notes EXIT

# El update in-app lee `versionCode: N` del body del release (no hace falta latest.json).
ensure_version_code_in_notes() {
    local path="$1"
    if grep -Eiq '^[[:space:]]*versionCode[[:space:]]*:[[:space:]]*[0-9]+[[:space:]]*$' "$path"; then
        return 0
    fi
    printf '\nversionCode: %s\n' "$NEXT_CODE" >> "$path"
}

if [[ -n "$NOTES_FILE" ]]; then
    NOTES_TMP="$(mktemp)"
    cat "$NOTES_FILE" > "$NOTES_TMP"
    ensure_version_code_in_notes "$NOTES_TMP"
    RELEASE_NOTES_PATH="$NOTES_TMP"
elif [[ -n "$NOTES_TEXT" ]]; then
    NOTES_TMP="$(mktemp)"
    printf '%s\n' "$NOTES_TEXT" > "$NOTES_TMP"
    ensure_version_code_in_notes "$NOTES_TMP"
    RELEASE_NOTES_PATH="$NOTES_TMP"
elif [[ -f "$PENDING_RELEASE_NOTES" ]]; then
    NOTES_TMP="$(mktemp)"
    cat "$PENDING_RELEASE_NOTES" > "$NOTES_TMP"
    ensure_version_code_in_notes "$NOTES_TMP"
    RELEASE_NOTES_PATH="$NOTES_TMP"
    echo -e "${GREEN}Notas:${NC} ${PENDING_RELEASE_NOTES}"
else
    NOTES_TMP="$(mktemp)"
    cat > "$NOTES_TMP" <<EOF
BestiaPop ${NEXT_NAME}

versionCode: ${NEXT_CODE}
EOF
    RELEASE_NOTES_PATH="$NOTES_TMP"
    echo -e "${YELLOW}Sin ${PENDING_RELEASE_NOTES} ni --notes: plantilla mínima. Preferí el skill bestiapop-release-changelog.${NC}"
fi

echo -e "\n${YELLOW}Creando GitHub Release ${TAG}…${NC}"
gh release create "$TAG" \
    --repo "$GITHUB_REPOSITORY" \
    --title "BestiaPop ${NEXT_NAME}" \
    --notes-file "$RELEASE_NOTES_PATH" \
    "$APK_DIST"

echo -e "\n${GREEN}Release publicado.${NC}"