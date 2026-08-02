#!/usr/bin/env bash
set -e

# Colored Output
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${CYAN}====================================================${NC}"
echo -e "${CYAN}   🎵 Bestia Pop - Build & Deploy                  ${NC}"
echo -e "${CYAN}====================================================${NC}"

# Check ADB
if ! command -v adb &> /dev/null; then
    if [ -f "/opt/android-sdk/platform-tools/adb" ]; then
        export PATH="/opt/android-sdk/platform-tools:$PATH"
    else
        echo -e "${RED}❌ ADB no encontrado. Asegurate de tener adb instalado.${NC}"
        exit 1
    fi
fi

# Check connected device
echo -e "\n${YELLOW}📱 Verificando dispositivos Android conectados por USB/WiFi...${NC}"
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" || true)

if [ -z "$DEVICES" ]; then
    echo -e "${RED}⚠️  No se encontró ningún dispositivo Android conectado.${NC}"
    echo -e "${YELLOW}Por favor, conecta tu dispositivo por USB, habilita 'Depuración USB' en Opciones de Desarrollador y vuelve a intentarlo.${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Dispositivo encontrado:${NC}"
adb devices -l | grep "device "

# Build APK
echo -e "\n${YELLOW}🔨 Compilando APK con Gradle...${NC}"
gradle assembleDebug -x compileDebugJavaWithJavac

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ El archivo APK no se encontró en $APK_PATH${NC}"
    exit 1
fi

# Install APK
echo -e "\n${YELLOW}📲 Instalando APK en el dispositivo...${NC}"
adb install -r "$APK_PATH"

echo -e "\n${GREEN}🚀 Lanzando la aplicación...${NC}"
adb shell am start -n com.bestiapop.android/.MainActivity

echo -e "\n${GREEN}✨ ¡Instalación y despliegue completados con éxito! 🎶${NC}"
