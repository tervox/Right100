# -*- coding: utf-8 -*-
"""Script unico: corrige sobreposicao do subsamplingView em GIF/SVG/APNG/AVIF/WebP,
reseta estado do zoom no WebP, e incrementa a versao no gradle.properties."""
from pathlib import Path
import sys, re

BASE = Path.home() / "Right100"
MA = BASE / "app/src/main/kotlin/com/goodwy/gallery/fragments/PhotoFragment.kt"
GP = BASE / "gradle.properties"

for p in (MA, GP):
    if not p.is_file():
        print("[!] nao encontrei " + str(p)); sys.exit(1)

ma = MA.read_text(encoding="utf-8")
gp = GP.read_text(encoding="utf-8")

def exigir(cond, rotulo):
    print(("OK    " if cond else "FALTA ") + rotulo)
    if not cond:
        sys.exit(1)

print("--- pre-voo: PhotoFragment ---")
exigir(ma.count("                gifViewFrame.beVisible()\n                gifViewFrame.controller.settings.isZoomEnabled = true\n                ensureBackgroundThread {") == 1,
       "loadGif: gifViewFrame.beVisible + isZoomEnabled + ensureBackgroundThread")
exigir(ma.count("    private fun loadSVG() {\n        if (context != null) {\n            Glide.with(requireContext())") == 1,
       "loadSVG: assinatura")
exigir(ma.count("    private fun loadAPNG() {\n        if (context != null) {\n            val drawable = APNGDrawable.fromFile(mMedium.path)") == 1,
       "loadAPNG: assinatura")
exigir(ma.count("    private fun loadAVIF() {\n        if (context != null) {\n            val drawable = AVIFDrawable.fromFile(mMedium.path)") == 1,
       "loadAVIF: assinatura")
exigir(ma.count("                binding.gesturesView.setImageDrawable(drawable)\n                binding.gesturesView.controller.settings.isZoomEnabled = true\n                if (mIsFragmentVisible && addZoomableView) {") == 1,
       "loadBitmap: WebP setImageDrawable + isZoomEnabled + scheduleZoomableView")

print("--- pre-voo: gradle.properties ---")
exigir("VERSION_NAME=8.4.84" in gp, "VERSION_NAME=8.4.84")
exigir("VERSION_CODE=8114" in gp, "VERSION_CODE=8114")

bal_antes = (ma.count("{") - ma.count("}"), ma.count("(") - ma.count(")"))

# 1. loadGif: adicionar subsamplingView.beGone + mIsSubsamplingVisible = false
ANTIGO1 = ("                gifViewFrame.beVisible()\n"
           "                gifViewFrame.controller.settings.isZoomEnabled = true\n"
           "                ensureBackgroundThread {")
NOVO1 = ("                gifViewFrame.beVisible()\n"
         "                binding.subsamplingView.beGone()\n"
         "                mIsSubsamplingVisible = false\n"
         "                gifViewFrame.controller.settings.isZoomEnabled = true\n"
         "                ensureBackgroundThread {")
exigir(ma.count(ANTIGO1) == 1, "ancora loadGif")
ma = ma.replace(ANTIGO1, NOVO1, 1)

# 2. loadSVG: adicionar subsamplingView.beGone + mIsSubsamplingVisible = false
ANTIGO2 = ("    private fun loadSVG() {\n"
           "        if (context != null) {\n"
           "            Glide.with(requireContext())")
NOVO2 = ("    private fun loadSVG() {\n"
         "        if (context != null) {\n"
         "            binding.subsamplingView.beGone()\n"
         "            mIsSubsamplingVisible = false\n"
         "            Glide.with(requireContext())")
exigir(ma.count(ANTIGO2) == 1, "ancora loadSVG")
ma = ma.replace(ANTIGO2, NOVO2, 1)

# 3. loadAPNG: adicionar subsamplingView.beGone + mIsSubsamplingVisible = false
ANTIGO3 = ("    private fun loadAPNG() {\n"
           "        if (context != null) {\n"
           "            val drawable = APNGDrawable.fromFile(mMedium.path)")
NOVO3 = ("    private fun loadAPNG() {\n"
         "        if (context != null) {\n"
         "            binding.subsamplingView.beGone()\n"
         "            mIsSubsamplingVisible = false\n"
         "            val drawable = APNGDrawable.fromFile(mMedium.path)")
exigir(ma.count(ANTIGO3) == 1, "ancora loadAPNG")
ma = ma.replace(ANTIGO3, NOVO3, 1)

# 4. loadAVIF: adicionar subsamplingView.beGone + mIsSubsamplingVisible = false
ANTIGO4 = ("    private fun loadAVIF() {\n"
           "        if (context != null) {\n"
           "            val drawable = AVIFDrawable.fromFile(mMedium.path)")
NOVO4 = ("    private fun loadAVIF() {\n"
         "        if (context != null) {\n"
         "            binding.subsamplingView.beGone()\n"
         "            mIsSubsamplingVisible = false\n"
         "            val drawable = AVIFDrawable.fromFile(mMedium.path)")
exigir(ma.count(ANTIGO4) == 1, "ancora loadAVIF")
ma = ma.replace(ANTIGO4, NOVO4, 1)

# 5. loadBitmap WebP: adicionar subsamplingView.beGone + mIsSubsamplingVisible = false + reset mInitialZoom/mCurrentGestureViewZoom
ANTIGO5 = ("                binding.gesturesView.setImageDrawable(drawable)\n"
           "                binding.gesturesView.controller.settings.isZoomEnabled = true\n"
           "                if (mIsFragmentVisible && addZoomableView) {")
NOVO5 = ("                binding.subsamplingView.beGone()\n"
         "                mIsSubsamplingVisible = false\n"
         "                mInitialZoom = 0f\n"
         "                mCurrentGestureViewZoom = 1f\n"
         "                binding.gesturesView.setImageDrawable(drawable)\n"
         "                binding.gesturesView.controller.settings.isZoomEnabled = true\n"
         "                if (mIsFragmentVisible && addZoomableView) {")
exigir(ma.count(ANTIGO5) == 1, "ancora loadBitmap WebP")
ma = ma.replace(ANTIGO5, NOVO5, 1)

# 6. gradle.properties: incrementar versao
mn = re.search(r'(?m)^[ \t]*VERSION_NAME=([^\r\n]+)', gp)
mc = re.search(r'(?m)^[ \t]*VERSION_CODE=([^\r\n]+)', gp)
if not mn or not mc:
    print("[!] VERSION_NAME/VERSION_CODE nao encontrados"); sys.exit(1)
atual_nome = mn.group(1).strip()
atual_cod = mc.group(1).strip()
partes = atual_nome.split(".")
prox_nome = ".".join(partes[:-1] + [str(int(partes[-1]) + 1)])
prox_cod = str(int(atual_cod) + 1)
gp = re.sub(r'(?m)^[ \t]*VERSION_NAME[ \t]*=.*$', "VERSION_NAME=" + prox_nome, gp)
gp = re.sub(r'(?m)^[ \t]*VERSION_CODE[ \t]*=.*$', "VERSION_CODE=" + prox_cod, gp)

bal_depois = (ma.count("{") - ma.count("}"), ma.count("(") - ma.count(")"))
if bal_antes != bal_depois:
    print("[!] chaves/parentheses nao balanceados: antes={} depois={}".format(bal_antes, bal_depois))
    sys.exit(1)

MA.write_text(ma, encoding="utf-8", newline="\n")
GP.write_text(gp, encoding="utf-8", newline="\n")

print()
print("[OK] Alteracoes aplicadas:")
print("    PhotoFragment: loadGif/SVG/APNG/AVIF esconde subsamplingView (nao sobrepoe mais)")
print("    PhotoFragment: WebP esconde subsamplingView e reseta mInitialZoom/mCurrentGestureViewZoom")
print("    gradle.properties: {} -> {}  (code {} -> {})".format(atual_nome, prox_nome, atual_cod, prox_cod))
