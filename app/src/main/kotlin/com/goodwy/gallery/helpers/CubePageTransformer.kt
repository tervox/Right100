package com.goodwy.gallery.helpers

import android.view.View
import androidx.viewpager.widget.ViewPager

class CubePageTransformer : ViewPager.PageTransformer {
    override fun transformPage(view: View, position: Float) {
        view.pivotX = if (position < 0f) view.width.toFloat() else 0f
        view.pivotY = view.height / 2f
        view.rotationY = 90f * position
        view.cameraDistance = view.width * 6f
    }
}
