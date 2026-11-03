# Regras para ferramentas de IA

Right100 - app Android em Kotlin, baseado em Simple Gallery / Fossify Gallery.

## Proibido neste repositorio

- **Executar scripts de patch** (`fix*.py`, `patch*.py`, `fix*.sh`, `patch*.sh`, `*.patch`).
  Eles existiram entre maio e junho de 2026, escreviam sintaxe invalida
  (`ArrayList<<Medium>()`) e reescreviam arquivos inteiros. Foram removidos.
  Se algum reaparecer, apague. Nao rode.
- **Reescrever arquivo Kotlin inteiro.** Toda alteracao e' cirurgica, no menor trecho possivel,
  com o texto antigo copiado exatamente do arquivo real.
- **Fazer `git push`** sem o dono pedir explicitamente.
- **Inventar** nome de arquivo, de funcao ou de campo. Leia o arquivo antes de propor mudanca.

## Fluxo do dono

1. A IA entrega um script Python com heredoc que **aborta** se o trecho antigo nao bater.
2. O dono roda no Termux (`~/Right100`), faz commit e push ele mesmo.
3. O GitHub Actions compila; o APK e' instalado e testado no aparelho.
