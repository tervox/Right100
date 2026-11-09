package com.goodwy.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.goodwy.gallery.models.Medium
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fila de miniaturas do processo inteiro. Grava o WebP permanente de cada midia em
 * MiniaturaPermanente (filesDir, sem teto).
 *
 * Tres filas, nesta ordem de atendimento:
 *   1. urgentes - celulas que o usuario esta vendo AGORA (MediaAdapter.priorizar)
 *   2. fotos    - resto da pasta, fotos primeiro porque decodificam em milissegundos
 *   3. videos   - por ultimo, porque cada um custa dezenas a centenas de milissegundos
 *
 * Cada decode roda com PRAZO. Antes a fila era uma thread so' e sem prazo: um video
 * corrompido ou muito pesado travava a thread e TODA a geracao parava junto, em silencio -
 * a tela ficava sem miniatura e o diagnostico nao mostrava nada.
 *
 * Quando a miniatura fica pronta, a fila avisa a grade (AvisoDeMiniatura) para redesenhar
 * so' aquela celula. Sem isso, quem parava num ponto continuava vendo o placeholder mesmo
 * depois do arquivo existir - ele so' aparecia ao sair e voltar na pasta.
 *
 * Roda enquanto o processo do app estiver vivo. Se o Android matar o app, a fila para e
 * retoma na proxima abertura. Nao e' servico em primeiro plano, de proposito.
 */
object FilaMiniaturas {

    interface AvisoDeMiniatura {
        fun miniaturaPronta(caminho: String)
    }

    private const val INTERVALO_MS = 5L
    private const val LADO_PX = 320
    private const val DIAG_CADA = 20
    private const val PRAZO_MS = 6000L
    private const val TENTATIVAS_MAX = 2
    private const val MAX_THREADS = 2
    // Interruptor da pausa durante a reproducao. false = a fila nunca para (comportamento
    // anterior). true = para enquanto um video toca, para nao disputar o decoder.
    private const val PAUSAR_FILA_NO_VIDEO = true

    private const val ESPERA_AVISO_MS = 5000L

    // O MediaStore nem sempre classifica formato menos comum (SVG, AVIF, RAW) como
    // imagem - alguns ficam com MEDIA_TYPE 0. Por isso aceita pela extensao tambem.
    private val EXT_FOTO = setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
        "jxl", "svg", "apng", "tif", "tiff", "ico", "psd", "dng", "cr2", "cr3",
        "nef", "arw", "orf", "raf", "rw2", "pef", "srw", "x3f", "3fr", "erf",
        "mef", "mos", "mrw", "nrw", "srf", "k25", "kdc", "dcr", "iiq"
    )
    private val EXT_VIDEO = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp", "3g2", "m4v",
        "ts", "mts", "m2ts", "mpg", "mpeg", "mpe", "ogv", "rmvb", "asf", "vob",
        "divx", "f4v", "m2v", "mxf", "mqv", "qt"
    )

    private val lock = Any()

    private val urgentes = LinkedHashMap<String, Medium>()
    private val fotos = LinkedHashMap<String, Medium>()
    private val videos = LinkedHashMap<String, Medium>()
    // Itens da pasta ABERTA: entram na frente da varredura de fundo.
    private val pasta = LinkedHashMap<String, Medium>()
    private val prontas = HashSet<String>()
    private val tentativas = HashMap<String, Int>()

    private var observador: android.database.ContentObserver? = null

    // Consulta em executor proprio: no decodificador ela entraria na fila atras de N
    // decodes e levaria minutos para comecar.
    private val consultor = Executors.newSingleThreadExecutor { alvo ->
        Thread(alvo, "Right100-varredura-miniaturas").apply { priority = Thread.MIN_PRIORITY }
    }

    private var app: Context? = null
    private var aviso: WeakReference<AvisoDeMiniatura>? = null
    private var geradas = 0L
    private var desistencias = 0L
    private var tempoTotal = 0L
    private var rodando = 0
    // Ligada enquanto um video esta sendo reproduzido (ver pausar/retomar).
    private var pausada = false

    private val executor = Executors.newFixedThreadPool(MAX_THREADS) { alvo ->
        Thread(alvo, "Right100-fila-miniaturas").apply { priority = Thread.MIN_PRIORITY }
    }

    private val decodificador = Executors.newCachedThreadPool { alvo ->
        Thread(alvo, "Right100-decode-miniatura").apply { priority = Thread.MIN_PRIORITY }
    }

    fun registrarAviso(destino: AvisoDeMiniatura?) {
        synchronized(lock) {
            aviso = if (destino == null) null else WeakReference(destino)
        }
    }

    fun enfileirar(
        contexto: Context,
        itens: List<Medium>,
        @Suppress("UNUSED_PARAMETER") colunas: Int = 0,
        @Suppress("UNUSED_PARAMETER") recortar: Boolean = true,
        @Suppress("UNUSED_PARAMETER") cantos: Int = 0
    ) {
        if (itens.isEmpty()) return
        ligarObservador(contexto.applicationContext)
        var novas = 0
        synchronized(lock) {
            if (app == null) app = contexto.applicationContext
            for (item in itens) {
                val caminho = item.path
                if (prontas.contains(caminho) || jaNaFila(caminho)) continue
                if (item.isGIF() || item.isSVG()) {
                    prontas.add(caminho)
                    continue
                }
                pasta[caminho] = item
                novas++
            }
            if (novas > 0) garantirThread()
        }
        if (novas > 0) {
            diag("miniaturas: pasta n=" + itens.size + " novas=" + novas + " fila=" + tamanho())
        }
    }

    /** Chamado a cada celula desenhada: o que esta na tela passa na frente de tudo. */
    fun priorizar(contexto: Context, itens: List<Medium>) {
        if (itens.isEmpty()) return
        synchronized(lock) {
            if (app == null) app = contexto.applicationContext
            for (item in itens) {
                val caminho = item.path
                if (prontas.contains(caminho) || item.isGIF() || item.isSVG()) continue
                fotos.remove(caminho)
                videos.remove(caminho)
                pasta.remove(caminho)
                urgentes[caminho] = item
            }
            garantirThread()
        }
    }

    fun esquecerTudo() {
        synchronized(lock) {
            prontas.clear()
            tentativas.clear()
            fotos.clear()
            videos.clear()
            pasta.clear()
            urgentes.clear()
        }
    }

    /** Tira caminhos da fila e do conjunto de prontas (midia apagada ou substituida). */
    fun esquecerCaminhos(caminhos: Collection<String>) {
        if (caminhos.isEmpty()) return
        synchronized(lock) {
            for (c in caminhos) {
                prontas.remove(c)
                tentativas.remove(c)
                urgentes.remove(c)
                fotos.remove(c)
                videos.remove(c)
                pasta.remove(c)
            }
        }
    }

    private fun larguraDoFundo(contexto: Context): Int =
        240

    /**
     * Gera o fundo a partir do arquivo da miniatura ja' gravado. Serve para as midias que
     * ja' tinham miniatura antes desta versao: nao precisa decodificar o original de novo.
     */
    private fun gerarFundoDoArquivo(contexto: Context, caminho: String) {
        val miniatura = try {
            MiniaturaPermanente.existente(contexto, caminho)
        } catch (_: Throwable) {
            null
        } ?: return

        var bmp: Bitmap? = null
        try {
            val opcoes = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            bmp = BitmapFactory.decodeFile(miniatura.absolutePath, opcoes)
            if (bmp != null) {
                MiniaturaPermanente.guardarFundo(contexto, caminho, bmp, larguraDoFundo(contexto))
            }
        } catch (_: Throwable) {
        } finally {
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }

    private fun decodificarComPrazo(contexto: Context, item: Medium): Bitmap? {
        val tarefa = try {
            decodificador.submit(Callable<Bitmap?> { decodificar(contexto, item) })
        } catch (_: Throwable) {
            return null
        }
        return try {
            tarefa.get(PRAZO_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            tarefa.cancel(true)
            diag("miniaturas: PRAZO estourado em " + item.path.substringAfterLast('/'))
            null
        } catch (_: Throwable) {
            null
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

        // VIDEO PRIMEIRO, e pela mesma fonte que a grade usa. Quem desenha a celula e' o
        // Glide, e no video ele pega o primeiro sync sample do arquivo (VideoDecoder,
        // getFrameAtTime(-1)). O loadThumbnail do MediaStore entrega OUTRO instante do
        // mesmo video - era por isso que a celula mostrava um momento e, quando o WebP da
        // fila ficava pronto, trocava para outro momento, parecendo "carregar de novo".
        // Miniatura do proprio Android primeiro: a grade pede a mesma fonte, entao a imagem nao muda depois.
        if (item.isVideo() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uriDe(item)?.let { uri ->
                try {
                    bmp = contexto.contentResolver.loadThumbnail(uri, Size(LADO_PX, LADO_PX), null)
                } catch (_: Exception) {
                }
            }
        }

        if (bmp == null && item.isVideo() && !item.path.startsWith("content://")) {
            try {
                bmp = QuadroDeVideo.primeiroQuadro(item.path)
            } catch (_: Exception) {
            }
            if (bmp != null && QuadroDeVideo.ehQuasePreto(bmp)) {
                // Fade-in: gravar o quadro de abertura deixaria a celula preta para
                // sempre. Pede o MEIO do video, em SEGUNDOS. A versao anterior passava
                // (ms / 2000), ou seja um QUARTO do video em vez da metade.
                val segundos = DuracaoVideos.duracaoReal(contexto, item.path, item.mediaStoreId)
                val doMeio = try {
                    QuadroDeVideo.quadroNoMeio(item.path, segundos)
                } catch (_: Exception) {
                    null
                }
                if (doMeio != null && !QuadroDeVideo.ehQuasePreto(doMeio)) {
                    if (bmp != doMeio) bmp.recycle()
                    bmp = doMeio
                } else {
                    // Nada aproveitavel pela decodificacao direta: cai para o provedor.
                    doMeio?.recycle()
                    bmp?.recycle()
                    bmp = null
                }
            }
        }

        if (bmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

        // Revalida quase-preto DEPOIS de cada fallback, nao so' no primeiro quadro.
        // Antes, um quadro preto vindo do loadThumbnail do provedor ou do ThumbnailUtils era
        // devolvido como sucesso e gravado como WebP - e como o WebP e' lido como imagem
        // estatica, a deteccao de quadro preto da grade nunca mais rodava naquela celula:
        // preto permanente, sem segunda chance.
        if (bmp != null && item.isVideo() && QuadroDeVideo.ehQuasePreto(bmp)) {
            bmp.recycle()
            bmp = null
        }

        // Ultima tentativa para video: o meio do arquivo. Se nem isso servir, devolve null
        // de proposito - melhor nenhum WebP do que um WebP preto gravado em definitivo.
        if (bmp == null && item.isVideo() && !item.path.startsWith("content://")) {
            val segundos = DuracaoVideos.duracaoReal(contexto, item.path, item.mediaStoreId)
            bmp = try {
                QuadroDeVideo.quadroNoMeio(item.path, segundos)
            } catch (_: Exception) {
                null
            }
            if (bmp != null && QuadroDeVideo.ehQuasePreto(bmp)) {
                bmp.recycle()
                bmp = null
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
