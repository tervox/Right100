package com.goodwy.gallery.fragments

import android.graphics.Point
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.view.MotionEvent
import android.view.View
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.goodwy.commons.extensions.*
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.models.Medium
import java.io.File
import kotlin.math.abs
import com.awxkee.jxlcoder.JxlCoder

abstract class ViewPagerFragment : Fragment() {
    var listener: FragmentListener? = null

    protected var mTouchDownTime = 0L
    protected var mTouchDownX = 0f
    protected var mTouchDownY = 0f
    protected var mCloseDownThreshold = 48f
    protected var mIgnoreCloseDown = false

    abstract fun fullscreenToggled(isFullscreen: Boolean)

    interface FragmentListener {
        fun fragmentClicked()

        fun videoEnded(): Boolean

        fun goToPrevItem()

        fun goToNextItem()

        fun launchViewVideoIntent(path: String)

        fun isSlideShowActive(): Boolean

        fun updatePlayPause(play: Boolean)

        fun isFullScreen(): Boolean

        fun refreshMenuItems() {}


    }

    fun getMediumExtendedDetails(medium: Medium): String {
        val context = context ?: return ""
        val file = File(medium.path)
        if (!context.getDoesFilePathExist(file.absolutePath)) {
            return ""
        }

        val path = "${file.parent?.trimEnd('/')}/"
        val exif = try {
            ExifInterface(medium.path)
        } catch (_: Exception) {
            return ""
        }

        val details = StringBuilder()
        val detailsFlag = context.config.extendedDetails
        if (detailsFlag and EXT_NAME != 0) {
            medium.name.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_PATH != 0) {
            path.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_SIZE != 0) {
            file.length().formatSize().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_RESOLUTION != 0) {
            getResolution(medium, file)?.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_LAST_MODIFIED != 0) {
            getFileLastModified(file).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_DATE_TAKEN != 0) {
            exif.getExifDateTaken(context).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_CAMERA_MODEL != 0) {
            exif.getExifCameraModel().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_EXIF_PROPERTIES != 0) {
            exif.getExifProperties().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_GPS != 0) {
            getLatLonAltitude(medium.path).let { if (it.isNotEmpty()) details.appendLine(it) }
        }
        return details.toString().trim()
    }

    fun getPathToLoad(medium: Medium): String {
        val context = context ?: return medium.path
        return if (context.isPathOnOTG(medium.path)) {
            medium.path.getOTGPublicPath(context)
        } else {
            medium.path
        }
    }

    private fun getResolution(medium: Medium, file: File): String? {
        if (medium.name.endsWith(".jxl",ignoreCase = true)) {
            val resolution = try {
                JxlCoder.getSize(file.readBytes())
            } catch (_: OutOfMemoryError) {
                null
            }
            return resolution?.let { Point(it.width,it.height).formatAsResolution() }
        } else {
            return context?.getResolution(file.absolutePath)?.formatAsResolution()
        }
    }

    private fun getFileLastModified(file: File): String {
        val context = context ?: return ""
        val projection = arrayOf(Images.Media.DATE_MODIFIED)
        val uri = Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            return if (cursor.moveToFirst()) {
                val dateModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000L
                dateModified.formatDate(context)
            } else {
                file.lastModified().formatDate(context)
            }
        }
        return ""
    }

    private fun getLatLonAltitude(path: String): String {
        var result = ""
        val exif = try {
            ExifInterface(path)
        } catch (_: Exception) {
            return ""
        }

        val latLon = FloatArray(2)

        if (exif.getLatLong(latLon)) {
            result = "${latLon[0]},  ${latLon[1]}"
        }

        val altitude = exif.getAltitude(0.0)
        if (altitude != 0.0) {
            result += ",  ${altitude}m"
        }

        return result.trimStart(',').trim()
    }

    protected fun updateVerticalGestureInterception(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                view.parent?.requestDisallowInterceptTouchEvent(false)

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - mTouchDownX)
                val dy = abs(event.rawY - mTouchDownY)

                // Antes so segurava depois de mCloseDownThreshold / 2. Ate esse
                // ponto o gesto ja tinha subido para o ViewPager, que ENGOLE o toque
                // para trocar de pagina — e o ACTION_UP nunca chegava em handleEvent,
                // entao o puxao para fechar nunca funcionava. Basta a intencao vertical
                // aparecer (dy > dx) para o ViewPager ser bloqueado.
                if (context?.config?.allowDownGesture == true &&
                    !mIgnoreCloseDown &&
                    abs(dy) > dx * 1.15f
                ) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    // O fechamento por arrasto NAO e mais decidido aqui. Cada view filha (foto, GIF, video)
    // tinha o seu proprio listener, e o ViewPager / GestureFrameLayout "roubavam" o toque
    // (ACTION_CANCEL) antes do ACTION_UP chegar, entao o gesto falhava de forma intermitente.
    // Agora quem decide e BaseViewerActivity.dispatchTouchEvent, que enxerga TODOS os toques.
    // Este metodo so guarda o ponto inicial para updateVerticalGestureInterception().
    @Suppress("UNUSED_PARAMETER")
    protected fun handleEvent(event: MotionEvent, isZoomedOut: () -> Boolean = { true }) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mTouchDownTime = System.currentTimeMillis()
                mTouchDownX = event.rawX
                mTouchDownY = event.rawY
                mIgnoreCloseDown = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> mIgnoreCloseDown = true

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mIgnoreCloseDown = false
        }
    }

    // True quando a midia esta no encaixe normal (sem zoom). Sobrescrito por Photo/VideoFragment.
    open fun isZoomedOutForDismiss(): Boolean = true

    // Onde o toque comecou: livre, faixa de brilho/volume ou controles. Ver DISMISS_AREA_*.
    open fun getDismissAreaMode(rawX: Float, rawY: Float): Int = DISMISS_AREA_FREE

    protected fun isTouchInside(v: View?, rawX: Float, rawY: Float): Boolean {
        if (v == null || !v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
    }
}
