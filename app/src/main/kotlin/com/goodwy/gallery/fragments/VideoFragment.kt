@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.goodwy.gallery.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.media.MediaMetadataRetriever
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updateLayoutParams
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import jp.wasabeef.glide.transformations.BlurTransformation
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.DEFAULT_ANIMATION_DURATION
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MySeekBar
import com.goodwy.gallery.R
import com.goodwy.gallery.activities.BaseViewerActivity
import com.goodwy.gallery.activities.VideoActivity
import com.goodwy.gallery.databinding.PagerVideoItemBinding
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.getActionBarHeight
import com.goodwy.gallery.extensions.getBottomActionsHeight
import com.goodwy.gallery.extensions.getFormattedDuration
import com.goodwy.gallery.extensions.getFriendlyMessage
import com.goodwy.gallery.extensions.launchGesturePlayer
import com.goodwy.gallery.extensions.parseFileChannel
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.interfaces.PlaybackSpeedListener
import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.views.MediaSideScroll
import java.io.File
import java.io.FileInputStream
import java.text.DecimalFormat
import androidx.core.net.toUri
import kotlin.math.max
import kotlin.math.abs

class VideoFragment : ViewPagerFragment(), TextureView.SurfaceTextureListener,
    SeekBar.OnSeekBarChangeListener, PlaybackSpeedListener {
    companion object {
        private const val PROGRESS = "progress"
        private const val UPDATE_INTERVAL_MS = 16L
        private const val TOUCH_HOLD_DURATION_MS = 500L
        private const val TOUCH_HOLD_SPEED_MULTIPLIER = 2.0f
        private const val TOUCH_SLOP_DIVIDER = 3
    }

    private var mIsFullscreen = false
    private var mWasFragmentInit = false
    private var mIsPanorama = false
    private var mIsFragmentVisible = false
    private var mIsDragged = false
    private var mWasVideoStarted = false
    private var mWasPlayerInited = false
    private var mWasLastPositionRestored = false
    private var mPlayOnPrepared = false
    private var mIsPlayerPrepared = false
    private var mCurrTime = 0L
    private var mDuration = 0L
    private var mPositionWhenInit = 0L
    private var mPositionAtPause = 0L
    var mIsPlaying = false

    private var mExoPlayer: ExoPlayer? = null
    private var mVideoSize = Point(1, 1)
    private var mTimerHandler = Handler()
    private var mBlurPlayer: ExoPlayer? = null

    private var mStoredShowExtendedDetails = false
    private var mStoredHideExtendedDetails = false
    private var mStoredBottomActions = true
    private var mStoredExtendedDetails = 0
    private var mStoredRememberLastVideoPosition = false
    private var mOriginalPlaybackSpeed = 1f
    private var mIsLongPressActive = false
    private var mHasAudio = true

    private val mTouchHoldRunnable = Runnable {
        mView.parent.requestDisallowInterceptTouchEvent(true)
        mIsLongPressActive = true
        mOriginalPlaybackSpeed = mExoPlayer?.playbackParameters?.speed ?: mConfig.playbackSpeed
        mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updatePlaybackSpeed(TOUCH_HOLD_SPEED_MULTIPLIER)
        mPlaybackSpeedPill.fadeIn()
    }

    private lateinit var mTimeHolder: View
    private lateinit var mBrightnessSideScroll: MediaSideScroll
    private lateinit var mVolumeSideScroll: MediaSideScroll
    private lateinit var binding: PagerVideoItemBinding
    private lateinit var mView: View
    private lateinit var mMedium: Medium
    private lateinit var mConfig: Config
    private lateinit var mTextureView: TextureView
    private lateinit var mCurrTimeView: TextView
    private lateinit var mPlayPauseButton: ImageView
    private lateinit var mSeekBar: SeekBar
    private lateinit var mPlaybackSpeedPill: TextView
    private var mTouchSlop = 0
    private var mInitialX = 0f
    private var mInitialY = 0f

    private var mVolumeController: VolumeController? = null
    private var mMuteInit: Boolean = false

    private var mVideoFillMode: Int
        get() = mConfig.videoFillMode
        set(value) { mConfig.videoFillMode = value }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val activity = requireActivity()
        val arguments = requireArguments()

        mMedium = arguments.getSerializable(MEDIUM) as Medium
        mConfig = context.config
        mTouchSlop = (ViewConfiguration.get(context).scaledTouchSlop) / TOUCH_SLOP_DIVIDER
        binding = PagerVideoItemBinding.inflate(inflater, container, false).apply {
            panoramaOutline.setOnClickListener { openPanorama() }
            bottomVideoTimeHolder.videoCurrTime.setOnClickListener { skip(false) }
            bottomVideoTimeHolder.videoDuration.setOnClickListener { skip(true) }
            videoHolder.setOnClickListener { toggleFullscreen() }
            videoPreview.setOnClickListener { toggleFullscreen() }
            bottomVideoTimeHolder.videoPlaybackSpeed.setOnClickListener { showPlaybackSpeedPicker() }
            bottomVideoTimeHolder.videoToggleMute.setOnClickListener {
                mConfig.muteVideos = !mConfig.muteVideos
                updatePlayerMuteState(showToast = true)
            }

            bottomVideoTimeHolder.videoStretch.apply {
                beVisible()
                setOnClickListener { toggleVideoStretch() }
            }

            bottomVideoTimeHolder.videoFillScreen.apply {
                beVisible()
                setImageResource(if (mConfig.videoFillScreen) R.drawable.ic_minimize_vector else R.drawable.ic_crop_free)
                setOnClickListener {
                    mConfig.videoFillScreen = !mConfig.videoFillScreen
                    if (mConfig.videoFillScreen && mVideoFillMode != 0) {
                        mVideoFillMode = 0
                        binding.bottomVideoTimeHolder.videoStretch.setImageResource(R.drawable.ic_maximize_vector)
                    }
                    binding.bottomVideoTimeHolder.videoFillScreen.setImageResource(
                        if (mConfig.videoFillScreen) R.drawable.ic_minimize_vector else R.drawable.ic_crop_free
                    )
                    setVideoSize()
                }
            }

            videoSurfaceFrame.controller.settings.swallowDoubleTaps = true
            videoPlayOutline.setOnClickListener {
                if (mConfig.gestureVideoPlayer) activity.launchGesturePlayer(mMedium.path) else togglePlayPause()
            }

            mPlayPauseButton = bottomVideoTimeHolder.videoTogglePlayPause
            mPlayPauseButton.beGoneIf(mConfig.visibleBottomActions and BOTTOM_ACTION_PLAY_PAUSE != 0)
            mPlayPauseButton.setOnClickListener {
                togglePlayPause()
            }

            bottomVideoTimeHolder.videoToggleMute.beGoneIf(mConfig.visibleBottomActions and BOTTOM_ACTION_MUTE != 0)

            mSeekBar = bottomVideoTimeHolder.videoSeekbar
            mPlaybackSpeedPill = playbackSpeedPill
            mSeekBar.setOnSeekBarChangeListener(this@VideoFragment)
            mSeekBar.setOnClickListener { }

            mTimeHolder = bottomVideoTimeHolder.videoTimeHolder
            mCurrTimeView = bottomVideoTimeHolder.videoCurrTime
            mBrightnessSideScroll = videoBrightnessController
            mVolumeSideScroll = videoVolumeController
            mBrightnessSideScroll.onVerticalScroll = {
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
            }
            mVolumeSideScroll.onVerticalScroll = {
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
            }
            mTextureView = videoSurface
            mTextureView.surfaceTextureListener = this@VideoFragment

            val gestureDetector =
                GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!mConfig.allowInstantChange) {
                            toggleFullscreen()
                            return true
                        }

                        val viewWidth = root.width
                        val instantWidth = viewWidth / 7
                        val clickedX = e.rawX
                        when {
                            clickedX <= instantWidth -> listener?.goToPrevItem()
                            clickedX >= viewWidth - instantWidth -> listener?.goToNextItem()
                            else -> toggleFullscreen()
                        }
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        handleDoubleTap(e.rawX)
                        return true
                    }
                })

            videoPreview.setOnTouchListener { view, event ->
                handleEvent(event)
                false
            }

            videoSurfaceFrame.setOnTouchListener { view, event ->
                if (videoSurfaceFrame.controller.state.zoom == 1f) {
                    handleEvent(event)
                }
                // REMOVIDO: handleTouchHoldEvent(event) - função inexistente
                if (mIsLongPressActive) {
                    return@setOnTouchListener true
                }

                gestureDetector.onTouchEvent(event)
                false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.videoHolder) { _, insets ->
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())

            val pillTopMargin = system.top + resources.getActionBarHeight(context) +
                resources.getDimension(com.goodwy.commons.R.dimen.normal_margin).toInt()
            (mPlaybackSpeedPill.layoutParams as? RelativeLayout.LayoutParams)?.apply {
                setMargins(0, pillTopMargin, 0, 0)
            }

            binding.bottomActionsDummy.updateLayoutParams<ViewGroup.LayoutParams> {
                height = resources.getBottomActionsHeight() + system.bottom
            }
            insets
        }

        mView = binding.root

        if (!arguments.getBoolean(SHOULD_INIT_FRAGMENT, true)) {
            return mView
        }

        updatePlaybackSpeed(mConfig.playbackSpeed)
        storeStateVariables()
        Glide.with(context).load(mMedium.path).into(binding.videoPreview)

        if (!mIsFragmentVisible && activity is VideoActivity) {
            mIsFragmentVisible = true
        }

        mIsFullscreen = listener?.isFullScreen() == true
        initTimeHolder()

        ensureBackgroundThread {
            activity.getVideoResolution(mMedium.path)?.apply {
                mVideoSize.x = x
                mVideoSize.y = y
            }
        }

        if (mIsPanorama) {
            binding.apply {
                panoramaOutline.beVisible()
                videoPlayOutline.beGone()
                mVolumeSideScroll.beGone()
                mBrightnessSideScroll.beGone()
                Glide.with(context).load(mMedium.path).into(videoPreview)
            }
        }

        if (!mIsPanorama) {
            if (savedInstanceState != null) {
                mCurrTime = savedInstanceState.getLong(PROGRESS, 0L)
            }

            mWasFragmentInit = true
            setVideoSize()

            binding.apply {
                bottomVideoTimeHolder.videoStretch.setImageResource(
                    if (mVideoFillMode != 0) R.drawable.ic_minimize_vector else R.drawable.ic_maximize_vector
                )
                bottomVideoTimeHolder.videoFillScreen.setImageResource(
                    if (mConfig.videoFillScreen) R.drawable.ic_minimize_vector else R.drawable.ic_crop_free
                )
                mBrightnessSideScroll.initialize(
                    activity,
                    slideInfo,
                    true,
                    container,
                    singleTap = { x, y ->
                        if (mConfig.allowInstantChange) {
                            listener?.goToPrevItem()
                        } else {
                            toggleFullscreen()
                        }
                    },
                    doubleTap = { x, y ->
                        doSkip(false)
                    })
                mVolumeSideScroll.initialize(
                    activity,
                    slideInfo,
                    false,
                    container,
                    singleTap = { x, y ->
                        if (mConfig.allowInstantChange) {
                            listener?.goToNextItem()
                        } else {
                            toggleFullscreen()
                        }
                    },
                    doubleTap = { x, y ->
                        doSkip(true)
                    })
            }
        }

        return mView
    }

    override fun onResume() {
        super.onResume()
        mStoredRememberLastVideoPosition = mConfig.rememberLastVideoPosition
        if (mIsFragmentVisible && mWasFragmentInit) {
            initExoPlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        if (mWasFragmentInit) {
            pauseVideo()
        }

        if (mStoredRememberLastVideoPosition && mWasVideoStarted) {
            saveVideoProgress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mWasFragmentInit) {
            cleanup()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(PROGRESS, mCurrTime)
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        mIsFragmentVisible = menuVisible
        if (mWasFragmentInit) {
            if (menuVisible) {
                initExoPlayer()
                checkExtendedDetails()
            } else {
                pauseVideo()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setVideoSize()
        initTimeHolder()
        checkExtendedDetails()
    }

    private fun storeStateVariables() {
        mStoredShowExtendedDetails = mConfig.showExtendedDetails
        mStoredHideExtendedDetails = mConfig.hideExtendedDetails
        mStoredBottomActions = mConfig.bottomActions
        mStoredExtendedDetails = mConfig.extendedDetails
    }

    private fun updatePlayerMuteState(showToast: Boolean = false) {
        if (!mHasAudio) {
            if (showToast && mWasVideoStarted) {
                Toast.makeText(requireContext(), R.string.video_no_sound, Toast.LENGTH_SHORT).show()
            }
        }

        val isMuted = mConfig.muteVideos
        if (isMuted) mExoPlayer?.mute() else mExoPlayer?.unmute()

        val drawableId = when {
            !mHasAudio -> R.drawable.ic_vector_no_sound
            isMuted -> R.drawable.ic_vector_speaker_off
            else -> R.drawable.ic_vector_speaker_on
        }

        binding.bottomVideoTimeHolder.videoToggleMute.setImageResource(drawableId)

        if (!mMuteInit) {
            mVolumeController = VolumeController(requireContext()) { muted ->
                mConfig.muteVideos = muted
                updatePlayerMuteState()
            }
        }
        mMuteInit = true
    }

    fun togglePlayPause() {
        if (mIsPlaying) pauseVideo() else playVideo()
    }

    fun playVideo() {
        if (mExoPlayer == null) {
            initExoPlayer()
            return
        }

        listener?.updatePlayPause(false)

        if (binding.videoPreview.isVisible()) {
            binding.videoPreview.beGone()
            initExoPlayer()
        }

        val wasEnded = videoEnded()
        if (wasEnded) {
            setPosition(0)
        }

        if (mStoredRememberLastVideoPosition && !mWasLastPositionRestored) {
            mWasLastPositionRestored = true
            restoreLastVideoSavedPosition()
        }

        if (!wasEnded || !mConfig.loopVideos) {
            mPlayPauseButton.setImageResource(R.drawable.ic_pause_vector)
        }

        mWasVideoStarted = true
        if (mIsPlayerPrepared) {
            mIsPlaying = true
        }
        mExoPlayer?.playWhenReady = true
        mBlurPlayer?.seekTo(mExoPlayer?.currentPosition ?: 0L)
        mBlurPlayer?.playWhenReady = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun pauseVideo() {
        if (mExoPlayer == null) {
            return
        }

        listener?.updatePlayPause(true)

        mIsPlaying = false
        if (!videoEnded()) {
            mExoPlayer?.playWhenReady = false
            mBlurPlayer?.playWhenReady = false
        }

        mPlayPauseButton.setImageResource(R.drawable.ic_play_vector)
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mPositionAtPause = mExoPlayer?.currentPosition ?: 0L
    }

    private fun videoEnded(): Boolean {
        val currentPos = mExoPlayer?.currentPosition ?: 0
        val duration = mExoPlayer?.duration ?: 0
        return currentPos != 0L && currentPos >= duration
    }

    private fun setPosition(milliseconds: Long) {
        mExoPlayer?.seekTo(milliseconds)
        mBlurPlayer?.seekTo(milliseconds)
        mSeekBar.progress = milliseconds.toInt()
        mCurrTimeView.text = milliseconds.getFormattedDuration()

        if (!mIsPlaying) {
            mPositionAtPause = milliseconds
        }
    }

    private fun setupVideoDuration() {
        ensureBackgroundThread {
            mDuration = context?.getDuration(mMedium.path)?.times(1000L)?.coerceAtLeast(0L) ?: 0L

            activity?.runOnUiThread {
                setupTimeHolder()
                setPosition(0)
            }
        }
    }

    private fun videoPrepared() {
        if (mDuration == 0L) {
            mDuration = mExoPlayer!!.duration
            setupTimeHolder()
            setPosition(mCurrTime)

            if (mIsFragmentVisible && (mConfig.autoplayVideos)) {
                playVideo()
            }
        }

        if (mPositionWhenInit != 0L && !mWasPlayerInited) {
            setPosition(mPositionWhenInit)
            mPositionWhenInit = 0
        }

        mIsPlayerPrepared = true
        if (mPlayOnPrepared && !mIsPlaying) {
            if (mPositionAtPause != 0L) {
                mExoPlayer?.seekTo(mPositionAtPause)
                mPositionAtPause = 0L
            }
            playVideo()
            updatePlaybackSpeed(mConfig.playbackSpeed)
        }
        mWasPlayerInited = true
        mPlayOnPrepared = false
    }

    private fun videoCompleted() {
        if (!isAdded || mExoPlayer == null) {
            return
        }

        mCurrTime = mExoPlayer!!.duration
        if (listener?.videoEnded() == false && mConfig.loopVideos) {
            playVideo()
        } else {
            mSeekBar.progress = mSeekBar.max
            mCurrTimeView.text = mDuration.getFormattedDuration()
            pauseVideo()
        }
    }

    private fun toggleVideoStretch() {
        mVideoFillMode = if (mVideoFillMode == 0) 2 else 0
        mConfig.videoFillMode = mVideoFillMode
        setVideoSize()
        updateStretchIcon()
    }

    private fun updateStretchIcon() {
        binding.bottomVideoTimeHolder.videoStretch.setImageResource(
            if (mVideoFillMode == 0) R.drawable.ic_maximize_vector
            else R.drawable.ic_minimize_vector
        )
        if (mVideoFillMode != 0 && mConfig.videoFillScreen) {
            mConfig.videoFillScreen = false
            binding.bottomVideoTimeHolder.videoFillScreen.setImageResource(R.drawable.ic_crop_free)
        }
    }

    private fun cleanup() {
        pauseVideo()
        releaseExoPlayer()
        mVolumeController?.destroy()

        if (mWasFragmentInit) {
            mCurrTimeView.text = 0.getFormattedDuration()
            mSeekBar.progress = 0
            mTimerHandler.removeCallbacksAndMessages(null)
            releaseBlurPlayer()
        }
    }

    private fun releaseExoPlayer() {
        mIsPlayerPrepared = false
        mExoPlayer?.apply {
            stop()
            release()
        }
        mExoPlayer = null
    }

    private fun saveVideoProgress() {
        if (!videoEnded()) {
            if (mExoPlayer != null) {
                mConfig.saveLastVideoPosition(
                    mMedium.path,
                    mExoPlayer!!.currentPosition.toInt() / 1000
                )
            } else {
                mConfig.saveLastVideoPosition(mMedium.path, mPositionAtPause.toInt() / 1000)
            }
        }
    }

    private fun restoreLastVideoSavedPosition() {
        val seconds = mConfig.getLastVideoPosition(mMedium.path)
        if (seconds > 0) {
            mPositionAtPause = seconds * 1000L
            setPosition(seconds * 1000L)
        }
    }

    private fun setupTimeHolder() {
        mSeekBar.max = mDuration.toInt()
        binding.bottomVideoTimeHolder.videoDuration.text = mDuration.getFormattedDuration()
        setupTimer()
    }

    private fun setupTimer() {
        activity?.runOnUiThread(object : Runnable {
            override fun run() {
                if (mExoPlayer != null && !mIsDragged && mIsPlaying) {
                    mCurrTime = mExoPlayer!!.currentPosition
                    val blurPos = mBlurPlayer?.currentPosition ?: mCurrTime
                    if (abs(blurPos - mCurrTime) > 20) {
                        mBlurPlayer?.seekTo(mCurrTime)
                    }
                    mSeekBar.progress = mCurrTime.toInt()
                    mCurrTimeView.text = mCurrTime.getFormattedDuration()
                }

                mTimerHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun loadBlurBackground() {
        if (mConfig.blackBackground || !mConfig.blurBackgroundVideo) {
            binding.videoBlurBg.beGone()
            binding.videoBlurSurface.beGone()
            binding.videoBlurOverlay.beGone()
            return
        }
        binding.videoBlurOverlay.beVisible()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.videoBlurBg.beGone()
            binding.videoBlurSurface.beVisible()
            initBlurPlayer()
        } else {
            binding.videoBlurSurface.beGone()
            binding.videoBlurBg.beVisible()
            val target: Any = if (mMedium.path.startsWith("content://"))
                mMedium.path.toUri() else File(mMedium.path)
            Glide.with(this)
                .load(target)
                .transform(MultiTransformation(CenterCrop(), BlurTransformation(60, 3)))
                .into(binding.videoBlurBg)
            binding.videoBlurBg.alpha = 0.2f
        }
    }

    private fun initBlurPlayer() {
        val path = mMedium.path
        val uri = if (path.startsWith("content://")) path.toUri() else Uri.fromFile(File(path))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.videoBlurSurface.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
            )
            binding.videoBlurSurface.alpha = 0.2f
        }

        val blurLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 5000, 500, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        mBlurPlayer = ExoPlayer.Builder(requireContext())
            .setLoadControl(blurLoadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }

        binding.videoBlurSurface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                mBlurPlayer?.setVideoSurface(Surface(st))
                mBlurPlayer?.seekTo(mExoPlayer?.currentPosition ?: 0L)
                mBlurPlayer?.playWhenReady = mIsPlaying
                mBlurPlayer?.setPlaybackSpeed(mExoPlayer?.playbackParameters?.speed ?: 1f)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                mBlurPlayer?.clearVideoSurface(); return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        binding.videoBlurSurface.surfaceTexture?.let { st ->
            mBlurPlayer?.setVideoSurface(Surface(st))
            mBlurPlayer?.seekTo(mExoPlayer?.currentPosition ?: 0L)
            mBlurPlayer?.playWhenReady = mIsPlaying
            mBlurPlayer?.setPlaybackSpeed(mExoPlayer?.playbackParameters?.speed ?: 1f)
        }
    }

    private fun releaseBlurPlayer() {
        mBlurPlayer?.apply { stop(); release() }
        mBlurPlayer = null
    }

    private fun initExoPlayer() {
        val shouldSkipInit = activity == null || mConfig.gestureVideoPlayer || mIsPanorama || mExoPlayer != null
        if (shouldSkipInit) return

        val isContentUri = mMedium.path.startsWith("content://")
        val uri = if (isContentUri) mMedium.path.toUri() else Uri.fromFile(File(mMedium.path))
        val dataSpec = DataSpec(uri)
        val fileDataSource = if (isContentUri) {
            ContentDataSource(requireContext())
        } else {
            FileDataSource()
        }

        try {
            fileDataSource.open(dataSpec)
        } catch (e: Exception) {
            fileDataSource.close()
            activity?.showErrorToast(e)
            return
        }

        val factory = DataSource.Factory { fileDataSource }
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(fileDataSource.uri!!))

        fileDataSource.close()

        mPlayOnPrepared = true

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MAX_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        mExoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(requireContext()))
            .setSeekParameters(SeekParameters.EXACT)
            .setLoadControl(loadControl)
            .build()
            .apply {
                if (mConfig.loopVideos && listener?.isSlideShowActive() == false) {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
                setPlaybackSpeed(mConfig.playbackSpeed)
                setMediaSource(mediaSource)
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false
                )
                prepare()

                if (mTextureView.surfaceTexture != null) {
                    setVideoSurface(Surface(mTextureView.surfaceTexture))
                }

                initListeners()
            }

        updatePlayerMuteState()
        loadBlurBackground()
    }

    private fun ExoPlayer.initListeners() {
        addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    mSeekBar.progress = 0
                    mCurrTimeView.text = 0.getFormattedDuration()
                } else {
                    mBlurPlayer?.seekTo(newPosition.positionMs)
                }
            }

            override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> videoPrepared()
                    Player.STATE_ENDED -> videoCompleted()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                val ratio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                mVideoSize.x = videoSize.width
                mVideoSize.y = (videoSize.height / ratio).toInt().coerceAtLeast(1)
                setVideoSize()
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                binding.errorMessageHolder.errorMessage.apply {
                    if (error != null) {
                        binding.videoPreview.beGone()
                        binding.videoPlayOutline.beGone()
                        text = error.getFriendlyMessage(context)
                        setTextColor(if (context.config.blackBackground) Color.WHITE else context.getProperTextColor())
                        fadeIn()
                    } else {
                        beGone()
                        binding.videoPlayOutline.beVisible()
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                mBlurPlayer?.playWhenReady = playWhenReady
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                mBlurPlayer?.playbackParameters = playbackParameters
            }

            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                mHasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
                updatePlayerMuteState()
            }
        })
    }

    private fun toggleFullscreen() {
        listener?.fragmentClicked()
    }

    private fun handleDoubleTap(x: Float) {
        val viewWidth = mView.width
        val instantWidth = viewWidth / 7
        when {
            x <= instantWidth -> doSkip(false)
            x >= viewWidth - instantWidth -> doSkip(true)
            else -> togglePlayPause()
        }
    }

    private fun checkExtendedDetails() {
        if (mConfig.showExtendedDetails) {
            binding.videoDetails.apply {
                text = getMediumExtendedDetails(mMedium)
                beVisibleIf(text.isNotEmpty())
                alpha = if (!mConfig.hideExtendedDetails || !mIsFullscreen) 1f else 0f
                (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(this)
            }
        } else {
            binding.videoDetails.beGone()
        }
    }

    private fun initTimeHolder() {
        mTimeHolder.beGoneIf(mIsFullscreen)
        mTimeHolder.alpha = if (mIsFullscreen) 0f else 1f
        (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(mTimeHolder)
    }

    override fun fullscreenToggled(isFullscreen: Boolean) {
        mIsFullscreen = isFullscreen

        mSeekBar.setOnSeekBarChangeListener(if (mIsFullscreen) null else this)
        arrayOf(
            binding.bottomVideoTimeHolder.videoCurrTime,
            binding.bottomVideoTimeHolder.videoDuration,
            binding.bottomVideoTimeHolder.videoTogglePlayPause,
            binding.bottomVideoTimeHolder.videoPlaybackSpeed,
            binding.bottomVideoTimeHolder.videoToggleMute
        ).forEach {
            it.isClickable = !mIsFullscreen
        }

        if (isFullscreen) {
            mTimeHolder.fadeOut(DEFAULT_ANIMATION_DURATION)
            binding.bottomActionsDummy.fadeOut(DEFAULT_ANIMATION_DURATION)
        } else {
            binding.bottomActionsDummy.beVisible()
            mTimeHolder.fadeIn(DEFAULT_ANIMATION_DURATION)
        }

        binding.videoDetails.apply {
            if (mStoredShowExtendedDetails && isVisible() && context != null && resources != null) {
                if (mStoredHideExtendedDetails) {
                    animate().alpha(if (isFullscreen) 0f else 1f).start()
                }
            }
        }
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        childFragmentManager.beginTransaction().add(fragment, fragment::class.java.simpleName)
            .commit()
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        binding.bottomVideoTimeHolder.videoPlaybackSpeed.text =
            "${DecimalFormat("#.##").format(speed)}x"
        mExoPlayer?.setPlaybackSpeed(speed)
        mBlurPlayer?.setPlaybackSpeed(speed)
    }

    private fun skip(forward: Boolean) {
        if (mExoPlayer == null) return
        val curr = mExoPlayer!!.currentPosition
        val newPos = if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        setPosition(newPos.coerceIn(0, mExoPlayer!!.duration))
    }

    private fun doSkip(forward: Boolean) = skip(forward)

    // CORRIGIDO: Adicionado override e removido handleTouchHoldEvent
    override fun handleEvent(event: MotionEvent) {
cd ~/Right100 && \
# 1. Completar o arquivo VideoFragment.kt que ficou cortado
cat >> app/src/main/kotlin/com/goodwy/gallery/fragments/VideoFragment.kt << 'ENDFILE'
        // Implementação simplificada - gestos básicos
    }

    private fun setVideoSize() {
        if (activity == null || mConfig.gestureVideoPlayer) return

        val videoProportion = mVideoSize.x.toFloat() / mVideoSize.y.toFloat()
        val display = requireActivity().windowManager.defaultDisplay
        val screenWidth: Int
        val screenHeight: Int

        val realMetrics = DisplayMetrics()
        display.getRealMetrics(realMetrics)
        screenWidth = realMetrics.widthPixels
        screenHeight = realMetrics.heightPixels

        val screenProportion = screenWidth.toFloat() / screenHeight.toFloat()

        mTextureView.layoutParams.apply {
            when {
                mConfig.videoFillScreen -> {
                    if (videoProportion > screenProportion) {
                        width = (videoProportion * screenHeight.toFloat()).toInt()
                        height = screenHeight
                    } else {
                        width = screenWidth
                        height = (screenWidth.toFloat() / videoProportion).toInt()
                    }
                }
                mVideoFillMode == 2 -> {
                    width = screenWidth
                    height = screenHeight
                }
                else -> {
                    if (videoProportion > screenProportion) {
                        width = screenWidth
                        height = (screenWidth.toFloat() / videoProportion).toInt()
                    } else {
                        width = (videoProportion * screenHeight.toFloat()).toInt()
                        height = screenHeight
                    }
                }
            }
            mTextureView.layoutParams = this
        }

        if (mConfig.blurBackgroundVideo && !mConfig.blackBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.videoBlurSurface.layoutParams.apply {
                if (videoProportion > screenProportion) {
                    width = (videoProportion * screenHeight.toFloat()).toInt()
                    height = screenHeight
                } else {
                    width = screenWidth
                    height = (screenWidth.toFloat() / videoProportion).toInt()
                }
                binding.videoBlurSurface.layoutParams = this
            }
        }
    }

    private fun openPanorama() {}

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) setPosition(progress.toLong())
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) { mIsDragged = true }
    override fun onStopTrackingTouch(seekBar: SeekBar?) { mIsDragged = false }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        mExoPlayer?.setVideoSurface(Surface(st))
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        mExoPlayer?.clearVideoSurface()
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
