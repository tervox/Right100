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

    // Varredura da galeria inteira. Lote baixo de proposito: a fila nunca enche com
    // coisa fora da tela e a pasta aberta depois entra na frente sem esperar tudo.
    private const val LOTE_VARREDURA = 30
    private const val VARREDURA_MAX = 80000
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
    private val prontas = HashSet<String>()
    private val tentativas = HashMap<String, Int>()

    /**
     * Item da varredura. Carrega o _ID do MediaStore: o Medium exige esse campo e sem ele
     * o decode caia sempre no caminho direto por arquivo (mais lento) em vez de pedir a
     * miniatura pronta ao provedor.
     */
    private class Pendente(val caminho: String, val video: Boolean, val id: Long)

    private val varredura = ArrayList<Pendente>()
    private var varreduraIndice = 0
    private var varreduraPreparando = false
    private var varreduraPronta = false
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
        pedirVarredura(contexto, false)
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
            varredura.clear()
            varreduraIndice = 0
            varreduraPronta = false
            varreduraPreparando = false
            fotos.clear()
            videos.clear()
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
            }
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
            // Fila da pasta baixou? Entrega o proximo lote da varredura da galeria.
            abastecerSePreciso()
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

    // ---------------- varredura da galeria ----------------

    /**
     * Pede a varredura. A consulta roda fora da thread da fila.
     * forcar=false: so' na primeira vez do processo.
     * forcar=true:  o MediaStore mudou, refaz a lista inteira.
     */
    private fun pedirVarredura(contexto: Context, forcar: Boolean) {
        ligarObservador(contexto.applicationContext)
        val iniciar = synchronized(lock) {
            if (varreduraPreparando) return
            if (varreduraPronta && !forcar) return
            varreduraPreparando = true
            true
        }
        if (iniciar) prepararVarredura(contexto.applicationContext, forcar)
    }

    /**
     * Escuta o MediaStore. Copiar 200 fotos dispara 200 avisos; sem o freio seriam
     * 200 consultas a galeria inteira. O relogio reinicia a cada aviso, entao a
     * consulta so' acontece quando o usuario para de mexer por 5 segundos - e e' uma
     * unica consulta, cobrindo tudo que mudou naquela leva.
     */
    private fun ligarObservador(contexto: Context) {
        if (observador != null) return
        try {
            val principal = android.os.Handler(android.os.Looper.getMainLooper())
            val obs = object : android.database.ContentObserver(principal) {
                private var agendado = false
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (agendado) return
                    agendado = true
                    principal.postDelayed({
                        agendado = false
                        pedirVarredura(contexto, true)
                    }, ESPERA_AVISO_MS)
                }
            }
            contexto.contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, obs
            )
            observador = obs
        } catch (_: Throwable) {
        }
    }

    /**
     * Monta a lista: um bloco por pasta, pastas em ordem alfabetica, e dentro de cada
     * pasta em ordem de nome. Nenhum filtro de pasta - .nomedia e pasta oculta sao do
     * usuario e entram igual.
     *
     * Sempre consulta completa, nunca "so' o que e' novo": arquivo movido de pasta
     * mantem o DATE_ADDED antigo, entao um filtro por data o ignoraria e ele ficaria
     * sem miniatura na pasta nova. O que ja' esta pronto e' pulado por `prontas`.
     */
    private fun prepararVarredura(contexto: Context, limpar: Boolean) {
        consultor.execute {
            val porPasta = LinkedHashMap<String, MutableList<Pendente>>()
            var total = 0
            try {
                val uri = MediaStore.Files.getContentUri("external")
                val projecao = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
                )
                contexto.contentResolver.query(uri, projecao, null, null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iDados = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val iTipo = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    while (c.moveToNext() && total < VARREDURA_MAX) {
                        val caminho = c.getString(iDados) ?: continue
                        if (caminho.isEmpty()) continue
                        val tipo = c.getInt(iTipo)
                        val ext = caminho.substringAfterLast('.', "").lowercase()
                        val eVideo = tipo == 2 || (tipo != 1 && ext in EXT_VIDEO)
                        val eFoto = tipo == 1 || (tipo != 2 && ext in EXT_FOTO)
                        if (!eVideo && !eFoto) continue
                        // GIF e SVG abrem o original na grade (animacao e vetor), entao
                        // miniatura permanente deles nao serve.
                        if (!eVideo && (ext == "gif" || ext == "svg")) continue
                        val pai = caminho.substringBeforeLast('/', "")
                        porPasta.getOrPut(pai) { ArrayList() }.add(Pendente(caminho, eVideo, c.getLong(iId)))
                        total++
                    }
                }
            } catch (_: Throwable) {
            }

            val pastas = porPasta.keys.sortedBy { it.lowercase() }
            val final = ArrayList<Pendente>(total)
            for (nome in pastas) {
                val grupo = porPasta[nome] ?: continue
                grupo.sortBy { it.caminho.substringAfterLast('/').lowercase() }
                final.addAll(grupo)
            }
            synchronized(lock) {
                varredura.clear()
                varredura.addAll(final)
                varreduraIndice = 0
                varreduraPronta = true
                varreduraPreparando = false
            }
            diag(
                "miniaturas: varredura=" + final.size + " pastas=" + pastas.size +
                    " truncada=" + (total >= VARREDURA_MAX)
            )
            if (limpar) {
                // O MediaStore mudou: pode ser exclusao definitiva, lixeira esvaziada ou
                // midia apagada por outro app. limparOrfas apaga a miniatura de quem nao
                // esta em disco NEM na lixeira, e preserva a de quem esta na lixeira.
                try {
                    val apagadas = MiniaturaPermanente.limparOrfasCompleto(contexto)
                    if (apagadas > 0) diag("miniaturas: orfas removidas=" + apagadas)
                } catch (_: Throwable) {
                }
            }
            garantirThread()
        }
    }

    /** Entrega o proximo lote da varredura, se a fila da pasta estiver baixa. */
    private fun abastecerSePreciso() {
        synchronized(lock) {
            if (!varreduraPronta) return
            if (urgentes.isNotEmpty()) return
            if (fotos.size + videos.size >= LOTE_VARREDURA) return
            var adicionadas = 0
            while (varreduraIndice < varredura.size && adicionadas < LOTE_VARREDURA) {
                val p = varredura[varreduraIndice++]
                val caminho = p.caminho
                if (prontas.contains(caminho) || jaNaFila(caminho)) continue
                // Argumentos NOMEADOS de proposito: o Medium tem 13 campos e varios sem
                // valor padrao (mediaStoreId, deletedTS...). Nomeados, um campo a mais no
                // futuro nao quebra esta chamada.
                val m = Medium(
                    id = null,
                    name = caminho.substringAfterLast('/'),
                    path = caminho,
                    parentPath = caminho.substringBeforeLast('/', ""),
                    modified = 0L,
                    taken = 0L,
                    size = 0L,
                    // TYPE_IMAGES/TYPE_VIDEOS vivem em com.goodwy.gallery.helpers.Constants,
                    // o mesmo pacote deste arquivo - por isso vem sem prefixo. Com
                    // "com.goodwy.commons.helpers." o Kotlin nao resolve e o build cai
                    // nas linhas 476-477 (erro do run 870).
                    type = if (p.video) TYPE_VIDEOS else TYPE_IMAGES,
                    videoDuration = 0,
                    isFavorite = false,
                    deletedTS = 0L,
                    mediaStoreId = p.id
                )
                if (p.video) videos[caminho] = m else fotos[caminho] = m
                adicionadas++
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
        if (item.isVideo() && !item.path.startsWith("content://")) {
            try {
                bmp = QuadroDeVideo.primeiroQuadro(item.path)
            } catch (_: Exception) {
            }
            if (bmp != null && QuadroDeVideo.ehQuasePreto(bmp)) {
                // Fade-in: gravar o quadro de abertura deixaria a celula preta para
                // sempre. Pede o MEIO do video, em SEGUNDOS. A versao anterior passava
                // (ms / 2000), ou seja um QUARTO do video em vez da metade.
                val segundos = (duracaoMs(item.path) / 1000L).toInt()
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
