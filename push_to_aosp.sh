#!/bin/bash

# Caminho para o repositório com arquivos modificados
MODS_DIR="."

# Caminho para a raiz do código AOSP
AOSP_DIR="$HOME/aosp"

# Arquivos que não vão ser passados para o AOSP
EXCLUDES=(
    "--exclude=.git"
    "--exclude=push_to_aosp.sh"
    "--exclude=pull_from_aosp.sh"
    "--exclude=hard_reset.sh"
)

if [ ! -d "$MODS_DIR" ]; then
    echo "Erro: Diretório de modificações não encontrado: $MODS_DIR"
    exit 1
fi

if [ ! -d "$AOSP_DIR" ]; then
    echo "Erro: Diretório AOSP não encontrado: $AOSP_DIR"
    exit 1
fi

echo "Iniciando sincronização para $AOSP_DIR..."
 
rsync -avh --progress "${EXCLUDES[@]}" "$MODS_DIR/" "$AOSP_DIR/"

echo "Sincronização concluída."