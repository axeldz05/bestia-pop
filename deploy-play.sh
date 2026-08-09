#!/usr/bin/env bash
# BestiaPop — AAB firmado listo para Google Play Console.
# Incrementa version.properties, valida requisitos de Play y corre bundleRelease.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PACKAGE="com.bestiapop.android"
OPT_IN_URL="https://play.google.com/apps/testing/${PACKAGE}"
PLAY_ICON="play/icon.png"
PLAY_ICON_PX=512
VERSION_FILE="version.properties"
KEYSTORE_PROPS="keystore.properties"
GOOGLE_SERVICES="app/google-services.json"
PLAY_SA="${PLAY_SERVICE_ACCOUNT_JSON:-play-service-account.json}"
AAB_GRADLE_OUT="app/build/outputs/bundle/release/app-release.aab"
MIN_TARGET_SDK=36
MIN_COMPILE_SDK=36

USAGE="Usage: $0 [options]
  (default)     bump VERSION_CODE + último dígito de VERSION_NAME, bundleRelease → dist/
  --no-bump     no tocar version.properties (reintento / mismo code)
  --version-name X   fijar VERSION_NAME (igual incrementa VERSION_CODE salvo --no-bump)
  --track TRACK alpha (default, closed testing) | internal | beta | production
  --upload      subir el AAB a Play (draft). Requiere ${PLAY_SA}
  --rollout     con --upload, status completed (testers reciben el update).
                Si la app está Draft en Console, Play obliga draft: el script hace fallback.
  --aab FILE    usar un AAB ya generado (salta bump + build)
  --dry-run     chequear requisitos y mostrar próxima versión; no escribe ni buildea
  -h, --help

Habitual (closed testing): $0 --upload --rollout
Opt-in testers: ${OPT_IN_URL}
Icono launcher + listing: ${PLAY_ICON} (PNG ${PLAY_ICON_PX}×${PLAY_ICON_PX}, reemplazá el placeholder)

Play exige AAB (no APK), targetSdk ${MIN_TARGET_SDK}+ (desde 2026-08-31), firma de upload
(no debug), versionCode estrictamente mayor al publicado, y 16 KB page-size (AGP 8.5.1+).

Primera vez en Console: creá la app con applicationId ${PACKAGE}, activá Play App Signing
y registrá el mismo upload keystore que keystore.properties. Closed testing (alpha)
Published + testers (emails o cualquiera con el link). Data safety / política de
privacidad / FGS mediaPlayback se declaran en la ficha, no en el AAB.

Service account para --upload (el JSON NO se arma en Play Console):
  1. Google Cloud → APIs → Enable 'Google Play Android Developer API'
     https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com
  2. IAM → Service Accounts → Create → Keys → Add key → JSON
     https://console.cloud.google.com/iam-admin/serviceaccounts
  3. Play Console → Users and permissions → Invite users
     (email ...@....iam.gserviceaccount.com, permiso Release / Admin)
  4. Guardá el JSON descargado como ${PLAY_SA} (gitignored).
  Forma de campos: play-service-account.json.example"

TRACK="alpha"
DO_UPLOAD=0
ROLLOUT=0
DO_BUMP=1
DRY_RUN=0
VERSION_NAME_OVERRIDE=""
EXISTING_AAB=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-bump) DO_BUMP=0; shift ;;
        --version-name) VERSION_NAME_OVERRIDE="${2:?}"; shift 2 ;;
        --track) TRACK="${2:?}"; shift 2 ;;
        --upload) DO_UPLOAD=1; shift ;;
        --rollout) ROLLOUT=1; shift ;;
        --aab) EXISTING_AAB="${2:?}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) echo "$USAGE"; exit 0 ;;
        *)
            echo -e "${RED}Opción desconocida: $1${NC}"
            echo "$USAGE"
            exit 1
            ;;
    esac
done

case "$TRACK" in
    internal|alpha|beta|production) ;;
    *)
        echo -e "${RED}Track inválido: $TRACK (internal|alpha|beta|production)${NC}"
        exit 1
        ;;
esac

read_prop() {
    local file="$1" key="$2"
    python3 - "$file" "$key" <<'PY'
import sys
from pathlib import Path
path, key = sys.argv[1], sys.argv[2]
for raw in Path(path).read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, _, v = line.partition("=")
    if k.strip() == key:
        print(v.strip())
        raise SystemExit(0)
raise SystemExit(f"missing {key} in {path}")
PY
}

write_version_file() {
    local code="$1" name="$2"
    cat > "$VERSION_FILE" <<EOF
# Fuente de verdad de versión (Play Console + install.sh --release).
# ./deploy-play.sh incrementa VERSION_CODE y el último número de VERSION_NAME.
VERSION_CODE=${code}
VERSION_NAME=${name}
EOF
}

bump_last_numeric() {
    python3 -c '
import re, sys
name = sys.argv[1]
matches = list(re.finditer(r"\d+", name))
if not matches:
    raise SystemExit(f"versionName {name!r} no tiene dígitos para incrementar")
last = matches[-1]
print(f"{name[:last.start()]}{int(last.group()) + 1}{name[last.end():]}")
' "$1"
}

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

# Valida play/icon.png (Play hi-res = 512 PNG). Con apply=1 copia a mipmap + foreground.
sync_play_icon() {
    local apply="${1:-0}"
    python3 - "$PLAY_ICON" "$PLAY_ICON_PX" "$apply" <<'PY'
import sys
from pathlib import Path

src = Path(sys.argv[1])
need = int(sys.argv[2])
apply = sys.argv[3] == "1"
if not src.is_file():
    raise SystemExit(f"Falta {src}. Poné un PNG {need}x{need} (placeholder o el ícono final).")
try:
    from PIL import Image
except ImportError as e:
    raise SystemExit("Hace falta Pillow (pip install Pillow) o generá los mipmap a mano.") from e

im = Image.open(src)
w, h = im.size
fmt = (im.format or "").upper()
if fmt != "PNG":
    raise SystemExit(f"{src} debe ser PNG (es {im.format or 'desconocido'}).")
if w != need or h != need:
    raise SystemExit(f"{src} debe ser {need}x{need} (Play hi-res icon). Ahora es {w}x{h}.")
nbytes = src.stat().st_size
if nbytes > 1024 * 1024:
    raise SystemExit(f"{src} supera 1 MB ({nbytes} bytes). Play rechaza el ícono hi-res.")
print(f"{src} OK  {w}x{h} PNG  {nbytes} bytes")
if not apply:
    raise SystemExit(0)

rgba = im.convert("RGBA")
densities = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
root = Path("app/src/main/res")
(root / "drawable").mkdir(parents=True, exist_ok=True)
rgba.save(root / "drawable" / "ic_launcher_foreground.png", "PNG", optimize=True)
for dens, px in densities.items():
    folder = root / f"mipmap-{dens}"
    folder.mkdir(parents=True, exist_ok=True)
    scaled = rgba.resize((px, px), Image.Resampling.LANCZOS)
    scaled.save(folder / "ic_launcher.png", "PNG", optimize=True)
    scaled.save(folder / "ic_launcher_round.png", "PNG", optimize=True)
print("launcher mipmaps + foreground actualizados desde", src)
PY
}

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   BestiaPop — Play Console AAB                      ${NC}"
echo -e "${CYAN}====================================================${NC}"

# --- Requisitos (bloquean upload / firma inválida) ---
echo -e "\n${YELLOW}Chequeando requisitos de Play…${NC}"

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

TARGET_SDK="$(grep -E '^\s*targetSdk\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+' || true)"
COMPILE_SDK="$(grep -E '^\s*compileSdk\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+' || true)"
if [[ -z "$TARGET_SDK" || "$TARGET_SDK" -lt "$MIN_TARGET_SDK" ]]; then
    echo -e "${RED}targetSdk=${TARGET_SDK:-?} — Play exige ${MIN_TARGET_SDK}+ desde 2026-08-31.${NC}"
    exit 1
fi
if [[ -z "$COMPILE_SDK" || "$COMPILE_SDK" -lt "$MIN_COMPILE_SDK" ]]; then
    echo -e "${RED}compileSdk=${COMPILE_SDK:-?} — debe ser ≥ ${MIN_COMPILE_SDK}.${NC}"
    exit 1
fi
echo -e "${GREEN}SDK OK${NC}  compileSdk=${COMPILE_SDK}  targetSdk=${TARGET_SDK}  minSdk=26"

if [[ ! -f "$KEYSTORE_PROPS" ]]; then
    echo -e "${RED}Falta ${KEYSTORE_PROPS}. Play no acepta firma debug.${NC}"
    echo -e "${YELLOW}Copiá keystore.properties.example → keystore.properties y generá el .jks de upload.${NC}"
    exit 1
fi
STORE_FILE="$(read_prop "$KEYSTORE_PROPS" storeFile)"
KEY_ALIAS="$(read_prop "$KEYSTORE_PROPS" keyAlias)"
if [[ -z "$STORE_FILE" || ! -f "$STORE_FILE" ]]; then
    echo -e "${RED}storeFile no existe: ${STORE_FILE:-?}${NC}"
    exit 1
fi
echo -e "${GREEN}Keystore OK${NC}  ${STORE_FILE}  alias=${KEY_ALIAS}"

if [[ ! -f "$GOOGLE_SERVICES" ]]; then
    echo -e "${RED}Falta ${GOOGLE_SERVICES} (Firebase Console, no un placeholder).${NC}"
    exit 1
fi
GS_PROJECT="$(python3 - "$GOOGLE_SERVICES" "$PACKAGE" <<'PY'
import json, sys
path, expected = sys.argv[1], sys.argv[2]
data = json.loads(open(path, encoding="utf-8").read())
project = str(data.get("project_info", {}).get("project_id", ""))
pkgs = [
    c.get("client_info", {}).get("android_client_info", {}).get("package_name")
    for c in data.get("client", [])
]
if expected not in pkgs:
    raise SystemExit(f"{path} no declara package_name {expected} (tiene {pkgs})")
placeholder = any(tok in project.lower() for tok in ("example", "your-project", "changeme", "demo-project"))
if placeholder or not project:
    raise SystemExit(f"{path} parece placeholder (project_id={project!r}). Usá el JSON real de Firebase.")
print(project)
PY
)"
echo -e "${GREEN}google-services.json OK${NC}  project=${GS_PROJECT}"

echo -e "\n${YELLOW}Icono Play (${PLAY_ICON})…${NC}"
ICON_MSG="$(sync_play_icon 0)"
echo -e "${GREEN}${ICON_MSG}${NC}"

if grep -Eq 'isDebuggable\s*=\s*true' app/build.gradle.kts; then
    echo -e "${RED}release no puede ser debuggable=true.${NC}"
    exit 1
fi

if [[ "$DO_UPLOAD" -eq 1 && ! -f "$PLAY_SA" ]]; then
    echo -e "${RED}--upload requiere ${PLAY_SA} (o PLAY_SERVICE_ACCOUNT_JSON).${NC}"
    exit 1
fi

NEXT_CODE="$CURRENT_CODE"
NEXT_NAME="$CURRENT_NAME"
if [[ "$DO_BUMP" -eq 1 && -z "$EXISTING_AAB" ]]; then
    NEXT_CODE=$((CURRENT_CODE + 1))
    if [[ -n "$VERSION_NAME_OVERRIDE" ]]; then
        NEXT_NAME="$VERSION_NAME_OVERRIDE"
    else
        NEXT_NAME="$(bump_last_numeric "$CURRENT_NAME")"
    fi
elif [[ -n "$VERSION_NAME_OVERRIDE" && -z "$EXISTING_AAB" ]]; then
    NEXT_NAME="$VERSION_NAME_OVERRIDE"
fi

echo -e "${CYAN}Versión actual:${NC} ${CURRENT_NAME} (${CURRENT_CODE})"
echo -e "${CYAN}Versión AAB:   ${NC} ${NEXT_NAME} (${NEXT_CODE})"

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo -e "\n${GREEN}Dry-run OK. No se escribió ni compiló nada.${NC}"
    echo "Para generar el AAB: $0"
    echo "Closed testing (habitual): $0 --upload --rollout"
    echo "Opt-in: ${OPT_IN_URL}"
    [[ "$DO_UPLOAD" -eq 1 ]] && echo "Upload quedaría en track=${TRACK} status=$([ "$ROLLOUT" -eq 1 ] && echo completed || echo draft)"
    exit 0
fi

if [[ -n "$EXISTING_AAB" ]]; then
    if [[ ! -f "$EXISTING_AAB" ]]; then
        echo -e "${RED}AAB no encontrado: ${EXISTING_AAB}${NC}"
        exit 1
    fi
    AAB_PATH="$EXISTING_AAB"
    echo -e "${YELLOW}Usando AAB existente (sin bump/build).${NC}"
else
    if [[ "$DO_BUMP" -eq 1 || -n "$VERSION_NAME_OVERRIDE" ]]; then
        write_version_file "$NEXT_CODE" "$NEXT_NAME"
        echo -e "${GREEN}Actualizado ${VERSION_FILE}${NC}  ${NEXT_NAME} (${NEXT_CODE})"
    fi

    echo -e "\n${YELLOW}Aplicando ${PLAY_ICON} → mipmap/ic_launcher…${NC}"
    sync_play_icon 1

    GRADLE="$(gradle_cmd)"
    echo -e "\n${YELLOW}bundleRelease (AAB firmado, no APK)…${NC}"
    "$GRADLE" bundleRelease

    if [[ ! -f "$AAB_GRADLE_OUT" ]]; then
        echo -e "${RED}No se generó ${AAB_GRADLE_OUT}${NC}"
        exit 1
    fi
    AAB_PATH="$AAB_GRADLE_OUT"
fi

# --- Validar el artefacto ---
echo -e "\n${YELLOW}Validando AAB…${NC}"

if ! jarsigner -verify "$AAB_PATH" >/dev/null; then
    echo -e "${RED}jarsigner: el AAB no está firmado correctamente.${NC}"
    exit 1
fi
CERT_OUT="$(keytool -printcert -jarfile "$AAB_PATH" 2>/dev/null || true)"
if echo "$CERT_OUT" | grep -qi 'CN=Android Debug'; then
    echo -e "${RED}El AAB está firmado con el keystore debug. Play lo rechaza.${NC}"
    exit 1
fi
echo -e "${GREEN}Firma OK${NC} (no es Android Debug)"

MANIFEST="$(find app/build/intermediates -path '*release*' -name AndroidManifest.xml -print -quit 2>/dev/null || true)"
if [[ -n "$MANIFEST" && -f "$MANIFEST" ]]; then
    python3 - "$MANIFEST" "$NEXT_CODE" "$MIN_TARGET_SDK" <<'PY'
import re, sys
from pathlib import Path
path, code, min_target = sys.argv[1:4]
text = Path(path).read_text(encoding="utf-8", errors="replace")

def attr(key):
    m = re.search(rf'{re.escape(key)}="([^"]+)"', text)
    return m.group(1) if m else ""

got_code = attr("android:versionCode")
got_target = attr("android:targetSdkVersion")
debuggable = attr("android:debuggable")
errors = []
if got_code and got_code != str(code):
    errors.append(f"versionCode manifest={got_code} expected={code}")
if got_target and int(got_target) < int(min_target):
    errors.append(f"targetSdk={got_target} < {min_target}")
if debuggable.lower() == "true":
    errors.append("android:debuggable=true")
if errors:
    raise SystemExit("; ".join(errors))
print(f"manifest OK  versionCode={got_code or code}  targetSdk={got_target or '?'}  debuggable={debuggable or 'false'}")
PY
else
    echo -e "${YELLOW}No hay merged manifest release (¿--aab sin build local?). Se omite chequeo XML.${NC}"
fi

# Bundle válido + 64-bit + ELF LOAD align ≥ 16 KB (Play desde 2025-11-01)
python3 - "$AAB_PATH" <<'PY'
import struct, sys, zipfile
from pathlib import Path

def load_aligns(so: bytes) -> list[int]:
    if so[:4] != b"\x7fELF":
        return []
    cls = so[4]
    aligns: list[int] = []
    if cls == 2:
        phoff = struct.unpack_from("<Q", so, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", so, 54)
        for i in range(phnum):
            off = phoff + i * phentsize
            if struct.unpack_from("<I", so, off)[0] == 1:
                aligns.append(struct.unpack_from("<Q", so, off + 48)[0])
    elif cls == 1:
        phoff = struct.unpack_from("<I", so, 28)[0]
        phentsize, phnum = struct.unpack_from("<HH", so, 42)
        for i in range(phnum):
            off = phoff + i * phentsize
            if struct.unpack_from("<I", so, off)[0] == 1:
                aligns.append(struct.unpack_from("<I", so, off + 28)[0])
    return aligns

aab = Path(sys.argv[1])
abis: set[str] = set()
so_count = 0
bad_16k: list[str] = []
with zipfile.ZipFile(aab) as zf:
    names = zf.namelist()
    if "BundleConfig.pb" not in names:
        raise SystemExit("no parece un Android App Bundle (falta BundleConfig.pb)")
    mapping = [n for n in names if n.endswith("proguard.map") or "obfuscation" in n]
    native_syms = [n for n in names if "debugsymbols" in n.lower() or n.endswith(".so.sym")]
    print(
        "Play metadata: "
        f"R8 mapping={'sí' if mapping else 'NO'}  "
        f"native symbols={'sí' if native_syms else 'NO (libs de AAR ya stripped?)'}"
    )
    for name in names:
        if not name.endswith(".so"):
            continue
        so_count += 1
        parts = name.split("/")
        for i, p in enumerate(parts):
            if p == "lib" and i + 1 < len(parts):
                abis.add(parts[i + 1])
                break
        aligns = load_aligns(zf.read(name))
        if not aligns or min(aligns) < 16384:
            bad_16k.append(f"{name} align={aligns}")
if so_count == 0:
    print("sin .so nativos — 16 KB / 64-bit N/A")
else:
    print(f"native libs: {so_count}  abis={sorted(abis)}")
    if "armeabi-v7a" in abis and "arm64-v8a" not in abis:
        raise SystemExit("Play exige 64-bit: hay armeabi-v7a sin arm64-v8a")
    if "x86" in abis and "x86_64" not in abis:
        raise SystemExit("Play exige 64-bit: hay x86 sin x86_64")
    if bad_16k:
        raise SystemExit("Play 16 KB page size FAIL:\n  " + "\n  ".join(bad_16k))
    print("16 KB ELF align OK")
PY

SAFE_NAME="$(echo "$NEXT_NAME" | tr '/ ' '__')"
mkdir -p dist
DIST_AAB="dist/BestiaPop-${SAFE_NAME}-${NEXT_CODE}.aab"
if [[ "$AAB_PATH" != "$DIST_AAB" ]]; then
    cp -f "$AAB_PATH" "$DIST_AAB"
fi
( cd dist && sha256sum "$(basename "$DIST_AAB")" > "$(basename "$DIST_AAB").sha256" )
echo -e "${GREEN}AAB listo:${NC} ${DIST_AAB}"
echo -e "${CYAN}$(cat "${DIST_AAB}.sha256")${NC}"

upload_play() {
    local aab="$1" code="$2" name="$3" track="$4" status="$5" sa="$6"
    python3 - "$aab" "$PACKAGE" "$code" "$name" "$track" "$status" "$sa" <<'PY'
import base64, json, os, ssl, subprocess, sys, tempfile, time
import urllib.error, urllib.parse, urllib.request
from pathlib import Path

aab, package, version_code, version_name, track, status, sa_path = sys.argv[1:8]
sa = json.loads(Path(sa_path).read_text(encoding="utf-8"))
email = sa["client_email"]
token_uri = sa.get("token_uri", "https://oauth2.googleapis.com/token")
private_key = sa["private_key"]

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

now = int(time.time())
header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}, separators=(",", ":")).encode())
claim = b64url(json.dumps({
    "iss": email,
    "scope": "https://www.googleapis.com/auth/androidpublisher",
    "aud": token_uri,
    "iat": now,
    "exp": now + 3600,
}, separators=(",", ":")).encode())
signing_input = f"{header}.{claim}".encode()
with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as keyf:
    keyf.write(private_key)
    key_path = keyf.name
try:
    sig = subprocess.check_output(
        ["openssl", "dgst", "-sha256", "-sign", key_path, "-binary"],
        input=signing_input,
    )
finally:
    os.unlink(key_path)
assertion = f"{header}.{claim}.{b64url(sig)}"
ctx = ssl.create_default_context()

class PlayApiError(Exception):
    def __init__(self, method, url, code, body):
        self.method, self.url, self.code, self.body = method, url, code, body
        super().__init__(f"Play API {method} {url}\nHTTP {code}: {body}")

def http_json(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, context=ctx) as resp:
            body = resp.read()
            return json.loads(body.decode()) if body else {}
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace")
        raise PlayApiError(method, url, e.code, err) from e

token_body = urllib.parse.urlencode({
    "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
    "assertion": assertion,
}).encode()
tok = http_json("POST", token_uri, data=token_body, headers={
    "Content-Type": "application/x-www-form-urlencoded",
})
access = tok["access_token"]
auth = {"Authorization": f"Bearer {access}"}
base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package}"

try:
    edit = http_json("POST", f"{base}/edits", data=b"", headers={**auth, "Content-Length": "0"})
    edit_id = edit["id"]
    print(f"edit {edit_id}")

    upload_url = (
        f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/"
        f"applications/{package}/edits/{edit_id}/bundles?uploadType=media"
    )
    aab_bytes = Path(aab).read_bytes()
    http_json("POST", upload_url, data=aab_bytes, headers={
        **auth,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(len(aab_bytes)),
    })
    print(f"bundle uploaded ({len(aab_bytes)} bytes)")
except PlayApiError as e:
    raise SystemExit(str(e)) from e

def put_release(rel_status: str):
    body = json.dumps({
        "releases": [{
            "name": version_name,
            "versionCodes": [str(version_code)],
            "status": rel_status,
        }]
    }).encode()
    http_json(
        "PUT",
        f"{base}/edits/{edit_id}/tracks/{track}",
        data=body,
        headers={**auth, "Content-Type": "application/json"},
    )

def commit_edit():
    http_json(
        "POST",
        f"{base}/edits/{edit_id}:commit",
        data=b"",
        headers={**auth, "Content-Length": "0"},
    )

final_status = status
try:
    put_release(status)
    commit_edit()
except PlayApiError as e:
    draft_app = "only releases with status draft may be created on draft app" in e.body.lower()
    if status == "completed" and draft_app:
        print(
            "App aún Draft en Console: Play solo acepta release draft "
            "hasta la primera publicación. Reintentando como draft…"
        )
        final_status = "draft"
        put_release("draft")
        commit_edit()
    else:
        raise SystemExit(str(e)) from e
print(f"committed track={track} status={final_status} version={version_name} ({version_code})")
if final_status == "draft":
    print("DRAFT_APP_FALLBACK=1")
PY
}

if [[ "$DO_UPLOAD" -eq 1 ]]; then
    STATUS="draft"
    [[ "$ROLLOUT" -eq 1 ]] && STATUS="completed"
    echo -e "\n${YELLOW}Subiendo a Play Console (track=${TRACK}, status=${STATUS})…${NC}"
    UPLOAD_LOG="$(upload_play "$DIST_AAB" "$NEXT_CODE" "$NEXT_NAME" "$TRACK" "$STATUS" "$PLAY_SA")"
    echo "$UPLOAD_LOG"
    echo -e "${GREEN}Subida OK.${NC} Revisá: https://play.google.com/console"
    if echo "$UPLOAD_LOG" | grep -q 'DRAFT_APP_FALLBACK=1' || [[ "$STATUS" == "draft" ]]; then
        echo -e "${YELLOW}Release en draft.${NC} Completá ficha + testers (Google Group) en Console y"
        echo "publicá la pista Closed testing a mano. Después --rollout ya puede ir completed."
    fi
else
    echo -e "\n${GREEN}Build Play-ready completado.${NC}"
    echo -e "${CYAN}Subí ${DIST_AAB} en:${NC}"
    echo "  https://play.google.com/console → Closed testing (alpha) → Crear versión"
    echo -e "${CYAN}O:${NC} $0 --upload --rollout --no-bump --aab ${DIST_AAB}"
fi

echo -e "\n${CYAN}Opt-in testers:${NC} ${OPT_IN_URL}"
echo -e "${CYAN}Ícono (reemplazá el placeholder):${NC} ${PLAY_ICON}  (${PLAY_ICON_PX}×${PLAY_ICON_PX} PNG)"
echo -e "${YELLOW}Ficha Console (no va en el AAB):${NC} Data safety, política de privacidad,"
echo "clasificación de contenido, público objetivo, declaración FGS mediaPlayback,"
echo "Play App Signing con este upload keystore. JSON upload: ${PLAY_SA}"
