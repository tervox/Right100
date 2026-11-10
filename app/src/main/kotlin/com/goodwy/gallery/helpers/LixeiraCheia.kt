package com.goodwy.gallery.helpers

// Marca que a lixeira recebeu item agora (mover para a lixeira): a tela principal
// insere a pasta Lixeira na hora, sem esperar uma varredura completa.
object LixeiraCheia {
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
