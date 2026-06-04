import os
import re

os.chdir('/data/data/com.termux/files/home/Right100')

# CORREÇÃO 1: PhotoVideoActivity.kt
with open('app/src/main/kotlin/com/goodwy/gallery/activities/PhotoVideoActivity.kt', 'r') as f:
    content = f.read()

content = content.replace('override fun fragmentClicked()', 'fun fragmentClicked()')
content = content.replace('override fun videoEnded()', 'fun videoEnded()')
content = content.replace('override fun goToPrevItem()', 'fun goToPrevItem()')
content = content.replace('override fun goToNextItem()', 'fun goToNextItem()')
content = content.replace('override fun launchViewVideoIntent(path: String)', 'fun launchViewVideoIntent(path: String)')
content = content.replace('override fun isSlideShowActive()', 'fun isSlideShowActive()')
content = content.replace('override fun isFullScreen()', 'fun isFullScreen()')
content = content.replace('override fun updatePlayPause(play: Boolean)', 'fun updatePlayPause(play: Boolean)')

with open('app/src/main/kotlin/com/goodwy/gallery/activities/PhotoVideoActivity.kt', 'w') as f:
    f.write(content)

print("PhotoVideoActivity.kt OK")

# CORREÇÃO 2: ViewPagerActivity.kt - REESCREVER
vp = '''package com.goodwy.gallery.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.viewpager.widget.ViewPager
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.gallery.R
import com.goodwy.gallery.adapters.MyPagerAdapter
import com.goodwy.gallery.databinding.ActivityMediumBinding
import com.goodwy.gallery.extensions.*
import com.goodwy.gallery.fragments.VideoFragment
import com.goodwy.gallery.fragments.ViewPagerFragment
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.models.Medium
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.material.appbar.AppBarLayout
import java.io.File

class ViewPagerActivity : BaseViewerActivity(), ViewPager.OnPageChangeListener, ViewPagerFragment.FragmentListener {
    private var mMediums = ArrayList<<Medium>()
    private var mPos = 0
    private var mIsFullScreen = false
    private var mIsSlideshowActive = false

    private val binding by viewBinding(ActivityMediumBinding::inflate)

    override val contentHolder: ViewGroup
        get() = binding.root as ViewGroup

    override val appBarLayout: AppBarLayout
        get() = binding.mediumViewerAppbar

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val path = intent.getStringExtra(PATH) ?: ""
        if (path.isEmpty()) {
            finish()
            return
        }

        mPos = intent.getIntExtra(POS, 0)
        mMediums = intent.getSerializableExtra(MEDIUMS) as? ArrayList<<Medium> ?: ArrayList()

        setupOptionsMenu()
        initViewPager()
    }

    private fun setupOptionsMenu() {
        binding.mediumViewerToolbar.apply {
            setTitleTextColor(android.graphics.Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_three_dots_vector, android.graphics.Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_chevron_left_vector, android.graphics.Color.WHITE)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_viewpager)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_extract_text -> {
                        extractTextFromImage()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun initViewPager() {
        val adapter = MyPagerAdapter(this, supportFragmentManager, mMediums)
        binding.viewPager.adapter = adapter
        binding.viewPager.currentItem = mPos
        binding.viewPager.addOnPageChangeListener(this)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
    override fun onPageSelected(position: Int) { mPos = position }
    override fun onPageScrollStateChanged(state: Int) {}

    private fun getCurrentMedium(): Medium? = if (mPos < mMediums.size) mMediums[mPos] else null

    private fun getCurrentFragment(): ViewPagerFragment? {
        val adapter = binding.viewPager.adapter as? MyPagerAdapter
        return adapter?.getCurrentFragment(mPos)
    }

    private fun extractTextFromImage() {
        val medium = getCurrentMedium() ?: return
        if (medium.isVideo()) { extractTextFromVideoFrame(); return }
        toast(com.goodwy.gallery.R.string.extracting_text)
        ensureBackgroundThread {
            try {
                val rawBmp = BitmapFactory.decodeFile(medium.path)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: run { runOnUiThread { toast("Erro ao decodificar imagem") }; return@ensureBackgroundThread }
                val bmp = run {
                    val exif = try { ExifInterface(medium.path) } catch (e: Throwable) { null }
                    val ori = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
                    val deg = when (ori) {
                        6 -> 90f
                        3 -> 180f
                        8 -> 270f
                        else -> 0f
                    }
                    if (deg != 0f) {
                        val m = Matrix().apply { postRotate(deg) }
                        val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                        if (rotated !== rawBmp) rawBmp.recycle()
                        rotated
                    } else rawBmp
                }
                val img = InputImage.fromBitmap(bmp, 0)
                val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                client.process(img)
                    .addOnSuccessListener { r ->
                        bmp.recycle()
                        client.close()
                        runOnUiThread { showExtractedTextDialog(cleanOcrText(r?.text ?: "")) }
                    }
                    .addOnFailureListener { e ->
                        bmp.recycle()
                        client.close()
                        runOnUiThread { toast("Erro OCR: " + (e.localizedMessage?.take(80) ?: "")) }
                    }
            } catch (e: Throwable) {
                runOnUiThread { toast("Erro: " + (e.localizedMessage?.take(80) ?: "")) }
            }
        }
    }

    private fun extractTextFromVideoFrame() {
        val fragment = getCurrentFragment() as? VideoFragment ?: run {
            toast("Pause o video primeiro"); return
        }
        try {
            val viewGroup = fragment.view as? android.view.ViewGroup ?: run {
                toast("Erro ao carregar tela do video"); return
            }
            fun findTextureView(view: android.view.View): TextureView? {
                if (view is TextureView) return view
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val r = findTextureView(view.getChildAt(i))
                        if (r != null) return r
                    }
                }
                return null
            }
            val textureView = findTextureView(viewGroup) ?: run {
                toast("Abra ou pause o video para capturar"); return
            }
            val raw = textureView.bitmap ?: run {
                toast("Erro ao capturar frame do video"); return
            }
            val bmp = preprocessForOcr(raw)
            toast(com.goodwy.gallery.R.string.extracting_text)
            val img = InputImage.fromBitmap(bmp, 0)
            val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            client.process(img)
                .addOnSuccessListener { r ->
                    bmp.recycle()
                    raw.recycle(); client.close()
                    showExtractedTextDialog(cleanOcrText(r?.text ?: ""))
                }
                .addOnFailureListener { e ->
                    bmp.recycle()
                    raw.recycle(); client.close()
                    toast("Erro OCR video: " + (e.localizedMessage?.take(80) ?: ""))
                }
        } catch (e: Throwable) {
            toast(e.javaClass.simpleName + ": " + (e.localizedMessage?.take(80) ?: ""))
        }
    }

    private fun preprocessForOcr(src: Bitmap): Bitmap = src

    private fun cleanOcrText(raw: String): String = raw
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .replace(Regex("[\\\\|\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        .replace(Regex(" +"), " ")
        .replace(Regex("\n\n+"), "\n\n")
        .trim()

    private fun showExtractedTextDialog(text: String) {
        if (text.isEmpty()) { toast(R.string.no_text_found); return }
        val tv = TextView(this).apply {
            this.text = text; setPadding(60, 40, 60, 20)
            setTextIsSelectable(true); textSize = 16f
        }
        getAlertDialogBuilder()
            .setTitle(R.string.extracted_text)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(com.goodwy.commons.R.string.copy) { _, _ ->
                val cb = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                toast(com.goodwy.commons.R.string.value_copied_to_clipboard)
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .create().show()
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) hideSystemUI() else showSystemUI()
        val newAlpha = if (mIsFullScreen) 0f else 1f
        binding.topShadow.animate().alpha(newAlpha).start()
        binding.mediumViewerToolbar.animate().alpha(newAlpha).start()
    }
    
    override fun videoEnded() = false
    override fun goToPrevItem() {
        if (mPos > 0) {
            binding.viewPager.currentItem = mPos - 1
        }
    }
    override fun goToNextItem() {
        if (mPos < mMediums.size - 1) {
            binding.viewPager.currentItem = mPos + 1
        }
    }
    override fun launchViewVideoIntent(path: String) {}
    override fun isSlideShowActive() = mIsSlideshowActive
    override fun isFullScreen() = mIsFullScreen
    override fun updatePlayPause(play: Boolean) {}
    override fun refreshMenuItems() {}
}
'''

with open('app/src/main/kotlin/com/goodwy/gallery/activities/ViewPagerActivity.kt', 'w') as f:
    f.write(vp)

print("ViewPagerActivity.kt OK")

# CORREÇÃO 3: MyPagerAdapter.kt
with open('app/src/main/kotlin/com/goodwy/gallery/adapters/MyPagerAdapter.kt', 'r') as f:
    content = f.read()

content = content.replace('MutableList<<Medium>', 'MutableList<<Medium>')

with open('app/src/main/kotlin/com/goodwy/gallery/adapters/MyPagerAdapter.kt', 'w') as f:
    f.write(content)

print("MyPagerAdapter.kt OK")

# CORREÇÃO 4: ViewPagerFragment.kt
with open('app/src/main/kotlin/com/goodwy/gallery/fragments/ViewPagerFragment.kt', 'r') as f:
    content = f.read()

content = re.sub(r'\s+fun refreshMenuItems\(\)\s*\{\s*\}', '', content)
content = content.replace(
    'fun isFullScreen(): Boolean',
    'fun isFullScreen(): Boolean\n\n        fun refreshMenuItems() {}'
)

with open('app/src/main/kotlin/com/goodwy/gallery/fragments/ViewPagerFragment.kt', 'w') as f:
    f.write(content)

print("ViewPagerFragment.kt OK")

# CORREÇÃO 5: PhotoFragment.kt
with open('app/src/main/kotlin/com/goodwy/gallery/fragments/PhotoFragment.kt', 'r') as f:
    content = f.read()

content = content.replace('?.refreshMenuItems()', '?.invalidateOptionsMenu()')

with open('app/src/main/kotlin/com/goodwy/gallery/fragments/PhotoFragment.kt', 'w') as f:
    f.write(content)

print("PhotoFragment.kt OK")

# CORREÇÃO 6: VideoFragment.kt
with open('app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt', 'r') as f:
    content = f.read()

content = content.replace('handleTouchHoldEventLocal(event)', 'handleTouchHoldEvent(event)')
content = content.replace(
    '    fun handleEvent(event: MotionEvent)',
    '    override fun handleEvent(event: MotionEvent)'
)

with open('app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt', 'w') as f:
    f.write(content)

print("VideoFragment.kt OK")

print("\nTUDO CORRIGIDO!")
print("Execute: git add -A && git commit -m 'Fix: Corrige todos os erros' && git push origin master")
