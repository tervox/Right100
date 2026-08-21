package com.goodwy.gallery.activities

import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.speech.RecognizerIntent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.goodwy.commons.dialogs.CreateNewFolderDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FileDirItem
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.views.MyGridLayoutManager
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.gallery.R
import com.goodwy.gallery.adapters.MediaAdapter
import com.goodwy.gallery.asynctasks.GetMediaAsynctask
import com.goodwy.gallery.databases.GalleryDatabase
import com.goodwy.gallery.databinding.ActivityMediaBinding
import com.goodwy.gallery.dialogs.*
import com.goodwy.gallery.extensions.*
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.interfaces.MediaOperationsListener
import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.models.ThumbnailItem
import com.goodwy.gallery.models.ThumbnailSection
import com.google.android.material.appbar.AppBarLayout
import java.io.File
import java.io.IOException
import java.util.Objects
import kotlin.math.abs

class MediaActivity : SimpleActivity(), MediaOperationsListener {
    override var isSearchBarEnabled = true

    private val LAST_MEDIA_CHECK_PERIOD = 3000L

    private var mPath = ""
    private var mIsGetImageIntent = false
    private var mIsGetVideoIntent = false
    private var mIsGetAnyIntent = false
    private var mIsGettingMedia = false
    private var mMediaInvalidated = true
    private var mLastSuccessfulMediaLoadAt = 0L
    private var mAllowPickingMultiple = false
    private var mShowAll = false
    private var mLoadedInitialPhotos = false
    private var mShowLoadingIndicator = true
    private var mWasFullscreenViewOpen = false
    private var mLastSearchedText = ""
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mCurrAsyncTask: GetMediaAsynctask? = null
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null

    // Evita iniciar dois preenchimentos concorrentes para a mesma pasta quando a lista
    // vem primeiro do Room e depois do MediaStore.
    private val durationFillLock = Any()
    private var durationFillPath = ""

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredShowFileTypes = true
    private var mStoredRoundedCorners = false
    private var mStoredMarkFavoriteItems = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredThumbnailSpacing = 0
    private var mStoredHideTopBarWhenScroll = false
    private var isSpeechToTextAvailable = false
    private var wasKeyboardVisible = false

    private val binding by viewBinding(ActivityMediaBinding::inflate)

    companion object {
        // @Volatile: mMedia/mMediaPath são escritos e lidos por várias threads em paralelo
        // (UI thread, callback do AsyncTask, thread de getCachedMedia, thread de
        // fillMissingVideoDurations). Sem @Volatile, uma thread pode não enxergar a
        // reatribuição feita por outra (visibilidade de memória), o que já foi observado
        // causando comportamento inconsistente / crash ao abrir pastas rapidamente.
        @Volatile
        var mMedia = ArrayList<ThumbnailItem>()
        @Volatile
        var mMediaPath = ""

        // mMedia/mMediaPath só guardam a ÚLTIMA pasta visitada (1 slot). Navegando por
        // várias pastas (A -> B -> A), a segunda visita a A já não batia nesse cache (porque
        // mMediaPath virou B ao visitar B), caindo sempre na consulta ao banco de novo. Este
        // mapa guarda as últimas quatro pastas visitadas (LRU) para reaproveitar navegação
        // recente sem manter uma cópia grande da biblioteca inteira em memória.
        private const val FOLDER_CACHE_MAX_SIZE = 4
        // A lista em memória permanece válida por alguns minutos; alterações reais são
        // detectadas pelo checkLastMediaChanged e ações explícitas usam forceRefresh.
        private const val MEDIA_CACHE_TTL_MS = 5 * 60_000L
        val mFolderMediaCacheUpdatedAt = HashMap<String, Long>()
        val mFolderMediaCache = object : LinkedHashMap<String, ArrayList<ThumbnailItem>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayList<ThumbnailItem>>?): Boolean {
                val shouldRemove = size > FOLDER_CACHE_MAX_SIZE
                if (shouldRemove) {
                    eldest?.key?.let { mFolderMediaCacheUpdatedAt.remove(it) }
                }
                return shouldRemove
            }
        }

        // Lock dedicado para mutações in-place em mMedia (indexOf/removeAll concorrentes com
        // reatribuições) — usado onde a mesma ArrayList pode ser lida por uma thread enquanto
        // outra a modifica, o que gera ConcurrentModificationException.
        private val mediaLock = Any()

        // Chamado pela Application (App.kt) quando o Android sinaliza pressão de memória
        // (onTrimMemory/onLowMemory). Antes disso não existia NENHUM tratamento desses sinais
        // em todo o app — os caches estáticos acima (mFolderMediaCache podendo guardar até 4
        // pastas inteiras, mMedia guardando a última pasta) nunca eram liberados, nem quando o
        // app ia pra segundo plano com pouca memória disponível. Isso deixa o sistema sem opção
        // além de matar o processo inteiro sem aviso — o que explica fechamentos "aleatórios",
        // sem relação com nenhuma tela específica.
        fun clearMemoryCaches(aggressive: Boolean) {
            synchronized(mFolderMediaCache) {
                mFolderMediaCache.clear()
                mFolderMediaCacheUpdatedAt.clear()
            }
            if (aggressive) {
                synchronized(mediaLock) {
                    mMedia = ArrayList()
                    mMediaPath = ""
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        intent.apply {
            mIsGetImageIntent = getBooleanExtra(GET_IMAGE_INTENT, false)
            mIsGetVideoIntent = getBooleanExtra(GET_VIDEO_INTENT, false)
            mIsGetAnyIntent = getBooleanExtra(GET_ANY_INTENT, false)
            mAllowPickingMultiple = getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }

        binding.mediaRefreshLayout.setOnRefreshListener { getMedia(forceRefresh = true) }
        setupSelectAllFab()
        try {
            mPath = intent.getStringExtra(DIRECTORY) ?: ""
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
            return
        }

        val sharedCacheUpdatedAt = synchronized(mFolderMediaCache) {
            mFolderMediaCacheUpdatedAt[mPath] ?: 0L
        }
        mLastSuccessfulMediaLoadAt = sharedCacheUpdatedAt
        mMediaInvalidated = sharedCacheUpdatedAt == 0L
            || System.currentTimeMillis() - sharedCacheUpdatedAt >= MEDIA_CACHE_TTL_MS

        storeStateVariables()
        setupOptionsMenu()
        refreshMenuItems()
        val scrollHorizontally = config.scrollHorizontally
        val view = if (scrollHorizontally) binding.mediaFastscroller else binding.mediaGrid
        setupEdgeToEdge(
            padTopSystem = listOf(binding.mediaMenu),
            padBottomImeAndSystem = listOf(view),
            moveBottomSystem = listOf(binding.mainTopTabsContainer),
        )

        if (config.changeColourTopBar) {
            val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
            setupSearchMenuScrollListener(
                scrollingView = binding.mediaGrid,
                searchMenu = binding.mediaMenu,
                surfaceColor = useSurfaceColor
            )
        }


        if (mShowAll) {
            registerFileUpdateListener()
        }

        binding.mediaEmptyTextPlaceholder2.setOnClickListener {
            showFilterMediaDialog()
        }

        updateWidgets()
        setupTabs()

        if (!scrollHorizontally) {
            val bottomBarSize = resources.getDimension(R.dimen.bottom_actions_height).toInt()
            val mediumMargin = resources.getDimension(com.goodwy.commons.R.dimen.medium_margin).toInt()
            binding.mediaFastscroller.trackMarginEnd = bottomBarSize
            binding.mediaGrid.setPadding(0, 0, 0, bottomBarSize + mediumMargin) // needed clipToPadding="false"
        }

        if (scrollHorizontally) setupKeyboardListener()
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        setupTabsColor()

        if (config.needRestart || mStoredHideTopBarWhenScroll != config.hideTopBarWhenScroll) {
            finish()
            startActivity(intent)
            return
        }

        if (mStoredAnimateGifs != config.animateGifs) {
            getMediaAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getMediaAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
//            mLoadedInitialPhotos = false
//            binding.mediaGrid.adapter = null
//            getMedia()
            finish()
            startActivity(intent)
            return
        }

        if (mStoredShowFileTypes != config.showThumbnailFileTypes) {
            getMediaAdapter()?.updateShowFileTypes(config.showThumbnailFileTypes)
        }

        if (mStoredTextColor != getProperTextColor()) {
            getMediaAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getMediaAdapter()?.updatePrimaryColor()
        }

        if (
            mStoredThumbnailSpacing != config.thumbnailSpacing
            || mStoredRoundedCorners != config.fileRoundedCorners
            || mStoredMarkFavoriteItems != config.markFavoriteItems
        ) {
            binding.mediaGrid.adapter = null
            setupAdapter()
        }

        if (isDynamicTheme() && !isSystemInDarkMode()) {
            binding.mediaGrid.setBackgroundColor(getSurfaceColor())
        }

        refreshMenuItems()

        val accentColor = getProperAccentColor()
        binding.mediaFastscroller.updateColors(accentColor)
        binding.mediaRefreshLayout.isEnabled = config.enablePullToRefresh
        getMediaAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.loadingIndicator.setIndicatorColor(getProperPrimaryColor())
        binding.mediaEmptyTextPlaceholder.setTextColor(getProperTextColor())
        binding.mediaEmptyTextPlaceholder2.setTextColor(getProperPrimaryColor())
        binding.mediaEmptyTextPlaceholder2.bringToFront()

//        val naviBarHeight =
//            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) navigationBarHeight
//            else if (navigationBarOnBottom) navigationBarWidth
//            else navigationBarHeight
//        (binding.mainTopTabsContainer.layoutParams as? CoordinatorLayout.LayoutParams)?.bottomMargin =
//            naviBarHeight + resources.getDimension(com.goodwy.commons.R.dimen.small_margin).toInt()

        // do not refresh Random sorted files after opening a fullscreen image and going Back
        val isRandomSorting = config.getFolderSorting(mPath) and SORT_BY_RANDOM != 0
        val wasFullscreen = mWasFullscreenViewOpen
        mWasFullscreenViewOpen = false
        // Pular reload se voltamos de fullscreen com mídia já carregada e ordenação não-aleatória
        // Evita scan completo do MediaStore toda vez que o usuário abre/fecha uma foto
        val skipReload = mMedia.isNotEmpty() && wasFullscreen && !isRandomSorting
        if (!skipReload) {
            if (shouldSkipAuthentication()) {
                tryLoadGallery()
            } else {
                handleLockedFolderOpening(mPath) { success ->
                    if (success) {
                        tryLoadGallery()
                    } else {
                        finish()
                    }
                }
            }
        } else {
            // O polling era cancelado no onPause e não era reiniciado ao voltar do
            // visualizador. Reative-o sem refazer a carga da grade.
            checkLastMediaChanged()
        }
    }

    override fun onPause() {
        super.onPause()
        mIsGettingMedia = false
        binding.mediaRefreshLayout.isRefreshing = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)

        if (!mMedia.isEmpty()) {
            mCurrAsyncTask?.stopFetching()
        }
    }

    override fun onStop() {
        super.onStop()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (config.showAll && !isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            unregisterFileUpdateListener()
            GalleryDatabase.destroyInstance()
        }

        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mediaMenu.isSearchOpen) {
            binding.mediaMenu.closeSearch()
            true
        } else {
            if (config.showAll) {
                appLockManager.lock()
            }
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            if (resultCode == RESULT_OK && resultData != null) {
                mMedia.clear()
                // Mesmo motivo do delete (ver deleteFilteredFiles): sem invalidar o cache da
                // pasta aqui também, voltar/recarregar essa pasta depois de editar uma imagem
                // podia mostrar a versão antiga (de antes da edição) vinda do cache, até uma
                // recarga posterior corrigir sozinha.
                synchronized(mFolderMediaCache) {
                    mFolderMediaCache.remove(mPath)
                    mFolderMediaCacheUpdatedAt.remove(mPath)
                }
                refreshItems()
            }
        } else if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK) {
            if (resultData != null) {
                val res: ArrayList<String> =
                    resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>

                val speechToText =  Objects.requireNonNull(res)[0]
                if (speechToText.isNotEmpty()) {
                    binding.mediaMenu.setText(speechToText)
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        val isDefaultFolder = !config.defaultFolder.isEmpty()
            && File(config.defaultFolder).compareTo(File(mPath)) == 0

        binding.mediaMenu.requireToolbar().menu.apply {
            if (isNewApp()) {
                val showAsAction = if (config.hideIconsInMenu) {
                    MenuItem.SHOW_AS_ACTION_NEVER
                } else {
                    MenuItem.SHOW_AS_ACTION_IF_ROOM
                }
                findItem(R.id.sort).setShowAsAction(showAsAction)
                findItem(R.id.toggle_filename).setShowAsAction(showAsAction)
                findItem(R.id.filter).setShowAsAction(showAsAction)
                findItem(R.id.open_camera).setShowAsAction(showAsAction)
            }
            findItem(R.id.search).isVisible = !config.showSearchBar
            findItem(R.id.group).isVisible = !config.scrollHorizontally

            findItem(R.id.empty_recycle_bin).isVisible = mPath == RECYCLE_BIN
            findItem(R.id.empty_disable_recycle_bin).isVisible = mPath == RECYCLE_BIN
            findItem(R.id.restore_all_files).isVisible = mPath == RECYCLE_BIN

            findItem(R.id.folder_view).isVisible = mShowAll
            findItem(R.id.open_camera).isVisible = mShowAll
            findItem(R.id.about).isVisible = mShowAll
            findItem(R.id.create_new_folder).isVisible =
                !mShowAll && mPath != RECYCLE_BIN && mPath != FAVORITES
            findItem(R.id.open_recycle_bin).isVisible = config.useRecycleBin && mPath != RECYCLE_BIN

            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible =
                (!isRPlus() || isExternalStorageManager()) && config.temporarilyShowHidden

            findItem(R.id.set_as_default_folder).isVisible = !isDefaultFolder && !mShowAll
            findItem(R.id.unset_as_default_folder).isVisible = isDefaultFolder

            val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
            findItem(R.id.column_count).isVisible = viewType == VIEW_TYPE_GRID
            findItem(R.id.toggle_filename).isVisible = viewType == VIEW_TYPE_GRID
        }
    }

    private fun setupOptionsMenu() {
        binding.mediaMenu.requireToolbar().inflateMenu(R.menu.menu_media)
        if (!mShowAll) {
            binding.mediaMenu.requireToolbar().navigationIcon =
                resources.getColoredDrawableWithColor(this, com.goodwy.commons.R.drawable.ic_chevron_left_vector, Color.WHITE)
            binding.mediaMenu.requireToolbar().setNavigationOnClickListener {
                super.onBackPressed()
            }
        }

        if (baseConfig.useSpeechToText) {
            isSpeechToTextAvailable = isSpeechToTextAvailable()
            binding.mediaMenu.showSpeechToText = isSpeechToTextAvailable
        }

//        binding.mediaMenu.toggleHideOnScroll(!config.scrollHorizontally && config.hideTopBarWhenScroll)
        // Top bar scroll
        val params = binding.mediaMenu.layoutParams as AppBarLayout.LayoutParams
        params.scrollFlags = if (!config.scrollHorizontally && config.hideTopBarWhenScroll) {
            AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
        } else 0
        binding.mediaMenu.layoutParams = params
        binding.mediaMenu.setupMenu()

        binding.mediaMenu.onSpeechToTextClickListener = {
            speechToText()
        }

        binding.mediaMenu.onSearchTextChangedListener = { text ->
            mLastSearchedText = text
            searchQueryChanged(text)
            binding.mediaRefreshLayout.isEnabled = text.isEmpty() && config.enablePullToRefresh
            binding.mediaMenu.clearSearch()
        }

        binding.mediaMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> launchSearchActivity()
                R.id.folder_view -> switchToFolderView()
                R.id.sort -> showSortingDialog()
                R.id.filter -> showFilterMediaDialog()
                R.id.empty_recycle_bin -> emptyRecycleBin()
                R.id.empty_disable_recycle_bin -> emptyAndDisableRecycleBin()
                R.id.restore_all_files -> restoreAllFiles()
                R.id.toggle_filename -> toggleFilenameVisibility()
                R.id.open_camera -> launchCamera()
                R.id.change_view_type -> changeViewType()
                R.id.group -> showGroupByDialog()
                R.id.create_new_folder -> createNewFolder()
                R.id.open_recycle_bin -> openRecycleBin()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.column_count -> changeColumnCount()
                R.id.set_as_default_folder -> setAsDefaultFolder()
                R.id.unset_as_default_folder -> unsetAsDefaultFolder()
                R.id.slideshow -> startSlideshow()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun launchSearchActivity() {
        hideKeyboard()
        Intent(this, SearchActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun startSlideshow() {
        if (mMedia.isNotEmpty()) {
            hideKeyboard()
            val mediums = ArrayList((getMediaAdapter()?.media ?: mMedia).filterIsInstance<Medium>())
            val item = mediums.firstOrNull() ?: return
            ViewPagerActivity.pendingMediums = mediums
            Intent(this, ViewPagerActivity::class.java).apply {
                putExtra(SKIP_AUTHENTICATION, shouldSkipAuthentication())
                putExtra(PATH, item.path)
                putExtra("pos", 0)
                putExtra(SHOW_ALL, mShowAll)
                putExtra(SLIDESHOW_START_ON_ENTER, true)
                startActivity(this)
            }
        }
    }

    private fun updateMenuColors() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        val statusBarColor = if (config.changeColourTopBar) getRequiredStatusBarColor(useSurfaceColor) else backgroundColor
        val scrollingViewOffset = scrollingView?.computeVerticalScrollOffset() ?: 0
        binding.mediaMenu.updateColors(statusBarColor, scrollingViewOffset)
    }

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredShowFileTypes = showThumbnailFileTypes
            mStoredMarkFavoriteItems = markFavoriteItems
            mStoredThumbnailSpacing = thumbnailSpacing
            mStoredRoundedCorners = fileRoundedCorners
            mShowAll = showAll && mPath != RECYCLE_BIN
            mStoredHideTopBarWhenScroll = hideTopBarWhenScroll
            needRestart = false
        }
    }

    private fun searchQueryChanged(text: String) {
        ensureBackgroundThread {
            try {
                val filtered = mMedia
                    .filter { it is Medium && it.name.contains(text, true) } as ArrayList
                filtered.sortBy { it is Medium && !it.name.startsWith(text, true) }
                val grouped = MediaFetcher(applicationContext).groupMedia(
                    media = filtered as ArrayList<Medium>, path = mPath
                )
                runOnUiThread {
                    if (grouped.isEmpty()) {
                        binding.mediaEmptyTextPlaceholder.text =
                            getString(com.goodwy.commons.R.string.no_items_found)
                        binding.mediaEmptyTextPlaceholder.beVisible()
                        binding.mediaFastscroller.beGone()
                    } else {
                        binding.mediaEmptyTextPlaceholder.beGone()
                        binding.mediaFastscroller.beVisible()
                    }

                    handleGridSpacing(grouped)
                    getMediaAdapter()?.updateMedia(grouped)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun tryLoadGallery() {
        requestMediaPermissions {
            val dirName = when (mPath) {
                FAVORITES -> getString(com.goodwy.commons.R.string.favorites)
                RECYCLE_BIN -> getString(com.goodwy.commons.R.string.recycle_bin)
                config.OTGPath -> getString(com.goodwy.commons.R.string.usb)
                else -> getHumanizedFilename(mPath)
            }

            val searchHint = if (mShowAll) {
                getString(com.goodwy.commons.R.string.search_files)
            } else {
                getString(com.goodwy.commons.R.string.search_in_placeholder, dirName)
            }

            binding.mediaMenu.updateHintText(searchHint)
//            if (!mShowAll) {
//                binding.mediaMenu.toggleForceArrowBackIcon(true)
//                binding.mediaMenu.onNavigateBackClickListener = {
//                    performDefaultBack()
//                }
//            }

            if (mShowLoadingIndicator) {
                binding.loadingIndicator.show()
                mShowLoadingIndicator = false
            }

            binding.mediaMenu.updateTitle(if (mShowAll) resources.getString(com.goodwy.strings.R.string.library) else {
                val dirSize = intent.getLongExtra(DIR_SIZE, 0L)
                if (config.showFolderSize && dirSize > 0) "$dirName · ${dirSize.formatSize()}"
                else dirName
            })
            binding.mediaMenu.searchBeVisibleIf(config.showSearchBar)
            getMedia()
            setupLayoutManager()
        }
    }

    private fun getMediaAdapter() = binding.mediaGrid.adapter as? MediaAdapter

    private fun setupAdapter() {
        if (!mShowAll && isDirEmpty()) {
            return
        }

        val currAdapter = binding.mediaGrid.adapter
        if (currAdapter == null) {
            initZoomListener()
            setupGlideScrollPause()
            // Um cache pequeno reduz reinflações sem manter dezenas de imagens e referências
            // de Glide fora da tela. Valores altos aumentam muito a memória em grades grandes.
            binding.mediaGrid.setItemViewCacheSize(4)
            MediaAdapter(
                activity = this,
                media = mMedia.clone() as ArrayList<ThumbnailItem>,
                listener = this,
                isAGetIntent = mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent,
                allowMultiplePicks = mAllowPickingMultiple,
                path = mPath,
                recyclerView = binding.mediaGrid,
                swipeRefreshLayout = binding.mediaRefreshLayout
            ) {
                if (it is Medium && !isFinishing) {
                    itemClicked(it.path)
                }
            }.apply {
                setupZoomListener(mZoomListener)
                    binding.mediaGrid.adapter = this
                    binding.mediaGrid.itemAnimator = null
                    binding.mediaGrid.setHasFixedSize(true)
                    binding.mediaGrid.postDelayed({ setVisibleMediaGifAnimations(true) }, 250)
            }

            setupLayoutManager()
            handleGridSpacing()
        } else if (mLastSearchedText.isEmpty()) {
            (currAdapter as MediaAdapter).updateMedia(mMedia)
            handleGridSpacing()
        } else {
            searchQueryChanged(mLastSearchedText)
        }

        setupScrollDirection()
        if (config.hideGroupingBarWhenScroll && !config.hideGroupingBar) setupTabsHide()
    }

    private fun setupScrollDirection() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        val scrollHorizontally = config.scrollHorizontally && viewType == VIEW_TYPE_GRID
        binding.mediaFastscroller.setScrollVertically(!scrollHorizontally)
    }

    // Antes esse pause/resume do Glide durante o scroll só existia dentro de setupTabsHide(),
    // que só roda se config.hideGroupingBarWhenScroll estiver ativo — duas configurações sem
    // relação nenhuma estavam amarradas juntas. A maioria dos usuários não tinha ESSA otimização
    // de scroll nenhuma. Agora roda sempre, independente dessa configuração.
    private fun setupGlideScrollPause() {
        binding.mediaGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // Não pause os requests do Glide durante o scroll. Pausar todos os requests
                // fazia as células recicladas perderem a imagem e precisarem recarregar ao
                // voltar para cima. O custo pesado aqui são as animações GIF, então controle
                // somente os drawables animados e mantenha thumbnails estáticas em cache.
                setVisibleMediaGifAnimations(newState == RecyclerView.SCROLL_STATE_IDLE)
            }
        })
    }

    private fun setVisibleMediaGifAnimations(allowAnimating: Boolean) {
        binding.mediaGrid.children.forEach { child ->
            val thumbnail = child.findViewById<ImageView>(R.id.medium_thumbnail) ?: return@forEach
            val drawable = thumbnail.drawable as? Animatable ?: return@forEach
            try {
                if (allowAnimating) {
                    if (!drawable.isRunning) drawable.start()
                } else if (drawable.isRunning) {
                    drawable.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun setupTabsHide() {
        val tabsContainer = binding.mainTopTabsContainer
        val duration: Long = 400
        binding.mediaGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var lastY = 0
            private val SCROLL_THRESHOLD = 10 // Minimal movement for reaction

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // Ignore minor movements
                if (abs(dy) < SCROLL_THRESHOLD) return

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
                val firstVisibleItem = layoutManager.findViewByPosition(firstVisibleItemPosition)

                // Checking whether we are at the top of the list
                val isAtTop = firstVisibleItemPosition == 0 &&
                    firstVisibleItem != null &&
                    firstVisibleItem.top >= 0 // The first element is fully visible and not shifted upwards.

                // If at the very top — always show
                if (isAtTop) {
                    if (tabsContainer.visibility != View.VISIBLE) {
                        tabsContainer.visibility = View.VISIBLE
                        tabsContainer.alpha = 0f
                        tabsContainer.translationY = tabsContainer.height.toFloat()
                        tabsContainer.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(duration)
                            .start()
                    }
                    return // We do not apply other rules if at the top
                }

                // If not at the top, process the scroll
                val isScrollingDown = dy > 0
                val isScrollingUp = dy < 0

                if (isScrollingDown) {
                    if (tabsContainer.isVisible) {
                        tabsContainer.animate()
                            .translationY(tabsContainer.height.toFloat())
                            .alpha(0f)
                            .setDuration(duration)
                            .withEndAction { tabsContainer.visibility = View.GONE }
                            .start()
                    }
                } else if (isScrollingUp) {
                    if (tabsContainer.isGone) {
                        tabsContainer.visibility = View.VISIBLE
                        tabsContainer.alpha = 0f
                        tabsContainer.translationY = tabsContainer.height.toFloat()
                        tabsContainer.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(duration)
                            .start()
                    }
                }

                lastY = dy
            }
        })
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed || config.getFolderSorting(mPath) and SORT_BY_RANDOM != 0) {
            return
        }

        mLastMediaHandler.removeCallbacksAndMessages(null)
        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getMedia(forceRefresh = true)
                    }
                } else {
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, isDirectorySorting = false, showFolderCheckbox = true, path = mPath) {
            mLoadedInitialPhotos = false
            binding.mediaGrid.adapter = null
            getMedia()
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            mLoadedInitialPhotos = false
            binding.mediaRefreshLayout.isRefreshing = true
            binding.mediaGrid.adapter = null
            getMedia()
        }
    }

    private fun emptyRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyTheRecycleBin {
                finish()
            }
        }
    }

    private fun emptyAndDisableRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyAndDisableTheRecycleBin {
                finish()
            }
        }
    }

    private fun restoreAllFiles() {
        val paths = mMedia.filter { it is Medium }.map { (it as Medium).path } as ArrayList<String>
        showRestoreConfirmationDialog(paths.size) {
            restoreRecycleBinPaths(paths) {
                ensureBackgroundThread {
                    directoryDB.deleteDirPath(RECYCLE_BIN)
                }
                finish()
            }
        }
    }

    private fun toggleFilenameVisibility() {
        config.displayFileNames = !config.displayFileNames
        getMediaAdapter()?.updateDisplayFilenames(config.displayFileNames)
    }

    private fun switchToFolderView() {
        hideKeyboard()
        config.showAll = false
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, false, mPath) {
            refreshMenuItems()
            setupLayoutManager()
            binding.mediaGrid.adapter = null
            setupAdapter()
        }
    }

    private fun showGroupByDialog() {
        ChangeGroupingDialog(this, mPath) {
            mLoadedInitialPhotos = false
            binding.mediaGrid.adapter = null
            getMedia()
            setupTabs()
        }
    }

    private fun deleteDirectoryIfEmpty() {
        if (config.deleteEmptyFolders) {
            val fileDirItem = FileDirItem(mPath, mPath.getFilenameFromPath(), true)
            if (!fileDirItem.isDownloadsFolder() && fileDirItem.isDirectory) {
                ensureBackgroundThread {
                    if (fileDirItem.getProperFileCount(this, true) == 0) {
                        tryDeleteFileDirItem(fileDirItem, allowDeleteFolder = true, deleteFromDatabase = true)
                    }
                }
            }
        }
    }

    private fun getMedia(forceRefresh: Boolean = false) {
        if (mIsGettingMedia) {
            return
        }

        if (forceRefresh) {
            mMediaInvalidated = true
        }

        val now = System.currentTimeMillis()
        val hasFreshCurrentMedia = mMediaPath == mPath
            && !mMediaInvalidated
            && now - mLastSuccessfulMediaLoadAt < MEDIA_CACHE_TTL_MS
        if (hasFreshCurrentMedia) {
            // A lista e os requests do Glide já estão vivos; não reinicie o scanner
            // só porque a Activity voltou do visualizador ou foi recriada.
            mIsGettingMedia = false
            runOnUiThread { setupAdapter() }
            return
        }

        // Primeiro consulte o cache em memória. A versão anterior lia e agrupava o
        // JSON persistente antes do LRU, então A -> B -> A ainda fazia I/O e trabalho
        // de agrupamento em toda entrada, mesmo com a lista já pronta.
        val folderCacheState = synchronized(mFolderMediaCache) {
            val cached = mFolderMediaCache[mPath]?.let { ArrayList(it) }
            val updatedAt = mFolderMediaCacheUpdatedAt[mPath] ?: 0L
            cached to updatedAt
        }
        val folderCached = folderCacheState.first
        val folderCacheFresh = folderCacheState.second > 0L
            && System.currentTimeMillis() - folderCacheState.second < MEDIA_CACHE_TTL_MS
        if (!forceRefresh && folderCached != null) {
            synchronized(mediaLock) {
                mMedia = folderCached
                mMediaPath = mPath
            }
            mMediaInvalidated = !folderCacheFresh
            runOnUiThread { setupAdapter() }
            mIsGettingMedia = false
            if (!folderCacheFresh) {
                binding.mediaGrid.post { if (!isDestroyed && mPath == mMediaPath) startAsyncTask() }
            } else {
                checkLastMediaChanged()
            }
            mLoadedInitialPhotos = true
            return
        }

        // O snapshot pode ter milhares de itens. Ler JSON e agrupar no thread da UI
        // era exatamente a pausa de 2–3 s percebida ao entrar na pasta. Faça a leitura
        // fora da UI e pinte o resultado assim que voltar, sem bloquear toques/frames.
        if (!forceRefresh) {
            mIsGettingMedia = true
            val requestedPath = mPath
            ensureBackgroundThread {
                val persisted = try {
                    applicationContext.getPersistedMediaSnapshot(
                        requestedPath,
                        mIsGetVideoIntent && !mIsGetImageIntent,
                        mIsGetImageIntent && !mIsGetVideoIntent
                    )
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing || requestedPath != mPath) return@runOnUiThread
                    if (persisted != null && persisted.isNotEmpty()) {
                        synchronized(mediaLock) {
                            mMedia = persisted
                            mMediaPath = requestedPath
                        }
                        synchronized(mFolderMediaCache) {
                            mFolderMediaCache[requestedPath] = ArrayList(persisted)
                            mFolderMediaCacheUpdatedAt[requestedPath] = System.currentTimeMillis()
                        }
                        mMediaInvalidated = true
                        mLastSuccessfulMediaLoadAt = System.currentTimeMillis()
                        // A grade com snapshot é exibida imediatamente; a confirmação
                        // começa só depois da primeira pintura.
                        setupAdapter()
                        binding.mediaGrid.post {
                            if (!isDestroyed && !isFinishing && mPath == mMediaPath) startAsyncTask()
                        }
                    } else {
                        loadMediaFromDatabaseAndScan(requestedPath)
                    }
                }
            }
            mLoadedInitialPhotos = true
            return
        }

        mIsGettingMedia = true

        // mMedia é companion object e persiste enquanto o processo continua vivo. Mostre
        // a grade imediatamente e revalide somente depois que a primeira pintura terminar;
        // consultar Room e iniciar o scanner antes disso competia com o Glide durante a entrada.
        if (!forceRefresh && mMedia.isNotEmpty() && mMediaPath == mPath) {
            mIsGettingMedia = false
            runOnUiThread { setupAdapter() }
            checkLastMediaChanged()
            mLoadedInitialPhotos = true
            return
        }

        // Primeira visita: banco de dados → primeira pintura → scan assíncrono
        loadMediaFromDatabaseAndScan(mPath)
        mLoadedInitialPhotos = true
    }

    private fun loadMediaFromDatabaseAndScan(requestedPath: String) {
        getCachedMedia(
            requestedPath,
            mIsGetVideoIntent && !mIsGetImageIntent,
            mIsGetImageIntent && !mIsGetVideoIntent
        ) { cached ->
            if (requestedPath != mPath || isDestroyed || isFinishing) return@getCachedMedia
            if (cached.isEmpty()) {
                runOnUiThread { binding.mediaRefreshLayout.isRefreshing = true }
            } else {
                gotMedia(cached, true)
            }
            startAsyncTask()
        }
    }

    // startAsyncTask() é chamado de dentro de callbacks de getCachedMedia(), que rodam em
    // background thread (ensureBackgroundThread). Isso fazia com que mCurrAsyncTask!!.execute()
    // — que dispara um AsyncTask — fosse chamado FORA da UI thread em praticamente toda
    // abertura de pasta, violando o contrato documentado do AsyncTask ("must be invoked on
    // the UI thread") e correndo em paralelo com qualquer outra chamada a startAsyncTask()
    // (ex.: usuário abrindo pastas rapidamente), sem nenhuma proteção. O runOnUiThread aqui
    // garante que toda a sequência (cancelar task antiga, criar e disparar a nova) aconteça
    // sempre na UI thread e sempre em sequência.
    private fun startAsyncTask() {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread

            mCurrAsyncTask?.stopFetching()
            val requestedPath = mPath
            val task = GetMediaAsynctask(
                context = applicationContext,
                mPath = requestedPath,
                isPickImage = mIsGetImageIntent && !mIsGetVideoIntent,
                isPickVideo = mIsGetVideoIntent && !mIsGetImageIntent,
                showAll = mShowAll
            ) {
                ensureBackgroundThread {
                    // Cancelar AsyncTask não impede todo callback já enfileirado; descarte
                    // resultados que pertencem a uma pasta que deixou de estar visível.
                    if (requestedPath != mPath || isFinishing || isDestroyed) {
                        return@ensureBackgroundThread
                    }
                    val oldMedia = synchronized(mediaLock) { mMedia.clone() as ArrayList<ThumbnailItem> }
                    val newMedia = it
                    try {
                        gotMedia(newMedia, false)

                        // remove cached files that are no longer valid for whatever reason
                        val newPaths = newMedia.asSequence()
                            .filterIsInstance<Medium>()
                            .map { it.path }
                            .toHashSet()
                        oldMedia
                            .asSequence()
                            .filterIsInstance<Medium>()
                            .filter { it.path !in newPaths }
                            .forEach {
                                if (mPath == FAVORITES && getDoesFilePathExist(it.path)) {
                                    favoritesDB.deleteFavoritePath(it.path)
                                    mediaDB.updateFavorite(it.path, false)
                                } else {
                                    mediaDB.deleteMediumPath(it.path)
                                }
                            }
                    } catch (_: Exception) {
                    }
                }
            }
            mCurrAsyncTask = task
            task.execute()
        }
    }

    private fun fillMissingVideoDurations(media: ArrayList<ThumbnailItem>) {
        if (!config.showThumbnailVideoDuration) return
        val requestedPath = mPath
        val videosWithout = media.filterIsInstance<Medium>()
            .filter { it.isVideo() && it.videoDuration <= 0 }
        if (videosWithout.isEmpty()) return

        val shouldStart = synchronized(durationFillLock) {
            if (durationFillPath == requestedPath) {
                false
            } else {
                durationFillPath = requestedPath
                true
            }
        }
        if (!shouldStart) return

        // O MediaStore e o Room normalmente resolvem a maior parte das durações. Para os
        // restantes, processamos todos os vídeos em pequenos lotes no background, em vez de
        // usar .take(4) e abandonar silenciosamente o quinto item em diante.
        ensureBackgroundThread {
            try {
                val persistedDurations = try {
                    when (requestedPath) {
                        FAVORITES -> mediaDB.getFavorites()
                        RECYCLE_BIN -> mediaDB.getDeletedMedia()
                        else -> mediaDB.getMediaFromPath(requestedPath)
                    }.asSequence()
                        .filter { it.videoDuration > 0 }
                        .associate { it.path to it.videoDuration }
                } catch (_: Exception) {
                    emptyMap()
                }

                videosWithout.chunked(4).forEach { batch ->
                    batch.forEach { medium ->
                        if (requestedPath != mPath || isDestroyed) return@ensureBackgroundThread
                        try {
                            val persisted = persistedDurations[medium.path]
                            val duration = persisted ?: android.media.MediaMetadataRetriever().use { retriever ->
                                if (medium.path.startsWith("content://")) {
                                    retriever.setDataSource(this, medium.path.toUri())
                                } else {
                                    retriever.setDataSource(medium.path)
                                }
                                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull()?.div(1000L)?.toInt() ?: 0
                            }
                            if (duration > 0) {
                                medium.videoDuration = duration
                                if (persisted == null) {
                                    try { mediaDB.updateVideoDuration(medium.path, duration) } catch (_: Exception) {}
                                }
                                runOnUiThread {
                                    if (requestedPath == mPath && !isDestroyed) {
                                        getMediaAdapter()?.updateVideoDuration(medium.path, duration)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                synchronized(durationFillLock) {
                    if (durationFillPath == requestedPath) durationFillPath = ""
                }
            }
        }
    }

    private fun setupSelectAllFab() {
        binding.fabSelectAll.beVisibleIf(config.showSelectAllFab)
        if (config.showSelectAllFab) {
            binding.fabSelectAll.setOnClickListener { getMediaAdapter()?.selectAllItems() }
        }
    }

    fun showSelectionFab(show: Boolean) {
        binding.mediaSelectionFab.beVisibleIf(show)
        if (show) {
            binding.fabCopy.setOnClickListener {
                getMediaAdapter()?.checkMediaManagementAndCopy(true)
            }
            binding.fabMove.setOnClickListener {
                getMediaAdapter()?.moveFilesTo()
            }
            binding.fabDelete.setOnClickListener {
                getMediaAdapter()?.askConfirmDelete()
            }
        }
    }

    private fun isDirEmpty(): Boolean {
        return if (mMedia.isEmpty() && config.filterMedia > 0) {
            if (mPath != FAVORITES && mPath != RECYCLE_BIN) {
                deleteDirectoryIfEmpty()
                deleteDBDirectory()
            }

            if (mPath == FAVORITES) {
                ensureBackgroundThread {
                    directoryDB.deleteDirPath(FAVORITES)
                }
            }

            if (mPath == RECYCLE_BIN) {
                binding.mediaEmptyTextPlaceholder.setText(com.goodwy.commons.R.string.no_items_found)
                binding.mediaEmptyTextPlaceholder.beVisible()
                binding.mediaEmptyTextPlaceholder2.beGone()
            } else {
                finish()
            }

            true
        } else {
            false
        }
    }

    private fun deleteDBDirectory() {
        ensureBackgroundThread {
            try {
                directoryDB.deleteDirPath(mPath)
            } catch (_: Exception) {
            }
        }
    }

    private fun createNewFolder() {
        CreateNewFolderDialog(this, mPath) {
            config.tempFolderPath = it
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                handleHiddenFolderPasswordProtection {
                    toggleTemporarilyShowHidden(true)
                }
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        getMedia(forceRefresh = true)
        refreshMenuItems()
    }

    private fun setupLayoutManager() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        layoutManager.spanCount = config.mediaColumnCnt
        val adapter = getMediaAdapter()
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter?.isASectionTitle(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }.also { it.isSpanIndexCacheEnabled = true }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun handleGridSpacing(media: ArrayList<ThumbnailItem> = mMedia) {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            val spanCount = config.mediaColumnCnt
            val limit = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 8 else 12
            val spacing = if (spanCount > limit) 0 else config.thumbnailSpacing
            val useGridPosition = media.firstOrNull() is ThumbnailSection

            var currentGridDecoration: GridSpacingItemDecoration? = null
            if (binding.mediaGrid.itemDecorationCount > 0) {
                currentGridDecoration =
                    binding.mediaGrid.getItemDecorationAt(0) as GridSpacingItemDecoration
                currentGridDecoration.items = media
            }

            val newGridDecoration = GridSpacingItemDecoration(
                spanCount = spanCount,
                spacing = spacing,
                isScrollingHorizontally = config.scrollHorizontally,
                addSideSpacing = config.fileRoundedCorners,
                items = media,
                useGridPosition = useGridPosition
            )
            if (currentGridDecoration.toString() != newGridDecoration.toString()) {
                if (currentGridDecoration != null) {
                    binding.mediaGrid.removeItemDecoration(currentGridDecoration)
                }
                binding.mediaGrid.addItemDecoration(newGridDecoration)
            }
        }
    }

    private fun initZoomListener() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getMediaAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getMediaAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            mZoomListener = null
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        com.goodwy.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount = (binding.mediaGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount, com.goodwy.commons.R.string.column_count) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.mediaColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.mediaColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.mediaColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.mediaGrid.layoutManager as MyGridLayoutManager).spanCount = config.mediaColumnCnt
        handleGridSpacing()
        refreshMenuItems()
        getMediaAdapter()?.apply {
            notifyItemRangeChanged(0, media.size)
        }
    }

    private fun isSetWallpaperIntent() = intent.getBooleanExtra(SET_WALLPAPER_INTENT, false)

    private fun itemClicked(path: String) {
        hideKeyboard()
        if (isSetWallpaperIntent()) {
            toast(R.string.setting_wallpaper)

            val wantedWidth = wallpaperDesiredMinimumWidth
            val wantedHeight = wallpaperDesiredMinimumHeight
            val ratio = wantedWidth.toFloat() / wantedHeight

            val options = RequestOptions()
                .override((wantedWidth * ratio).toInt(), wantedHeight)
                .fitCenter()

            Glide.with(this)
                .asBitmap()
                .load(File(path))
                .apply(options)
                .into(object : SimpleTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        try {
                            WallpaperManager.getInstance(applicationContext).setBitmap(resource)
                            setResult(RESULT_OK)
                        } catch (_: IOException) {
                        }

                        finish()
                    }
                })
        } else if (mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent) {
            Intent().apply {
                data = path.toUri()
                setResult(RESULT_OK, this)
            }
            finish()
        } else {
            mWasFullscreenViewOpen = true
            if (!path.isVideoFast()) {
                openInViewPager(path)
                return
            }

            when (config.videoPlayerType) {
                VIDEO_PLAYER_SYSTEM -> openSystemDefaultPlayer(path)
                VIDEO_PLAYER_APP -> if (config.gestureVideoPlayer) launchGesturePlayer(path) else openInViewPager(path)
                else -> openInViewPager(path) // unreachable by design
            }
        }
    }

    private fun openInViewPager(path: String) {
        val mediums = ArrayList((getMediaAdapter()?.media ?: mMedia).filterIsInstance<Medium>())
        val pos = mediums.indexOfFirst { it.path == path }.coerceAtLeast(0)
        ViewPagerActivity.pendingMediums = mediums
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(SKIP_AUTHENTICATION, shouldSkipAuthentication())
            putExtra(PATH, path)
            putExtra("pos", pos)
            putExtra(SHOW_ALL, mShowAll)
            putExtra(SHOW_FAVORITES, mPath == FAVORITES)
            putExtra(SHOW_RECYCLE_BIN, mPath == RECYCLE_BIN)
            putExtra(IS_FROM_GALLERY, true)
            startActivity(this)
        }
    }

    private fun openSystemDefaultPlayer(path: String) {
        openPath(
            path = path,
            forceChooser = false,
            extras = hashMapOf(SHOW_FAVORITES to (mPath == FAVORITES)).apply {
                if (path.startsWith(recycleBinPath)) put(IS_IN_RECYCLE_BIN, true)
                if (shouldSkipAuthentication()) put(SKIP_AUTHENTICATION, true)
            }
        )
    }

    private fun gotMedia(media: ArrayList<ThumbnailItem>, isFromCache: Boolean) {
        mIsGettingMedia = false
        checkLastMediaChanged()
        synchronized(mediaLock) {
            mMedia = media
            mMediaPath = mPath
        }
        // Registre também uma pasta vazia: sem isso, cada reentrada em uma pasta sem
        // mídias repetia toda a consulta ao banco e o scan completo.
        synchronized(mFolderMediaCache) {
            mFolderMediaCache[mPath] = ArrayList(media)
            mFolderMediaCacheUpdatedAt[mPath] = System.currentTimeMillis()
        }
        applicationContext.saveMediaSnapshot(mPath, media)

        // Também preenche os itens que vieram do Room/cache, não apenas os que chegaram
        // pelo scan novo do MediaStore.
        fillMissingVideoDurations(media)

        runOnUiThread {
            binding.loadingIndicator.hide()
            binding.mediaRefreshLayout.isRefreshing = false
            binding.mediaEmptyTextPlaceholder.beVisibleIf(media.isEmpty() && !isFromCache)
            binding.mediaEmptyTextPlaceholder2.beVisibleIf(media.isEmpty() && !isFromCache)

            if (binding.mediaEmptyTextPlaceholder.isVisible()) {
                binding.mediaEmptyTextPlaceholder.text = getString(R.string.no_media_with_filters)
            }
            binding.mediaFastscroller.beVisibleIf(binding.mediaEmptyTextPlaceholder.isGone())
            // O cache do Room também é uma fonte válida para a primeira pintura.
            // Antes, isFromCache=true deixava a grade sem adapter até o scan terminar,
            // anulando todo o ganho e fazendo a entrada parecer travada por segundos.
            if (media.isNotEmpty() || !isFromCache) setupAdapter()
        }

        if (!isFromCache) {
            mMediaInvalidated = false
            mLastSuccessfulMediaLoadAt = System.currentTimeMillis()
        }

        mLatestMediaId = getLatestMediaId()
        mLatestMediaDateId = getLatestMediaByDateId()
        if (!isFromCache) {
            val mediaToInsert =
                (mMedia).filter { it is Medium && it.deletedTS == 0L }.map { it as Medium }
            Thread {
                try {
                    mediaDB.insertAll(mediaToInsert)
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        val filtered = fileDirItems
            .filter { !getIsPathDirectory(it.path) && it.path.isMediaFile() } as ArrayList
        if (filtered.isEmpty()) {
            return
        }

        if (
            config.useRecycleBin
            && !skipRecycleBin
            && !filtered.first().path.startsWith(recycleBinPath)
        ) {
            val movingItems = resources.getQuantityString(
                com.goodwy.commons.R.plurals.moving_items_into_bin,
                filtered.size,
                filtered.size
            )
            toast(movingItems)

            movePathsInRecycleBin(filtered.map { it.path } as ArrayList<String>) {
                if (it) {
                    deleteFilteredFiles(filtered)
                } else {
                    toast(com.goodwy.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            val deletingItems = resources.getQuantityString(
                com.goodwy.commons.R.plurals.deleting_items,
                filtered.size,
                filtered.size
            )
            toast(deletingItems)
            deleteFilteredFiles(filtered)
        }
    }

    private fun shouldSkipAuthentication(): Boolean {
        return intent.getBooleanExtra(SKIP_AUTHENTICATION, false)
    }

    private fun deleteFilteredFiles(filtered: ArrayList<FileDirItem>) {
        deleteFiles(filtered) {
            if (!it) {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
                return@deleteFiles
            }

            val filteredPaths = filtered.asSequence().map { it.path }.toHashSet()
            synchronized(mediaLock) {
                mMedia.removeAll { (it as? Medium)?.path in filteredPaths }
            }
            // mFolderMediaCache guarda uma cópia separada por pasta (pro caso de A -> B -> A).
            // Sem atualizar ela também, voltar pra essa pasta mostrava a versão antiga, com o
            // arquivo já apagado/movido pra lixeira ainda aparecendo até uma recarga posterior
            // "por baixo" corrigir sozinha — o que o usuário vê como "o item apagado continua lá".
            synchronized(mFolderMediaCache) {
                mFolderMediaCache[mPath]?.removeAll {
                    (it as? Medium)?.path in filteredPaths
                }
            }

            ensureBackgroundThread {
                val useRecycleBin = config.useRecycleBin
                filtered.forEach {
                    if (it.path.startsWith(recycleBinPath) || !useRecycleBin) {
                        deleteDBPath(it.path)
                    }
                }
            }

            if (mMedia.isEmpty()) {
                deleteDirectoryIfEmpty()
                deleteDBDirectory()
                finish()
            }
        }
    }

    override fun refreshItems() {
        getMedia(forceRefresh = true)
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        Intent().apply {
            putExtra(PICKED_PATHS, paths)
            setResult(RESULT_OK, this)
        }
        finish()
    }

    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) {
        var currentGridPosition = 0
        media.forEach {
            if (it is Medium) {
                it.gridPosition = currentGridPosition++
            } else if (it is ThumbnailSection) {
                currentGridPosition = 0
            }
        }

        if (binding.mediaGrid.itemDecorationCount > 0) {
            val currentGridDecoration =
                binding.mediaGrid.getItemDecorationAt(0) as GridSpacingItemDecoration
            currentGridDecoration.items = media
        }
    }

    private fun setAsDefaultFolder() {
        config.defaultFolder = mPath
        refreshMenuItems()
    }

    private fun unsetAsDefaultFolder() {
        config.defaultFolder = ""
        refreshMenuItems()
    }

    private fun setupTabsColor() {
        val tabBackground = when {
            isDynamicTheme() && !isSystemInDarkMode() -> getProperBackgroundColor()
            isLightTheme() -> resources.getColor(R.color.tab_background_light)
            isGrayTheme() -> resources.getColor(R.color.tab_background_gray)
            isDarkTheme() -> resources.getColor(R.color.tab_background_dark)
            isBlackTheme() -> resources.getColor(R.color.tab_background_black)
            else -> getSurfaceColor().adjustAlpha(0.95f)
        }
        binding.mainTopTabsBackground.backgroundTintList = ColorStateList.valueOf(tabBackground)
        binding.groupButton.backgroundTintList = ColorStateList.valueOf(tabBackground)
        binding.groupButton.setColorFilter(getProperTextColor())

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.mainTopTabsHolder.setSelectedTabIndicatorColor(backgroundColor)
        binding.mainTopTabsHolder.setTabTextColors(getProperTextColor(), getProperPrimaryColor())
    }

    private fun setupTabs() {
        binding.mainTopTabsHolder.removeAllTabs()
        val pathToUse = mPath.ifEmpty { SHOW_ALL }
        val currGrouping = config.getFolderGrouping(pathToUse)
        val tabType = getTabType(currGrouping)
        if (tabType != 0 && !config.scrollHorizontally && !config.hideGroupingBar) {
            binding.mainTopTabsContainer.beVisible()
            binding.groupButton.beGoneIf(config.hideGroupingButton)
            tabsList.forEachIndexed { index, _ ->
                val tab = binding.mainTopTabsHolder.newTab().setText(getTabLabel(index, tabType))
                tab.contentDescription = getTabLabel(index, tabType)
                binding.mainTopTabsHolder.addTab(tab, index)
                binding.mainTopTabsHolder.setTabTextColors(getProperTextColor(),
                    getProperPrimaryColor())
            }

            binding.mainTopTabsHolder.onTabSelectionChanged(
                tabUnselectedAction = {
                    it.icon?.applyColorFilter(getProperTextColor())
                    it.icon?.alpha = 220 // max 255
                },
                tabSelectedAction = {
                    it.icon?.applyColorFilter(getProperPrimaryColor())
                    it.icon?.alpha = 220 // max 255
                    getMediaAdapter()?.finishActMode()
                    toggleGroup(getTabGroupBy(it.position, tabType), pathToUse, currGrouping)
                }
            )

            binding.mainTopTabsHolder.selectTab(binding.mainTopTabsHolder.getTabAt(getDefaultTab(currGrouping)))

            binding.groupButton.setOnClickListener { showGroupByDialog() }
        } else binding.mainTopTabsContainer.beGone()
    }

    private fun getTabType(currGrouping: Int): Int {
        return when {
            currGrouping and GROUP_BY_LAST_MODIFIED_YEARLY != 0 || currGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 ||
                currGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 || currGrouping and GROUP_BY_LAST_MODIFIED_NONE != 0-> 1
            currGrouping and GROUP_BY_DATE_TAKEN_YEARLY != 0 || currGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0 ||
                currGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 || currGrouping and GROUP_BY_DATE_TAKEN_NONE != 0-> 2
            currGrouping and GROUP_BY_FILE_TYPE != 0 || currGrouping and GROUP_BY_EXTENSION != 0 ||
                currGrouping and GROUP_BY_FOLDER != 0 || currGrouping and GROUP_BY_OTHER_NONE != 0-> 3
            else -> 0
        }
    }

    private fun getDefaultTab(currGrouping: Int): Int {
        return when {
            currGrouping and GROUP_BY_LAST_MODIFIED_YEARLY != 0 ||
                currGrouping and GROUP_BY_DATE_TAKEN_YEARLY != 0 ||
                    currGrouping and GROUP_BY_FILE_TYPE != 0 -> 0
            currGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 ||
                currGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0 ||
                    currGrouping and GROUP_BY_EXTENSION != 0 -> 1
            currGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 ||
                currGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 ||
                    currGrouping and GROUP_BY_FOLDER != 0 -> 2
            else -> 3
        }
    }

    private fun getTabLabel(position: Int, tabType: Int): String {
        val stringId = if (tabType == 3) {
            when (position) {
                0 -> R.string.by_file_type
                1 -> R.string.by_extension
                2 -> R.string.by_folder
                else -> com.goodwy.strings.R.string.all_g
            }
        } else {
            when (position) {
                0 -> R.string.years
                1 -> R.string.months
                2 -> R.string.days
                else -> com.goodwy.strings.R.string.all_g
            }
        }

        return resources.getString(stringId)
    }

    private fun getTabGroupBy(position: Int, tabType: Int): Int {
        val stringId = when (tabType) {
            1 -> {
                when (position) {
                    0 -> GROUP_BY_LAST_MODIFIED_YEARLY
                    1 -> GROUP_BY_LAST_MODIFIED_MONTHLY
                    2 -> GROUP_BY_LAST_MODIFIED_DAILY
                    else -> GROUP_BY_LAST_MODIFIED_NONE
                }
            }
            2 -> {
                when (position) {
                    0 -> GROUP_BY_DATE_TAKEN_YEARLY
                    1 -> GROUP_BY_DATE_TAKEN_MONTHLY
                    2 -> GROUP_BY_DATE_TAKEN_DAILY
                    else -> GROUP_BY_DATE_TAKEN_NONE
                }
            }
            3 -> {
                when (position) {
                    0 -> GROUP_BY_FILE_TYPE
                    1 -> GROUP_BY_EXTENSION
                    2 -> GROUP_BY_FOLDER
                    else -> GROUP_BY_OTHER_NONE
                }
            }
            else -> GROUP_BY_NONE
        }

        return stringId
    }

    private fun toggleGroup(groupBy: Int, path: String, currGrouping: Int) {
        var groupNew = groupBy
        if (currGrouping and GROUP_DESCENDING != 0) groupNew = groupNew or GROUP_DESCENDING
        if (currGrouping and GROUP_SHOW_FILE_COUNT != 0) groupNew = groupNew or GROUP_SHOW_FILE_COUNT

        if (config.hasCustomGrouping(path)) {
            config.saveFolderGrouping(path, groupNew)
        } else {
            config.removeFolderGrouping(path)
            config.groupBy = groupNew
        }

        mLoadedInitialPhotos = false
//        binding.mediaGrid.adapter = null
        getMedia()
    }

    // Goodwy
    private fun setupKeyboardListener() {
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val rootView = binding.root
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            // We obtain the height of the visible area
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)

            // Calculate the height of the invisible area (potentially the keyboard)
            val heightDiff = screenHeight - rect.bottom

            val isKeyboardVisible = heightDiff > 200.dpToPx(this)

            if (wasKeyboardVisible && !isKeyboardVisible) {
                // The keyboard has just disappeared.
                onKeyboardHidden()
            }

            wasKeyboardVisible = isKeyboardVisible
        }
    }

    private fun onKeyboardHidden() {
        if (config.scrollHorizontally) {
            recreateLayoutManager()
        }
    }

    private fun recreateLayoutManager() {
        if (config.scrollHorizontally) {
            val oldAdapter = binding.mediaGrid.adapter
            val scrollPosition = (binding.mediaGrid.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition() ?: 0

            // Save the adapter
            binding.mediaGrid.adapter = null

            // Creating a new layoutManager
            val newLayoutManager = MyGridLayoutManager(this, config.mediaColumnCnt).apply {
                orientation = RecyclerView.HORIZONTAL
                spanCount = config.mediaColumnCnt
            }

            binding.mediaGrid.layoutManager = newLayoutManager
            binding.mediaGrid.adapter = oldAdapter
            binding.mediaGrid.scrollToPosition(scrollPosition)
        }
    }
}
