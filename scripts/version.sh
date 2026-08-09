# shellcheck shell=bash
# Helpers de version.properties. Sourcear desde release.sh / deploy-play.sh
# (VERSION_FILE default: version.properties).

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
    local file="${3:-${VERSION_FILE:-version.properties}}"
    cat > "$file" <<EOF
# Fuente de verdad de versión (GitHub Releases + install.sh --release).
# ./release.sh incrementa VERSION_CODE y el último número de VERSION_NAME.
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
