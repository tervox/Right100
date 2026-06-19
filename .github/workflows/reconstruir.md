---
name: Reconstrução Universal Right Gallery
---
# Missão: Reconstruir do zero (App Universal e Configurável)
O foco é performance e compatibilidade em qualquer celular Android.

## 🚫 NÃO MEXER (Já está pronto):
- Fundo de cor e Blur das fotos.

## ✅ IMPLEMENTAR COM CHAVE LIGA/DESLIGA NAS CONFIGURAÇÕES:
1. GIFs ultra-leves (baixo consumo de RAM).
2. Gestão de botões na interface (usuário escolhe o que ver).
3. Super FAB (Selecionar Tudo, Copiar, Mover, Lixeira).
4. Tela Cheia e Tela Esticada (Fill/Stretch).
5. OCR em fotos e frames de vídeos.
6. Salvar estado da tela (cheia/esticada) ao trocar de vídeo.
7. Performance extrema: Abertura de pastas com milhares de mídias.
8. Metadados instantâneos: Carregar duração dos vídeos sem delay.
9. Pausa automática: Pausar vídeo ao iniciar Copiar/Mover.
10. Background Tasks: Mover, copiar e deletar sem travar a UI.
11. Renomeação em massa (estilo potente do app NMM).
12. Fundo de vídeo (Efeito 15f): Vídeo desfocado no fundo (15f) com ajuste de intensidade.
13. Slideshow Randômico com transições variadas.
14. Código universal em Kotlin (Material Design).

## Regras Técnicas:
- Build: './gradlew assembleFossDebug' deve passar.
- Persistência: Usar SharedPreferences para as configurações.
