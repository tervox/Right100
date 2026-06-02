import sys
import os
import re

def clean_and_fix():
    # --- 1. Limpar Config.kt (Remover duplicatas e adicionar uma vez) ---
    config_path = 'app/src/main/kotlin/com/goodwy/gallery/helpers/Config.kt'
    if os.path.exists(config_path):
        content = open(config_path).read()
        # Remove todas as declarações existentes dessas variáveis para evitar conflito
        for var in ["blurBackgroundPhoto", "blurBackgroundVideo", "showSelectAllFab"]:
            content = re.sub(r'var ' + var + r': Boolean.*?\n\s+get\(\).*?\n\s+set\(value\).*?\n', '', content, flags=re.DOTALL)
        
        # Adiciona elas uma única vez no início da classe
        new_vars = (
            '    var blurBackgroundPhoto: Boolean\n'
            '        get() = prefs.getBoolean("blur_background_photo", false)\n'
            '        set(value) = prefs.edit { putBoolean("blur_background_photo", value) }\n\n'
            '    var blurBackgroundVideo: Boolean\n'
            '        get() = prefs.getBoolean("blur_background_video", false)\n'
            '        set(value) = prefs.edit { putBoolean("blur_background_video", value) }\n\n'
            '    var showSelectAllFab: Boolean\n'
            '        get() = prefs.getBoolean("show_select_all_fab", false)\n'
            '        set(value) = prefs.edit { putBoolean("show_select_all_fab", value) }\n'
        )
        content = content.replace('class Config(context: Context) : BaseConfig(context) {', 
                                  'class Config(context: Context) : BaseConfig(context) {\n' + new_vars)
        open(config_path, 'w').write(content)
        print("OK: Config.kt limpo e atualizado")

    # --- 2. VideoFragment.kt (Sincronização, Fill Mode e Blur Suave) ---
    video_path = 'app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt'
    if os.path.exists(video_path):
        content = open(video_path).read()
        
        # Ajustar intensidade do Blur (20f é mais nítido e leve que 60f)
        content = content.replace('25f, 25f', '20f, 20f').replace('60f, 60f', '20f, 20f')
        
        # Garantir que o mBlurPlayer siga o redimensionamento do vídeo principal
        if "mBlurPlayer?.videoScalingMode = mExoPlayer?.videoScalingMode" not in content:
            content = content.replace('mExoPlayer?.setPlaybackSpeed(speed)', 
                                      'mExoPlayer?.setPlaybackSpeed(speed)\n        mBlurPlayer?.setPlaybackSpeed(speed)\n        mBlurPlayer?.videoScalingMode = mExoPlayer?.videoScalingMode ?: 0')

        # Salvar o estado da tela (Fill Mode)
        if "mConfig.videoFillMode = mode" not in content:
            content = re.sub(r'(fun updateFillMode.*?\{)', r'\1\n        val mode = (mConfig.videoFillMode + 1) % 3\n        mConfig.videoFillMode = mode', content)

        open(video_path, 'w').write(content)
        print("OK: VideoFragment.kt atualizado (Blur suave + Fill Mode)")

    # --- 3. Corrigir referências nas Activities ---
    for act in ['app/src/main/kotlin/com/goodwy/gallery/activities/MediaActivity.kt', 
                'app/src/main/kotlin/com/goodwy/gallery/activities/SettingsActivity.kt']:
        if os.path.exists(act):
            c = open(act).read()
            c = c.replace('config.mConfig.selectAllFab', 'config.showSelectAllFab')
            c = c.replace('mConfig.context!!.config.blurBackgroundVideo', 'mConfig.blurBackgroundVideo')
            open(act, 'w').write(c)
            print(f"OK: {act} corrigido")

clean_and_fix()
