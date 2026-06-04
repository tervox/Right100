package com.goodwy.gallery.activities

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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

    override val contentHolder: ViewGroup
        get() = binding.root as ViewGroup

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
        initFragment()
    }

    override fun onResume() {
        super.onResume()
        if (config.blackBackground) {
            binding.root.setBackgroundColor(Color.BLACK)
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

    }

    fun fragmentClicked() {
        if (mIsFullScreen) hideSystemUI() else showSystemUI()
        mFragment?.fullscreenToggled(mIsFullScreen)
        val newAlpha = if (mIsFullScreen) 0f else 1f
        binding.topShadow.animate().alpha(newAlpha).start()
        binding.mediumViewerToolbar.animate().alpha(newAlpha).withStartAction {
            binding.mediumViewerToolbar.beVisible()
        }.withEndAction {
            binding.mediumViewerToolbar.beVisibleIf(newAlpha == 1f)
        }.start()
    }

    fun videoEnded() = false
    fun goToPrevItem() {}
    fun goToNextItem() {}
    fun launchViewVideoIntent(path: String) {}
    fun isSlideShowActive() = false
    fun isFullScreen() = mIsFullScreen
    fun updatePlayPause(play: Boolean) {}
}
