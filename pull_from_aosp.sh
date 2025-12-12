#!/bin/bash

# Caminho para o repositório onde serão salvos as modificações
MODS_DIR="."

# Caminho para a raiz do código AOSP
AOSP_DIR="$HOME/aosp"

# Arquivos que devem ser ignorados na volta
EXCLUDES=(
    "--exclude=.git"
    "--exclude=env.sh"
)

if [ ! -d "$MODS_DIR" ]; then
    echo "Erro: Diretório de modificações não encontrado: $MODS_DIR"
    exit 1
fi

if [ ! -d "$AOSP_DIR" ]; then
    echo "Erro: Diretório AOSP não encontrado: $AOSP_DIR"
    exit 1
fi

echo "-----------------------------------------------------"
echo "ATENÇÃO: Copiando do AOSP ($AOSP_DIR) para REPO ($MODS_DIR)"
echo "Apenas arquivos que JÁ EXISTEM no seu repositório serão atualizados."
echo "-----------------------------------------------------"

rsync -avh --progress --existing --update "${EXCLUDES[@]}" "$AOSP_DIR/" "$MODS_DIR/"

echo "Sincronização reversa concluída."