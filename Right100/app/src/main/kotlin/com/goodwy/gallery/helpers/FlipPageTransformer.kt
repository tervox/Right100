package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager

class FlipPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        view.translationX = view.width * -position
        val pct = 1f - Math.abs(position)
        view.alpha = pct
        val rot = 180f * position
        view.rotationY = rot
        view.cameraDistance = view.width * 8f
    }
}
