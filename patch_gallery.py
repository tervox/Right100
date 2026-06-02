import sys
import os
import re

def fix_file(rel_path, replacements):
    full_path = os.path.join(os.getcwd(), rel_path)
    if not os.path.exists(full_path):
        print(f"PULANDO: {rel_path} nao encontrado")
        return
    
    content = open(full_path).read()
    original = content
    for old, new in replacements:
        content = content.replace(old, new)
    
    if content != original:
        open(full_path, 'w').write(content)
        print(f"OK: {rel_path} atualizado")
    else:
        print(f"SKIP: {rel_path} ja estava correto ou padrao nao encontrado")

# --- CORREÇÃO 1: Config.kt (Define os acessores) ---
fix_file('app/src/main/kotlin/com/goodwy/gallery/helpers/Config.kt', [
    ('class Config(context: Context) : BaseConfig(context) {', 
     'class Config(context: Context) : BaseConfig(context) {\n    var blurBackgroundPhoto: Boolean\n        get() = prefs.getBoolean("blur_background_photo", false)\n        set(value) = prefs.edit { putBoolean("blur_background_photo", value) }\n\n    var blurBackgroundVideo: Boolean\n        get() = prefs.getBoolean("blur_background_video", false)\n        set(value) = prefs.edit { putBoolean("blur_background_video", value) }\n\n    var showSelectAllFab: Boolean\n        get() = prefs.getBoolean("show_select_all_fab", false)\n        set(value) = prefs.edit { putBoolean("show_select_all_fab", value) }\n')
])

# --- CORREÇÃO 2: VideoFragment.kt (Sincroniza Blur, Velocidade e Save Position) ---
fix_file('app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt', [
    ('mConfig.context!!.config.blurBackgroundVideo', 'mConfig.blurBackgroundVideo'),
    ('createBlurEffect(60f, 60f', 'createBlurEffect(25f, 25f'),
    ('mExoPlayer?.seekTo(milliseconds)', 'mExoPlayer?.seekTo(milliseconds)\n        mBlurPlayer?.seekTo(milliseconds)'),
    ('mExoPlayer?.setPlaybackSpeed(speed)', 'mExoPlayer?.setPlaybackSpeed(speed)\n        mBlurPlayer?.setPlaybackSpeed(speed)'),
    ('mExoPlayer!!.playWhenReady = true', 'mExoPlayer!!.playWhenReady = true\n            mBlurPlayer?.playWhenReady = true'),
    ('private fun playVideo() {', 'private fun playVideo() {\n        restoreLastVideoSavedPosition()')
])

# --- CORREÇÃO 3: PhotoFragment.kt (Fix Blur) ---
fix_file('app/src/main/kotlin/com/goodwy/gallery/fragments/PhotoFragment.kt', [
    ('ctx.config.mConfig.blurBackgroundPhoto', 'ctx.config.blurBackgroundPhoto')
])

# --- CORREÇÃO 4: Activities (Fix SelectAllFab) ---
activity_fixes = [('config.mConfig.selectAllFab', 'config.showSelectAllFab')]
fix_file('app/src/main/kotlin/com/goodwy/gallery/activities/MediaActivity.kt', activity_fixes)
fix_file('app/src/main/kotlin/com/goodwy/gallery/activities/SettingsActivity.kt', activity_fixes)
