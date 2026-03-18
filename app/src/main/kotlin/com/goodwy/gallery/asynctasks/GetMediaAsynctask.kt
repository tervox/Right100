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
        val lastModifieds = if (getProperLastModified) mediaFetcher.getLastModifieds() else HashMap()
        val dateTakens = if (getProperDateTaken) mediaFetcher.getDateTakens() else HashMap()
        val videoDurationsBatch = if (getVideoDurations) mediaFetcher.getVideoDurationsBatch() else HashMap()

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
