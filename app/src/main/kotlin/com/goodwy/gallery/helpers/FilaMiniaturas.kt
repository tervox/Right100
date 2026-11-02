package com.goodwy.gallery.helpers

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.goodwy.commons.extensions.isPathOnOTG
import com.goodwy.commons.views.MySquareImageView
import com.goodwy.gallery.extensions.loadImage
import com.goodwy.gallery.models.Medium
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Fila de miniaturas do processo inteiro.
 *
 * Por que existe: o pre-carregamento dentro do MediaAdapter nao sobrevive a saida da pasta.
 * Duas razoes concretas: o Executor era um campo do adapter (morre com a tela) e cada
 * request usava Glide.with(view), que fica preso ao RequestManager da Activity - quando a
 * Activity e' destruida o Glide cancela esses pedidos. Aqui o contexto e' o da aplicacao,
 * entao nada e' cancelado ao trocar de pasta: a fila continua ate terminar.
 *
 * Uma thread, prioridade minima, uma pasta depois da outra. Um item a cada INTERVALO_MS
 * para nao competir com o que esta na tela.
 */
object FilaMiniaturas {

    private const val INTERVALO_MS = 45L
    private const val LIMPAR_APOS_MS = 4000L

    // Deduplicacao pelo processo inteiro: pasta reaberta nao refaz o que ja foi feito.
    private val jaEnfileirados = Collections.synchronizedSet(HashSet<String>())

    private val principal = Handler(Looper.getMainLooper())

    private val fila = Executors.newSingleThreadExecutor { alvo ->
        Thread(alvo, "Right100-fila-miniaturas").apply { priority = Thread.MIN_PRIORITY }
    }

    fun enfileirar(
        contexto: Context,
        itens: List<Medium>,
        colunas: Int,
        recortar: Boolean,
        cantos: Int
    ) {
        if (itens.isEmpty()) return
        val app = contexto.applicationContext

        val lote = ArrayList<Medium>(itens.size)
        synchronized(jaEnfileirados) {
            itens.forEach { item ->
                val chave = item.path + "|" + item.type + "|" + colunas + "|" + recortar + "|" + cantos
                if (jaEnfileirados.add(chave)) lote.add(item)
            }
        }
        if (lote.isEmpty()) return

        fila.execute {
            lote.forEach { item ->
                principal.post { aquecer(app, item, colunas, recortar, cantos) }
                try {
                    Thread.sleep(INTERVALO_MS)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /**
     * Reproduz o caminho exato que setupThumbnail usa. Se divergir, o Glide grava o bitmap
     * numa chave que o bind nunca vai procurar e o trabalho todo e' jogado fora.
     */
    private fun caminhoParaCarregar(contexto: Context, item: Medium): String {
        val path = item.path
        if (path.startsWith("content://")) return path

        val podeUsarId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            item.mediaStoreId > 0L &&
            !contexto.isPathOnOTG(path) &&
            !Environment.isExternalStorageManager()

        return if (podeUsarId) {
            ContentUris.withAppendedId(
                MediaStore.Files.getContentUri("external"),
                item.mediaStoreId
            ).toString()
        } else {
            path
        }
    }

    private fun aquecer(contexto: Context, item: Medium, colunas: Int, recortar: Boolean, cantos: Int) {
        try {
            val caminho = caminhoParaCarregar(contexto, item)

            // MySquareImageView, e nao ImageView: loadImage exige esse tipo. Foi exatamente
            // essa troca que quebrou o build #843.
            val alvo = MySquareImageView(contexto)
            alvo.tag = caminho

            contexto.loadImage(
                type = item.type,
                path = caminho,
                target = alvo,
                horizontalScroll = false,
                animateGifs = false,
                cropThumbnails = recortar,
                roundCorners = cantos,
                // Mesma assinatura do bind, byte por byte.
                signature = ObjectKey("thumbnail-v12-" + item.getKey()),
                skipMemoryCacheAtPaths = null,
                columnCount = colunas,
                fallbackPath = null,
                // A deteccao de quadro quase preto fica onde sempre esteve: no bind. Aqui
                // ela so' faria o pedido falhar e nao guardar nada no cache.
                detectarQuadroPreto = false,
                onError = {}
            )

            // O alvo nunca vai para a tela. Sem o clear o RequestManager da aplicacao
            // seguraria cada View ate' o processo morrer.
            alvo.postDelayed({
                try {
                    Glide.with(contexto).clear(alvo)
                } catch (_: Exception) {
                }
            }, LIMPAR_APOS_MS)
        } catch (_: Exception) {
        }
    }

    fun esquecerTudo() {
        synchronized(jaEnfileirados) { jaEnfileirados.clear() }
    }
}
