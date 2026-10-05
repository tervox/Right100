package com.goodwy.gallery.activities

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.launch
import com.goodwy.commons.extensions.updateMarginWithBase
import com.goodwy.commons.extensions.updatePaddingWithBase
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.fragments.ViewPagerFragment
import com.goodwy.gallery.helpers.DISMISS_AREA_BLOCKED
import com.goodwy.gallery.helpers.DISMISS_AREA_FREE
import com.goodwy.gallery.helpers.DISMISS_AREA_STRIP
import com.goodwy.gallery.helpers.DISMISS_MIN_DISTANCE_DP
import com.goodwy.gallery.helpers.DISMISS_STRIP_HEIGHT_FRACTION
import com.goodwy.gallery.helpers.MAX_CLOSE_DOWN_GESTURE_DURATION
import kotlin.math.abs

abstract class BaseViewerActivity : SimpleActivity() {
    override val padCutout: Boolean = false
    abstract val contentHolder: View
    abstract val appBarLayout: AppBarLayout

    // ---- Fechar o visualizador arrastando para cima/baixo ----
    // Decidido aqui (e nao nas views filhas) porque dispatchTouchEvent da Activity ve TODOS os
    // toques, mesmo quando o ViewPager, o GestureFrameLayout ou o SubsamplingScaleImageView
    // consomem ou cancelam o gesto. Fragment atual vem de getDismissFragment().
    protected open fun getDismissFragment(): ViewPagerFragment? = null

    private var mDismissDownX = 0f
    private var mDismissDownY = 0f
    private var mDismissDownTime = 0L
    private var mDismissArmed = false
    private var mDismissAreaMode = DISMISS_AREA_FREE
    private var mDismissMultiTouch = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        try {
            trackDismissGesture(ev)
        } catch (e: Exception) {
            mDismissArmed = false
        }
        return handled
    }

    private fun trackDismissGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDismissMultiTouch = false
                mDismissDownX = ev.rawX
                mDismissDownY = ev.rawY
                mDismissDownTime = System.currentTimeMillis()
                val fragment = getDismissFragment()
                mDismissAreaMode = when {
                    fragment == null -> DISMISS_AREA_BLOCKED
                    isTouchOnAppBar(ev.rawX, ev.rawY) -> DISMISS_AREA_BLOCKED
                    else -> fragment.getDismissAreaMode(ev.rawX, ev.rawY)
                }
                mDismissArmed = config.allowDownGesture &&
                    mDismissAreaMode != DISMISS_AREA_BLOCKED &&
                    fragment?.isZoomedOutForDismiss() == true
            }

            MotionEvent.ACTION_POINTER_DOWN -> mDismissMultiTouch = true

            MotionEvent.ACTION_CANCEL -> mDismissArmed = false

            MotionEvent.ACTION_UP -> {
                val armed = mDismissArmed && !mDismissMultiTouch
                mDismissArmed = false
                if (!armed || isFinishing || isDestroyed) return

                val diffX = mDismissDownX - ev.rawX
                val diffY = mDismissDownY - ev.rawY
                val duration = System.currentTimeMillis() - mDismissDownTime
                val density = resources.displayMetrics.density
                val screenHeight = resources.displayMetrics.heightPixels

                val minDistance = if (mDismissAreaMode == DISMISS_AREA_STRIP) {
                    // Nas faixas de brilho/volume um arrasto curto ajusta o valor; so um
                    // arrasto longo e rapido conta como "fechar".
                    screenHeight * DISMISS_STRIP_HEIGHT_FRACTION
                } else {
                    DISMISS_MIN_DISTANCE_DP * density
                }
                val maxDuration = if (mDismissAreaMode == DISMISS_AREA_STRIP) {
                    MAX_CLOSE_DOWN_GESTURE_DURATION.toLong()
                } else {
                    MAX_CLOSE_DOWN_GESTURE_DURATION * 3L
                }

                val verticalDominant = abs(diffY) > abs(diffX) * 1.2f
                val farEnough = abs(diffY) > minDistance
                val fastEnough = duration < maxDuration
                // Confere o zoom de novo na soltura: o usuario pode ter dado zoom no meio do gesto.
                val stillZoomedOut = getDismissFragment()?.isZoomedOutForDismiss() == true

                com.goodwy.gallery.App.logGesture(
                    "DISMISS up diffX=%.1f diffY=%.1f dur=%d area=%d vertical=%b far=%b(min=%.0f) fast=%b zoomedOut=%b".format(
                        diffX, diffY, duration, mDismissAreaMode, verticalDominant, farEnough, minDistance, fastEnough, stillZoomedOut
                    )
                )

                if (verticalDominant && farEnough && fastEnough && stillZoomedOut) {
                    finish()
                    // diffY < 0: o dedo desceu, a tela sai para baixo; senao o dedo subiu.
                    if (diffY < 0) {
                        overridePendingTransition(0, com.goodwy.commons.R.anim.slide_down)
                    } else {
                        overridePendingTransition(com.goodwy.commons.R.anim.slide_down, 0)
                    }
                }
            }
        }
    }

    private fun isTouchOnAppBar(rawX: Float, rawY: Float): Boolean {
        val bar = appBarLayout
        if (!bar.isShown) return false
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + bar.width && rawY >= loc[1] && rawY <= loc[1] + bar.height
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBarsAppearance = false

        val contentRoot = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            setupEdgeToEdge(insets)
            insets
        }
        registerShowNotchCollector(contentRoot)
    }

    private fun registerShowNotchCollector(view: View) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                config.showNotchFlow.collect {
                    view.requestApplyInsets()
                }
            }
        }
    }

    private fun setupEdgeToEdge(insets: WindowInsetsCompat) {
        if (config.showNotch) {
            val systemAndCutout =
                insets.getInsetsIgnoringVisibility(Type.systemBars() or Type.displayCutout())
            appBarLayout.updatePaddingWithBase(
                top = systemAndCutout.top,
                left = systemAndCutout.left,
                right = systemAndCutout.right
            )

            contentHolder.updatePaddingWithBase(left = 0, top = 0, right = 0, bottom = 0)
        } else {
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
            val cutout = insets.getInsetsIgnoringVisibility(Type.displayCutout())
            appBarLayout.updatePaddingWithBase(
                top = if (cutout.top > 0) 0 else system.top,
                left = if (cutout.left > 0) 0 else system.left,
                right = if (cutout.right > 0) 0 else system.right
            )

            contentHolder.updatePaddingWithBase(
                left = cutout.left,
                top = cutout.top,
                right = cutout.right,
                bottom = cutout.bottom
            )
        }
    }

    fun applyProperHorizontalInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            if (config.showNotch) {
                val systemAndCutout =
                    insets.getInsetsIgnoringVisibility(Type.systemBars() or Type.displayCutout())
                view.updateMarginWithBase(
                    left = systemAndCutout.left,
                    right = systemAndCutout.right
                )
            } else {
                val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
                val cutout = insets.getInsetsIgnoringVisibility(Type.displayCutout())
                view.updateMarginWithBase(
                    left = if (cutout.left > 0) 0 else system.left,
                    right = if (cutout.right > 0) 0 else system.right
                )
            }
            insets
        }
    }

    // A tela e edge-to-edge (o conteudo desenha por baixo da barra de navegacao do sistema
    // de proposito, pra foto/video ocupar a tela toda). Mas isso significa que qualquer view
    // fixada no rodape (como a barra de acoes) precisa ganhar um respiro de baixo do tamanho
    // da barra de navegacao, ou os icones ficam embaixo dela e o toque nao funciona.
    fun applyProperBottomInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
            view.updatePaddingWithBase(bottom = system.bottom)
            insets
        }
        view.requestApplyInsets()
    }
}
