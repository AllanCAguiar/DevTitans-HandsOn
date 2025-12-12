# DevTITANS - Hands-On - Equipe 02

## Contribuidores
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >
<img src="https://github.com/DevTITANS05/Hands-On-Linux-fork-/assets/21023906/85e61f3e-476c-47a4-82d5-4054e856c67b" width="180" >

### Papéis dos Contribuidores
- **Allan Carvalho de Aguiar**
  - Pesquisa do código responsável pela tela de desbloqueio
  - Desenvolvimento das alterações em Java
  - Documentação da atividade

- **Desenvolvedor 2**
  - Pesquisa do código responsável pela tela de desbloqueio
  - Desenvolvimento das alterações em Java
  - Documentação da atividade

- **Desenvolvedor 3**
  - Desenvolvimento das alterações em Java
  - Aplicação de patches e testes

- **Desenvolvedor 4**
  - Desenvolvimento das alterações em Java
  - Aplicação de patches e testes

- **Desenvolvedor 5**
  - Pesquisa do código responsável pela tela de desbloqueio
  - Documentação da atividade

## Scripts de Automação e Workflow

Este repositório contém scripts utilitários para facilitar a interação entre o código versionado neste projeto e a árvore de código fonte do AOSP (localizada em ~/aosp).

### Pré-requisitos

Para que os scripts funcionem corretamente, a estrutura de diretórios esperada é:

- Código AOSP: ~/aosp (Home do usuário > pasta aosp)

- Este Repositório: Pode estar em qualquer local.
---
### Configuração de Ambiente

`env.sh`


Configura as variáveis de ambiente necessárias para a compilação do Android. Ele carrega o envsetup.sh do AOSP e seleciona o target devtitans_kraken-eng.

    Nota: Este script deve ser executado com source para que as variáveis persistam no seu terminal atual.

Uso:
```bash
source ./env.sh
```
---
### Sincronização de Arquivos

Estes scripts utilizam rsync para mover arquivos entre este repositório e a árvore do AOSP.

`push_to_aosp.sh` **(Local ➔ AOSP)**

Envia as modificações feitas neste repositório para a pasta do AOSP (~/aosp).

- Utilize este script quando você editar arquivos aqui e quiser prepará-los para compilação.

- Ignora: Arquivos do git e os próprios scripts de automação.

Uso:
```bash
./push_to_aosp.sh
```

`pull_from_aosp.sh` **(AOSP ➔ Local)**

Traz modificações feitas diretamente na pasta do AOSP de volta para este repositório.

- Importante: Este script utiliza a flag --existing. Isso significa que ele apenas atualiza arquivos que já existem neste repositório. Ele não copiará arquivos novos ou desconhecidos do AOSP para cá.

- Utilize este script para salvar seu trabalho após testar alterações diretamente no código fonte do Android.

Uso:
```bash
./pull_from_aosp.sh
```
---
### Limpeza e Reset
`hard_reset.sh`

Realiza uma limpeza profunda em todos os repositórios git dentro da pasta ~/aosp.

- Executa git reset --hard HEAD: Desfaz alterações não commitadas.

- Executa git clean -fdx: Remove arquivos não rastreados (untracked) e ignorados.

- PERIGO: Execute este script apenas se quiser descartar todas as alterações não salvas na sua pasta do AOSP e retorná-la ao estado original.

Uso:
```bash
./hard_reset.sh
```

---
### Permissões de Execução
Caso os scripts não estejam executáveis, rode o comando abaixo na raiz deste projeto:
Bash
```bash
chmod +x *.sh
```