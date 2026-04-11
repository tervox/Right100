package com.goodwy.gallery.asynctasks

import android.content.Context
import android.os.AsyncTask
import com.goodwy.commons.helpers.FAVORITES
import com.goodwy.commons.helpers.SORT_BY_DATE_MODIFIED
import com.goodwy.commons.helpers.SORT_BY_DATE_TAKEN
import com.goodwy.commons.helpers.SORT_BY_SIZE
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.getFavoritePaths
import com.goodwy.gallery.databases.GalleryDatabase
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.models.ThumbnailItem

class GetMediaAsynctask(
    val context: Context, val mPath: String, val isPickImage: Boolean = false, val isPickVideo: Boolean = false,
    val showAll: Boolean, val callback: (media: ArrayList<ThumbnailItem>) -> Unit
) :
    AsyncTask<Void, Void, ArrayList<ThumbnailItem>>() {
    private val mediaFetcher = MediaFetcher(context)

    companion object {
        // Cache de durações válido por 5 minutos dentro da mesma sessão do app.
        // Evita query full ao MediaStore toda vez que o usuário abre uma pasta.
        @Volatile private var cachedDurationsMap: HashMap<String, Int>? = null
        @Volatile private var cachedDurationsTimestamp: Long = 0L
        private const val DURATIONS_CACHE_TTL_MS = 5 * 60 * 1000L // 5 min

        fun invalidateDurationsCache() {
            cachedDurationsMap = null
            cachedDurationsTimestamp = 0L
        }
    }

    override fun doInBackground(vararg params: Void): ArrayList<ThumbnailItem> {
        val pathToUse = if (showAll) SHOW_ALL else mPath
        val folderGrouping = context.config.getFolderGrouping(pathToUse)
        val folderSorting = context.config.getFolderSorting(pathToUse)
        val getProperDateTaken = folderSorting and SORT_BY_DATE_TAKEN != 0 ||
            folderGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 ||
            folderGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0 ||
            folderGrouping and GROUP_BY_DATE_TAKEN_YEARLY != 0

        val getProperLastModified = folderSorting and SORT_BY_DATE_MODIFIED != 0 ||
            folderGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 ||
            folderGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 ||
            folderGrouping and GROUP_BY_LAST_MODIFIED_YEARLY != 0

        // showFolderSize controla o badge na tela de pastas (Directory.size já está no banco)
        // getProperFileSize aqui é só para ordenação por tamanho dentro de uma pasta
        val getProperFileSize = folderSorting and SORT_BY_SIZE != 0
        val favoritePaths = context.getFavoritePaths()
        val getVideoDurations = context.config.showThumbnailVideoDuration

        // Queries por pasta são muito mais rápidas que varrer todo o dispositivo
        val lastModifieds = when {
            !getProperLastModified -> HashMap()
            showAll -> mediaFetcher.getLastModifieds()
            else -> mediaFetcher.getFolderLastModifieds(mPath)
        }
        val dateTakens = when {
            !getProperDateTaken -> HashMap()
            showAll -> mediaFetcher.getDateTakens()
            else -> mediaFetcher.getFolderDateTakens(mPath)
        }
        val videoDurationsBatch = if (getVideoDurations) {
            if (showAll) {
                // showAll varre todo o dispositivo — usa cache global com TTL
                val now = System.currentTimeMillis()
                val cached = cachedDurationsMap
                if (cached != null && (now - cachedDurationsTimestamp) < DURATIONS_CACHE_TTL_MS) {
                    cached
                } else {
                    val fresh = mediaFetcher.getVideoDurationsBatch()
                    cachedDurationsMap = fresh
                    cachedDurationsTimestamp = now
                    fresh
                }
            } else {
                // Pasta específica — query filtrada, muito mais rápida
                mediaFetcher.getVideoDurationsForFolder(mPath)
            }
        } else HashMap()

        val media = if (showAll) {
            val foldersToScan = mediaFetcher.getFoldersToScan().filter { it != RECYCLE_BIN && it != FAVORITES && !context.config.isFolderProtected(it) }
            val media = ArrayList<Medium>()
            foldersToScan.forEach {
                val newMedia = mediaFetcher.getFilesFrom(
                    it, isPickImage, isPickVideo, getProperDateTaken, getProperLastModified, getProperFileSize,
                    favoritePaths, getVideoDurations, lastModifieds, dateTakens.clone() as HashMap<String, Long>, null,
                    videoDurationsBatch
                )
                media.addAll(newMedia)
            }

            mediaFetcher.sortMedia(media, context.config.getFolderSorting(SHOW_ALL))
            media
        } else {
            mediaFetcher.getFilesFrom(
                mPath, isPickImage, isPickVideo, getProperDateTaken, getProperLastModified, getProperFileSize, favoritePaths,
                getVideoDurations, lastModifieds, dateTakens, null, videoDurationsBatch
            )
        }

        // Salva durações dos vídeos no banco para acelerar próximas cargas
        if (getVideoDurations) {
            val mediaDB = GalleryDatabase.getInstance(context.applicationContext).MediumDao()
            media.filterIsInstance<Medium>()
                .filter { it.isVideo() && it.videoDuration > 0 }
                .forEach { medium ->
                    try {
                        mediaDB.updateVideoDuration(medium.path, medium.videoDuration)
                    } catch (_: Exception) {}
                }
        }

        return mediaFetcher.groupMedia(media, pathToUse)
    }

    override fun onPostExecute(media: ArrayList<ThumbnailItem>) {
        super.onPostExecute(media)
        callback(media)
    }

    fun stopFetching() {
        mediaFetcher.shouldStop = true
        cancel(true)
    }
}
