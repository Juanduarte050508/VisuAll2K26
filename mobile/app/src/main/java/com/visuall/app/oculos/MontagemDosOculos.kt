package com.visuall.app.oculos

/**
 * Como a camera esta presa na armacao.
 *
 * Na haste dos oculos ela nao cabe em pe: fica deitada, virada 90 graus. A
 * placa nao desfaz isso. A OV5640 sabe espelhar e virar de cabeca pra baixo,
 * mas nao sabe girar 90 graus, e girar o JPEG na propria placa custaria
 * decodificar e comprimir de novo a cada quadro -- ela nao da conta. Entao o
 * giro acontece no app, com a imagem ja decodificada, antes da tela e do
 * reconhecimento.
 *
 * Girado, o quadro dos oculos fica em pe e com a mesma proporcao 3:4 da camera
 * do celular, que e a forma que o reconhecimento e o desenho dos landmarks
 * esperam.
 */
internal object MontagemDosOculos {

    /**
     * Quanto girar o quadro que chega, em graus no sentido do relogio (e o
     * sentido do Matrix.postRotate do Android).
     *
     * 90 e o valor conferido na tela, nesta armacao. Com 270 a imagem saiu de
     * cabeca pra baixo, entao os dois unicos candidatos ja foram testados: se um
     * dia a camera trocar de lado na haste, o valor volta a ser 270.
     */
    const val ROTACAO_GRAUS = 90f
}
