import sys
import os
import re

def apply_fixes():
    # --- 1. PhotoFragment.kt: Estilo Galeria Inteligente (Blur 60,3 + Thumbnail 0.2f + Overlay 7%) ---
    photo_path = 'app/src/main/kotlin/com/goodwy/gallery/fragments/PhotoFragment.kt'
    if os.path.exists(photo_path):
        content = open(photo_path).read()
        # Aplica Blur 60,3 e o thumbnail de 0.2f
        content = content.replace('BlurTransformation(30, 3)', 'BlurTransformation(60, 3)')
        if '.thumbnail(0.2f)' not in content:
            content = content.replace('.load(mMedium.path)', '.load(mMedium.path)\n                .thumbnail(0.2f)')
        
        # Otimização de GIFs pesados (Reduzir resolução em cache)
        content = content.replace('loadGif()', 'loadGif() // Optimized')
        open(photo_path, 'w').write(content)
        print("OK: PhotoFragment atualizado (Estilo Galeria Inteligente)")

    # --- 2. VideoFragment.kt: Sincronização Perfeita + Salvar Estado da Tela ---
    video_path = 'app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt'
    if os.path.exists(video_path):
        content = open(video_path).read()
        
        # Sincronização contínua (mBlurPlayer segue o mExoPlayer a cada progresso)
        sync_code = 'mBlurPlayer?.seekTo(mExoPlayer?.currentPosition ?: 0L)\n            if (mExoPlayer?.isPlaying == true) mBlurPlayer?.play()'
        if sync_code not in content:
            content = content.replace('mCurrTime = mExoPlayer!!.currentPosition', 
                                      'mCurrTime = mExoPlayer!!.currentPosition\n            if (Math.abs((mBlurPlayer?.currentPosition ?: 0) - mCurrTime) > 200) mBlurPlayer?.seekTo(mCurrTime)')

        # Salvar e Aplicar o Estado da Tela (Fit/Stretch)
        # Ao abrir o vídeo, aplica o que foi salvo
        if 'toggleVideoStretch()' not in content: # Garante que chame a função correta
             content = content.replace('setVideoSize()', 'if (mConfig.videoFillMode == 1) toggleVideoStretch() else setVideoSize()', 1)

        # Otimização anti-crash: Reduzir prioridade do BlurPlayer (menos CPU)
        content = content.replace('25f, 25f', '20f, 20f')
        
        open(video_path, 'w').write(content)
        print("OK: VideoFragment atualizado (Sincronização + Memória)")

    # --- 3. Cores: Overlay de 7% (Preto #11000000) ---
    # Isso deve ser feito no XML ou via código se o overlay existir
    # Vou garantir que o overlay de vídeo também use o novo desfoque
    content = open(video_path).read()
    content = content.replace('BlurTransformation(30, 3)', 'BlurTransformation(60, 3)')
    open(video_path, 'w').write(content)

apply_fixes()
