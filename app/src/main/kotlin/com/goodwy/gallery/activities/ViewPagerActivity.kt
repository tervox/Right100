package com.goodwy.gallery.activities

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.MenuItem
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class ViewPagerActivity : BaseViewerActivity(), ViewPager.OnPageChangeListener, ViewPagerFragment.FragmentListener {

    companion object {
        var pendingMediums: ArrayList<Medium>? = null
    }

    private var mMediums = ArrayList<Medium>()
    private var mPos = 0
    private var mLastPage = -1
    private var mIsFullScreen = false
    private var mIsSlideshowActive = false
    private var mSlideshowHandler = Handler()
    private var mSlideshowInterval = SLIDESHOW_DEFAULT_INTERVAL
    private var mSlideshowMoveBackwards = false
    private var mRandomTransformerAppliedForGesture = false

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

        if (config.hideSystemUI) {
            binding.viewPager.post {
                Handler().postDelayed({
                    if (!isDestroyed && !mIsFullScreen) fragmentClicked()
                }, HIDE_SYSTEM_UI_DELAY)
            }
        }

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
        initBottomActions()
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

        // Um vizinho de cada lado é suficiente para a troca com efeito. Manter dois
        // carregava até cinco TextureViews/ExoPlayers simultaneamente, deixando o toque
        // lateral e o arraste disputarem CPU e memória.
        binding.viewPager.offscreenPageLimit = 1
        applyViewerTransformer()
    }

    private var mCurrentTransformer: ViewPager.PageTransformer? = null
    private var mLastRandomAnimation: Int? = null

    private fun applyViewerTransformer() {
        val animation = if (config.viewerAnimation == SLIDESHOW_ANIMATION_RANDOM) {
            val choices = mRandomAnimations.filter { it != mLastRandomAnimation }
            choices.random().also { mLastRandomAnimation = it }
        } else {
            config.viewerAnimation
        }
        mCurrentTransformer = createCleanTransformer(buildTransformer(animation))
        binding.viewPager.setPageTransformer(false, mCurrentTransformer)
    }

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
            R.id.menu_settings -> startActivity(Intent(this, com.goodwy.gallery.activities.SettingsActivity::class.java))
            R.id.menu_change_orientation -> cycleChangeOrientation()
            R.id.menu_resize -> launchResizeImageDialog(path)
            else -> return false
        }
        return true
    }

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
        binding.bottomActions.bottomPlayPause.setOnClickListener {
            runWhenCurrentVideoFragmentReady { it.togglePlayPause() }
        }

        binding.bottomActions.bottomMute.beVisibleIf(isVideo && visible and BOTTOM_ACTION_MUTE != 0)
        binding.bottomActions.bottomMute.setOnClickListener {
            runWhenCurrentVideoFragmentReady { it.toggleMuteFromActivity() }
            updatePlayerMuteState()
        }

        binding.bottomActions.bottomRotate.beVisibleIf(visible and BOTTOM_ACTION_ROTATE != 0 && isImage)
        binding.bottomActions.bottomRotate.setOnClickListener { rotateCurrentImage(90) }
        binding.bottomActions.bottomRotate.setOnLongClickListener { toast(R.string.rotate); true }

        binding.bottomActions.bottomChangeOrientation.beVisibleIf(visible and BOTTOM_ACTION_CHANGE_ORIENTATION != 0)
        binding.bottomActions.bottomChangeOrientation.setOnClickListener { cycleChangeOrientation() }
        binding.bottomActions.bottomChangeOrientation.setOnLongClickListener { toast(R.string.change_orientation); true }
        updateChangeOrientationIcon()

        binding.bottomActions.bottomToggleFileVisibility.beVisibleIf(visible and BOTTOM_ACTION_TOGGLE_VISIBILITY != 0 && !isInRecycleBin)
        binding.bottomActions.bottomToggleFileVisibility.setOnClickListener { toggleVisibility(medium?.isHidden() != true) }
        binding.bottomActions.bottomToggleFileVisibility.setOnLongClickListener { toast(R.string.toggle_file_visibility); true }

        binding.bottomActions.bottomResize.beVisibleIf(visible and BOTTOM_ACTION_RESIZE != 0 && isImage)
        binding.bottomActions.bottomResize.setOnClickListener { launchResizeImageDialog(getCurrentPath()) }
        binding.bottomActions.bottomResize.setOnLongClickListener { toast(com.goodwy.commons.R.string.resize); true }

        if (medium != null) updateBottomActionIcons(medium)
        applyBottomActionsOrder()
    }

    private fun bottomActionViewFor(id: Int) = when (id) {
        BOTTOM_ACTION_SHARE -> binding.bottomActions.bottomShare
        BOTTOM_ACTION_TOGGLE_FAVORITE -> binding.bottomActions.bottomFavorite
        BOTTOM_ACTION_PLAY_PAUSE -> binding.bottomActions.bottomPlayPause
        BOTTOM_ACTION_MUTE -> binding.bottomActions.bottomMute
        BOTTOM_ACTION_PROPERTIES -> binding.bottomActions.bottomProperties
        BOTTOM_ACTION_DELETE -> binding.bottomActions.bottomDelete
        BOTTOM_ACTION_EDIT -> binding.bottomActions.bottomEdit
        BOTTOM_ACTION_ROTATE -> binding.bottomActions.bottomRotate
        BOTTOM_ACTION_CHANGE_ORIENTATION -> binding.bottomActions.bottomChangeOrientation
        BOTTOM_ACTION_SLIDESHOW -> binding.bottomActions.bottomSlideshow
        BOTTOM_ACTION_SHOW_ON_MAP -> binding.bottomActions.bottomShowOnMap
        BOTTOM_ACTION_TOGGLE_VISIBILITY -> binding.bottomActions.bottomToggleFileVisibility
        BOTTOM_ACTION_RENAME -> binding.bottomActions.bottomRename
        BOTTOM_ACTION_SET_AS -> binding.bottomActions.bottomSetAs
        BOTTOM_ACTION_COPY -> binding.bottomActions.bottomCopy
        BOTTOM_ACTION_MOVE -> binding.bottomActions.bottomMove
        BOTTOM_ACTION_EXTRACT_TEXT -> binding.bottomActions.bottomExtractText
        BOTTOM_ACTION_RESIZE -> binding.bottomActions.bottomResize
        else -> null
    }

    private val mDefaultBottomActionOrder = listOf(
        BOTTOM_ACTION_SHARE, BOTTOM_ACTION_TOGGLE_FAVORITE, BOTTOM_ACTION_PLAY_PAUSE, BOTTOM_ACTION_MUTE,
        BOTTOM_ACTION_PROPERTIES, BOTTOM_ACTION_DELETE, BOTTOM_ACTION_EDIT, BOTTOM_ACTION_ROTATE,
        BOTTOM_ACTION_CHANGE_ORIENTATION, BOTTOM_ACTION_SLIDESHOW, BOTTOM_ACTION_SHOW_ON_MAP,
        BOTTOM_ACTION_TOGGLE_VISIBILITY, BOTTOM_ACTION_RENAME, BOTTOM_ACTION_SET_AS, BOTTOM_ACTION_COPY,
        BOTTOM_ACTION_MOVE, BOTTOM_ACTION_EXTRACT_TEXT, BOTTOM_ACTION_RESIZE
    )

    private fun applyBottomActionsOrder() {
        val savedOrder = config.bottomActionsOrder
        val orderIds = if (savedOrder.isNotBlank()) {
            val parsed = savedOrder.split(",").mapNotNull { it.trim().toIntOrNull() }
            parsed + mDefaultBottomActionOrder.filter { it !in parsed }
        } else {
            mDefaultBottomActionOrder
        }

        val visibleViews = orderIds.mapNotNull { id -> bottomActionViewFor(id)?.takeIf { it.isVisible() } }
        if (visibleViews.isEmpty()) return

        val wrapper = binding.bottomActions.bottomActionsWrapper
        val constraintSet = ConstraintSet()
        constraintSet.clone(wrapper)

        for (i in visibleViews.indices) {
            val view = visibleViews[i]
            constraintSet.clear(view.id, ConstraintSet.START)
            constraintSet.clear(view.id, ConstraintSet.END)
            if (i == 0) {
                constraintSet.connect(view.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            } else {
                constraintSet.connect(view.id, ConstraintSet.START, visibleViews[i - 1].id, ConstraintSet.END)
            }
            if (i == visibleViews.lastIndex) {
                constraintSet.connect(view.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else {
                constraintSet.connect(view.id, ConstraintSet.END, visibleViews[i + 1].id, ConstraintSet.START)
            }
            constraintSet.setHorizontalBias(view.id, 0.5f)
        }
        constraintSet.applyTo(wrapper)
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

    private fun askConfirmDelete() {
        val medium = getCurrentMedium() ?: return
        val fileDirItem = medium.toFileDirItem()
        val isInRecycleBin = medium.getIsInRecycleBin()

        if ((config.skipDeleteConfirmation || config.tempSkipDeleteConfirmation) && !isInRecycleBin) {
            deleteCurrentFile(skipRecycleBin = config.tempSkipRecycleBin)
            return
        }

        val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin && !isInRecycleBin)
            com.goodwy.commons.R.string.move_to_recycle_bin_confirmation
        else com.goodwy.commons.R.string.deletion_confirmation

        val name = "\"${medium.name}\""
        val question = String.format(getString(baseString), name)
        val showSkipOption = config.useRecycleBin && !isInRecycleBin

        DeleteWithRememberDialog(this, question, showSkipOption) { remember, skipRecycleBin ->
            if (remember) {
                config.tempSkipRecycleBin = skipRecycleBin
                config.skipDeleteConfirmation = true
            }
            deleteCurrentFile(skipRecycleBin)
        }
    }

    private fun deleteCurrentFile(skipRecycleBin: Boolean) {
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
        val oldPath = medium.path
        RenameItemDialog(this, oldPath) { newPath ->
            medium.path = newPath
            medium.name = newPath.getFilenameFromPath()
            ensureBackgroundThread { updateDBMediaPath(oldPath, newPath) }
            updateTitle()
        }
    }

    private fun rotateCurrentImage(degrees: Int) {
        getCurrentPhotoFragment()?.rotateImageViewBy(degrees)
    }

    private fun cycleChangeOrientation() {
        requestedOrientation = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        updateChangeOrientationIcon()
    }

    private fun updateChangeOrientationIcon() {
        val icon = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> R.drawable.ic_orientation_portrait_vector
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.drawable.ic_orientation_landscape_vector
            else -> R.drawable.ic_orientation_auto_vector
        }
        binding.bottomActions.bottomChangeOrientation.setImageResource(icon)
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

        if (config.slideshowRandomOrder) {
            mMediums.shuffle()
            mPos = 0
            initViewPager()
        }

        applySlideshowTransformer()
        scheduleSwipe()
    }

    private fun createCleanTransformer(delegate: ViewPager.PageTransformer?): ViewPager.PageTransformer {
        return object : ViewPager.PageTransformer {
            override fun transformPage(view: android.view.View, position: Float) {
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.rotation = 0f
                view.rotationX = 0f
                view.rotationY = 0f
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                delegate?.transformPage(view, position)
            }
        }
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

    private fun applySlideshowTransformer() {
        val animation = if (config.slideshowAnimation == SLIDESHOW_ANIMATION_RANDOM) {
            mRandomAnimations.random()
        } else {
            config.slideshowAnimation
        }
        val transformer = createCleanTransformer(buildTransformer(animation))
        binding.viewPager.setPageTransformer(false, transformer)
    }

    private fun stopSlideshow() {
        if (mIsSlideshowActive) {
            mIsSlideshowActive = false
            mSlideshowHandler.removeCallbacksAndMessages(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            getCurrentFragment()?.view?.apply {
                alpha = 1f; scaleX = 1f; scaleY = 1f
                translationX = 0f; translationY = 0f
                rotation = 0f; rotationX = 0f; rotationY = 0f
            }
            applyViewerTransformer()
            mIsFullScreen = false
            showSystemUI()
            fullscreenToggled()
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

    private var pagerTransitionAnimator: ValueAnimator? = null
    private var pagerScrollState = ViewPager.SCROLL_STATE_IDLE
    // Um inteiro acumulava os eventos e fazia toques opostos se anularem. A fila
    // preserva a ordem real dos comandos: tocar direita, direita, esquerda significa
    // executar exatamente esses três passos, sem voltar artificialmente à página anterior.
    private val pendingNavigationRequests = java.util.ArrayDeque<Int>()
    private var isDispatchingNavigation = false

    private fun dispatchPendingNavigation() {
        if (pendingNavigationRequests.isEmpty()
            || isDispatchingNavigation
            || pagerTransitionAnimator?.isRunning == true
            || binding.viewPager.isFakeDragging
            || pagerScrollState != ViewPager.SCROLL_STATE_IDLE
        ) return

        val step = pendingNavigationRequests.removeFirst()
        isDispatchingNavigation = true
        binding.viewPager.post {
            isDispatchingNavigation = false
            if (!isDestroyed) navigateToItem(step)
        }
    }

    private fun animatePagerTransition(forward: Boolean) {
        val pager = binding.viewPager
        val start = pager.currentItem
        val target = start + if (forward) 1 else -1
        if (target !in mMediums.indices || pager.width <= 0 || pager.isFakeDragging) {
            if (target in mMediums.indices) pager.setCurrentItem(target, true)
            else if (mIsSlideshowActive) {
                stopSlideshow()
                toast(R.string.slideshow_ended)
            }
            return
        }

        pagerTransitionAnimator?.cancel()
        val animator = ValueAnimator.ofInt(0, pager.width).apply {
            interpolator = DecelerateInterpolator()
            duration = SLIDESHOW_SLIDE_DURATION
        }
        pagerTransitionAnimator = animator
        var oldDrag = 0
        animator.addUpdateListener { anim ->
            if (pager.isFakeDragging) {
                val drag = anim.animatedValue as Int
                try {
                    pager.fakeDragBy((drag - oldDrag) * if (forward) -1f else 1f)
                    oldDrag = drag
                } catch (_: Exception) {
                    animator.cancel()
                }
            }
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(a: Animator) {
                if (pager.isFakeDragging) {
                    try { pager.endFakeDrag() } catch (_: Exception) {}
                }
                if (pagerTransitionAnimator === animator) pagerTransitionAnimator = null
                if (pager.currentItem == start && mIsSlideshowActive) {
                    stopSlideshow()
                    toast(R.string.slideshow_ended)
                }
                pager.post { dispatchPendingNavigation() }
            }
            override fun onAnimationCancel(a: Animator) {
                if (pager.isFakeDragging) {
                    try { pager.endFakeDrag() } catch (_: Exception) {}
                }
                if (pagerTransitionAnimator === animator) pagerTransitionAnimator = null
                pager.post { dispatchPendingNavigation() }
            }
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
        })
        if (pager.beginFakeDrag()) animator.start()
        else {
            pagerTransitionAnimator = null
            pager.setCurrentItem(target, true)
        }
    }

    private var mOcrInProgress = false
    private var mTextRecognizer: TextRecognizer? = null

    private fun getTextRecognizer(): TextRecognizer {
        return mTextRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
            mTextRecognizer = it
        }
    }

    private fun prepareBitmapForOcr(bitmap: Bitmap, maxDimension: Int = 1920): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        // Imagens pequenas têm caracteres minúsculos demais para o detector. Um upscale
        // moderado melhora acentos e pontuação sem enviar uma foto gigante ao ML Kit.
        val targetDimension = when {
            largest > maxDimension -> maxDimension
            largest in 1..960 -> (largest * 2).coerceAtMost(maxDimension)
            else -> largest
        }
        if (targetDimension == largest) return bitmap
        val scale = targetDimension.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun enhanceBitmapForOcr(bitmap: Bitmap): Bitmap {
        val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Aumenta contraste levemente, preservando bordas de acentos e símbolos.
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(floatArrayOf(
                    1.15f, 0f, 0f, 0f, -15f,
                    0f, 1.15f, 0f, 0f, -15f,
                    0f, 0f, 1.15f, 0f, -15f,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        }
        Canvas(enhanced).drawBitmap(bitmap, 0f, 0f, paint)
        if (enhanced !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return enhanced
    }

    private fun getExifRotation(path: String): Int {
        return try {
            val exif = if (path.startsWith("content://") || path.startsWith("file://")) {
                contentResolver.openInputStream(Uri.parse(path))?.use { ExifInterface(it) }
            } else {
                ExifInterface(path)
            }
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1) {
                6 -> 90
                3 -> 180
                8 -> 270
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun hasUsefulOcrFrame(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width < 16 || bitmap.height < 16) return false
        val sample = try {
            Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        } catch (_: Exception) {
            return true
        }
        var min = 255
        var max = 0
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val color = sample.getPixel(x, y)
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                min = minOf(min, luminance)
                max = maxOf(max, luminance)
            }
        }
        if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        return max - min >= 8
    }

    private fun rotateBitmapForOcr(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun decodeOcrBitmap(path: String, options: BitmapFactory.Options): Bitmap? {
        return if (path.startsWith("content://") || path.startsWith("file://")) {
            contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } else {
            BitmapFactory.decodeFile(path, options)
        }
    }

    private fun setRetrieverDataSource(retriever: MediaMetadataRetriever, path: String) {
        if (path.startsWith("content://")) {
            retriever.setDataSource(this, Uri.parse(path))
        } else {
            retriever.setDataSource(path)
        }
    }

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
            var bmp: Bitmap? = null
            try {
                val sampledOpts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    decodeOcrBitmap(medium.path, this)
                    val largest = maxOf(outWidth, outHeight)
                    // 1920px preserva textos pequenos e símbolos, mas evita enviar
                    // fotos gigantes inteiras ao recognizer e reduz a latência/CPU.
                    inSampleSize = if (largest <= 1920) 1 else {
                        Integer.highestOneBit(largest / 1920).coerceAtLeast(1)
                    }
                    inJustDecodeBounds = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val rawBmp = decodeOcrBitmap(medium.path, sampledOpts)
                    ?: run {
                        mOcrInProgress = false
                        runOnUiThread { toast(com.goodwy.commons.R.string.unknown_error_occurred) }
                        return@ensureBackgroundThread
                    }

                bmp = rotateBitmapForOcr(prepareBitmapForOcr(rawBmp), getExifRotation(medium.path))
                bmp = enhanceBitmapForOcr(bmp!!)
                val resultBitmap = bmp ?: return@ensureBackgroundThread
                getTextRecognizer().process(InputImage.fromBitmap(resultBitmap, 0))
                    .addOnSuccessListener { result ->
                        if (!resultBitmap.isRecycled) resultBitmap.recycle()
                        mOcrInProgress = false
                        if (getCurrentPath() == requestedPath) {
                            runOnUiThread { showExtractedTextDialog(cleanOcrText(result.text)) }
                        }
                    }
                    .addOnFailureListener { e ->
                        if (!resultBitmap.isRecycled) resultBitmap.recycle()
                        mOcrInProgress = false
                        runOnUiThread { showErrorToast(e) }
                    }
            } catch (e: Throwable) {
                bmp?.takeIf { !it.isRecycled }?.recycle()
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
        val currentPositionMs = videoFragment.getCurrentVideoPositionMs()
        toast(R.string.extracting_text)

        val fastFrame = videoFragment.captureCurrentFrame()
        if (fastFrame != null && hasUsefulOcrFrame(fastFrame)) {
            var frameForOcr = prepareBitmapForOcr(fastFrame)
            frameForOcr = enhanceBitmapForOcr(frameForOcr)
            getTextRecognizer().process(InputImage.fromBitmap(frameForOcr, 0))
                .addOnSuccessListener { result ->
                    val text = cleanOcrText(result.text)
                    if (!frameForOcr.isRecycled) frameForOcr.recycle()
                    if (text.isNotEmpty() && getCurrentPath() == requestedPath) {
                        mOcrInProgress = false
                        runOnUiThread { showExtractedTextDialog(text) }
                    } else {
                        // O frame da TextureView pode estar válido, mas ser um instante sem
                        // legenda/texto. Nesse caso, tente frames próximos via retriever.
                        extractVideoOcrCandidates(medium, requestedPath, currentPositionMs)
                    }
                }
                .addOnFailureListener {
                    if (!frameForOcr.isRecycled) frameForOcr.recycle()
                    extractVideoOcrCandidates(medium, requestedPath, currentPositionMs)
                }
            return
        }
        fastFrame?.takeIf { !it.isRecycled }?.recycle()
        extractVideoOcrCandidates(medium, requestedPath, currentPositionMs)
    }

    private fun extractVideoOcrCandidates(medium: Medium, requestedPath: String, currentPositionMs: Long) {
        ensureBackgroundThread {
            val retriever = android.media.MediaMetadataRetriever()
            val frames = ArrayList<Bitmap>()
            val positions = listOf(
                currentPositionMs.coerceAtLeast(0L),
                (currentPositionMs - 1000L).coerceAtLeast(0L),
                currentPositionMs + 1000L
            ).distinct()
            try {
                setRetrieverDataSource(retriever, medium.path)
                val rotation = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                positions.forEach { positionMs ->
                    val rawFrame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(
                            positionMs * 1000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                            1600,
                            1600
                        )
                    } else {
                        retriever.getFrameAtTime(
                            positionMs * 1000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                    if (rawFrame != null && hasUsefulOcrFrame(rawFrame)) {
                        var frame = rotateBitmapForOcr(prepareBitmapForOcr(rawFrame), rotation)
                        frame = enhanceBitmapForOcr(frame)
                        frames.add(frame)
                    } else {
                        rawFrame?.takeIf { !it.isRecycled }?.recycle()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            runOnUiThread { processNextVideoOcrFrame(frames, 0, requestedPath) }
        }
    }

    private fun processNextVideoOcrFrame(frames: List<Bitmap>, index: Int, requestedPath: String) {
        if (getCurrentPath() != requestedPath) {
            frames.drop(index).forEach { if (!it.isRecycled) it.recycle() }
            mOcrInProgress = false
            return
        }
        if (index >= frames.size) {
            mOcrInProgress = false
            toast(R.string.no_text_found)
            return
        }

        val frame = frames[index]
        getTextRecognizer().process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { result ->
                val text = cleanOcrText(result.text)
                if (!frame.isRecycled) frame.recycle()
                if (text.isNotEmpty() && getCurrentPath() == requestedPath) {
                    frames.drop(index + 1).forEach { if (!it.isRecycled) it.recycle() }
                    mOcrInProgress = false
                    showExtractedTextDialog(text)
                } else {
                    processNextVideoOcrFrame(frames, index + 1, requestedPath)
                }
            }
            .addOnFailureListener { error ->
                if (!frame.isRecycled) frame.recycle()
                if (index + 1 < frames.size) {
                    processNextVideoOcrFrame(frames, index + 1, requestedPath)
                } else {
                    frames.drop(index + 1).forEach { if (!it.isRecycled) it.recycle() }
                    mOcrInProgress = false
                    showErrorToast(error)
                }
            }
    }

    private fun cleanOcrText(raw: String): String {
        // Não compacte espaços nem remova caracteres: isso destruía separadores,
        // pontuação e símbolos úteis ao copiar código, documentos e textos técnicos.
        return java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0000", "")
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

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

    private fun getCurrentMedium(): Medium? = mMediums.getOrNull(mPos)
    private fun getCurrentPath(): String = getCurrentMedium()?.path ?: ""

    private fun runWhenCurrentVideoFragmentReady(action: (VideoFragment) -> Unit) {
        val expectedPath = getCurrentPath()
        var attempts = 0
        fun tryNow() {
            val fragment = getCurrentFragment() as? VideoFragment
            val isTheCurrentVideo = fragment?.let {
                try { it.getMediumPath() == expectedPath } catch (_: Exception) { false }
            } == true
            if (isTheCurrentVideo && fragment!!.isAdded && fragment.view != null) {
                action(fragment)
            } else if (!isDestroyed && attempts++ < 60) {
                // A superfície pode ser criada depois do item primário. Aguarde até
                // aproximadamente 1,5 s sem obrigar o usuário a tocar repetidamente.
                binding.viewPager.postDelayed({ tryNow() }, 24L)
            }
        }
        tryNow()
    }

    private fun getCurrentFragment(): ViewPagerFragment? {
        val position = binding.viewPager.currentItem
        val adapter = binding.viewPager.adapter as? MyPagerAdapter
        val mapped = adapter?.getCurrentFragment(position)
        if (mapped != null) return mapped

        val tag = "android:switcher:${binding.viewPager.id}:$position"
        return supportFragmentManager.findFragmentByTag(tag) as? ViewPagerFragment
    }
    private fun getCurrentPhotoFragment(): PhotoFragment? = getCurrentFragment() as? PhotoFragment

    private fun updateTitle() {
        binding.mediumViewerToolbar.title = getCurrentMedium()?.name ?: ""
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    


    override fun onPageSelected(position: Int) {
        mPos = position
        updateTitle()
        // A visibilidade dos botões dependia apenas do onResume. Depois de trocar
        // foto -> vídeo, o botão continuava oculto; depois de vídeo -> foto, ficava
        // stale. Recalcule os controles imediatamente usando o Medium, que já existe.
        initBottomActions()
        refreshMenuItems()
        scheduleSwipe()
        refreshCurrentPageState(position)
    }

    private fun refreshCurrentPageState(position: Int, attempt: Int = 0) {
        if (isDestroyed || binding.viewPager.currentItem != position) return
        initBottomActions()
        refreshMenuItems()
        val fragment = getCurrentFragment()
        if (fragment is VideoFragment) fragment.onBecameVisible()
        // A criação do fragmento e da TextureView é assíncrona. Continue por até
        // aproximadamente 1,2 s para que os controles apareçam e o player seja
        // conectado mesmo quando a transição tem efeito visual.
        val fragmentReady = fragment?.isAdded == true && fragment.view != null
        if (!fragmentReady && attempt < 48) {
            binding.viewPager.postDelayed({ refreshCurrentPageState(position, attempt + 1) }, 24L)
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
        pagerScrollState = state
        when (state) {
            ViewPager.SCROLL_STATE_DRAGGING, ViewPager.SCROLL_STATE_SETTLING -> {
                // Escolher outro transformer no onPageSelected trocava o PageTransformer
                // no meio da transição. Isso força relayout enquanto a TextureView do
                // vídeo muda de página e pode deixar o player sem superfície/controles.
                // Escolha uma única vez no início do gesto e mantenha o mesmo transformer
                // até o fim. O slideshow automático aplica o próprio efeito antes do fake drag.
                if (!mIsSlideshowActive
                    && config.viewerAnimation == SLIDESHOW_ANIMATION_RANDOM
                    && !mRandomTransformerAppliedForGesture
                ) {
                    applyViewerTransformer()
                    mRandomTransformerAppliedForGesture = true
                }
            }
            ViewPager.SCROLL_STATE_IDLE -> {
                mRandomTransformerAppliedForGesture = false
                // Não remova e reaplique o transformer aqui: essa troca desnecessária
                // pode desanexar a TextureView e interromper o surface do ExoPlayer.
                refreshMenuItems()
                dispatchPendingNavigation()
            }
        }
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) hideSystemUI() else { stopSlideshow(); showSystemUI() }
        (binding.viewPager.adapter as? MyPagerAdapter)?.toggleFullscreen(mIsFullScreen)
        fullscreenToggled()
    }

    private fun fullscreenToggled() {
        val newAlpha = if (mIsFullScreen) 0f else 1f
        binding.topShadow.animate().alpha(newAlpha).start()
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

    private fun navigateToItem(offset: Int) {
        if (offset == 0) return
        if (pagerTransitionAnimator?.isRunning == true || binding.viewPager.isFakeDragging || pagerScrollState != ViewPager.SCROLL_STATE_IDLE) {
            if (pendingNavigationRequests.size < 32) pendingNavigationRequests.addLast(offset.coerceIn(-1, 1))
            return
        }

        val target = binding.viewPager.currentItem + offset
        if (target !in mMediums.indices) return
        if (!mIsSlideshowActive && config.viewerAnimation != SLIDESHOW_ANIMATION_NONE) {
            // Para o toque lateral, setCurrentItem(true) usa o mecanismo nativo de
            // settling e mantém o PageTransformer. Cada pedido seguinte fica na fila
            // e só é iniciado quando o pager chega a IDLE.
            applyViewerTransformer()
            mRandomTransformerAppliedForGesture = config.viewerAnimation == SLIDESHOW_ANIMATION_RANDOM
        }
        binding.viewPager.setCurrentItem(target, true)
    }

    override fun goToPrevItem() = navigateToItem(-1)
    override fun goToNextItem() = navigateToItem(1)
    override fun launchViewVideoIntent(path: String) {}

    override fun onDestroy() {
        mTextRecognizer?.close()
        mTextRecognizer = null
        super.onDestroy()
    }
}
