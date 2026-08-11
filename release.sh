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
  --dry-run     chequear requisitos, tag y notas resueltas; no escribe ni buildea
  -h, --help

Notas (prioridad): --notes-file → --notes → CHANGELOG.release-notes.md (si existe) → plantilla mínima.
El body nunca queda vacío (lo lee Ajustes → Actualización) y siempre lleva 'versionCode: N'.
Tag v{VERSION_NAME} fijado al commit compilado si ya está en el remoto; se verifica tras publicar.
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

# El update in-app lee `versionCode: N` del body del release (no hace falta latest.json).
ensure_version_code_in_notes() {
    local path="$1"
    if grep -Eiq '^[[:space:]]*versionCode[[:space:]]*:[[:space:]]*[0-9]+[[:space:]]*$' "$path"; then
        return 0
    fi
    printf '\nversionCode: %s\n' "$NEXT_CODE" >> "$path"
}

# La pantalla Ajustes → Actualización muestra este body: nunca dejarlo con solo título + versionCode.
ensure_notes_have_body() {
    local path="$1"
    local content
    content="$( { grep -Eiv '^[[:space:]]*versionCode[[:space:]]*:[[:space:]]*[0-9]+[[:space:]]*$' "$path" |
        grep -Fxv "BestiaPop ${NEXT_NAME}" | tr -d '[:space:]'; } || true)"
    if [[ -z "$content" ]]; then
        printf -- '\n- Mantenimiento y mejoras internas\n' >> "$path"
    fi
    return 0
}

# Deja RELEASE_NOTES_PATH + NOTES_SOURCE listos (prioridad: --notes-file → --notes → CHANGELOG.release-notes.md → plantilla).
resolve_release_notes() {
    NOTES_TMP="$(mktemp)"
    if [[ -n "$NOTES_FILE" ]]; then
        cat "$NOTES_FILE" > "$NOTES_TMP"
        NOTES_SOURCE="$NOTES_FILE"
    elif [[ -n "$NOTES_TEXT" ]]; then
        printf '%s\n' "$NOTES_TEXT" > "$NOTES_TMP"
        NOTES_SOURCE="--notes"
    elif [[ -f "$PENDING_RELEASE_NOTES" ]]; then
        cat "$PENDING_RELEASE_NOTES" > "$NOTES_TMP"
        NOTES_SOURCE="$PENDING_RELEASE_NOTES"
    else
        printf 'BestiaPop %s\n' "$NEXT_NAME" > "$NOTES_TMP"
        NOTES_SOURCE="plantilla mínima"
        echo -e "${YELLOW}Sin ${PENDING_RELEASE_NOTES} ni --notes: plantilla mínima. Preferí el skill bestiapop-release-changelog.${NC}"
    fi
    ensure_notes_have_body "$NOTES_TMP"
    ensure_version_code_in_notes "$NOTES_TMP"
    RELEASE_NOTES_PATH="$NOTES_TMP"
}

# Los cuatro invariantes que parsea la app (GitHubReleaseParser / AppReleaseSelection).
verify_published_release() {
    local json_tmp
    json_tmp="$(mktemp)"
    if ! gh release view "$TAG" --repo "$GITHUB_REPOSITORY" \
        --json tagName,isDraft,isPrerelease,body,assets > "$json_tmp" 2>/dev/null; then
        rm -f "$json_tmp"
        echo -e "${RED}No se pudo leer el release ${TAG} para verificarlo.${NC}"
        return 1
    fi
    local result=0
    python3 - "$json_tmp" "$TAG" "$NEXT_CODE" <<'PY' || result=1
import json, re, sys
path, tag, code = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.loads(open(path, encoding="utf-8").read())
body = data.get("body") or ""
assets = [a.get("name", "") for a in data.get("assets") or []]
problems = []
if data.get("tagName") != tag:
    problems.append(f"tag publicado {data.get('tagName')!r} != {tag!r}")
if data.get("isDraft"):
    problems.append("el release quedó como draft (la app lo ignora)")
if data.get("isPrerelease"):
    problems.append("el release quedó como prerelease (la app lo ignora)")
if not re.search(rf"(?im)^\s*versionCode\s*:\s*{code}\s*$", body):
    problems.append(f"falta 'versionCode: {code}' en las notas")
if not any(re.fullmatch(r"BestiaPop.*\.apk", n, re.I) for n in assets):
    problems.append(f"no hay asset BestiaPop*.apk (assets: {assets})")
for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PY
    rm -f "$json_tmp"
    return "$result"
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

# El update in-app matchea la versión instalada por `versionCode: N` y, si falta, por tag v{VERSION_NAME}.
if ! [[ "$NEXT_NAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo -e "${RED}VERSION_NAME inválido para un tag: ${NEXT_NAME} (solo A-Z a-z 0-9 . _ -).${NC}"
    exit 1
fi
TAG="v${NEXT_NAME}"

RELEASE_TARGET=""
if [[ "$DO_UPLOAD" -eq 1 ]]; then
    if gh release view "$TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
        echo -e "${RED}Ya existe el release ${TAG} en ${GITHUB_REPOSITORY}. Bumpeá la versión o borralo.${NC}"
        exit 1
    fi
    # Sin --target el tag cae en el HEAD de la rama por defecto, que puede no ser lo compilado.
    HEAD_SHA="$(git rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "$HEAD_SHA" ]] && gh api "repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}" >/dev/null 2>&1; then
        RELEASE_TARGET="$HEAD_SHA"
    elif [[ -n "$HEAD_SHA" ]]; then
        echo -e "${YELLOW}El commit local ${HEAD_SHA:0:7} no está en el remoto: el tag ${TAG} va a apuntar a la rama por defecto.${NC}"
    fi
fi

NOTES_TMP=""
cleanup_notes() {
    [[ -n "$NOTES_TMP" && -f "$NOTES_TMP" ]] && rm -f "$NOTES_TMP"
}
trap cleanup_notes EXIT
resolve_release_notes
echo -e "${GREEN}Notas:${NC} ${NOTES_SOURCE}"

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo -e "\n${GREEN}Dry-run OK. No se escribió ni compiló nada.${NC}"
    echo "Tag: ${TAG}"
    echo "Commit: ${RELEASE_TARGET:-rama por defecto}"
    echo "URL: ${LATEST_URL}"
    echo -e "\n${CYAN}--- notas ---${NC}"
    cat "$RELEASE_NOTES_PATH"
    echo -e "${CYAN}-------------${NC}"
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

echo -e "\n${YELLOW}Creando GitHub Release ${TAG}…${NC}"
CREATE_ARGS=(
    "$TAG"
    --repo "$GITHUB_REPOSITORY"
    --title "BestiaPop ${NEXT_NAME}"
    --notes-file "$RELEASE_NOTES_PATH"
    --latest
)
if [[ -n "$RELEASE_TARGET" ]]; then
    CREATE_ARGS+=(--target "$RELEASE_TARGET")
fi
gh release create "${CREATE_ARGS[@]}" "$APK_DIST"

echo -e "\n${YELLOW}Verificando lo que va a leer la app…${NC}"
if verify_published_release; then
    echo -e "${GREEN}Release publicado y fetcheable.${NC} ${LATEST_URL}"
else
    echo -e "${RED}El release ${TAG} se publicó pero no cumple lo que espera el update in-app (ver arriba).${NC}"
    exit 1
fi