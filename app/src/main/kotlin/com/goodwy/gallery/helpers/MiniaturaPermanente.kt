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

    fun guardar(context: Context, mediaPath: String, bmp: Bitmap): Boolean {
        if (mediaPath.startsWith("content://") || bmp.isRecycled) return false
        val mtime = mtimeDe(mediaPath)
        if (mtime <= 0L) return false

        val destino = arquivoPara(context, mediaPath, mtime)
        if (destino.isFile && destino.length() > 0L) return true

        try {
            // .tmp e so' depois renomeia: se o app morrer no meio da escrita, nunca fica um
            // arquivo truncado sendo servido como valido.
            val tmp = File(destino.parentFile, destino.name + ".tmp")
            FileOutputStream(tmp).use { saida -> comprimir(bmp, saida) }
            if (tmp.length() > 0L && tmp.renameTo(destino)) {
                apagarVersoesAntigas(destino)
                return true
            }
            tmp.delete()
        } catch (_: Exception) {
            try { File(destino.parentFile, destino.name + ".tmp").delete() } catch (_: Exception) {}
        }
        return false
    }

    private fun comprimir(bmp: Bitmap, saida: FileOutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALIDADE, saida)
        } else {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, QUALIDADE, saida)
        }
    }

    private fun apagarVersoesAntigas(destino: File) {
        try {
            val base = destino.name.substringBeforeLast('@')
            destino.parentFile?.listFiles()?.forEach { irmao ->
                if (irmao != destino && irmao.name.startsWith(base + "@")) irmao.delete()
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
                if (!f.name.endsWith(".webp")) continue

                checados++
                val semExtensao = f.name.removeSuffix(".webp")
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
