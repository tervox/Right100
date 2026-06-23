package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

class DepthPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        when {
            position < -1f -> view.alpha = 0f
            position <= 0f -> {
                view.alpha = 1f
                view.translationX = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            position <= 1f -> {
                view.alpha = 1f - position
                view.translationX = view.width * -position
                val scale = 0.75f + (1f - 0.75f) * (1f - abs(position))
                view.scaleX = scale
                view.scaleY = scale
            }
            else -> view.alpha = 0f
        }
    }
}
