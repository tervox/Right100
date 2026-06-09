package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

class ZoomInPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        view.translationX = view.width * -position
        val scale = if (position < 0f) 1f + position * 0.3f else 1f - position * 0.3f
        view.scaleX = scale.coerceAtLeast(0.7f)
        view.scaleY = scale.coerceAtLeast(0.7f)
        view.alpha = if (abs(position) >= 1f) 0f else 1f - abs(position) * 0.5f
    }
}
