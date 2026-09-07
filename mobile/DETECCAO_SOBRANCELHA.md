# Deteccao de sobrancelha levantada

Auditoria e plano de validacao da primeira versao testavel no Android. O escopo
atual e deliberadamente restrito ao modo **CORPO** e ao gesto bilateral (as duas
sobrancelhas). O objetivo desta etapa e obter medidas reais no Xiaomi 15 antes
de transformar valores provisórios em parametros definitivos.

## 1. Diagnostico do codigo original

O fluxo Android existente e:

```text
CameraX ImageAnalysis (RGBA, ~640x480, KEEP_ONLY_LATEST)
  -> LibrasAnalyzer.prepararBitmap (reduz, rotaciona e espelha a frontal)
  -> HandLandmarker + PoseLandmarker + FaceLandmarker no modo CORPO
  -> extracao de features
  -> classificadores/estado temporal
  -> callbacks para LibrasFragment
  -> LandmarkOverlayView e interface
```

Versoes verificadas no projeto: Android Gradle Plugin 8.2.2, Kotlin 1.9.22,
Java 17, compile/target SDK 34, min SDK 26, CameraX 1.3.1, MediaPipe Tasks
Vision 0.10.14, ONNX Runtime 1.18.0 e TensorFlow Lite 2.16.1. O modelo
`face_landmarker.task` embarcado contem detector, landmarks, geometria facial e
o modelo de blendshapes.

Problemas encontrados antes desta alteracao:

| Local | Problema | Impacto/evidencia | Resolucao |
|---|---|---|---|
| `FaceMarkerEngine.kt` | Distancia fixa `0.38` para todas as pessoas | Anatomia, distancia da camera e perspectiva mudam a medida | Baseline neutro individual e distancia normalizada |
| `FaceMarkerEngine.kt` | Media entre sobrancelha e pontos 159/386 da palpebra | A referencia se move ao piscar e ao abrir o olho | Centros das sobrancelhas contra os cantos rigidos dos olhos |
| `FaceMarkerEngine.kt` | Decisao por media curta de quadros | Dependia do FPS e podia oscilar | Maquina de estados baseada em milissegundos |
| `FaceMarkerEngine.kt` | Sem blendshapes, pose ou rejeicao de falsos positivos | Piscar, olhos abertos e movimento da cabeca podiam parecer gesto | Estrategia hibrida e vetos explicitos |
| `FaceMarkerEngine.kt` | `numFaces=1` | Um segundo rosto podia substituir silenciosamente o usuario | Detectar ate dois e rejeitar quando a contagem nao for exatamente um |
| `LibrasFragment.kt` | O `?` dependia de existir texto na frase | Com frase vazia o teste parecia nao funcionar | Marcador visual independente da frase |
| `LibrasFragment.kt` | Nenhum valor observavel em aparelho | Impossivel separar erro de landmark, limiar, pose ou UI | Painel de diagnostico no modo CORPO + LINHAS |
| Camera2 em dois fragments | Uso experimental sem anotacao AndroidX | `lintDebug` falhava com 8 erros | Anotacao localizada, sem mudar o comportamento |

Os nomes anatomicos esquerdo/direito podem ser confundidos pela camera frontal
espelhada. Por isso a implementacao interna chama os lados de A e B. O grupo A
usa os landmarks 33/133 e os blendshapes com sufixo `Right`; o grupo B usa
362/263 e sufixo `Left`. A associacao numerica esta explicita, mas o rotulo
visual esquerda/direita so deve ser fixado depois do teste no aparelho.

## 2. Fontes e decisoes

| Fonte | Tecnica/informacao util | Aplicacao e limitacao | Decisao/licenca |
|---|---|---|---|
| [Face Landmarker para Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android) | Configuracao Android, modos IMAGE/VIDEO/LIVE_STREAM, landmarks, blendshapes e matrizes | E a API ja usada pelo app; custo real das saidas extras precisa ser medido no Xiaomi | Adotar a API existente |
| [Configuracao do Face Landmarker](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker) | `num_faces`, confiancas e flags de saida; suavizacao interna so para um rosto | Usamos dois rostos para rejeicao explicita e filtro proprio | Adaptar |
| [Exemplo Android oficial](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/face_landmarker/android) | Helper, rotacao/espelhamento, blendshapes, executor e overlay | Referencia de integracao, nao um classificador de pergunta | Adaptar conceitos; Apache-2.0 |
| [Model Card do Blendshape V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf) | Confirma `browInnerUp`, `browOuterUpLeft/Right`, `browDownLeft/Right`, `eyeBlinkLeft/Right` e `eyeWideLeft/Right` | Escores nao sao universais e exigem baseline/validacao | Adotar sinais, sem limiar global |
| [Grafo oficial de blendshapes](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc) | Fluxo entre landmarks e classificador de blendshapes | Implementacao C++ interna, nao precisa ser copiada | Consultar; Apache-2.0 |
| [Face Mesh e Face Geometry](https://github.com/google-ai-edge/mediapipe/blob/master/docs/solutions/face_mesh.md) | Coordenadas normalizadas usam camera de perspectiva fraca; geometria canonica e transformacao Procrustes | Explica por que pixel/cm direto e fragil | Normalizar e usar matriz de transformacao |
| [ML Kit Face Mesh](https://developers.google.com/ml-kit/vision/face-mesh-detection/android) | 468 pontos, API beta, requisitos de imagem e ate dois rostos | Duplicaria detector e dependencia; nao oferece aqui vantagem sobre MediaPipe + blendshapes | Rejeitar nesta fase |
| [Contornos do ML Kit](https://developers.google.com/android/reference/com/google/mlkit/vision/face/FaceContour) | Contornos superiores/inferiores de sobrancelhas e olhos | Face Detection classifica olhos/sorriso, nao a acao da sobrancelha | Rejeitar nesta fase |
| [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze) | Backpressure e obrigacao de fechar `ImageProxy` | O projeto ja usa `KEEP_ONLY_LATEST` e fecha no `finally` | Manter |
| [Transformacao CameraX](https://developer.android.com/media/camera/camerax/transform-output) | Coordenadas, rotacao, recorte e mapeamento para a preview | O projeto usa transformacao manual coerente com `FILL_CENTER`; precisa de inspecao visual no aparelho | Manter e validar |
| [One Euro Filter](https://gery.casiez.net/publications/CHI2012-casiez.pdf) | Suavizacao adaptativa para equilibrar ruido e atraso | Boa opcao futura; adiciona parametros sem dataset que os justifique | Rejeitar por enquanto; EMA temporal e mais simples |
| [Analise de acao da sobrancelha e movimento da cabeca](https://publications.ri.cmu.edu/automatic-analysis-and-recognition-of-brow-actions-and-head-motion-in-spontaneous-facial-behavior) | Trata movimento/pose da cabeca como sinal interferente | Trabalho anterior ao MediaPipe, mas o principio continua aplicavel | Adotar rejeicao por pose |

O [repositorio MediaPipe](https://github.com/google-ai-edge/mediapipe) e os
exemplos oficiais usam Apache-2.0. Nao foi copiado bloco substancial de codigo;
as APIs e os padroes de integracao foram adaptados. A versao do projeto foi
mantida em 0.10.14: as APIs necessarias foram verificadas no artefato local, e
uma atualizacao ampla de dependencia nao e necessaria para este experimento.

## 3. Comparacao das estrategias

| Estrategia | Vantagens | Riscos | Escolha |
|---|---|---|---|
| Somente blendshapes | Sem escolher landmarks manualmente; sem unidade de pixel | Modelo generico, escores variam por usuario/pose/oclusao | Nao usar sozinho |
| Somente geometria | Interpretavel, facil de diagnosticar e normalizar | Ainda sofre com perspectiva, pose e erro de landmarks | Fallback e evidencia principal |
| Hibrida | Uma fonte valida a outra; permite vetar piscar/franzir e diagnosticar | Mais custo e parametros | **Escolhida** |
| Formula antiga | Baixo custo | Referencia movel, limiar global e nenhum estado temporal | Rejeitada |

Nao foi treinada uma nova rede neural nesta etapa. O modelo MediaPipe existente
ja estima landmarks e blendshapes; a camada nova calibra cada usuario e combina
os sinais. Os videos servirao primeiro para medir distribuicoes e ajustar a
regra com evidencia. So vale treinar um classificador proprio se os dados
mostrarem que a separacao nao pode ser obtida com esses sinais.

## 4. Arquitetura implementada

```text
Face Landmarker
  |- 478 landmarks -> geometria normalizada e pontos vermelhos
  |- blendshapes    -> subir/descer/piscar/olho aberto
  `- matriz facial -> pitch/yaw/roll
          |
          v
BrowGestureDetector (Kotlin puro)
  baseline robusto -> EMA -> escore hibrido -> vetos -> maquina de estados
          |
          v
LibrasFragment
  |- marcador `?` independente da frase
  `- painel de diagnostico quando CORPO + LINHAS esta ativo
```

O estado e sincronizado porque a troca de modo vem da UI e os frames chegam no
executor unico da camera. Nenhuma imagem, landmark ou dado biometrico e salvo.

## 5. Geometria e formula

Landmarks usados:

- Sobrancelha A: 70, 105 e 107; sobrancelha B: 300, 334 e 336.
- Olho A: cantos 33 e 133; olho B: cantos 362 e 263.
- O terceiro ponto vermelho de cada olho e virtual: a media dos dois cantos.
- Nariz: 1. Boca: 61, 13, 291 e 14.

Primeiro se calcula o centro dos tres pontos de cada sobrancelha e o centro dos
dois cantos de cada olho. Seja `e` o vetor entre os centros dos olhos,
`s = ||e||` a distancia interocular e `n` o vetor unitario perpendicular a
linha dos olhos. Para cada lado:

```text
gap = dot(centro_olho - centro_sobrancelha, n) / s
delta_gap = gap_filtrado - gap_neutro_pessoal
```

Isso remove escala/distancia da camera e corrige inclinacao 2D (roll). A matriz
facial e usada para rejeitar pitch/yaw/roll excessivos, em vez de tentar
classificar durante uma pose que deforma a medida.

Os limiares pessoais sao calculados na calibracao neutra:

```text
sigma_robusto = 1.4826 * MAD(amostras)
limiar_entrada = max(minimo_seguro, 4.5 * sigma_robusto)
limiar_saida = 0.55 * limiar_entrada
```

O escore de cada lado e:

```text
score = 0.55 * razao_geometria
      + 0.35 * razao_browOuterUp
      + 0.10 * razao_browInnerUp
```

Cada razao e o delta positivo dividido pelo limiar pessoal e limitada a 2. Com
blendshapes, o candidato exige `score >= 1` e validacao cruzada entre geometria
e blendshape, salvo geometria muito forte. Sem blendshapes, a geometria precisa
chegar a 1.45 vezes o limiar. No modo atual os dois lados precisam passar.

## 6. Estados, parametros e falsos positivos

```text
NO_FACE -> CALIBRATING -> NEUTRAL -> CANDIDATE_RAISE
        -> RAISED -> COOLDOWN -> NEUTRAL
```

| Parametro inicial | Valor | Motivo |
|---|---:|---|
| Calibracao neutra | 2.000 ms e >=10 amostras | Rapida, mas robusta a um frame |
| Suavizacao EMA | tau 110 ms | Reduz jitter sem atraso longo |
| Sustentacao para entrar | 280 ms | Rejeita picos e piscadas |
| Sustentacao para sair | 240 ms | Evita oscilacao na borda |
| Visibilidade minima | 700 ms | O simbolo permanece legivel |
| Cooldown | 900 ms | Impede repeticao imediata |
| Perda breve de rosto | 350 ms | Evita flicker por uma falha do detector |
| Reset sem rosto | 2.000 ms | Nova pessoa recebe novo baseline |
| Adaptacao do neutro | tau 15 s, so longe do gesto | Acompanha deriva sem aprender a expressao levantada |
| Pose maxima | pitch 20, yaw 22, roll 20 graus | Rejeita perspectiva excessiva |
| Piscar | `eyeBlink > 0.65` | Nao inicia pergunta |
| Olhos muito abertos | `eyeWide > 0.75` sem geometria de sobrancelha | Rejeita arregalar isolado |
| Franzir | aumento de `browDown` >= limiar pessoal/minimo 0.14 | Separa franzimento de elevacao |

Esses valores sao pontos de partida, nao resultados de um dataset VisuAll. O
painel torna cada decisao mensuravel para que sejam calibrados com os videos.
"Falso cabeca" significa um video negativo em que a pessoa mantem a expressao
neutra, mas olha para cima/baixo, vira para os lados, inclina, aproxima ou afasta
o rosto. O sistema precisa continuar sem mostrar `?`.

## 7. Interface e ciclo de vida

- O `?` agora aparece mesmo quando a frase esta vazia, somente no modo CORPO.
- A UI so muda quando `markerVisible` muda; isso evita atualizacoes e flicker a
  cada frame.
- A ativacao e anunciada uma vez pela acessibilidade.
- Callbacks de texto/visibilidade entram na main thread; o overlay usa
  `postInvalidate()`.
- Perda breve do rosto conserva o marcador; perda longa limpa estado/baseline.
- Troca de modo reinicia a maquina; `close()` fecha detectores e o executor e
  encerrado no ciclo de vida do fragment.
- A camera frontal e espelhada antes da inferencia, de acordo com o dataset
  existente; o overlay recebe coordenadas nesse mesmo espaco.
- O app esta travado em retrato no manifesto. Portanto, paisagem nao faz parte
  do comportamento executavel atual, embora exista recurso `layout-land`.

O marcador e apenas visual nesta fase. Transformar interrogacao em propriedade
semantica da frase e repassa-la para TTS/oculos e um passo separado, pois exige
decidir quando a pergunta e confirmada e como o protocolo dos oculos representa
esse estado.

## 8. Desempenho e observabilidade

- CameraX usa `STRATEGY_KEEP_ONLY_LATEST`, RGBA e alvo 640x480 4:3.
- O `ImageProxy` e sempre fechado em `finally`; bitmaps temporarios sao
  reciclados depois da inferencia sincrona.
- Detector facial roda a cada 5 frames; a maquina usa timestamps reais e nao
  depende dessa taxa.
- GPU e tentada primeiro, com fallback para CPU.
- O app ja mede tempo de mao, rosto, pose, classificador e FPS no Logcat com a
  tag `LibrasPerf`.
- `numFaces=2`, blendshapes e matriz aumentam custo. CPU, bateria e FPS ainda
  precisam ser medidos no Xiaomi; nao ha base para afirmar custo aceitavel sem
  o teste fisico.

Com modo CORPO e LINHAS ligado, o painel exibe rosto/limiar de confianca,
progresso da calibracao, estado, gaps brutos/filtrados, baseline, deltas,
limiares, blendshapes, escores, pose e motivo exato de aceitar/rejeitar. A
FaceLandmarkerResult nao fornece neste uso um escore facial final por rosto; o
texto `>=0.50` informa o minimo configurado, nao uma confianca inventada.

## 9. Testes automatizados

`BrowGestureDetectorTest` cobre:

- calibracao antes do gesto;
- rejeicao de um unico quadro alto;
- ativacao bilateral depois do tempo minimo;
- veto de piscar;
- veto de pose excessiva;
- diferenca entre modo bilateral e unilateral;
- histerese, visibilidade minima e cooldown;
- perda curta do rosto sem flicker.

`FacePoseEstimatorTest` cobre matriz identidade, yaw com escala embutida e
matriz incompleta. As sequencias usam timestamps irregulares para provar que o
comportamento depende de milissegundos, nao de numero de frames.

Comandos de verificacao:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

## 10. Matriz de validacao manual

Cada linha deve registrar: aparelho, luz, distancia, oculos/obstrucao, baseline
A/B, delta maximo A/B, blendshapes, pose, estado, ativou corretamente e numero
de falsos positivos.

| Classe | Variacoes minimas | Esperado |
|---|---|---|
| Neutro | 30-60 s, perto/longe, luz frontal/lateral/contraluz | Zero `?` |
| Pergunta bilateral | rapido e sustentado; 5 repeticoes | Um evento por gesto |
| Unilateral | esquerda e direita | Nao ativa no modo BOTH |
| Piscar | normal, forte e repetido | Nao ativa |
| Olhos abertos | sem levantar e junto do gesto | Isolado nao ativa; gesto real pode ativar |
| Testa franzida | leve/forte | Nao ativa |
| Falso cabeca | cima, baixo, lados, inclinacao, aproximar/afastar | Nao ativa; painel mostra pose quando excessiva |
| Rosto | centro/bordas, perda e retorno, dois rostos | Sem flicker; dois rostos rejeitados |
| Aparencia | usuarios diferentes, oculos, barba, franja, peles | Recalibra e mantem taxa aceitavel |
| Dispositivo | Xiaomi 15 e aparelho lento | FPS/latencia/temperatura aceitaveis |

## 11. Protocolo dos videos

Os videos devem ser autorizados pelo participante e usados localmente. Nao e
preciso gravar vinte videos do mesmo tipo. Um primeiro lote equilibrado e:

1. Cinco videos neutros de 10 s (`neutro_01` a `neutro_05`).
2. Cinco videos com sobrancelhas levantadas por 10 s (`pergunta_01` a `05`).
3. Cinco videos de 20 s repetindo: neutro 2 s, levantar, segurar 1 s, abaixar e
   esperar 2 s (`ciclo_01` a `05`).
4. Tres a cinco videos de piscadas, 5-10 s (`falso_piscar_01`...).
5. Tres a cinco videos de falso cabeca, 10-15 s (`falso_cabeca_01`...).
6. Se possivel, dois videos com olhos arregalados e dois franzindo a testa.

Gravar sempre com o rosto inteiro visivel, sem filtro do aplicativo, mantendo
alguns videos na luz normal e outros numa luz mais dificil. Para calibrar
generalizacao, videos de duas ou mais pessoas valem mais do que dezenas da
mesma pessoa.

## 12. Como validar no Xiaomi 15

1. Instale o APK debug e abra o VisuAll.
2. Entre na tela de Libras e selecione **CORPO**.
3. Ative **LINHAS**: os 17 pontos devem ficar vermelhos e o painel deve surgir.
4. Fique frontal e neutro por pelo menos 2-3 s, sem piscar muito. Espere o
   estado mudar de `CALIBRANDO` para `NEUTRO`.
5. Levante as duas sobrancelhas e segure por cerca de meio segundo. O painel
   deve passar por `CANDIDATO` e `LEVANTADA`; o `?` deve aparecer sozinho.
6. Abaixe: depois da visibilidade minima, deve passar por `AGUARDANDO SAIDA`,
   `COOLDOWN` e voltar a `NEUTRO`.
7. Tente piscar, arregalar os olhos e executar os movimentos de falso cabeca.
   Fotografe o painel se algo ativar indevidamente.
8. Envie uma captura neutra e outra levantada contendo o painel, ou os videos
   nomeados pelo protocolo. Os valores permitem ajustar o proximo commit sem
   adivinhar um limiar em pixel ou centimetro.

## 13. Riscos e proximos experimentos

Ainda dependem de aparelho/dados reais:

- validar a convencao da matriz e dos lados A/B na camera frontal;
- medir latencia, FPS, temperatura e consumo com blendshapes/matriz ativos;
- escolher os limiares definitivos por taxa de falso positivo por minuto;
- verificar franja, oculos, luz baixa e diferentes anatomias;
- decidir se pergunta unilateral sera aceita; hoje o modo e `BOTH`;
- avaliar se uma regressao/logistica pequena sobre as features supera a regra;
- posteriormente representar interrogacao como dado semantico na frase, TTS e
  protocolo dos oculos, em vez de transmitir apenas o caractere visual.

Nao se deve treinar um modelo final somente com uma pessoa: ele pode memorizar
rosto, iluminacao e camera. Primeiro se exportam features anonimizadas por frame
(sem imagem) dos videos, divide-se o conjunto por pessoa — nunca frames da mesma
pessoa nos dois lados — e mede-se precisao, recall e falsos positivos por
minuto. So depois se decide entre conservar a regra calibrada ou substituir o
`BrowGestureDetector` por um classificador treinado com a mesma interface.
