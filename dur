package com.goodwy.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File

// Duracao lida do PROPRIO arquivo, usada so quando o MediaStore devolve 0.
//   1) MediaMetadataRetriever - le o cabecalho (moov). Rapido, mas devolve null em
//      MP4 fragmentado, arquivo copiado pela metade ou com indice incompleto.
//   2) MediaExtractor         - le a duracao da trilha de VIDEO. Acerta nesses casos
//      e tambem nos videos sem nenhuma trilha de audio.
object DuracaoVideos {
    private val cache = HashMap<String, Int>()
    private val emAndamento = HashSet<String>()

    @Synchronized
    fun para(path: String, padrao: Int = 0): Int = cache[path] ?: padrao

    @Synchronized
    fun jaTentou(path: String): Boolean =
        cache.containsKey(path) || emAndamento.contains(path)

    fun duracaoReal(path: String): Int = duracaoReal(null, path, 0L)

    fun duracaoReal(context: Context?, path: String, mediaStoreId: Long = 0L): Int {
        if (path.isBlank()) return 0

        synchronized(this) {
            cache[path]?.let { return it }
            if (!emAndamento.add(path)) return 0
        }

        try {
            val filePath = if (path.startsWith("file://")) {
                Uri.parse(path).path ?: ""
            } else {
                path
            }

            var segundos = when {
                path.startsWith("content://") ->
                    if (context != null) viaUri(context, Uri.parse(path)) else 0
                File(filePath).isFile -> viaArquivo(filePath)
                else -> 0
            }

            if (segundos <= 0 && context != null) {
                for (uri in urisDoMediaStore(context, path, mediaStoreId)) {
                    if (uri.toString() == path) continue
                    val encontrado = viaUri(context, uri)
                    if (encontrado > 0) {
                        segundos = encontrado
                        break
                    }
                }
            }

            if (segundos <= 0) segundos = porAmostras(context, path)

            if (segundos > 0) {
                synchronized(this) { cache[path] = segundos }
            } else {
                // Diagnostico: antes um 0 voltava em silencio e nao dava para saber em
                // que estagio a cadeia morreu (retriever -> trilha de video -> amostras).
                // Com esta linha, o relatorio do Right100Diag aponta o arquivo exato
                // que ficou em 00:00 mesmo depois de todas as vias.
                try {
                    com.goodwy.gallery.extensions.Right100Diag.add(
                        "duracao: nenhuma via resolveu " + path
                    )
                } catch (_: Exception) {
                }
            }
            return segundos
        } finally {
            synchronized(this) { emAndamento.remove(path) }
        }
    }

    // Ultimo recurso, e o unico que funciona nos MP4 que nao declaram duracao:
    // fragmentados, ou com o campo de duracao zerado pelo aplicativo que baixou o arquivo.
    // Esses tocam normalmente, mas todo leitor de metadados responde 0 - sao os que
    // aparecem como 00:00, sem miniatura. Aqui percorremos as amostras reais da trilha
    // de video ate a ultima e usamos o tempo dela.
    private fun porAmostras(context: Context?, path: String): Int {
        var extractor: MediaExtractor? = null
        var descritor: android.content.res.AssetFileDescriptor? = null
        try {
            extractor = MediaExtractor()
            val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else {
                null
            }

            if (uri != null) {
                val resolver = context?.contentResolver ?: return 0
                descritor = resolver.openAssetFileDescriptor(uri, "r") ?: return 0
                extractor.setDataSource(
                    descritor.fileDescriptor,
                    descritor.startOffset,
                    descritor.declaredLength
                )
            } else {
                extractor.setDataSource(if (path.startsWith("file://")) {
                    Uri.parse(path).path ?: path
                } else {
                    path
                })
            }

            var trilha = -1
            for (i in 0 until extractor.trackCount) {
                val mime = try {
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                }
                if (mime != null && mime.startsWith("video/")) {
                    trilha = i
                    break
                }
            }
            if (trilha < 0 && extractor.trackCount > 0) trilha = 0
            if (trilha < 0) return 0
            extractor.selectTrack(trilha)

            val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
            var ultimoUs = 0L
            var amostrasLidas = 0
            while (amostrasLidas < 500_000) {
                val tempo = extractor.sampleTime
                if (tempo < 0) break
                if (tempo > ultimoUs) ultimoUs = tempo
                if (extractor.readSampleData(buffer, 0) < 0) break
                amostrasLidas++
                if (!extractor.advance()) break
            }

            return if (ultimoUs > 0L) {
                ((ultimoUs + 999_999L) / 1_000_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            }
        } catch (_: Exception) {
            return 0
        } finally {
            try { descritor?.close() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun urisDoMediaStore(
        context: Context,
        path: String,
        mediaStoreId: Long
    ): List<Uri> {
        val collection = MediaStore.Files.getContentUri("external")
        val uris = linkedSetOf<Uri>()

        if (path.startsWith("content://") || path.startsWith("file://")) {
            uris.add(Uri.parse(path))
        }

        var idAtual = 0L
        if (!path.startsWith("content://") && !path.startsWith("file://")) {
            idAtual = try {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }

        if (idAtual > 0L) {
            uris.add(ContentUris.withAppendedId(collection, idAtual))
        }
        if (mediaStoreId > 0L && (idAtual == 0L || mediaStoreId == idAtual)) {
            uris.add(ContentUris.withAppendedId(collection, mediaStoreId))
        }
        return uris.toList()
    }

    private fun viaArquivo(path: String): Int {
        val metadados = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                milissegundosParaSegundos(
                    retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                )
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            0
        }
        if (metadados > 0) return metadados

        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(path)
            duracaoDaTrilhaDeVideo(extractor)
        } catch (_: Exception) {
            0
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun viaUri(context: Context, uri: Uri): Int {
        val metadados = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    milissegundosParaSegundos(
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                    )
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
        if (metadados > 0) return metadados

        var extractor: MediaExtractor? = null
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor = MediaExtractor()
                extractor!!.setDataSource(descriptor.fileDescriptor)
                duracaoDaTrilhaDeVideo(extractor!!)
            } ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun duracaoDaTrilhaDeVideo(extractor: MediaExtractor): Int {
        var maiorDuracaoUs = 0L
        for (i in 0 until extractor.trackCount) {
            try {
                val formato = extractor.getTrackFormat(i)
                val mime = formato.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") ||
                    !formato.containsKey(MediaFormat.KEY_DURATION)
                ) {
                    continue
                }
                maiorDuracaoUs = maxOf(
                    maiorDuracaoUs,
                    formato.getLong(MediaFormat.KEY_DURATION)
                )
            } catch (_: Exception) {
            }
        }

        return if (maiorDuracaoUs > 0L) {
            ((maiorDuracaoUs + 999_999L) / 1_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
    }

    private fun milissegundosParaSegundos(milissegundos: Long): Int {
        return if (milissegundos > 0L) {
            ((milissegundos + 999L) / 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
    }
}
