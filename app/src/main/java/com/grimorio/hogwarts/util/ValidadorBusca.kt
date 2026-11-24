package com.grimorio.hogwarts.util

object ValidadorBusca {

    const val MINIMO_CARACTERES = 3

    fun validar(bruto: String): Resultado {
        val texto = bruto.trim()
        if (texto.isEmpty()) {
            return Resultado.Invalido(mensagem = Mensagem.VAZIO)
        }
        if (texto.length < MINIMO_CARACTERES) {
            return Resultado.Invalido(mensagem = Mensagem.CURTO)
        }
        return Resultado.Ok(texto)
    }

    enum class Mensagem { VAZIO, CURTO }

    sealed class Resultado {
        data class Ok(val texto: String) : Resultado()
        data class Invalido(val mensagem: Mensagem) : Resultado()
    }
}
