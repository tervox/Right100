package com.goodwy.gallery.helpers

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt

// Duracao lida do PROPRIO arquivo, usada so quando o MediaStore devolve 0.
// Motivo: getVideoDurationsBatch e getVideoDurationsForFolder gravam apenas valores
// maiores que zero. Video sem trilha de audio (mp4 mudo) e alguns arquivos recem-copiados
// chegam com DURATION = 0 no MediaStore, nunca entram no mapa e a grade mostra 00:00.
// A leitura aqui e o ultimo recurso: em segundo plano, uma vez por arquivo e por sessao.
object DuracaoVideos {
    private val cache = HashMap<String, Int>()
    private val tentados = HashSet<String>()

    @Synchronized
    fun para(path: String, padrao: Int = 0): Int = cache[path] ?: padrao

    @Synchronized
    fun jaTentou(path: String): Boolean = tentados.contains(path)

    // Permite nova tentativa (usado quando o usuario troca de pasta ou reentra).
    @Synchronized
    fun esquecerTentativas() {
        tentados.clear()
    }

    fun duracaoReal(path: String): Int {
        synchronized(this) {
            cache[path]?.let { return it }
            if (!tentados.add(path)) return 0
        }
        val arquivo = File(path)
        if (!arquivo.exists()) {
            com.goodwy.gallery.extensions.Right100Diag.add("duracao: arquivo ausente " + arquivo.name)
            return 0
        }
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val texto = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val ms = texto?.toLongOrNull() ?: 0L
            val segundos = (ms / 1000.0).roundToInt()
            if (segundos > 0) {
                synchronized(this) { cache[path] = segundos }
            }
            // Diagnostico: mostra exatamente o que o proprio arquivo informa. Se aqui vier
            // ms=null ou ms=0, o problema NAO e o app - e o arquivo nao tem duracao no
            // cabecalho e nenhum player consegue mostrar tempo.
            com.goodwy.gallery.extensions.Right100Diag.add(
                "duracao: " + arquivo.name + " ms=" + texto + " -> " + segundos + "s"
            )
            segundos
        } catch (e: Exception) {
            com.goodwy.gallery.extensions.Right100Diag.add(
                "duracao: erro " + arquivo.name + " " + e.javaClass.simpleName
            )
            0
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }
}
