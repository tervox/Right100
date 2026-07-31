@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package com.goodwy.gallery.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updateLayoutParams
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.interfaces.PlaybackSpeedListener
import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.views.MediaSideScroll
import java.io.File
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
    private var mIsPlayerPrepared = false
    private var mPlayOnPrepared = false
    private var mCurrTime = 0L
    private var mDuration = 0L
    private var mPositionWhenInit = 0L
    private var mPositionAtPause = 0L
    var mIsPlaying = false

    private var mExoPlayer: ExoPlayer? = null
    private var mVideoSize = Point(1, 1)
    // Thread dedicada para o timer do seekbar: evita 60 callbacks/s no main thread
    // enquanto o decoder de vídeo está rodando na mesma prioridade.
    private val mTimerThread = android.os.HandlerThread("VideoTimerThread").also { it.start() }
    private var mTimerHandler = android.os.Handler(mTimerThread.looper)
    private val mMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mTimerRunnable: Runnable? = null  // referencia para poder cancelar o loop
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null

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
        mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop / TOUCH_SLOP_DIVIDER

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

            videoSurfaceFrame.controller.settings.swallowDoubleTaps = true

            videoPlayOutline.setOnClickListener {
                if (mConfig.gestureVideoPlayer) activity.launchGesturePlayer(mMedium.path)
                else togglePlayPause()
            }

            mPlayPauseButton = bottomVideoTimeHolder.videoTogglePlayPause
            mPlayPauseButton.beGoneIf(mConfig.visibleBottomActions and BOTTOM_ACTION_PLAY_PAUSE != 0)
            mPlayPauseButton.setOnClickListener { togglePlayPause() }

            bottomVideoTimeHolder.videoToggleMute.beGoneIf(mConfig.visibleBottomActions and BOTTOM_ACTION_MUTE != 0)

            mSeekBar = bottomVideoTimeHolder.videoSeekbar
            mPlaybackSpeedPill = playbackSpeedPill
            mSeekBar.setOnSeekBarChangeListener(this@VideoFragment)
            mSeekBar.setOnClickListener { }

            mTimeHolder = bottomVideoTimeHolder.videoTimeHolder
            mCurrTimeView = bottomVideoTimeHolder.videoCurrTime
            mBrightnessSideScroll = videoBrightnessController
            mVolumeSideScroll = videoVolumeController
            mBrightnessSideScroll.onVerticalScroll = { mTimerHandler.removeCallbacks(mTouchHoldRunnable) }
            mVolumeSideScroll.onVerticalScroll = { mTimerHandler.removeCallbacks(mTouchHoldRunnable) }
            mTextureView = videoSurface
            mTextureView.surfaceTextureListener = this@VideoFragment

            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!mConfig.allowInstantChange) { toggleFullscreen(); return true }
                    val viewWidth = root.width
                    val instantWidth = viewWidth / 7
                    when {
                        e.rawX <= instantWidth -> listener?.goToPrevItem()
                        e.rawX >= viewWidth - instantWidth -> listener?.goToNextItem()
                        else -> toggleFullscreen()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap(e.rawX)
                    return true
                }
            })

            videoPreview.setOnTouchListener { _, event -> handleEvent(event); false }

            videoSurfaceFrame.setOnTouchListener { _, event ->
                if (videoSurfaceFrame.controller.state.zoom == 1f) handleEvent(event)
                handleTouchHoldEvent(event)
                if (mIsLongPressActive) return@setOnTouchListener true
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.videoHolder) { _, insets ->
            val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
            val pillTopMargin = system.top + resources.getActionBarHeight(context) +
                resources.getDimension(com.goodwy.commons.R.dimen.normal_margin).toInt()
            (mPlaybackSpeedPill.layoutParams as? RelativeLayout.LayoutParams)?.apply { setMargins(0, pillTopMargin, 0, 0) }
            binding.bottomActionsDummy.updateLayoutParams<ViewGroup.LayoutParams> {
                height = resources.getBottomActionsHeight() + system.bottom
            }
            insets
        }

        mView = binding.root
        if (!arguments.getBoolean(SHOULD_INIT_FRAGMENT, true)) return mView

        updatePlaybackSpeed(mConfig.playbackSpeed)
        storeStateVariables()
        Glide.with(context).load(mMedium.path).into(binding.videoPreview)

        if (!mIsFragmentVisible && activity is VideoActivity) mIsFragmentVisible = true
        mIsFullscreen = listener?.isFullScreen() == true
        initTimeHolder()

        ensureBackgroundThread {
            activity.getVideoResolution(mMedium.path)?.apply { mVideoSize.x = x; mVideoSize.y = y }
        }

        if (mIsPanorama) {
            binding.apply {
                panoramaOutline.beVisible(); videoPlayOutline.beGone()
                mVolumeSideScroll.beGone(); mBrightnessSideScroll.beGone()
                Glide.with(context).load(mMedium.path).into(videoPreview)
            }
        }

        if (!mIsPanorama) {
            if (savedInstanceState != null) mCurrTime = savedInstanceState.getLong(PROGRESS, 0L)
            mWasFragmentInit = true
            setVideoSize()
            binding.apply {
                mBrightnessSideScroll.initialize(activity, slideInfo, true, container,
                    singleTap = { _, _ -> if (mConfig.allowInstantChange) listener?.goToPrevItem() else toggleFullscreen() },
                    doubleTap = { _, _ -> doSkip(false) }
                )
                mVolumeSideScroll.initialize(activity, slideInfo, false, container,
                    singleTap = { _, _ -> if (mConfig.allowInstantChange) listener?.goToNextItem() else toggleFullscreen() },
                    doubleTap = { _, _ -> doSkip(true) }
                )
                videoSurface.onGlobalLayout {
                    if (mIsFragmentVisible && mConfig.autoplayVideos && !mConfig.gestureVideoPlayer) playVideo()
                }
            }
        }

        setupVideoDuration()
        if (mStoredRememberLastVideoPosition) restoreLastVideoSavedPosition()

        mVolumeController = VolumeController(context) { isMuted ->
            if (mMuteInit) { mConfig.muteVideos = isMuted; updatePlayerMuteState() }
        }

        return mView
    }

    override fun onResume() {
        super.onResume()
        mConfig = requireContext().config
        requireActivity().updateTextColors(binding.videoHolder)
        val allowVideoGestures = mConfig.allowVideoGestures
        mTextureView.beGoneIf(mConfig.gestureVideoPlayer || mIsPanorama)
        binding.videoSurfaceFrame.beGoneIf(mTextureView.isGone())
        mVolumeSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)
        mBrightnessSideScroll.beVisibleIf(allowVideoGestures && !mIsPanorama)
        checkExtendedDetails()
        initTimeHolder()
        storeStateVariables()
        context?.let {
            (mSeekBar as MySeekBar?)!!.setColors(it.getProperTextColor(), it.getProperPrimaryColor(),
                if (!mConfig.blackBackground) it.getProperTextColor() else resources.getColor(com.goodwy.commons.R.color.white))
        }
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        pauseVideo()
        if (mStoredRememberLastVideoPosition && mIsFragmentVisible && mWasVideoStarted) saveVideoProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activity?.isChangingConfigurations == false) cleanup()
        if (::mVolumeSideScroll.isInitialized) mVolumeSideScroll.cleanup()
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (mIsFragmentVisible && !menuVisible) pauseVideo()
        mIsFragmentVisible = menuVisible
        if (mWasFragmentInit && menuVisible && mConfig.autoplayVideos && !mConfig.gestureVideoPlayer) playVideo()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setVideoSize()
        binding.videoSurfaceFrame.onGlobalLayout { binding.videoSurfaceFrame.controller.resetState() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(PROGRESS, mCurrTime)
    }

    private fun storeStateVariables() {
        mConfig.apply {
            mStoredShowExtendedDetails = showExtendedDetails
            mStoredHideExtendedDetails = hideExtendedDetails
            mStoredExtendedDetails = extendedDetails
            mStoredBottomActions = bottomActions
            mStoredRememberLastVideoPosition = rememberLastVideoPosition
        }
    }

    private fun saveVideoProgress() {
        if (!videoEnded()) {
            mConfig.saveLastVideoPosition(mMedium.path, (mExoPlayer?.currentPosition ?: mPositionAtPause).toInt() / 1000)
        }
    }

    private fun restoreLastVideoSavedPosition() {
        val seconds = mConfig.getLastVideoPosition(mMedium.path)
        if (seconds > 0) { mPositionAtPause = seconds * 1000L; setPosition(seconds * 1000L) }
    }

    private fun setupTimeHolder() {
        mSeekBar.max = mDuration.toInt()
        binding.bottomVideoTimeHolder.videoDuration.text = mDuration.getFormattedDuration()
        setupTimer()
    }

    private fun setupTimer() {
        // Cancela qualquer loop anterior antes de criar um novo;
        // sem isso cada chamada acumula um Runnable no mMainHandler
        // => multiplos updates de UI e multiplos audios simultaneos.
        mTimerRunnable?.let { mMainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (mExoPlayer != null && !mIsDragged && mIsPlaying) {
                    mCurrTime = mExoPlayer!!.currentPosition
                    mSeekBar.progress = mCurrTime.toInt()
                    mCurrTimeView.text = mCurrTime.getFormattedDuration()
                }
                mMainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        mTimerRunnable = runnable
        mMainHandler.post(runnable)
    }

    private fun initExoPlayer() {
        if (activity == null || mConfig.gestureVideoPlayer || mIsPanorama || mExoPlayer != null) return
        val isContentUri = mMedium.path.startsWith("content://")
        val uri = if (isContentUri) mMedium.path.toUri() else Uri.fromFile(File(mMedium.path))
        val dataSpec = DataSpec(uri)
        val fileDataSource = if (isContentUri) ContentDataSource(requireContext()) else FileDataSource()
        try { fileDataSource.open(dataSpec) } catch (e: Exception) { fileDataSource.close(); return }
        val factory = DataSource.Factory { fileDataSource }
        val mediaSource: MediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(fileDataSource.uri!!))
        fileDataSource.close()
        // Restaurado do original: sinaliza que o usuário pediu explicitamente pra tocar este
        // vídeo (via playVideo() chamando initExoPlayer() num player ainda não criado),
        // independente da configuração de reprodução automática. Sem isso, se "reprodução
        // automática" estiver desligada, nada disparava o play de verdade depois da primeira
        // inicialização do player - o vídeo ficava preparado mas nunca realmente tocava.
        mPlayOnPrepared = true
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(EXOPLAYER_MIN_BUFFER_MS, EXOPLAYER_MAX_BUFFER_MS, EXOPLAYER_MIN_BUFFER_MS, EXOPLAYER_MIN_BUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        mExoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(requireContext()))
            .setSeekParameters(SeekParameters.CLOSEST_SYNC).setLoadControl(loadControl).build().apply {
                if (mConfig.loopVideos && listener?.isSlideShowActive() == false) repeatMode = Player.REPEAT_MODE_ONE
                setPlaybackSpeed(mConfig.playbackSpeed); setMediaSource(mediaSource)
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                // ExoPlayer nasce com playWhenReady=true por padrão. Sem essa linha, o player
                // criado aqui (chamado por onSurfaceTextureAvailable assim que a superfície do
                // TextureView fica pronta, SEM checar mConfig.autoplayVideos) começava a tocar
                // sozinho — inclusive com "reprodução automática" desligada nas configurações.
                // Toda reprodução real agora só começa via playVideo(), que já conecta a
                // superfície de vídeo antes de habilitar a reprodução.
                playWhenReady = false
                prepare()
                mSurface?.let { setVideoSurface(it) }
                initListeners()
            }
        updatePlayerMuteState()
    }

    private fun ExoPlayer.initListeners() {
        addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, @Player.DiscontinuityReason reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { mSeekBar.progress = 0; mCurrTimeView.text = 0.getFormattedDuration() }
            }
            override fun onPlaybackStateChanged(@Player.State state: Int) {
                when (state) { Player.STATE_READY -> videoPrepared(); Player.STATE_ENDED -> videoCompleted() }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                mVideoSize.x = videoSize.width; mVideoSize.y = (videoSize.height / videoSize.pixelWidthHeightRatio).toInt(); setVideoSize()
            }
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                binding.errorMessageHolder.errorMessage.apply {
                    if (error != null) {
                        binding.videoPreview.beGone(); binding.videoPlayOutline.beGone()
                        text = error.getFriendlyMessage(context)
                        setTextColor(if (context.config.blackBackground) Color.WHITE else context.getProperTextColor()); fadeIn()
                    } else { beGone(); binding.videoPlayOutline.beVisible() }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                mHasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO); updatePlayerMuteState()
            }
        })
    }

    private fun toggleFullscreen() = listener?.fragmentClicked()

    private fun handleDoubleTap(x: Float) {
        val viewWidth = mView.width; val instantWidth = viewWidth / 7
        when { x <= instantWidth -> doSkip(false); x >= viewWidth - instantWidth -> doSkip(true); else -> togglePlayPause() }
    }

    private fun checkExtendedDetails() {
        if (mConfig.showExtendedDetails) {
            binding.videoDetails.apply {
                text = getMediumExtendedDetails(mMedium); beVisibleIf(text.isNotEmpty())
                alpha = if (!mConfig.hideExtendedDetails || !mIsFullscreen) 1f else 0f
                (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(this)
            }
        } else binding.videoDetails.beGone()
    }

    private fun initTimeHolder() {
        mTimeHolder.beGoneIf(mIsFullscreen); mTimeHolder.alpha = if (mIsFullscreen) 0f else 1f
        (activity as? BaseViewerActivity)?.applyProperHorizontalInsets(mTimeHolder)
        if (mConfig.gestureVideoPlayer) return
        binding.bottomVideoTimeHolder.videoFillScreen.beGone()
        binding.bottomVideoTimeHolder.videoStretch.apply {
            beVisible()
            fun updateIcon() {
                setImageResource(when {
                    mConfig.videoFillMode == 2 -> R.drawable.ic_maximize_vector
                    else -> R.drawable.ic_crop_free
                })
            }
            // Garante que um fill residual de versao anterior seja resetado
            if (mConfig.videoFillScreen) { mConfig.videoFillScreen = false }
            updateIcon()
            setOnClickListener {
                if (mConfig.videoFillMode != 2) {
                    mConfig.videoFillMode = 2; mConfig.videoFillScreen = false
                } else {
                    mConfig.videoFillMode = 0; mConfig.videoFillScreen = false
                }
                setVideoSize(); updateIcon()
            }
        }
    }

    private fun openPanorama() { TODO("Panorama is not yet implemented.") }

    override fun fullscreenToggled(isFullscreen: Boolean) {
        mIsFullscreen = isFullscreen
        mSeekBar.setOnSeekBarChangeListener(if (mIsFullscreen) null else this)
        arrayOf(binding.bottomVideoTimeHolder.videoCurrTime, binding.bottomVideoTimeHolder.videoDuration, binding.bottomVideoTimeHolder.videoTogglePlayPause, binding.bottomVideoTimeHolder.videoPlaybackSpeed, binding.bottomVideoTimeHolder.videoToggleMute).forEach { it.isClickable = !mIsFullscreen }
        if (isFullscreen) { mTimeHolder.fadeOut(); binding.bottomActionsDummy.fadeOut() }
        else { binding.bottomActionsDummy.beVisible(); mTimeHolder.fadeIn() }
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        childFragmentManager.beginTransaction().add(fragment, fragment::class.java.simpleName).commit()
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        @SuppressLint("SetTextI18n")
        binding.bottomVideoTimeHolder.videoPlaybackSpeed.text = "${DecimalFormat("#.##").format(speed)}x"
        mExoPlayer?.setPlaybackSpeed(speed)
    }

    private fun skip(forward: Boolean) {
        if (mIsPanorama) return
        if (mExoPlayer == null) { playVideo(); return }
        mPositionAtPause = 0L; doSkip(forward)
    }

    private fun doSkip(forward: Boolean) {
        if (mExoPlayer == null) return
        val curr = mExoPlayer!!.currentPosition
        var newPosition = if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        newPosition = newPosition.coerceIn(0, maxOf(mExoPlayer!!.duration, 0))
        setPosition(newPosition)
    }

    private var mLastSeekMs = 0L
    private val mSeekThrottleMs = 60L

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            val newPosition = progress.toLong()
            if (mExoPlayer != null) {
                if (!mWasPlayerInited) mPositionWhenInit = newPosition
                mSeekBar.progress = newPosition.toInt(); mCurrTimeView.text = newPosition.getFormattedDuration()
                if (!mIsPlaying) mPositionAtPause = newPosition
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - mLastSeekMs >= mSeekThrottleMs) { mLastSeekMs = now; mExoPlayer?.seekTo(newPosition) }
            }
            if (mExoPlayer == null) { mPositionAtPause = newPosition; playVideo() }
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (mExoPlayer == null) return
        mExoPlayer!!.setSeekParameters(SeekParameters.CLOSEST_SYNC); mExoPlayer!!.playWhenReady = false; mIsDragged = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (mIsPanorama) { openPanorama(); return }
        if (mExoPlayer == null) return
        mExoPlayer!!.setSeekParameters(SeekParameters.EXACT); mExoPlayer!!.seekTo(mSeekBar.progress.toLong())
        if (mIsPlaying) mExoPlayer!!.playWhenReady = true
        mIsDragged = false
    }

    fun getCurrentVideoPositionMs(): Long = mExoPlayer?.currentPosition ?: mCurrTime

    fun captureCurrentFrame(): android.graphics.Bitmap? =
        try { mTextureView.getBitmap() } catch (_: Exception) { null }

    fun togglePlayPause() {
        if (activity == null || !isAdded) return
        if (mIsPlaying) pauseVideo() else playVideo()
    }

    private fun updatePlayerMuteState(showToast: Boolean = false) {
        val isMuted = mConfig.muteVideos
        if (mHasAudio) { if (isMuted) mExoPlayer?.mute() else mExoPlayer?.unmute() }
        else if (showToast && mWasVideoStarted) activity?.toast(R.string.video_no_sound)
        binding.bottomVideoTimeHolder.videoToggleMute.setImageResource(when {
            !mHasAudio -> R.drawable.ic_vector_no_sound; isMuted -> R.drawable.ic_vector_speaker_off; else -> R.drawable.ic_vector_speaker_on
        })
        mMuteInit = true
    }

    fun playVideo() {
        if (mExoPlayer == null) { initExoPlayer(); return }
        listener?.updatePlayPause(false)
        if (binding.videoPreview.isVisible()) { binding.videoPreview.beGone() }
        val wasEnded = videoEnded()
        if (wasEnded) setPosition(0)
        if (mStoredRememberLastVideoPosition && !mWasLastPositionRestored) { mWasLastPositionRestored = true; restoreLastVideoSavedPosition() }
        if (!wasEnded || !mConfig.loopVideos) mPlayPauseButton.setImageResource(R.drawable.ic_pause_vector)
        if (!mWasVideoStarted) binding.bottomVideoTimeHolder.videoPlaybackSpeed.text = "${DecimalFormat("#.##").format(mConfig.playbackSpeed)}x"
        mWasVideoStarted = true
        if (mIsPlayerPrepared) mIsPlaying = true
        if (mSurface != null) { mExoPlayer?.setVideoSurface(mSurface) }
        else if (mTextureView.surfaceTexture != null) {
            mSurface = Surface(mTextureView.surfaceTexture)
            mExoPlayer?.setVideoSurface(mSurface)
        }
        mExoPlayer?.playWhenReady = true
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun pauseVideo() {
        if (mExoPlayer == null) return
        listener?.updatePlayPause(true)
        mIsPlaying = false
        if (!videoEnded()) mExoPlayer?.playWhenReady = false
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
        mExoPlayer?.seekTo(milliseconds); mSeekBar.progress = milliseconds.toInt()
        mCurrTimeView.text = milliseconds.getFormattedDuration()
        if (!mIsPlaying) mPositionAtPause = milliseconds
    }

    private fun setupVideoDuration() {
        ensureBackgroundThread {
            mDuration = context?.getDuration(mMedium.path)?.times(1000L)?.coerceAtLeast(0L) ?: 0L
            activity?.runOnUiThread { setupTimeHolder(); setPosition(0) }
        }
    }

    private fun videoPrepared() {
        if (mDuration == 0L) { mDuration = mExoPlayer!!.duration; setupTimeHolder(); setPosition(mCurrTime); if (mIsFragmentVisible && mConfig.autoplayVideos) playVideo() }
        if (mPositionWhenInit != 0L && !mWasPlayerInited) { setPosition(mPositionWhenInit); mPositionWhenInit = 0L }
        mIsPlayerPrepared = true
        // Restaurado do original: se o usuário pediu explicitamente pra tocar (mPlayOnPrepared,
        // setado em initExoPlayer()) e ainda não está tocando, inicia a reprodução agora que o
        // player ficou pronto - independente da configuração de reprodução automática.
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

    private fun videoCompleted() = listener?.videoEnded()

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        mSurfaceTexture = surface
        mSurface = Surface(surface)
        if (mExoPlayer != null) mExoPlayer!!.setVideoSurface(mSurface) else initExoPlayer()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        mSurfaceTexture = null
        mSurface = null
        // Retorna false (não true) - igual ao original. true diz ao Android "pode liberar essa
        // superfície agora"; se isso acontecer enquanto o player ainda está tocando (ex: durante
        // um detach/relayout temporário da view), o vídeo perde onde renderizar pra sempre,
        // mesmo depois de uma nova superfície ficar disponível - o áudio não é afetado por isso
        // e continua tocando normalmente, explicando a tela preta com áudio.
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun setVideoSize() {
        val videoHeight = mVideoSize.y; val videoWidth = mVideoSize.x
        if (videoHeight == 0 || videoWidth == 0) return
        val view = mTextureView
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        when {
            mConfig.videoFillMode == 2 -> {
                // Esticado: MATCH_PARENT usa o tamanho real do frame (displayMetrics ignora barras de sistema)
                view.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                view.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            mConfig.videoFillScreen -> {
                // Fill: FitMethod.NONE impede o GestureFrameLayout de encolher o filho
                // (padrao INSIDE anulava o modo fill tornando-o identico ao normal)
                val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
                if (videoRatio > screenRatio) {
                    view.layoutParams.height = screenHeight
                    view.layoutParams.width = (screenHeight * videoRatio).toInt()
                } else {
                    view.layoutParams.width = screenWidth
                    view.layoutParams.height = (screenWidth / videoRatio).toInt()
                }
            }
            else -> {
                // Normal: encaixa com margem, sem recorte
                val margin = resources.getDimension(com.goodwy.commons.R.dimen.activity_margin).toInt()
                val viewHeight = screenHeight - margin
                val viewWidth = screenWidth - margin
                val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
                if (videoRatio > viewRatio) { view.layoutParams.width = viewWidth; view.layoutParams.height = (viewWidth / videoRatio).toInt() }
                else { view.layoutParams.width = (viewHeight * videoRatio).toInt(); view.layoutParams.height = viewHeight }
            }
        }
        view.requestLayout()
        // Aplica o novo FitMethod apos o layout medir o filho no novo tamanho
        binding.videoSurfaceFrame.onGlobalLayout {
            binding.videoSurfaceFrame.controller.resetState()
            if (mConfig.videoFillScreen) {
                // INSIDE fit encaixa o vídeo dentro do frame (letterbox/pillarbox).
                // scaleX/Y aplicam uma escala adicional ao TextureView para cobrir o frame.
                // As transformadas compõem: GestureFrameLayout translada o canvas,
                // scaleX/Y do filho escala sobre isso — resultado: fill com bordas recortadas. ✓
                val frameW = binding.videoSurfaceFrame.width.toFloat()
                val frameH = binding.videoSurfaceFrame.height.toFloat()
                if (view.width > 0 && view.height > 0) {
                    val scale = maxOf(frameW / view.width, frameH / view.height)
                    view.scaleX = scale
                    view.scaleY = scale
                }
            } else {
                view.scaleX = 1f
                view.scaleY = 1f
            }
        }
    }

    fun releasePlayerForFileOp() = cleanup()

    private fun cleanup() {
        mTimerRunnable?.let { mMainHandler.removeCallbacks(it) }
        mTimerRunnable = null
        mExoPlayer?.release(); mExoPlayer = null
        mTimerHandler.removeCallbacksAndMessages(null)
        try { mTimerThread.quit() } catch (_: Exception) {}
    }

    protected fun handleTouchHoldEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mInitialX = event.rawX; mInitialY = event.rawY
                mTimerHandler.removeCallbacks(mTouchHoldRunnable)
                mTimerHandler.postDelayed(mTouchHoldRunnable, TOUCH_HOLD_DURATION_MS.toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val distX = abs(event.rawX - mInitialX); val distY = abs(event.rawY - mInitialY)
                if (distX > mTouchSlop || distY > mTouchSlop) { mIsLongPressActive = false; mTimerHandler.removeCallbacks(mTouchHoldRunnable) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mIsLongPressActive = false; mTimerHandler.removeCallbacks(mTouchHoldRunnable)
                if (mOriginalPlaybackSpeed != 1f) { updatePlaybackSpeed(mOriginalPlaybackSpeed); mPlaybackSpeedPill.fadeOut() }
            }
        }
    }
}
