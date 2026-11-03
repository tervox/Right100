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

    private const val INTERVALO_MS = 20L
    private const val LADO_PX = 320
    private const val DIAG_CADA = 20
    private const val PRAZO_MS = 6000L
    private const val TENTATIVAS_MAX = 2

    private val lock = Any()

    private val urgentes = LinkedHashMap<String, Medium>()
    private val fotos = LinkedHashMap<String, Medium>()
    private val videos = LinkedHashMap<String, Medium>()
    private val prontas = HashSet<String>()
    private val tentativas = HashMap<String, Int>()

    private var app: Context? = null
    private var aviso: WeakReference<AvisoDeMiniatura>? = null
    private var geradas = 0L
    private var desistencias = 0L
    private var tempoTotal = 0L
    private var rodando = false

    private val executor = Executors.newSingleThreadExecutor { alvo ->
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
                if (item.isVideo()) videos[caminho] = item else fotos[caminho] = item
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
            urgentes.clear()
        }
    }

    private fun jaNaFila(caminho: String): Boolean =
        urgentes.containsKey(caminho) || fotos.containsKey(caminho) || videos.containsKey(caminho)

    private fun tamanho(): Int = synchronized(lock) { urgentes.size + fotos.size + videos.size }

    private fun garantirThread() {
        if (rodando) return
        rodando = true
        executor.execute {
            try {
                laco()
            } finally {
                synchronized(lock) {
                    rodando = false
                    if (urgentes.isNotEmpty() || fotos.isNotEmpty() || videos.isNotEmpty()) garantirThread()
                }
            }
        }
    }

    private fun proximo(): Pair<String, Medium>? = synchronized(lock) {
        val filas = arrayOf(urgentes, fotos, videos)
        for (fila in filas) {
            val entrada = fila.entries.firstOrNull()
            if (entrada != null) {
                fila.remove(entrada.key)
                return Pair(entrada.key, entrada.value)
            }
        }
        return null
    }

    private fun laco() {
        var marcadorDiag = 0L
        while (true) {
            val par = proximo() ?: break
            val contexto = app ?: break
            val inicioMs = System.currentTimeMillis()

            var temArquivo = false
            // So' avisa a grade quando o arquivo nasceu AGORA. Antes avisava tambem
            // quando ele ja' existia - e ao abrir uma pasta com o app recem-iniciado
            // isso virava uma onda de rebinds, um a cada 20 ms, na ordem da pasta:
            // a grade piscava celula por celula sem nada ter mudado na tela.
            var gerouAgora = false
            try {
                if (MiniaturaPermanente.existente(contexto, par.first) != null) {
                    temArquivo = true
                } else {
                    val bmp = decodificarComPrazo(contexto, par.second)
                    if (bmp != null) {
                        MiniaturaPermanente.guardar(contexto, par.first, bmp)
                        bmp.recycle()
                    }
                    temArquivo = MiniaturaPermanente.existente(contexto, par.first) != null
                    gerouAgora = temArquivo
                }
                // Fundo desfocado do visualizador: gerado aqui, em thread de prioridade
                // minima, a partir do WebP de 320 px. Antes o desfoque rodava na thread
                // principal a cada pagina criada do pager e travava a troca de foto.
                if (temArquivo && MiniaturaPermanente.existenteFundo(contexto, par.first) == null) {
                    gerarFundoDoArquivo(contexto, par.first)
                }
            } catch (_: Throwable) {
                temArquivo = false
            }

            val gastoMs = System.currentTimeMillis() - inicioMs
            var total = 0L
            synchronized(lock) {
                if (temArquivo) {
                    prontas.add(par.first)
                    tentativas.remove(par.first)
                    geradas++
                } else {
                    val tentativa = (tentativas[par.first] ?: 0) + 1
                    if (tentativa >= TENTATIVAS_MAX) {
                        // Desistiu de verdade: nao fica tentando para sempre, mas avisa.
                        prontas.add(par.first)
                        tentativas.remove(par.first)
                        desistencias++
                        diag("miniaturas: DESISTI " + par.first.substringAfterLast('/') + " tentativas=" + tentativa)
                    } else {
                        // Devolve para o fim da fila: o travamento pode ter sido pontual.
                        tentativas[par.first] = tentativa
                        if (!jaNaFila(par.first)) {
                            if (par.second.isVideo()) videos[par.first] = par.second else fotos[par.first] = par.second
                        }
                    }
                }
                tempoTotal += gastoMs
                total = geradas
            }

            if (temArquivo && gerouAgora) {
                val destino = synchronized(lock) { aviso?.get() }
                try {
                    destino?.miniaturaPronta(par.first)
                } catch (_: Throwable) {
                }
            }

            if (total - marcadorDiag >= DIAG_CADA) {
                marcadorDiag = total
                var media = 0L
                synchronized(lock) { if (geradas > 0) media = tempoTotal / geradas }
                diag(
                    "miniaturas: geradas=" + total + " fila=" + tamanho() +
                        " ms/med=" + media + " ultima=" + gastoMs + "ms desistencias=" + desistencias
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
            var total = 0L
            synchronized(lock) {
                total = geradas
                if (geradas > 0) media = tempoTotal / geradas
            }
            diag("miniaturas: fila vazia geradas=" + total + " ms/med=" + media + " desistencias=" + desistencias)
            try {
                MiniaturaPermanente.limparOrfas(contexto)
            } catch (_: Throwable) {
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
