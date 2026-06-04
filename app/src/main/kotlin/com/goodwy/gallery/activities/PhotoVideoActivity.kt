package com.goodwy.gallery.activities

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ActivityMediumBinding
import com.goodwy.gallery.extensions.*
import com.goodwy.gallery.fragments.PhotoFragment
import com.goodwy.gallery.fragments.VideoFragment
import com.goodwy.gallery.fragments.ViewPagerFragment
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.models.Medium
import com.google.android.material.appbar.AppBarLayout

open class PhotoVideoActivity : BaseViewerActivity() {
    protected var mMedium: Medium? = null
    protected var mIsFullScreen = false
    protected var mFragment: ViewPagerFragment? = null
    protected var mUri: Uri? = null
    protected var mMuteInit = false
    protected var mIsVideo = false

    private val binding by viewBinding(ActivityMediumBinding::inflate)

    override val contentHolder: View
        get() = binding.fragmentHolder

    override val appBarLayout: AppBarLayout
        get() = binding.mediumViewerAppbar

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        mUri = intent.data
        if (mUri == null) {
            finish()
            return
        }

        val path = applicationContext.getRealPathFromURI(mUri!!) ?: ""
        if (path.isNotEmpty()) {
            mMedium = Medium(
                null,
                path.getFilenameFromPath(),
                path,
                path.getParentPath(),
                0L,
                0L,
                0L,
                0,
                0,
                false,
                0L,
                0L,
                0
            )
            mMedium?.type = if (mIsVideo) TYPE_VIDEOS else TYPE_IMAGES
        }

        setupOptionsMenu()
        setupBottomActions()
        initFragment()
    }

    override fun onResume() {
        super.onResume()
        if (config.blackBackground) {
            binding.fragmentHolder.setBackgroundColor(Color.BLACK)
        }
    }

    private fun setupOptionsMenu() {
        binding.mediumViewerToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.goodwy.commons.R.drawable.ic_chevron_left_vector, Color.WHITE)
            setNavigationOnClickListener { finish() }
        }

        updateMenuItemColors(binding.mediumViewerToolbar.menu, forceWhiteIcons = true)
        binding.mediumViewerToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_set_as -> setAs(mMedium?.path ?: mUri.toString())
                R.id.menu_open_with -> openPath(mMedium?.path ?: mUri.toString(), true)
                R.id.menu_share -> shareMediumPath(mMedium?.path ?: mUri.toString())
                R.id.menu_edit -> openEditor(mMedium?.path ?: mUri.toString())
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun initFragment() {
        val isVideo = mMedium?.isVideo() == true || mMedium?.isGIF() == true
        mFragment = if (isVideo) {
            VideoFragment()
        } else {
            PhotoFragment()
        }

        val bundle = Bundle()
        bundle.putSerializable(MEDIUM, mMedium)
        bundle.putBoolean(SHOULD_INIT_FRAGMENT, true)
        mFragment?.arguments = bundle

        supportFragmentManager.beginTransaction().replace(R.id.fragment_placeholder, mFragment!!).commit()
        updateBottomActionIcons()
    }

    private fun setupBottomActions() {
        binding.bottomActions.bottomFavorite.beGone()
        binding.bottomActions.bottomDelete.beGone()
        binding.bottomActions.bottomDetails.beGone()
        binding.bottomActions.bottomEdit.beGone()
        binding.bottomActions.bottomCopy.beGone()
        binding.bottomActions.bottomMove.beGone()
        binding.bottomActions.bottomRename.beGone()
        binding.bottomActions.bottomRotate.beGone()
        binding.bottomActions.bottomProperties.beGone()

        binding.bottomActions.bottomResize.setOnClickListener {
            mMedium?.let {
                resizeImage(it.path)
            }
        }

        binding.bottomActions.bottomPlayPause.setOnClickListener {
            (mFragment as? VideoFragment)?.togglePlayPause()
        }

        binding.bottomActions.bottomMute.setOnClickListener {
            config.muteVideos = !config.muteVideos
            updatePlayerMuteState()
        }
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) hideSystemUI() else showSystemUI()
        mFragment?.fullscreenToggled(mIsFullScreen)

        val newAlpha = if (mIsFullScreen) 0f else 1f
        binding.topShadow.animate().alpha(newAlpha).start()
        if (!binding.bottomActions.root.isGone()) {
            binding.bottomActions.root.animate().alpha(newAlpha).start()
        }

        binding.mediumViewerToolbar.animate().alpha(newAlpha).withStartAction {
            binding.mediumViewerToolbar.beVisible()
        }.withEndAction {
            binding.mediumViewerToolbar.beVisibleIf(newAlpha == 1f)
        }.start()
    }

    override fun videoEnded() = false
    override fun goToPrevItem() {}
    override fun goToNextItem() {}
    override fun launchViewVideoIntent(path: String) {}
    override fun isSlideShowActive() = false
    override fun isFullScreen() = mIsFullScreen

    override fun updatePlayPause(play: Boolean) {
        if (play) {
            binding.bottomActions.bottomPlayPause.setImageResource(R.drawable.ic_play_vector)
        } else {
            binding.bottomActions.bottomPlayPause.setImageResource(R.drawable.ic_pause_vector)
        }
    }

    private fun updatePlayerMuteState() {
        val isMuted = config.muteVideos
        val drawableId = if (isMuted) R.drawable.ic_vector_speaker_off else R.drawable.ic_vector_speaker_on
        binding.bottomActions.bottomMute.setImageResource(drawableId)
        mMuteInit = true
    }

    private fun updateBottomActionIcons() {
        if (mMedium == null) return
        val isVideo = mMedium?.isVideo() == true || mMedium?.isGIF() == true
        binding.bottomActions.bottomPlayPause.beVisibleIf(isVideo && config.visibleBottomActions and BOTTOM_ACTION_PLAY_PAUSE != 0)
        binding.bottomActions.bottomMute.beVisibleIf(isVideo && config.visibleBottomActions and BOTTOM_ACTION_MUTE != 0)
        binding.bottomActions.bottomResize.beVisibleIf(config.visibleBottomActions and BOTTOM_ACTION_RESIZE != 0 && mMedium?.isImage() == true)
        updatePlayerMuteState()
    }
}
