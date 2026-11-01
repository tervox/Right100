package com.goodwy.gallery.helpers

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt

// Duracao lida do PROPRIO arquivo, usada so quando o MediaStore devolve 0.
//   1) MediaMetadataRetriever - le o cabecalho (moov). Rapido, mas devolve null em
//      MP4 fragmentado, arquivo copiado pela metade ou com indice incompleto.
//   2) MediaExtractor         - le a duracao da trilha de VIDEO. Acerta nesses casos
//      e tambem nos videos sem nenhuma trilha de audio.
object DuracaoVideos {
    private val cache = HashMap<String, Int>()
    private val tentados = HashSet<String>()

    @Synchronized
    fun para(path: String, padrao: Int = 0): Int = cache[path] ?: padrao

    @Synchronized
    fun jaTentou(path: String): Boolean = tentados.contains(path)

    fun duracaoReal(path: String): Int {
        synchronized(this) {
            cache[path]?.let { return it }
            if (!tentados.add(path)) return 0
        }
        if (!File(path).exists()) return 0

        var segundos = 0
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val texto = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            segundos = ((texto?.toLongOrNull() ?: 0L) / 1000.0).roundToInt()
        } catch (_: Exception) {
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        if (segundos <= 0) segundos = viaExtractor(path)
        if (segundos > 0) synchronized(this) { cache[path] = segundos }
        return segundos
    }

    private fun viaExtractor(path: String): Int {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(path)
            var maior = 0L
            for (i in 0 until extractor.trackCount) {
                val formato = extractor.getTrackFormat(i)
                val mime = try { formato.getString(MediaFormat.KEY_MIME) } catch (_: Exception) { null } ?: continue
                if (!mime.startsWith("video/")) continue
                if (!formato.containsKey(MediaFormat.KEY_DURATION)) continue
                val d = try { formato.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
                if (d > maior) maior = d
            }
            (maior / 1000.0).roundToInt()
        } catch (_: Exception) {
            0
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
