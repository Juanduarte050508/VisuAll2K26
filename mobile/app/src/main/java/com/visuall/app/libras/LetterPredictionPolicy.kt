package com.visuall.app.libras

/**
 * Combina o classificador multiclasse com os reforcos binarios opcionais.
 *
 * O geral tem prioridade porque compara todas as letras entre si. Um modelo
 * individual so responde quando o geral rejeitou o quadro; alem de evitar que
 * um binario superconfiante troque a letra, o lambda deixa de executar varios
 * modelos ONNX no caminho comum.
 */
internal object LetterPredictionPolicy {
    fun generalFirst(
        general: Prediction,
        specialist: () -> Prediction?
    ): Prediction {
        if (general.letra != "-") return general
        val fallback = specialist()
        return fallback?.takeIf { it.letra != "-" } ?: general
    }
}
