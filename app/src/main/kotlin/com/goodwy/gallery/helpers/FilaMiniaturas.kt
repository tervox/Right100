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
import java.util.concurrent.Executors

/**
 * Fila de miniaturas do processo inteiro: uma thread de prioridade minima que grava o
 * WebP permanente de cada midia (foto e video) em MiniaturaPermanente.
 *
 * Duas filas:
 *  - urgentes: as celulas que o usuario esta vendo AGORA. MediaAdapter.setupThumbnail
 *    chama priorizar() a cada bind. Antes existia so' a fila da pasta inteira, na ordem
 *    do indice: se o usuario estava no meio da pasta, a fila consumia os primeiros itens
 *    (que ninguem estava olhando) e o que estava na tela parecia nao carregar.
 *  - pendentes: o resto da pasta, enfileirado uma vez quando a pasta abre.
 *
 * Sem teto de tamanho. Roda enquanto o processo do app estiver vivo: se o Android matar
 * o app ou o usuario fechar pelas recentes, a fila para e retoma na proxima abertura.
 * Nao e' servico em primeiro plano - de proposito, para nao ficar notificacao fixa.
 */
object FilaMiniaturas {

    private const val INTERVALO_MS = 25L
    private const val LADO_PX = 320
    private const val DIAG_CADA = 20

    private val lock = java.lang.Object()
    private val urgentes = LinkedHashMap<String, Medium>()
    private val pendentes = LinkedHashMap<String, Medium>()
    private val prontas = HashSet<String>()
    private var app: Context? = null
    private var geradas = 0L
    private var tempoTotal = 0L
    private var rodando = false

    private val executor = Executors.newSingleThreadExecutor { alvo ->
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
        var novas = 0
        synchronized(lock) {
            if (app == null) app = contexto.applicationContext
            for (item in itens) {
                val caminho = item.path
                if (prontas.contains(caminho) || pendentes.containsKey(caminho) || urgentes.containsKey(caminho)) continue
                if (item.isGIF() || item.isSVG()) {
                    prontas.add(caminho)
                    continue
                }
                pendentes[caminho] = item
                novas++
            }
            if (novas > 0) garantirThread()
        }
        if (novas > 0) {
            diag("miniaturas: pasta n=" + itens.size + " novas=" + novas + " naFila=" + tamanho())
        }
    }

    /** Chamado a cada celula desenhada: o que esta na tela passa na frente. */
    fun priorizar(contexto: Context, itens: List<Medium>) {
        if (itens.isEmpty()) return
        synchronized(lock) {
            if (app == null) app = contexto.applicationContext
            for (item in itens) {
                val caminho = item.path
                if (prontas.contains(caminho) || item.isGIF() || item.isSVG()) continue
                pendentes.remove(caminho)
                urgentes[caminho] = item
            }
            garantirThread()
        }
    }

    fun esquecerTudo() {
        synchronized(lock) {
            prontas.clear()
            pendentes.clear()
            urgentes.clear()
        }
    }

    private fun tamanho(): Int = synchronized(lock) { urgentes.size + pendentes.size }

    private fun garantirThread() {
        if (rodando) return
        rodando = true
        executor.execute {
            try {
                laco()
            } finally {
                synchronized(lock) {
                    rodando = false
                    if (urgentes.isNotEmpty() || pendentes.isNotEmpty()) garantirThread()
                }
            }
        }
    }

    private fun laco() {
        var marcadorDiag = 0L
        while (true) {
            val par = synchronized(lock) {
                val primeiro = urgentes.entries.firstOrNull()
                if (primeiro != null) {
                    urgentes.remove(primeiro.key)
                    Pair(primeiro.key, primeiro.value)
                } else {
                    val seguinte = pendentes.entries.firstOrNull()
                    if (seguinte == null) {
                        null
                    } else {
                        pendentes.remove(seguinte.key)
                        Pair(seguinte.key, seguinte.value)
                    }
                }
            } ?: break

            val contexto = app ?: break
            val inicioMs = System.currentTimeMillis()
            try {
                if (MiniaturaPermanente.existente(contexto, par.first) == null) {
                    val bmp = decodificar(contexto, par.second)
                    if (bmp != null) {
                        MiniaturaPermanente.guardar(contexto, par.first, bmp)
                        bmp.recycle()
                    }
                }
            } catch (_: Throwable) {
            }

            val gastoMs = System.currentTimeMillis() - inicioMs
            val total: Long
            synchronized(lock) {
                prontas.add(par.first)
                geradas++
                tempoTotal += gastoMs
                total = geradas
            }
            if (total - marcadorDiag >= DIAG_CADA) {
                marcadorDiag = total
                diag(
                    "miniaturas: geradas=" + total +
                        " naFila=" + tamanho() +
                        " ms/med=" + (tempoTotal / total) +
                        " ultima=" + gastoMs + "ms"
                )
            }

            try {
                Thread.sleep(INTERVALO_MS)
            } catch (_: InterruptedException) {
                break
            }
        }

        val contexto = app
        if (contexto != null) {
            var media = 0L
            synchronized(lock) {
                if (geradas > 0) media = tempoTotal / geradas
            }
            diag("miniaturas: fila vazia geradas=" + geradas + " ms/med=" + media)
            try {
                MiniaturaPermanente.limparOrfas(contexto)
            } catch (_: Throwable) {
            }
        }
    }

    private fun diag(msg: String) {
        try {
            com.goodwy.gallery.extensions.Right100Diag.add(msg)
        } catch (_: Throwable) {
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
            // Video que comeca em fade-in entrega quadro quase preto. Gravar isso deixaria
            // a celula preta para sempre, porque a grade passaria a abrir o arquivo direto.
            // Tenta o quadro do MEIO; se nem ele servir, nao grava nada.
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
}
