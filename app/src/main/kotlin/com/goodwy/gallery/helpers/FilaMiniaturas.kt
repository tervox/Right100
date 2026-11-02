package com.goodwy.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.goodwy.gallery.models.Medium
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Fila de miniaturas do processo inteiro, numa thread de prioridade minima.
 *
 * Gera e grava a miniatura permanente (foto, video) via MiniaturaPermanente. Depois disso a
 * celula nao decodifica mais nada: so' abre o WebP de 320 px.
 *
 * Usa o contexto da aplicacao, entao continua depois que o usuario sai da pasta. Sem teto:
 * nada e' apagado por quantidade ou por tempo.
 */
object FilaMiniaturas {

    private const val INTERVALO_MS = 40L
    private const val LADO_PX = 320

    private val jaFeitos = Collections.synchronizedSet(HashSet<String>())
    private val fila = Executors.newSingleThreadExecutor { alvo ->
        Thread(alvo, "Right100-fila-miniaturas").apply { priority = Thread.MIN_PRIORITY }
    }

    fun enfileirar(
        contexto: Context,
        itens: List<Medium>,
        @Suppress("UNUSED_PARAMETER") colunas: Int = 0,
        @Suppress("UNUSED_PARAMETER") recortar: Boolean = true,
        @Suppress("UNUSED_PARAMETER") cantos: Int = 0
    ) {
        if (itens.isEmpty()) return
        val app = contexto.applicationContext
        val lote = ArrayList<Medium>(itens.size)
        synchronized(jaFeitos) {
            itens.forEach { if (jaFeitos.add(it.path)) lote.add(it) }
        }
        if (lote.isEmpty()) return

        fila.execute {
            for (item in lote) {
                try {
                    if (MiniaturaPermanente.existente(app, item.path) == null) {
                        val bmp = decodificar(app, item)
                        if (bmp != null) {
                            MiniaturaPermanente.guardar(app, item.path, bmp)
                            bmp.recycle()
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(INTERVALO_MS)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
            try { MiniaturaPermanente.limparOrfas(app) } catch (_: Throwable) {}
        }
    }

    private fun uriDe(item: Medium): Uri? {
        if (item.path.startsWith("content://")) return Uri.parse(item.path)
        if (item.mediaStoreId > 0L) {
            return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), item.mediaStoreId)
        }
        return null
    }

    private fun decodificar(contexto: Context, item: Medium): Bitmap? {
        var bmp: Bitmap? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uriDe(item)?.let { uri ->
                try {
                    bmp = contexto.contentResolver.loadThumbnail(uri, Size(LADO_PX, LADO_PX), null)
                } catch (_: Exception) {
                }
            }
        }

        if (bmp == null && !item.path.startsWith("content://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                bmp = if (item.isVideo()) {
                    ThumbnailUtils.createVideoThumbnail(File(item.path), Size(LADO_PX, LADO_PX), null)
                } else {
                    ThumbnailUtils.createImageThumbnail(File(item.path), Size(LADO_PX, LADO_PX), null)
                }
            } catch (_: Exception) {
            }
        }

        if (item.isVideo() && !item.path.startsWith("content://")) {
            if (bmp == null) {
                try {
                    bmp = QuadroDeVideo.primeiroQuadro(item.path)
                } catch (_: Exception) {
                }
            }
            // Video que comeca em fade-in (X/Twitter, GIF convertido) entrega um quadro
            // quase preto. Gravar isso colocaria a celula em preto para sempre, porque a
            // grade passaria a abrir o arquivo direto, sem a cadeia de fallback. Aqui a
            // fila tenta o quadro do MEIO; se nem ele servir, nao grava nada e a celula
            // continua usando a midia original com o fallback de sempre.
            if (bmp != null && QuadroDeVideo.ehQuasePreto(bmp)) {
                val doMeio = try {
                    val ms = duracaoMs(item.path)
                    if (ms > 0L) QuadroDeVideo.quadroNoMeio(item.path, (ms / 2000L).toInt()) else null
                } catch (_: Exception) {
                    null
                }
                if (doMeio != null && !QuadroDeVideo.ehQuasePreto(doMeio)) {
                    if (bmp != doMeio) bmp.recycle()
                    bmp = doMeio
                } else {
                    doMeio?.recycle()
                    bmp?.recycle()
                    return null
                }
            }
        }

        return bmp
    }

    private fun duracaoMs(path: String): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    fun esquecerTudo() {
        synchronized(jaFeitos) { jaFeitos.clear() }
    }
}
