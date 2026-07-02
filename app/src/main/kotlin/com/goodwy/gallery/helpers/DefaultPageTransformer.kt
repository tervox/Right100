package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager

class DefaultPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        // Antes esse método era vazio ({}). Os outros transformadores (Cube, ZoomOut, Depth,
        // Fade) alteram rotationY/scaleX/scaleY/translationX/alpha da view durante a transição.
        // Se o usuário interrompe a apresentação NO MEIO de uma transição animada (ex: cube
        // rotacionando), essas propriedades ficam "sujas" na página atual, já que trocar para
        // este transformador não desfazia nada. Uma view com rotationY/scale não-identidade
        // tem seus limites de toque deslocados pelo Android, o que pode quebrar gestos como o
        // de arrastar pra fechar o visualizador.
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.rotationX = 0f
        view.rotationY = 0f
    }
}
