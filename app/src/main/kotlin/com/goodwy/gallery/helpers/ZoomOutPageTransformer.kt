package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

private const val MIN_SCALE = 0.85f
private const val MIN_ALPHA = 0.5f

class ZoomOutPageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        val pageWidth = view.width.toFloat()
        val pageHeight = view.height.toFloat()
        when {
            position < -1f -> view.alpha = 0f
            position <= 1f -> {
                val scaleFactor = MIN_SCALE.coerceAtLeast(1f - abs(position))
                val vertMargin = pageHeight * (1f - scaleFactor) / 2f
                val horzMargin = pageWidth * (1f - scaleFactor) / 2f
                view.translationX = if (position < 0f) horzMargin - vertMargin / 2f else -horzMargin + vertMargin / 2f
                view.scaleX = scaleFactor
                view.scaleY = scaleFactor
                view.alpha = MIN_ALPHA + (scaleFactor - MIN_SCALE) / (1f - MIN_SCALE) * (1f - MIN_ALPHA)
            }
            else -> view.alpha = 0f
        }
    }
}
