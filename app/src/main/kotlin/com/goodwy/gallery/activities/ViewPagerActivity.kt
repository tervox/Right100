package com.goodwy.gallery.activities

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.view.MenuItem
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.viewpager.widget.ViewPager
import com.goodwy.commons.dialogs.PropertiesDialog
import com.goodwy.commons.dialogs.RenameItemDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FileDirItem
import com.goodwy.gallery.R
import com.goodwy.gallery.adapters.MyPagerAdapter
import com.goodwy.gallery.databinding.ActivityMediumBinding
import com.goodwy.gallery.dialogs.DeleteWithRememberDialog
import com.goodwy.gallery.dialogs.SlideshowDialog
import com.goodwy.gallery.extensions.*
import com.goodwy.gallery.fragments.PhotoFragment
import com.goodwy.gallery.fragments.VideoFragment
import com.goodwy.gallery.fragments.ViewPagerFragment
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.models.Medium
import com.google.android.material.appbar.AppBarLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File

class ViewPagerActivity : BaseViewerActivity(), ViewPager.OnPageChangeListener, ViewPagerFragment.FragmentListener {

    companion object {
        var pendingMediums: ArrayList<Medium>? = null
    }

    private var mMediums = ArrayList<Medium>()
    private var mPos = 0
    private var mIsFullScreen = false
    private var mIsSlideshowActive = false
    private var mSlideshowHandler = Handler()
    private var mSlideshowInterval = SLIDESHOW_DEFAULT_INTERVAL
    private var mSlideshowMoveBackwards = false

    private val binding by viewBinding(ActivityMediumBinding::inflate)

    override val contentHolder: ViewGroup
        get() = binding.root as ViewGroup

    override val appBarLayout: AppBarLayout
        get() = binding.mediumViewerAppbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val path = intent.getStringExtra(PATH) ?: ""
        if (path.isEmpty()) { finish(); return }

        mMediums = pendingMediums ?: ArrayList()
        pendingMediums = null

        if (mMediums.isEmpty()) {
            // fallback: cria lista com o arquivo único (ex: intent externo)
            val type = when {
                path.isVideoFast() -> TYPE_VIDEOS
                path.isGif() -> TYPE_GIFS
                else -> TYPE_IMAGES
            }
            mMediums.add(Medium(null, path.getFilenameFromPath(), path, path.getParentPath(), 0, 0, 0, type, 0, false, 0L, 0L))
        }

        mPos = intent.getIntExtra("pos", 0).coerceIn(0, (mMediums.size - 1).coerceAtLeast(0))

        binding.mediumViewerToolbar.title = mMediums.getOrNull(mPos)?.name ?: path.getFilenameFromPath()

        setupOptionsMenu()
        initViewPager()
        applyProperBottomInsets(binding.bottomActions.root)
        initBottomActions()

        // A configuração "Esconder barra de sistema" auto-esconde a interface após um delay
        if (config.hideSystemUI) {
            binding.viewPager.post {
                Handler().postDelayed({
                    if (!isDestroyed && !mIsFullScreen) fragmentClicked()
                }, HIDE_SYSTEM_UI_DELAY)
            }
        }

        // Quando vem do botão "Apresentação" da tela de pastas, mostra o diálogo de opções
        if (intent.getBooleanExtra(SLIDESHOW_START_ON_ENTER, false)) {
            binding.viewPager.post { initSlideshow() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("saved_path", getCurrentPath())
    }


    override fun onResume() {
        super.onResume()
        refreshMenuItems()
    }

    override fun onPause() {
        super.onPause()
        stopSlideshow()
    }

    private fun initViewPager() {
        val adapter = MyPagerAdapter(this, supportFragmentManager, mMediums)
        binding.viewPager.adapter = adapter
        binding.viewPager.currentItem = mPos
        binding.viewPager.addOnPageChangeListener(this)
        binding.viewPager.offscreenPageLimit = 2
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    private fun setupOptionsMenu() {
        binding.mediumViewerToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_chevron_left_vector, Color.WHITE)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_viewpager)
        }
        updateMenuItemColors(binding.mediumViewerToolbar.menu, forceWhiteIcons = true)
        binding.mediumViewerToolbar.setOnMenuItemClickListener { handleMenuClick(it) }
    }

    override fun refreshMenuItems() {
        val medium = getCurrentMedium() ?: return
        val isImage = medium.isImage()
        val isInRecycleBin = medium.getIsInRecycleBin()
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0

        runOnUiThread {
            binding.mediumViewerToolbar.menu.apply {
                findItem(R.id.menu_share)?.isVisible = visibleBottomActions and BOTTOM_ACTION_SHARE == 0
                findItem(R.id.menu_delete)?.isVisible = visibleBottomActions and BOTTOM_ACTION_DELETE == 0
                findItem(R.id.menu_edit)?.isVisible = visibleBottomActions and BOTTOM_ACTION_EDIT == 0 && isImage
                findItem(R.id.menu_rename)?.isVisible = visibleBottomActions and BOTTOM_ACTION_RENAME == 0 && !isInRecycleBin
                findItem(R.id.menu_rotate)?.isVisible = isImage
                findItem(R.id.menu_properties)?.isVisible = visibleBottomActions and BOTTOM_ACTION_PROPERTIES == 0
                findItem(R.id.menu_set_as)?.isVisible = visibleBottomActions and BOTTOM_ACTION_SET_AS == 0
                findItem(R.id.menu_copy_to)?.isVisible = visibleBottomActions and BOTTOM_ACTION_COPY == 0
                findItem(R.id.menu_move_to)?.isVisible = visibleBottomActions and BOTTOM_ACTION_MOVE == 0
                findItem(R.id.menu_show_on_map)?.isVisible = visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP == 0
                findItem(R.id.menu_slideshow)?.isVisible = visibleBottomActions and BOTTOM_ACTION_SLIDESHOW == 0
                findItem(R.id.menu_add_to_favorites)?.isVisible = !medium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !isInRecycleBin
                findItem(R.id.menu_remove_from_favorites)?.isVisible = medium.isFavorite && visibleBottomActions and BOTTOM_ACTION_TOGGLE_FAVORITE == 0 && !isInRecycleBin
                findItem(R.id.menu_hide)?.isVisible = !medium.isHidden() && !isInRecycleBin && (!isRPlus() || isExternalStorageManager())
                findItem(R.id.menu_unhide)?.isVisible = medium.isHidden() && !isInRecycleBin && (!isRPlus() || isExternalStorageManager())
                findItem(R.id.menu_restore_file)?.isVisible = isInRecycleBin
                findItem(R.id.menu_open_with)?.isVisible = true
                findItem(R.id.menu_extract_text)?.isVisible = (isImage && !medium.isGIF()) || medium.isVideo()
            }

            if (config.bottomActions) updateBottomActionIcons(medium)
        }
    }

    private fun handleMenuClick(item: MenuItem): Boolean {
        val path = getCurrentPath()
        when (item.itemId) {
            R.id.menu_share -> shareMediumPath(path)
            R.id.menu_delete -> askConfirmDelete()
            R.id.menu_edit -> openEditor(path)
            R.id.menu_rename -> renameCurrentFile()
            R.id.menu_rotate_right -> rotateCurrentImage(90)
            R.id.menu_rotate_left -> rotateCurrentImage(-90)
            R.id.menu_rotate_one_eighty -> rotateCurrentImage(180)
            R.id.menu_properties -> PropertiesDialog(this, path, config.shouldShowHidden)
            R.id.menu_set_as -> setAs(path)
            R.id.menu_copy_to -> copyMoveTo(true)
            R.id.menu_move_to -> copyMoveTo(false)
            R.id.menu_show_on_map -> showFileOnMap(path)
            R.id.menu_slideshow -> initSlideshow()
            R.id.menu_open_with -> openPath(path, true)
            R.id.menu_add_to_favorites -> toggleFavorite()
            R.id.menu_remove_from_favorites -> toggleFavorite()
            R.id.menu_hide -> toggleVisibility(true)
            R.id.menu_unhide -> toggleVisibility(false)
            R.id.menu_restore_file -> restoreCurrentFile()
            R.id.menu_extract_text -> extractTextFromCurrentMedia()
            else -> return false
        }
        return true
    }

    // ── Bottom actions ─────────────────────────────────────────────────────────

    private fun initBottomActions() {
        if (!config.bottomActions) {
            binding.bottomActions.root.beGone()
            return
        }
        binding.bottomActions.root.beVisible()
        val iconColor = Color.WHITE
        val medium = getCurrentMedium()
        val visible = config.visibleBottomActions
        val isImage = medium?.isImage() == true
        val isVideo = medium?.isVideo() == true || medium?.isGIF() == true
        val isInRecycleBin = medium?.getIsInRecycleBin() == true

        // Esconde todos primeiro, mostra só o que deve aparecer
        listOf(
            binding.bottomActions.bottomShare,
            binding.bottomActions.bottomFavorite,
            binding.bottomActions.bottomDelete,
            binding.bottomActions.bottomEdit,
            binding.bottomActions.bottomProperties,
            binding.bottomActions.bottomSetAs,
            binding.bottomActions.bottomCopy,
            binding.bottomActions.bottomMove,
            binding.bottomActions.bottomRename,
            binding.bottomActions.bottomSlideshow,
            binding.bottomActions.bottomShowOnMap,
            binding.bottomActions.bottomExtractText,
            binding.bottomActions.bottomPlayPause,
            binding.bottomActions.bottomMute,
            binding.bottomActions.bottomRotate,
            binding.bottomActions.bottomChangeOrientation,
            binding.bottomActions.bottomToggleFileVisibility,
            binding.bottomActions.bottomResize
        ).forEach { it.beGone(); it.applyColorFilter(iconColor) }

        // Agora mostra os que devem aparecer e conecta cliques
        binding.bottomActions.bottomShare.beVisibleIf(visible and BOTTOM_ACTION_SHARE != 0)
        binding.bottomActions.bottomShare.setOnClickListener { shareMediumPath(getCurrentPath()) }
        binding.bottomActions.bottomShare.setOnLongClickListener { toast(com.goodwy.commons.R.string.share); true }

        binding.bottomActions.bottomFavorite.beVisibleIf(visible and BOTTOM_ACTION_TOGGLE_FAVORITE != 0 && !isInRecycleBin)
        binding.bottomActions.bottomFavorite.setOnClickListener { toggleFavorite() }
        binding.bottomActions.bottomFavorite.setOnLongClickListener { toast(R.string.toggle_favorite); true }

        binding.bottomActions.bottomDelete.beVisibleIf(visible and BOTTOM_ACTION_DELETE != 0)
        binding.bottomActions.bottomDelete.setOnClickListener { askConfirmDelete() }
        binding.bottomActions.bottomDelete.setOnLongClickListener { toast(com.goodwy.commons.R.string.delete); true }

        binding.bottomActions.bottomEdit.beVisibleIf(visible and BOTTOM_ACTION_EDIT != 0 && isImage)
        binding.bottomActions.bottomEdit.setOnClickListener { openEditor(getCurrentPath()) }
        binding.bottomActions.bottomEdit.setOnLongClickListener { toast(R.string.edit); true }

        binding.bottomActions.bottomProperties.beVisibleIf(visible and BOTTOM_ACTION_PROPERTIES != 0)
        binding.bottomActions.bottomProperties.setOnClickListener { PropertiesDialog(this, getCurrentPath(), config.shouldShowHidden) }
        binding.bottomActions.bottomProperties.setOnLongClickListener { toast(com.goodwy.commons.R.string.properties); true }

        binding.bottomActions.bottomSetAs.beVisibleIf(visible and BOTTOM_ACTION_SET_AS != 0 && isImage)
        binding.bottomActions.bottomSetAs.setOnClickListener { setAs(getCurrentPath()) }
        binding.bottomActions.bottomSetAs.setOnLongClickListener { toast(R.string.set_as); true }

        binding.bottomActions.bottomCopy.beVisibleIf(visible and BOTTOM_ACTION_COPY != 0)
        binding.bottomActions.bottomCopy.setOnClickListener { copyMoveTo(true) }
        binding.bottomActions.bottomCopy.setOnLongClickListener { toast(com.goodwy.commons.R.string.copy); true }

        binding.bottomActions.bottomMove.beVisibleIf(visible and BOTTOM_ACTION_MOVE != 0)
        binding.bottomActions.bottomMove.setOnClickListener { copyMoveTo(false) }
        binding.bottomActions.bottomMove.setOnLongClickListener { toast(com.goodwy.commons.R.string.move); true }

        binding.bottomActions.bottomRename.beVisibleIf(visible and BOTTOM_ACTION_RENAME != 0 && !isInRecycleBin)
        binding.bottomActions.bottomRename.setOnClickListener { renameCurrentFile() }
        binding.bottomActions.bottomRename.setOnLongClickListener { toast(com.goodwy.commons.R.string.rename); true }

        binding.bottomActions.bottomSlideshow.beVisibleIf(visible and BOTTOM_ACTION_SLIDESHOW != 0)
        binding.bottomActions.bottomSlideshow.setOnClickListener { initSlideshow() }
        binding.bottomActions.bottomSlideshow.setOnLongClickListener { toast(R.string.slideshow); true }

        binding.bottomActions.bottomShowOnMap.beVisibleIf(visible and BOTTOM_ACTION_SHOW_ON_MAP != 0)
        binding.bottomActions.bottomShowOnMap.setOnClickListener { showFileOnMap(getCurrentPath()) }
        binding.bottomActions.bottomShowOnMap.setOnLongClickListener { toast(R.string.show_on_map); true }

        binding.bottomActions.bottomExtractText.beVisibleIf(visible and BOTTOM_ACTION_EXTRACT_TEXT != 0 && (isImage || isVideo))
        binding.bottomActions.bottomExtractText.setOnClickListener { extractTextFromCurrentMedia() }
        binding.bottomActions.bottomExtractText.setOnLongClickListener { toast(R.string.extract_text); true }

        binding.bottomActions.bottomPlayPause.beVisibleIf(isVideo && visible and BOTTOM_ACTION_PLAY_PAUSE != 0)
        binding.bottomActions.bottomPlayPause.setOnClickListener { (getCurrentFragment() as? VideoFragment)?.togglePlayPause() }

        binding.bottomActions.bottomMute.beVisibleIf(isVideo && visible and BOTTOM_ACTION_MUTE != 0)
        binding.bottomActions.bottomMute.setOnClickListener {
            config.muteVideos = !config.muteVideos
            updatePlayerMuteState()
        }

        if (medium != null) updateBottomActionIcons(medium)
    }

    private fun updateBottomActionIcons(medium: Medium) {
        val starIcon = if (medium.isFavorite) com.goodwy.commons.R.drawable.ic_star_vector else com.goodwy.commons.R.drawable.ic_star_outline_vector
        binding.bottomActions.bottomFavorite.setImageResource(starIcon)
        updatePlayerMuteState()
    }

    override fun updatePlayPause(play: Boolean) {
        binding.bottomActions.bottomPlayPause.setImageResource(if (play) R.drawable.ic_play_vector else R.drawable.ic_pause_vector)
    }

    private fun updatePlayerMuteState() {
        val icon = if (config.muteVideos) R.drawable.ic_vector_speaker_off else R.drawable.ic_vector_speaker_on
        binding.bottomActions.bottomMute.setImageResource(icon)
    }

    // ── File actions ────────────────────────────────────────────────────────────

    private fun askConfirmDelete() {
        val medium = getCurrentMedium() ?: return
        val fileDirItem = medium.toFileDirItem()
        val isInRecycleBin = medium.getIsInRecycleBin()
        val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin && !isInRecycleBin)
            com.goodwy.commons.R.string.move_to_recycle_bin_confirmation
        else com.goodwy.commons.R.string.deletion_confirmation

        val name = "\"${medium.name}\""
        val question = String.format(getString(baseString), name)
        val showSkipOption = config.useRecycleBin && !isInRecycleBin

        DeleteWithRememberDialog(this, question, showSkipOption) { remember, skipRecycleBin ->
            if (remember) config.tempSkipRecycleBin = skipRecycleBin
            deleteCurrentFile(skipRecycleBin)
        }
    }

    private fun deleteCurrentFile(skipRecycleBin: Boolean) {
        // Libera o player antes: sem isso, deletar vídeo em reprodução causa IllegalArgumentException
        (getCurrentFragment() as? VideoFragment)?.releasePlayerForFileOp()
        val medium = getCurrentMedium() ?: return
        val path = medium.path
        val fileDirItem = medium.toFileDirItem()

        if (config.useRecycleBin && !skipRecycleBin && !medium.getIsInRecycleBin()) {
            movePathsInRecycleBin(arrayListOf(path)) { success ->
                runOnUiThread {
                    if (success) onCurrentFileRemoved()
                    else toast(com.goodwy.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            tryDeleteFileDirItem(fileDirItem, false, true) { success ->
                runOnUiThread { if (success) onCurrentFileRemoved() }
            }
        }
    }

    private fun onCurrentFileRemoved() {
        if (mPos < 0 || mPos >= mMediums.size) return
        mMediums.removeAt(mPos)
        if (mMediums.isEmpty()) { finish(); return }
        if (mPos >= mMediums.size) mPos = mMediums.size - 1
        (binding.viewPager.adapter as? MyPagerAdapter)?.notifyDataSetChanged()
        binding.viewPager.setCurrentItem(mPos, false)
        updateTitle()
        refreshMenuItems()
    }

    private fun renameCurrentFile() {
        val medium = getCurrentMedium() ?: return
        RenameItemDialog(this, medium.path) { newPath ->
            medium.path = newPath
            medium.name = newPath.getFilenameFromPath()
            ensureBackgroundThread { updateDBMediaPath(medium.path, newPath) }
            updateTitle()
        }
    }

    private fun rotateCurrentImage(degrees: Int) {
        getCurrentPhotoFragment()?.rotateImageViewBy(degrees)
    }

    private fun toggleFavorite() {
        val medium = getCurrentMedium() ?: return
        medium.isFavorite = !medium.isFavorite
        ensureBackgroundThread {
            updateFavorite(medium.path, medium.isFavorite)
            runOnUiThread { refreshMenuItems() }
        }
    }

    private fun toggleVisibility(hide: Boolean) {
        toggleFileVisibility(getCurrentPath(), hide) { newPath ->
            getCurrentMedium()?.apply {
                path = newPath
                name = newPath.getFilenameFromPath()
            }
            updateTitle()
            refreshMenuItems()
        }
    }

    private fun copyMoveTo(isCopy: Boolean) {
        // Libera o player antes: mover arquivo em reprodução pode causar IllegalArgumentException
        if (!isCopy) (getCurrentFragment() as? VideoFragment)?.releasePlayerForFileOp()
        val path = getCurrentPath()
        val fileDirItems = arrayListOf(FileDirItem(path, path.getFilenameFromPath()))
        tryCopyMoveFilesTo(fileDirItems, isCopy) { destination ->
            runOnUiThread {
                config.tempFolderPath = ""
                if (!isCopy) {
                    onCurrentFileRemoved()
                    updateFavoritePaths(fileDirItems, destination)
                }
            }
        }
    }

    private fun restoreCurrentFile() {
        restoreRecycleBinPath(getCurrentPath()) {
            onCurrentFileRemoved()
        }
    }

    // ── Slideshow ───────────────────────────────────────────────────────────────

    private fun initSlideshow() {
        SlideshowDialog(this) { startSlideshow() }
    }

    private fun startSlideshow() {
        hideSystemUI()
        mIsFullScreen = true
        (binding.viewPager.adapter as? MyPagerAdapter)?.toggleFullscreen(true)
        fullscreenToggled()
        mSlideshowInterval = config.slideshowInterval
        mSlideshowMoveBackwards = config.slideshowMoveBackwards
        mIsSlideshowActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Embaralha a ordem se configurado
        if (config.slideshowRandomOrder) {
            mMediums.shuffle()
            mPos = 0
            initViewPager()
        }

        applySlideshowTransformer()
        scheduleSwipe()
    }

    private fun buildTransformer(animation: Int): ViewPager.PageTransformer? = when (animation) {
        SLIDESHOW_ANIMATION_FADE -> FadePageTransformer()
        SLIDESHOW_ANIMATION_CUBE -> CubePageTransformer()
        SLIDESHOW_ANIMATION_DEPTH -> object : ViewPager.PageTransformer {
            override fun transformPage(view: android.view.View, position: Float) {
                when {
                    position < -1f -> view.alpha = 0f
                    position <= 0f -> { view.alpha = 1f; view.translationX = 0f; view.scaleX = 1f; view.scaleY = 1f }
                    position <= 1f -> {
                        view.alpha = 1f - position
                        view.translationX = view.width * -position
                        val scale = 0.75f + 0.25f * (1f - kotlin.math.abs(position))
                        view.scaleX = scale; view.scaleY = scale
                    }
                    else -> view.alpha = 0f
                }
            }
        }
        SLIDESHOW_ANIMATION_FLIP -> FlipPageTransformer()
        SLIDESHOW_ANIMATION_ZOOM_IN -> ZoomInPageTransformer()
        SLIDESHOW_ANIMATION_ZOOM_OUT -> object : ViewPager.PageTransformer {
            override fun transformPage(view: android.view.View, position: Float) {
                val pw = view.width.toFloat(); val ph = view.height.toFloat()
                when {
                    position < -1f -> view.alpha = 0f
                    position <= 1f -> {
                        val s = (0.85f).coerceAtLeast(1f - kotlin.math.abs(position))
                        val vm = ph * (1f - s) / 2f; val hm = pw * (1f - s) / 2f
                        view.translationX = if (position < 0f) hm - vm / 2f else -hm + vm / 2f
                        view.scaleX = s; view.scaleY = s
                        view.alpha = 0.5f + (s - 0.85f) / 0.15f * 0.5f
                    }
                    else -> view.alpha = 0f
                }
            }
        }
        else -> null
    }

    private val mRandomAnimations = listOf(
        SLIDESHOW_ANIMATION_FADE, SLIDESHOW_ANIMATION_CUBE, SLIDESHOW_ANIMATION_DEPTH,
        SLIDESHOW_ANIMATION_FLIP, SLIDESHOW_ANIMATION_ZOOM_IN, SLIDESHOW_ANIMATION_ZOOM_OUT
    )

    // No modo aleatório, sorteia uma animação diferente a cada chamada (cada transição)
    private fun applySlideshowTransformer() {
        val animation = if (config.slideshowAnimation == SLIDESHOW_ANIMATION_RANDOM) {
            mRandomAnimations.random()
        } else {
            config.slideshowAnimation
        }
        val transformer = buildTransformer(animation)
        binding.viewPager.setPageTransformer(false, transformer ?: DefaultPageTransformer())
    }

    private fun stopSlideshow() {
        if (mIsSlideshowActive) {
            mIsSlideshowActive = false
            mSlideshowHandler.removeCallbacksAndMessages(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.viewPager.setPageTransformer(false, DefaultPageTransformer())
            showSystemUI()
        }
    }

    private fun scheduleSwipe() {
        mSlideshowHandler.removeCallbacksAndMessages(null)
        if (!mIsSlideshowActive) return
        val current = getCurrentMedium() ?: return
        if (current.isImage() || current.isGIF()) {
            mSlideshowHandler.postDelayed({
                if (mIsSlideshowActive && !isDestroyed) swipeToNext()
            }, mSlideshowInterval * 1000L)
        } else {
            (getCurrentFragment() as? VideoFragment)?.playVideo()
        }
    }

    private fun swipeToNext() {
        applySlideshowTransformer()
        if (config.slideshowAnimation == SLIDESHOW_ANIMATION_NONE) {
            goToNextMedium(!mSlideshowMoveBackwards)
        } else {
            animatePagerTransition(!mSlideshowMoveBackwards)
        }
    }

    private fun goToNextMedium(forward: Boolean) {
        val next = binding.viewPager.currentItem + if (forward) 1 else -1
        if (next < 0 || next >= mMediums.size) {
            if (config.loopSlideshow) {
                binding.viewPager.setCurrentItem(if (forward) 0 else mMediums.lastIndex, false)
            } else {
                stopSlideshow()
                toast(R.string.slideshow_ended)
            }
        } else {
            binding.viewPager.setCurrentItem(next, false)
        }
    }

    private fun animatePagerTransition(forward: Boolean) {
        val animator = ValueAnimator.ofInt(0, binding.viewPager.width)
        animator.interpolator = DecelerateInterpolator()
        animator.duration = SLIDESHOW_SLIDE_DURATION
        val start = binding.viewPager.currentItem
        var oldDrag = 0

        animator.addUpdateListener { anim ->
            if (binding.viewPager.isFakeDragging) {
                val drag = anim.animatedValue as Int
                try { binding.viewPager.fakeDragBy((drag - oldDrag) * if (forward) -1f else 1f) }
                catch (_: Exception) { stopSlideshow() }
                oldDrag = drag
            }
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(a: Animator) {
                if (binding.viewPager.isFakeDragging) {
                    try { binding.viewPager.endFakeDrag() } catch (_: Exception) { stopSlideshow() }
                    if (binding.viewPager.currentItem == start) {
                        stopSlideshow(); toast(R.string.slideshow_ended)
                    }
                }
            }
            override fun onAnimationCancel(a: Animator) { try { binding.viewPager.endFakeDrag() } catch (_: Exception) {} }
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
        })
        binding.viewPager.beginFakeDrag()
        animator.start()
    }

    // ── OCR ────────────────────────────────────────────────────────────────────

    private var mOcrInProgress = false

    private fun extractTextFromCurrentMedia() {
        if (mOcrInProgress) {
            toast(R.string.extracting_text)
            return
        }
        val medium = getCurrentMedium() ?: return
        if (medium.isVideo()) { extractTextFromVideoFrame(); return }

        mOcrInProgress = true
        val requestedPath = medium.path
        toast(R.string.extracting_text)
        ensureBackgroundThread {
            try {
                val sampledOpts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(medium.path, this)
                    val d = maxOf(outWidth, outHeight)
                    inSampleSize = if (d <= 1280) 1 else Integer.highestOneBit(d / 1280)
                    inJustDecodeBounds = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val rawBmp = BitmapFactory.decodeFile(medium.path, sampledOpts)
                    ?: run {
                        mOcrInProgress = false
                        runOnUiThread { toast(com.goodwy.commons.R.string.unknown_error_occurred) }
                        return@ensureBackgroundThread
                    }

                val exif = try { ExifInterface(medium.path) } catch (_: Throwable) { null }
                val deg = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
                val bmp = if (deg != 0f) {
                    val m = Matrix().apply { postRotate(deg) }
                    val r = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                    if (r !== rawBmp) rawBmp.recycle()
                    r
                } else rawBmp

                val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                client.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        bmp.recycle(); client.close()
                        mOcrInProgress = false
                        // Só mostra o resultado se o usuário ainda estiver na mesma mídia
                        if (getCurrentPath() == requestedPath) {
                            runOnUiThread { showExtractedTextDialog(cleanOcrText(result.text)) }
                        }
                    }
                    .addOnFailureListener { e ->
                        bmp.recycle(); client.close()
                        mOcrInProgress = false
                        runOnUiThread { showErrorToast(e) }
                    }
            } catch (e: Throwable) {
                mOcrInProgress = false
                runOnUiThread { showErrorToast(e.localizedMessage ?: "") }
            }
        }
    }

    private fun extractTextFromVideoFrame() {
        if (mOcrInProgress) {
            toast(R.string.extracting_text)
            return
        }
        val medium = getCurrentMedium() ?: run { toast(R.string.no_text_found); return }
        val videoFragment = getCurrentFragment() as? VideoFragment ?: run {
            toast(R.string.no_text_found); return
        }

        mOcrInProgress = true
        val requestedPath = medium.path
        toast(R.string.extracting_text)

        // ── Caminho RÁPIDO: captura o frame já renderizado na GPU ──────────────────
        // TextureView.getBitmap() é instantâneo (sem I/O, sem seek de vídeo).
        // Funciona enquanto o vídeo estiver visível (tocando ou pausado com frame).
        val fastFrame = videoFragment.captureCurrentFrame()
        if (fastFrame != null) {
            val client = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            client.process(com.google.mlkit.vision.common.InputImage.fromBitmap(fastFrame, 0))
                .addOnSuccessListener { result ->
                    fastFrame.recycle(); client.close()
                    mOcrInProgress = false
                    if (getCurrentPath() == requestedPath)
                        runOnUiThread { showExtractedTextDialog(cleanOcrText(result.text)) }
                }
                .addOnFailureListener { e ->
                    fastFrame.recycle(); client.close()
                    mOcrInProgress = false
                    runOnUiThread { showErrorToast(e) }
                }
            return
        }

        // ── Fallback LENTO: MediaMetadataRetriever ────────────────────────────────
        // Usado quando a TextureView não tem frame (ex: vídeo ainda não iniciou).
        val currentPositionMs = videoFragment.getCurrentVideoPositionMs()
        ensureBackgroundThread {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(medium.path)
                val timeUs = currentPositionMs * 1000L

                val bitmap = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                        1280, 1280
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                }

                retriever.release()

                if (bitmap == null) {
                    mOcrInProgress = false
                    runOnUiThread { toast(R.string.no_text_found) }
                    return@ensureBackgroundThread
                }

                val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        bitmap.recycle(); client.close()
                        mOcrInProgress = false
                        if (getCurrentPath() == requestedPath) {
                            runOnUiThread { showExtractedTextDialog(cleanOcrText(result.text)) }
                        }
                    }
                    .addOnFailureListener { e ->
                        bitmap.recycle(); client.close()
                        mOcrInProgress = false
                        runOnUiThread { showErrorToast(e) }
                    }
            } catch (e: Exception) {
                try { retriever.release() } catch (_: Exception) {}
                mOcrInProgress = false
                runOnUiThread { showErrorToast(e) }
            }
        }
    }

    private fun cleanOcrText(raw: String) = raw
        .lines().map { it.trim() }.filter { it.isNotBlank() }
        .joinToString("\n")
        .replace(Regex(" +"), " ")
        .replace(Regex("\n\n+"), "\n\n")
        .trim()

    private fun showExtractedTextDialog(text: String) {
        if (text.isEmpty()) { toast(R.string.no_text_found); return }
        val tv = TextView(this).apply {
            this.text = text
            setPadding(60, 40, 60, 20)
            setTextIsSelectable(true)
            textSize = 16f
        }
        getAlertDialogBuilder()
            .setTitle(R.string.extract_text)
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(com.goodwy.commons.R.string.copy) { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("text", text))
                toast(com.goodwy.commons.R.string.value_copied_to_clipboard)
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .create().show()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun getCurrentMedium(): Medium? = mMediums.getOrNull(mPos)
    private fun getCurrentPath(): String = getCurrentMedium()?.path ?: ""
    private fun getCurrentFragment(): ViewPagerFragment? = (binding.viewPager.adapter as? MyPagerAdapter)?.getCurrentFragment(mPos)
    private fun getCurrentPhotoFragment(): PhotoFragment? = getCurrentFragment() as? PhotoFragment

    private fun updateTitle() {
        binding.mediumViewerToolbar.title = getCurrentMedium()?.name ?: ""
    }

    // ── ViewPager callbacks ────────────────────────────────────────────────────

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        mPos = position
        updateTitle()
        refreshMenuItems()
        scheduleSwipe()
    }

    override fun onPageScrollStateChanged(state: Int) {}

    // ── FragmentListener ───────────────────────────────────────────────────────

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) hideSystemUI() else { stopSlideshow(); showSystemUI() }
        (binding.viewPager.adapter as? MyPagerAdapter)?.toggleFullscreen(mIsFullScreen)
        fullscreenToggled()
    }

    private fun fullscreenToggled() {
        val newAlpha = if (mIsFullScreen) 0f else 1f
        binding.topShadow.animate().alpha(newAlpha).start()
        // Anima o AppBarLayout inteiro (não só o Toolbar) para esconder completamente
        binding.mediumViewerAppbar.animate().alpha(newAlpha).withStartAction {
            binding.mediumViewerAppbar.beVisible()
        }.withEndAction {
            binding.mediumViewerAppbar.beVisibleIf(newAlpha == 1f)
        }.start()
        if (config.bottomActions) {
            binding.bottomActions.root.animate().alpha(newAlpha).withStartAction {
                binding.bottomActions.root.beVisible()
            }.withEndAction {
                binding.bottomActions.root.beVisibleIf(newAlpha == 1f)
            }.start()
        }
    }

    override fun videoEnded(): Boolean {
        if (mIsSlideshowActive) swipeToNext()
        return mIsSlideshowActive
    }

    override fun isSlideShowActive() = mIsSlideshowActive
    override fun isFullScreen() = mIsFullScreen
    override fun goToPrevItem() { binding.viewPager.setCurrentItem(binding.viewPager.currentItem - 1, false) }
    override fun goToNextItem() { binding.viewPager.setCurrentItem(binding.viewPager.currentItem + 1, false) }
    override fun launchViewVideoIntent(path: String) {}
}
