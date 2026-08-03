#!/bin/bash

# ==============================================================================
# Script: flac_to_mp3.sh
# Descripción: Toma carpetas de música con archivos .flac, clona la estructura
#              de directorios agregando " MP3" al nombre de la carpeta de destino
#              y convierte recursivamente todos los .flac a .mp3 comprimiendo peso.
# ==============================================================================

set -e

# Configuración de calidad MP3
# Opciones recomendadas:
#   - "320k" : Calidad máxima constante (CBR 320 kbps)
#   - "192k" : Excelente balance compresión/peso (CBR 192 kbps)
#   - "V0"   : Calidad variable VBR alta (~245 kbps)
#   - "V2"   : Calidad variable VBR estándar (~190 kbps)
BITRATE="320k"

# Comprobar dependencia ffmpeg
if ! command -v ffmpeg &> /dev/null; then
    echo "Error: 'ffmpeg' no está instalado. Instálalo con: sudo apt install ffmpeg"
    exit 1
fi

# Validar argumentos
if [ "$#" -lt 1 ]; then
    echo "Uso: $0 <directorio1> [directorio2 ...]"
    echo "Ejemplo: $0 '/home/usuario/Música/MiAlbum'"
    exit 1
fi

for SRC_DIR in "$@"; do
    # Remover barra al final si existe
    SRC_DIR="${SRC_DIR%/}"

    if [ ! -d "$SRC_DIR" ]; then
        echo "¡Advertencia! '$SRC_DIR' no es un directorio válido. Omitiendo..."
        continue
    fi

    DEST_DIR="${SRC_DIR} MP3"

    echo "======================================================================"
    echo "Carpeta Origen  : $SRC_DIR"
    echo "Carpeta Destino : $DEST_DIR"
    echo "Calidad MP3     : $BITRATE"
    echo "======================================================================"

    # 1. Crear clon de estructura de carpetas
    echo "[1/3] Recreando estructura de carpetas..."
    find "$SRC_DIR" -type d -print0 | while IFS= read -r -d '' dir; do
        rel_path="${dir#"$SRC_DIR"}"
        mkdir -p "$DEST_DIR$rel_path"
    done

    # 2. Copiar archivos adicionales (carátulas, portadas, .jpg, .png, etc.)
    echo "[2/3] Copiando archivos adjuntos (portadas, portadas .jpg/.png, etc.)..."
    find "$SRC_DIR" -type f ! -iname "*.flac" -print0 | while IFS= read -r -d '' file; do
        rel_file="${file#"$SRC_DIR/"}"
        dest_file="$DEST_DIR/$rel_file"
        # Copiar si no existe
        if [ ! -f "$dest_file" ]; then
            cp "$file" "$dest_file"
        fi
    done

    # 3. Convertir archivos .flac a .mp3
    echo "[3/3] Convirtiendo archivos .flac a .mp3..."
    
    # Contar total de flacs
    total_flac=$(find "$SRC_DIR" -type f -iname "*.flac" | wc -l)
    
    if [ "$total_flac" -eq 0 ]; then
        echo "No se encontraron archivos .flac en '$SRC_DIR'."
        continue
    fi

    current=0
    find "$SRC_DIR" -type f -iname "*.flac" -print0 | while IFS= read -r -d '' flac_file; do
        current=$((current + 1))
        rel_file="${flac_file#"$SRC_DIR/"}"
        base_name="${rel_file%.*}"
        dest_mp3="$DEST_DIR/${base_name}.mp3"

        if [ -f "$dest_mp3" ] && [ -s "$dest_mp3" ]; then
            echo "[$current/$total_flac] Omitiendo (ya existe): $rel_file"
            continue
        fi

        echo "[$current/$total_flac] Convirtiendo: $rel_file"

        # Parámetros ffmpeg:
        # -nostdin : Evita que ffmpeg consuma stdin del bucle while read
        # -y : Sobreescribe destino si fuera necesario
        # -map_metadata 0 : Preserva tags (artista, álbum, título, etc.) y carátula
        # -id3v2_version 3 : Compatibilidad estándar de tags ID3v3
        ffmpeg -nostdin -hide_banner -loglevel error -y \
            -i "$flac_file" \
            -map_metadata 0 \
            -id3v2_version 3 \
            -codec:a libmp3lame \
            -b:a "$BITRATE" \
            "$dest_mp3"

    done

    echo "----------------------------------------------------------------------"
    echo "¡Proceso finalizado con éxito para: $SRC_DIR!"
    echo "Guardado en: $DEST_DIR"
    echo "----------------------------------------------------------------------"
    echo ""
done

echo "¡Todos los directorios han sido procesados!"
