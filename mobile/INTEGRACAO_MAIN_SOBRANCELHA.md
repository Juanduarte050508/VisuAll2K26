# Integracao da main com a sobrancelha — 12/09/2026

## Base e escopo

- Branch de trabalho: `Ajuste_Pontos_Rosto`.
- Branch local antes da integracao: `2d35ccf`.
- Main consultada depois de `fetch`: `5db7de7e2ac876e1f4216eba2be23a8f18fe907f`
  (`Answer mode rotation`, 10/09/2026).
- Base comum: `254d860`.
- Merge local: `c746c1f`. Sem push.

O pedido inicialmente era apenas visual; depois Julio confirmou que queria o
projeto atual completo com a sobrancelha anterior preservada. Por isso foi
integrada a main, e nao copiada uma versao antiga da tela por cima dela.

## Novidades recebidas

1. Camera dos oculos por MJPEG/Wi-Fi, selecao de endereco/rede, descarte de
   quadros antigos, bloqueio de tela e ferramentas/documentacao ESP32.
2. `EncaixeDeQuadro` distingue imagem preenchendo a tela (`CORTANDO`) de
   imagem inteira com faixas (`INTEIRA`). O overlay facial usa esse mesmo
   mapeamento, junto das maos e do corpo.
3. Proporcao da camera compartilhada com a tela Libras e retirada da exigencia
   de 60 FPS nessa tela.
4. Botoes no estilo preto/dourado, novos icones, leitura da frase inteira ao
   tocar no balao, ajustes da lixeira e rotacao do modo resposta.
5. Ajustes de traducao do segundo verbo no Android/Python e novos testes.

Fontes: [historico da main integrada](https://github.com/Juanduarte050508/VisuAll/commits/5db7de7e2ac876e1f4216eba2be23a8f18fe907f/)
e os arquivos de producao/teste dessa revisao, lidos localmente.

## Conflito resolvido e comportamentos preservados

- A simulacao de merge acusou um conflito textual em `LibrasFragment.kt`:
  imports de Camera2. A main retirou o uso experimental que fixava FPS.
  Foram removidos os imports e a anotacao `OptIn` agora desnecessaria nesse
  metodo; nao se restaurou a configuracao antiga de 60 FPS.
- `LibrasAnalyzer.analisarQuadro` recebeu automaticamente a chamada facial
  antes da separacao Corpo/Alfabeto. Os callbacks de pontos, diagnostico e
  interrogacao foram preservados no fragmento.
- `BrowGestureDetector`, features, baseline, limiares, modelo e modo bilateral
  nao foram alterados. Em `FaceMarkerEngine` mudou apenas um comentario de cor.
- Texto compartilhado e interrogacao persistente do `SentenceBuilder` foram
  mantidos. A pergunta nao depende de continuar com a sobrancelha levantada.
- Pontos faciais continuam vindo do FaceLandmarker (17 pontos, incluindo tres
  por sobrancelha e tres por olho), sem duplicar os pontos faciais da pose.
  Agora usam o mesmo azul `#8FC0FF` e raio de 6 px dos pontos da main.
- Os botoes e demais alteracoes visuais da main foram mantidos. Nessa revisao
  o botao LINHAS e preto/dourado, nao azul. Pontos e painel tecnico continuam
  exclusivos de Corpo; a deteccao de pergunta continua nos dois modos.
- Nao foi feito treinamento nem alterada sensibilidade de mao/corpo.

## Validacao

Executar em `mobile`, com JDK 17 e Android SDK 34:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

A primeira execucao apos o merge passou: 204 testes, zero falhas/erros ou
testes ignorados; lint com zero erros e 208 avisos; APK debug gerado.

Foi acrescentado um teste JVM que encadeia `BrowGestureDetector`,
`SentenceBuilder` e `PhraseOutput`: um evento sustentado confirma a pergunta,
baixar a sobrancelha nao remove a pontuacao, alternar trechos nao perde a frase,
o texto para leitura integral contem `?` e a remocao explicita preserva o texto.
Esse teste nao instancia CameraX, MediaPipe ou a interface Android.

A execucao final, incluindo o ajuste azul e esse teste, tambem passou:
**205 testes, zero falhas/erros e zero ignorados**, lint sem erros (208 avisos)
e `assembleDebug` concluido. APK em
`mobile/app/build/outputs/apk/debug/app-debug.apk` a partir da raiz do repositorio.

## Limites e testes obrigatorios no aparelho

Build e testes JVM nao provam precisao da camera nem ausencia de regressao
visual. Nao houve instalacao, smoke test fisico ou inspeção visual nesta etapa.

1. Abrir Corpo com LINHAS: verificar pontos azuis sobre sobrancelhas, olhos,
   nariz e boca, sem um segundo conjunto deslocado; esperar calibracao neutra.
2. Levantar as duas sobrancelhas e abaixar: `?` deve aparecer e permanecer.
   Repetir piscadas e movimentos de cabeca para observar falsos positivos.
3. Com uma palavra e `?`, alternar Corpo/Alfabeto/Corpo; ambos devem permanecer.
   Conferir lixeira, limpeza e leitura da frase ao tocar no balao.
4. Abrir resposta, girar e voltar: verificar preview, frase, interrogacao e
   retorno do reconhecimento. A activity trata `orientation|screenSize`, mas
   isso nao substitui o teste de ciclo de vida no dispositivo.
5. Se usar os oculos, testar conectar/desconectar, recorte e voltar ao celular.

Riscos encontrados por leitura, nao reproduzidos fisicamente e nao corrigidos
neste ajuste:

- A fonte de rede chama `analisarQuadro` em `oculos-analise`, diferente do
  executor que cria os detectores do celular. O FaceMarkerEngine usa GPU e
  espera criacao/uso no mesmo executor. A troca de fonte precisa ser validada
  com logs; um merge sem conflito nao garante essa compatibilidade.
- Ao voltar dos oculos, `bindCamera` recria o analyzer. A preservacao de frase
  e pergunta entre modos nao garante preservacao nessa troca de fonte.
- `analisarQuadro` retorna imediatamente quando pausado, antes do `finally`
  que recicla o bitmap. O caminho de rede entrega a posse desse bitmap; medir
  memoria ao manter o modo resposta aberto com stream ativo.

Firmware ESP32, backend Python, latencia, bateria e precisao com pessoas reais
nao foram validados por estes testes Android. Nenhum dado facial foi gravado.
