package com.goodwy.gallery.helpers

/**
 * Aviso em memoria de que a lixeira acabou de ser esvaziada.
 * Quem sabe disso no instante do toque e' a tela da lixeira; a lista de pastas consome ao voltar.
 */
object LixeiraEsvaziada {
    @Volatile private var marcadaEm = 0L
    @Volatile private var consumidaEm = 0L

    fun marcar() {
        marcadaEm = System.currentTimeMillis()
    }

    fun consumir(): Boolean {
        val marca = marcadaEm
        if (marca > consumidaEm) {
            consumidaEm = marca
            return true
        }
        return false
    }
}
