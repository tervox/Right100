package com.goodwy.gallery.helpers

import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.models.ThumbnailItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Renames que o MediaStore ainda nao indexou (caminho antigo -> novo).
 *
 * A pasta e as telas sao remontadas por varreduras periodicas. Ate o sistema indexar, essas
 * varreduras devolvem o nome ANTIGO, e o nome novo que estava na tela era sobrescrito -
 * por isso o rename so "valia" ao sair e entrar na pasta de novo.
 */
object RenomeadosRecentes {
    private const val VALIDADE_MS = 120_000L
    private val mapa = ConcurrentHashMap<String, Pair<String, Long>>()

    fun registrar(velho: String, novo: String) {
        if (velho.isEmpty() || novo.isEmpty() || velho == novo) return
        mapa[velho] = Pair(novo, System.currentTimeMillis() + VALIDADE_MS)
    }

    fun aplicar(lista: List<ThumbnailItem>) {
        if (mapa.isEmpty() || lista.isEmpty()) return
        val agora = System.currentTimeMillis()
        mapa.entries.removeAll { it.value.second < agora }
        if (mapa.isEmpty()) return

        val resolvidos = HashSet<String>()
        lista.forEach { item ->
            if (item is Medium) {
                val par = mapa[item.path] ?: return@forEach
                val novo = par.first
                if (File(novo).exists()) {
                    item.path = novo
                    item.name = novo.substringAfterLast('/')
                    resolvidos.add(novo)
                } else {
                    resolvidos.add(item.path)
                }
            }
        }
        if (resolvidos.isNotEmpty()) mapa.entries.removeAll { resolvidos.contains(it.value.first) }
    }
}
