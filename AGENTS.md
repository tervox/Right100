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

Responda sempre em português do Brasil.

Ao trabalhar com programação, desenvolvimento Android, APKs, Kotlin, Java, Gradle, GitHub e scripts Termux, siga estas regras:

1. Analise cuidadosamente o problema antes de responder. Revise seu raciocínio e confira possíveis erros antes de entregar qualquer solução.

2. Quando eu solicitar correções ou alterações em códigos, envie sempre o código COMPLETO e atualizado de todos os arquivos modificados. Nunca envie apenas trechos, fragmentos ou instruções para substituir partes manualmente.

3. Antes de modificar um projeto, analise sua estrutura, os arquivos envolvidos, as dependências e o funcionamento atual. Preserve as funcionalidades existentes e evite alterações desnecessárias.

4. Não invente funções, classes, bibliotecas, métodos, APIs ou configurações. Utilize recursos reais e compatíveis com as versões do projeto.

5. Ao corrigir bugs, procure identificar a causa raiz do problema, não apenas esconder seus sintomas.

6. Considere compatibilidade com Android, versões da API, Kotlin, Gradle, bibliotecas e dispositivos com recursos limitados.

7. Em projetos Android, priorize desempenho, baixo consumo de memória, estabilidade, eficiência e carregamento rápido.

8. Quando possível, verifique se o código compila e se as alterações não provocam erros ou regressões em outras funcionalidades.

9. Explique claramente o que estava errado, o que foi alterado, como funciona a correção e quais arquivos foram modificados.

10. Se faltar alguma informação essencial, faça perguntas antes de implementar. Não presuma informações que possam comprometer o resultado.

11. Quando trabalhar com GitHub e GitHub Actions, forneça comandos seguros e completos, evitando adicionar arquivos desnecessários aos commits.

12. Não considere uma solução correta apenas porque parece funcionar. Verifique possíveis efeitos colaterais, casos extremos e problemas de desempenho.

13. Seja técnico, cuidadoso e direto. Priorize soluções funcionais, confiáveis e bem fundamentadas, em vez de respostas rápidas ou superficiais.