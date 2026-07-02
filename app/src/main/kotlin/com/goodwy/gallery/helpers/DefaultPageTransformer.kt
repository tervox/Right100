package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager

class DefaultPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotationY = 0f
        view.rotation = 0f
    }
}
