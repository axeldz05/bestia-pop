#!/usr/bin/env bash
set -e

# Colored Output
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

USAGE="Usage: $0 [--debug|--release]
  --debug   (default) assembleDebug + adb install — daily development
  --release assembleRelease (signed) + adb install — beta-like build for device testing"

BUILD_TYPE="debug"
case "${1:-}" in
  --release|-r) BUILD_TYPE="release" ;;
  --debug|-d|"") BUILD_TYPE="debug" ;;
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

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   Bestia Pop - Build & Deploy (${BUILD_TYPE})        ${NC}"
echo -e "${CYAN}====================================================${NC}"

# Ensure Firebase config exists (real file from Console, or example for local builds)
GOOGLE_SERVICES="app/google-services.json"
if [ ! -f "$GOOGLE_SERVICES" ]; then
    if [ -f "app/google-services.json.example" ]; then
        echo -e "${YELLOW}No app/google-services.json — copying from example (replace with Firebase Console file for real Crashlytics).${NC}"
        cp app/google-services.json.example "$GOOGLE_SERVICES"
    else
        echo -e "${RED}Missing $GOOGLE_SERVICES (and no example). Download it from Firebase Console.${NC}"
        exit 1
    fi
fi

# Check ADB
if ! command -v adb &> /dev/null; then
    if [ -f "/opt/android-sdk/platform-tools/adb" ]; then
        export PATH="/opt/android-sdk/platform-tools:$PATH"
    else
        echo -e "${RED}ADB no encontrado. Asegurate de tener adb instalado.${NC}"
        exit 1
    fi
fi

# Check connected device
echo -e "\n${YELLOW}Verificando dispositivos Android conectados por USB/WiFi...${NC}"
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" || true)

if [ -z "$DEVICES" ]; then
    echo -e "${RED}No se encontró ningún dispositivo Android conectado.${NC}"
    echo -e "${YELLOW}Conecta el dispositivo por USB, habilita Depuración USB y vuelve a intentarlo.${NC}"
    exit 1
fi

echo -e "${GREEN}Dispositivo encontrado:${NC}"
adb devices -l | grep "device "

if [ "$BUILD_TYPE" = "release" ]; then
    if [ ! -f "keystore.properties" ]; then
        echo -e "${YELLOW}No hay keystore.properties — release usará firma debug de fallback.${NC}"
        echo -e "${YELLOW}Para beta firmada: copiá keystore.properties.example → keystore.properties y generá el .jks.${NC}"
    fi
    echo -e "\n${YELLOW}Compilando APK release con Gradle...${NC}"
    gradle assembleRelease -x compileReleaseJavaWithJavac
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    echo -e "\n${YELLOW}Compilando APK debug con Gradle...${NC}"
    gradle assembleDebug -x compileDebugJavaWithJavac
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}El archivo APK no se encontró en $APK_PATH${NC}"
    exit 1
fi

# Install APK
echo -e "\n${YELLOW}Instalando APK en el dispositivo...${NC}"
adb install -r "$APK_PATH"

echo -e "\n${GREEN}Lanzando la aplicación...${NC}"
adb shell am start -n com.bestiapop.android/.MainActivity

echo -e "\n${GREEN}Instalación y despliegue completados (${BUILD_TYPE}).${NC}"
if [ "$BUILD_TYPE" = "release" ]; then
    echo -e "${CYAN}Para amigos: subí este APK (o bundleRelease) a Firebase App Distribution.${NC}"
fi
