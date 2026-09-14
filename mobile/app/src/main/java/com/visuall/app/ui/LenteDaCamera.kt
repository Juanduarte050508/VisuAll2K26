package com.visuall.app.ui

import android.content.Context
import androidx.camera.core.CameraSelector

/**
 * A lente escolhida: traseira ou frontal.
 *
 * Mesmo motivo do [ProporcaoDaCamera]: a escolha e feita numa tela e usada em
 * outra. Cada tela guardava a sua lente, e com valores iniciais diferentes -- a
 * da camera comecava na traseira e a do modo Libras na frontal. Quem estava na
 * traseira e tocava no modo Libras via a camera virar pra selfie sozinha.
 *
 * Guardada em disco pelos mesmos motivos: o caminho entre as telas nao e unico
 * e a escolha deve sobreviver a fechar o app.
 */
internal object LenteDaCamera {

    private const val ARQUIVO = "visuall_camera"
    private const val CHAVE = "lente"

    /** Traseira e o padrao: e com ela que a tela da camera sempre abriu. */
    private const val PADRAO = CameraSelector.LENS_FACING_BACK

    private fun prefs(context: Context) =
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /** CameraSelector.LENS_FACING_BACK ou LENS_FACING_FRONT. */
    fun lente(context: Context): Int =
        when (val guardada = prefs(context).getInt(CHAVE, PADRAO)) {
            CameraSelector.LENS_FACING_FRONT, CameraSelector.LENS_FACING_BACK -> guardada
            else -> PADRAO
        }

    fun guardar(context: Context, lente: Int) {
        prefs(context).edit().putInt(CHAVE, lente).apply()
    }
}
