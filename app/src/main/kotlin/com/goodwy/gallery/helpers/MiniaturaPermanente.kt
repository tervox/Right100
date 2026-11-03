package com.goodwy.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * Miniaturas permanentes em filesDir, SEM teto de tamanho.
 *
 * filesDir porque o Android NUNCA apaga essa pasta por conta propria, nem com o aparelho
 * cheio. A saida e' manual: o botao "Limpar cache" do app (SettingsActivity.setupClearCache
 * chama MiniaturaPermanente.limparTudo) ou "limpar dados".
 *
 * Nao e' o cache do Glide: aquele e' LRU com teto de 250 MB e nao permite apagar a miniatura
 * de uma midia so'. Aqui cada midia tem um arquivo proprio - e' isso que permite saber de
 * quem ele e' e apagar so' o que saiu de vez.
 *
 *   /storage/emulated/0/DCIM/a.mp4  ->  files/miniaturas/v1/storage/emulated/0/DCIM/a.mp4@1730.webp
 *
 * WebP q80 320 px: ~15 KB por midia (100 mil midias = ~1,5 GB).
 */
object MiniaturaPermanente {

    private const val VERSAO = "v1"
    private const val QUALIDADE = 80
    private const val QUALIDADE_FUNDO = 100
    // Forca do desfoque do fundo, em pixels na resolucao de trabalho (~320-360).
    // 66 = aparencia do BestGallery (quase uniforme). 40 = medio. 24 = sutil.
    private const val LARGURA_FUNDO = 240
    private const val RAIO_FUNDO = 30
    private const val SUFIXO_FUNDO = ".fundo8"

    private fun raiz(context: Context): File =
        File(context.filesDir, "miniaturas/" + VERSAO).apply { if (!isDirectory) mkdirs() }

    private fun arquivoPara(context: Context, mediaPath: String, mtime: Long): File {
        val relativo = mediaPath.trimStart('/')
        val nome = relativo.substringAfterLast('/')
        val pasta = relativo.substringBeforeLast('/', "")
        val destino = File(File(raiz(context), pasta), nome + "@" + mtime + ".webp")
        destino.parentFile?.mkdirs()
        return destino
    }

    private fun mtimeDe(mediaPath: String): Long = try {
        val original = File(mediaPath)
        if (original.isFile) original.lastModified() else 0L
    } catch (_: Exception) {
        0L
    }

    fun existente(context: Context, mediaPath: String): File? {
        if (mediaPath.startsWith("content://")) return null
        val mtime = mtimeDe(mediaPath)
        if (mtime <= 0L) return null
        val f = arquivoPara(context, mediaPath, mtime)
        return if (f.isFile && f.length() > 0L) f else null
    }

    // ------------------------------------------------------- fundo desfocado

    private fun arquivoFundo(context: Context, mediaPath: String, mtime: Long): File {
        val base = arquivoPara(context, mediaPath, mtime)
        return File(base.parentFile, base.name.removeSuffix(".webp") + SUFIXO_FUNDO + ".webp")
    }

    /** Fundo desfocado ja' gravado, ou null se ainda nao existe (ou a midia mudou). */
    fun existenteFundo(context: Context, mediaPath: String): File? {
        if (mediaPath.startsWith("content://")) return null
        val mtime = mtimeDe(mediaPath)
        if (mtime <= 0L) return null
        val f = arquivoFundo(context, mediaPath, mtime)
        return if (f.isFile && f.length() > 0L) f else null
    }

    /**
     * Gera e grava o fundo desfocado a partir da miniatura. Sempre chamado pela
     * FilaMiniaturas, em thread de prioridade minima: nunca na thread principal e nunca
     * no visualizador. E' isso que tira o desfoque do caminho da troca de foto.
     */
    fun guardarFundo(context: Context, mediaPath: String, fonte: Bitmap, larguraAlvo: Int): Boolean {
        if (mediaPath.startsWith("content://") || fonte.isRecycled) return false
        if (fonte.width <= 0 || fonte.height <= 0) return false
        val mtime = mtimeDe(mediaPath)
        if (mtime <= 0L) return false

        val destino = arquivoFundo(context, mediaPath, mtime)
        if (destino.isFile && destino.length() > 0L) return true

        var borrado: Bitmap? = null
        try {
            val largura = LARGURA_FUNDO
            // Aspecto da TELA, nao da foto. O BestGallery decodifica a foto
            // cobrindo a view (CENTER_OUTSIDE) e so' depois divide por 3: o
            // fundo fica sempre com o aspecto da tela e a ampliacao final e'
            // sempre 3,0x, em qualquer formato de foto. Antes o fundo herdava
            // o aspecto da foto - numa 3:4 era ampliado 4,7x e o desfoque saia
            // 57% mais forte que o dele.
            val altura = alturaDoFundo(context, largura)
            val recorte = recortarParaAspecto(fonte, largura, altura)

            // Sigma identico ao do BestGallery. Ele usa Stack Blur raio 60,
            // cujo kernel triangular equivale a duas caixas de largura 61:
            //   2 * ((61^2 - 1) / 12) = 620  ->  sigma 24,9 px.
            // desfocar() faz quatro passadas (h,v,h,v), ou seja duas caixas
            // por eixo. Com raio 30 a largura da caixa e' 61 e o sigma bate
            // exatamente. Com raio 60 (o que estava aqui) o sigma era 49,4:
            // o dobro do BestGallery, e o degrade ficava lavado.
            val passo = RAIO_FUNDO
            var atual: Bitmap = Bitmap.createScaledBitmap(recorte, largura, altura, true)
            if (recorte !== fonte) recorte.recycle()
            repeat(1) {
                val proximo = desfocar(atual, passo)
                if (atual !== fonte) atual.recycle()
                atual = proximo
            }
            borrado = atual
            if (gravarArquivo(destino, atual, QUALIDADE_FUNDO)) {
                apagarFundosAntigos(destino)
                return true
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            val b = borrado
            try {
                if (b != null && !b.isRecycled) b.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Altura do fundo no aspecto da tela. A largura e' fixa, entao a ampliacao
     * do centerCrop passa a ser sempre a mesma (limitada pela largura) e o
     * desfoque na tela deixa de depender do formato da foto.
     */
    private fun alturaDoFundo(context: Context, largura: Int): Int {
        return try {
            val dm = context.resources.displayMetrics
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return largura
            (largura.toFloat() * dm.heightPixels / dm.widthPixels).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            largura
        }
    }

    /** Recorta no aspecto alvo pelo centro, como o centerCrop do Glide. */
    private fun recortarParaAspecto(fonte: Bitmap, largura: Int, altura: Int): Bitmap {
        if (largura <= 0 || altura <= 0) return fonte
        val alvo = altura.toFloat() / largura
        val real = fonte.height.toFloat() / fonte.width
        val r: IntArray = if (real > alvo) {
            val nova = (fonte.width * alvo).toInt().coerceIn(1, fonte.height)
            intArrayOf(0, (fonte.height - nova) / 2, fonte.width, nova)
        } else {
            val nova = (fonte.height / alvo).toInt().coerceIn(1, fonte.width)
            intArrayOf((fonte.width - nova) / 2, 0, nova, fonte.height)
        }
        return try {
            Bitmap.createBitmap(fonte, r[0], r[1], r[2], r[3])
        } catch (_: Exception) {
            fonte
        }
    }

    private fun apagarFundosAntigos(destino: File) {
        try {
            val base = destino.name.substringBeforeLast('@')
            destino.parentFile?.listFiles()?.forEach { irmao ->
                if (irmao != destino && irmao.name.startsWith(base + "@") && irmao.name.contains(".fundo") && irmao.name.endsWith(".webp")) {
                    irmao.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Media de caixa em duas passadas por eixo. Nao usa RenderScript, nao usa biblioteca
     * externa e nao exige bitmap mutavel (le os pixels, calcula, devolve um bitmap novo).
     */
    private fun desfocar(origem: Bitmap, raio: Int): Bitmap {
        val largura = origem.width
        val altura = origem.height
        val total = largura * altura
        val pixels = IntArray(total)
        origem.getPixels(pixels, 0, largura, 0, 0, largura, altura)

        val r = raio.coerceIn(1, (minOf(largura, altura) / 2).coerceAtLeast(1))
        val temporario = IntArray(total)

        desfocarEixo(pixels, temporario, largura, altura, r, true)
        desfocarEixo(temporario, pixels, largura, altura, r, false)
        desfocarEixo(pixels, temporario, largura, altura, r, true)
        desfocarEixo(temporario, pixels, largura, altura, r, false)

        val saida = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        saida.setPixels(pixels, 0, largura, 0, 0, largura, altura)
        return saida
    }

    private fun desfocarEixo(entrada: IntArray, saida: IntArray, largura: Int, altura: Int, raio: Int, horizontal: Boolean) {
        val janela = raio * 2 + 1
        if (horizontal) {
            for (y in 0 until altura) {
                val base = y * largura
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -raio..raio) {
                    val c = entrada[base + i.coerceIn(0, largura - 1)]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
                for (x in 0 until largura) {
                    saida[base + x] = ((a / janela) shl 24) or ((r / janela) shl 16) or ((g / janela) shl 8) or (b / janela)
                    val sai = entrada[base + (x - raio).coerceIn(0, largura - 1)]
                    val entra = entrada[base + (x + raio + 1).coerceIn(0, largura - 1)]
                    a += ((entra ushr 24) and 0xFF) - ((sai ushr 24) and 0xFF)
                    r += ((entra ushr 16) and 0xFF) - ((sai ushr 16) and 0xFF)
                    g += ((entra ushr 8) and 0xFF) - ((sai ushr 8) and 0xFF)
                    b += (entra and 0xFF) - (sai and 0xFF)
                }
            }
        } else {
            for (x in 0 until largura) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -raio..raio) {
                    val c = entrada[i.coerceIn(0, altura - 1) * largura + x]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
                for (y in 0 until altura) {
                    saida[y * largura + x] = ((a / janela) shl 24) or ((r / janela) shl 16) or ((g / janela) shl 8) or (b / janela)
                    val sai = entrada[(y - raio).coerceIn(0, altura - 1) * largura + x]
                    val entra = entrada[(y + raio + 1).coerceIn(0, altura - 1) * largura + x]
                    a += ((entra ushr 24) and 0xFF) - ((sai ushr 24) and 0xFF)
                    r += ((entra ushr 16) and 0xFF) - ((sai ushr 16) and 0xFF)
                    g += ((entra ushr 8) and 0xFF) - ((sai ushr 8) and 0xFF)
                    b += (entra and 0xFF) - (sai and 0xFF)
                }
            }
        }
    }

    fun guardar(context: Context, mediaPath: String, bmp: Bitmap): Boolean {
        if (mediaPath.startsWith("content://") || bmp.isRecycled) return false
        val mtime = mtimeDe(mediaPath)
        if (mtime <= 0L) return false

        val destino = arquivoPara(context, mediaPath, mtime)
        if (destino.isFile && destino.length() > 0L) return true

        if (gravarArquivo(destino, bmp, QUALIDADE)) {
            apagarVersoesAntigas(destino)
            return true
        }
        return false
    }

    private fun gravarArquivo(destino: File, bmp: Bitmap, qualidade: Int): Boolean {
        return try {
            // .tmp e so' depois renomeia: se o app morrer no meio da escrita, nunca fica um
            // arquivo truncado sendo servido como valido.
            val tmp = File(destino.parentFile, destino.name + ".tmp")
            FileOutputStream(tmp).use { saida -> comprimir(bmp, saida, qualidade) }
            if (tmp.length() > 0L && tmp.renameTo(destino)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (_: Exception) {
            try { File(destino.parentFile, destino.name + ".tmp").delete() } catch (_: Exception) {}
            false
        }
    }

    private fun comprimir(bmp: Bitmap, saida: FileOutputStream, qualidade: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, qualidade, saida)
        } else {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, qualidade, saida)
        }
    }

    private fun apagarVersoesAntigas(destino: File) {
        try {
            val base = destino.name.substringBeforeLast('@')
            destino.parentFile?.listFiles()?.forEach { irmao ->
                // Nao mexe no fundo: ele tem a mesma base e seria apagado junto. Quem
                // remove fundo velho e' o apagarFundosAntigos / o limparOrfas.
                val ehFundo = irmao.name.endsWith(SUFIXO_FUNDO + ".webp")
                if (irmao != destino && !ehFundo && irmao.name.startsWith(base + "@")) irmao.delete()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Apaga miniaturas de midias que sumiram DE VEZ. Se a midia esta' na lixeira, mantem -
     * restaurar continua instantaneo. Consulta as duas lixeiras conhecidas do app: a privada
     * (files/.trash) e a publica (.right100_trash).
     */
    fun limparOrfas(context: Context, limite: Int = 800): Int {
        val naLixeira = nomesNaLixeira(context)
        var checados = 0
        var removidos = 0
        val raizStr = raiz(context).absolutePath.trimEnd('/')
        val pilha = ArrayDeque<File>()
        pilha.addLast(raiz(context))

        while (pilha.isNotEmpty() && checados < limite) {
            val pasta = pilha.removeLast()
            val filhos = try { pasta.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in filhos) {
                if (checados >= limite) break
                if (f.isDirectory) {
                    pilha.addLast(f)
                    continue
                }
                if (f.name.endsWith(".tmp")) {
                    f.delete()
                    continue
                }
                // Fundos da geracao anterior (".fundo.webp") nao sao mais servidos:
                // o sufixo mudou para ".fundo2". Apagar em vez de deixar ocupando espaco.
                if (f.name.endsWith(".fundo.webp") && !f.name.endsWith(SUFIXO_FUNDO + ".webp")) {
                    f.delete()
                    continue
                }
                if (!f.name.endsWith(".webp")) continue

                checados++
                // O fundo fica ao lado da miniatura com o sufixo ".fundo":
                //   a.jpg@1730.webp   e   a.jpg@1730.fundo.webp
                // Sem tirar o sufixo antes de procurar o "@", o nome nao seria reconhecido
                // como pertencente a uma midia e o fundo seria apagado como orfao.
                val semExtensao = f.name.removeSuffix(".webp").removeSuffix(SUFIXO_FUNDO)
                val corte = semExtensao.lastIndexOf('@')
                if (corte <= 0) {
                    f.delete()
                    removidos++
                    continue
                }
                val base = semExtensao.substring(0, corte)
                val mtime = semExtensao.substring(corte + 1).toLongOrNull() ?: -1L
                val relativo = f.parentFile.absolutePath.removePrefix(raizStr).trimStart('/')
                val caminhoOriginal = "/" + (if (relativo.isEmpty()) "" else relativo + "/") + base
                val original = File(caminhoOriginal)

                val sumiuDeVez = !original.isFile && !naLixeira.contains(original.name)
                val mudou = mtime > 0 && original.isFile && original.lastModified() != mtime
                if (sumiuDeVez || mudou) {
                    f.delete()
                    removidos++
                }
            }
        }
        return removidos
    }

    private fun nomesNaLixeira(context: Context): HashSet<String> {
        val nomes = HashSet<String>()
        val raizes = ArrayList<File>(2)
        raizes.add(File(context.filesDir, ".trash"))
        try {
            Environment.getExternalStorageDirectory()?.let { raizes.add(File(it, ".right100_trash")) }
        } catch (_: Exception) {
        }
        raizes.forEach { raizTrash ->
            try {
                raizTrash.walkTopDown().forEach { f -> if (f.isFile) nomes.add(f.name) }
            } catch (_: Exception) {
            }
        }
        return nomes
    }

    fun tamanhoBytes(context: Context): Long {
        var total = 0L
        val pilha = ArrayDeque<File>()
        pilha.addLast(raiz(context))
        while (pilha.isNotEmpty()) {
            val pasta = pilha.removeLast()
            val filhos = try { pasta.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in filhos) {
                if (f.isDirectory) pilha.addLast(f) else total += f.length()
            }
        }
        return total
    }

    fun limparTudo(context: Context) {
        try { raiz(context).deleteRecursively() } catch (_: Exception) {}
    }
}
