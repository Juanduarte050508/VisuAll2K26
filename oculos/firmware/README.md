# Firmware dos óculos

O que roda na placa: cria a própria rede Wi-Fi e serve a câmera em MJPEG, no
mesmo formato do `oculos/mock_esp32_cam.py`. Do lado do app não muda nada além
do endereço.

```
rede....: VisuAll-Oculos     senha: visuall2026
no app..: http://192.168.4.1/stream
no nav..: http://192.168.4.1/
```

A rede é criada **pela placa** (SoftAP). Não depende de roteador, de internet,
nem de nada instalado no lugar — que é o requisito do projeto. O celular entra
nessa rede e fala direto com a placa. Do lado do app isso já está resolvido:
ver `RedeDosOculos`, que impede o Android de fugir de uma rede sem internet.

## Compilar

```powershell
.\compilar.ps1                  # só compila
.\compilar.ps1 -Porta COM5      # compila e grava
.\compilar.ps1 -Limpo           # recompila do zero
```

Usa o `arduino-cli` embutido na Arduino IDE 2.x, se houver. Precisa do core
`esp32:esp32` (instalado uma vez, ~1 GB):

```powershell
arduino-cli core install esp32:esp32 --additional-urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
```

Estado atual: **compila limpo**, sem avisos. Ocupa 945 KB (30% do espaço de
programa) e 55 KB de RAM (17%).

### A opção que mais dá dor de cabeça

A linha de opções da placa está dentro do script justamente para não ser
digitada:

```
esp32:esp32:esp32s3:PSRAM=opi,FlashSize=16M,FlashMode=qio,PartitionScheme=huge_app,CDCOnBoot=default
```

**`PSRAM=opi`** é a que importa. A N16R8 tem PSRAM *octal*, e o padrão da IDE é
PSRAM desligada. Compilando com o padrão o firmware **sobe normalmente** — não
dá erro nenhum — mas a câmera fica com um único buffer na RAM interna e o
stream engasga. O sintoma manda procurar na antena, na bateria, no cabo; em
tudo menos na opção de compilação. Por isso o firmware imprime um aviso no
monitor serial se subir sem PSRAM.

`PartitionScheme=huge_app` também não é opcional: câmera + Wi-Fi não cabem na
partição padrão de 1,2 MB.

## O que só a placa pode confirmar

**Os pinos da câmera.** O `camera_pins.h` é cópia literal do arquivo do próprio
core do ESP32 — nenhum número foi digitado à mão —, mas *qual* dos modelos é a
sua placa só dá pra saber com ela ligada. O sketch usa
`CAMERA_MODEL_ESP32S3_EYE`, que é o mapeamento das ESP32-S3-CAM mais comuns
(mesmo pinout da Freenove ESP32-S3-WROOM).

Se estiver errado, o monitor serial diz na primeira linha:

```
esp_camera_init falhou: 0x105 (ESP_ERR_NOT_FOUND)
```

Aí é trocar o `#define CAMERA_MODEL_...` no topo do `.ino` por outro do
`camera_pins.h` — `CAMERA_MODEL_ESP32S3_CAM_LCD` e `CAMERA_MODEL_XIAO_ESP32S3`
são os próximos candidatos — e recompilar.

Quando acertar, o serial mostra qual sensor respondeu:

```
sensor detectado: PID 0x5640  (OV5640=0x5640, OV2640=0x26, OV3660=0x3660)
```

Isso separa dois problemas que parecem o mesmo: pino errado (a câmera nem
responde) de sensor diferente do esperado (responde, com outro PID).

## Calor

A OV5640 esquenta muito. No primeiro teste, depois de uns 6 minutos ligada no
USB, ela ficou quente a ponto de não dar para manter o dedo atrás dela, e isso
sem ninguém recebendo imagem: o sensor fica capturando sem parar desde que
liga.

Por isso o firmware deixa a câmera **dormindo** e só a acorda enquanto alguém
recebe o stream. O monitor serial avisa cada troca:

```
camera dormindo (ninguem recebendo imagem)
camera acordada (stream comecou)
```

Isso também serve de teste. Se a câmera esquentar muito **mesmo dormindo**, o
calor não vem do trabalho do sensor, e sim de algum problema elétrico: pare e
confira a placa e o módulo antes de continuar.

Dormindo, ela ficou fria: 5 minutos parada e, no toque, entre morna e fria.
**Transmitindo, o problema continua.** Partindo fria, a imagem se divide de
calor, com a metade esquerda clara e a direita escura, o que atrapalha pegar os
pontos da mão. Esfriando, volta ao normal. Não é contato com o ESP: a câmera
não encosta na blindagem.

| Clock  | VCO do PLL | Quadros/s | Imagem dividiu em |
|--------|------------|-----------|-------------------|
| 20 MHz | 1800 MHz   | 22        | 32 s              |
| 10 MHz | 900 MHz    | 11        | 50 s              |
| 20 MHz | 900 MHz    | 22        | 50 s              |

Com o clock em 10 MHz caíram juntos o ritmo e o VCO do PLL da câmera, e o VCO
chama atenção: com o PLL que o driver usa para JPEG em QVGA, ele trabalha a
1800 MHz, e o driver do Linux para esta mesma câmera avisa que não pode passar
de 1 GHz. O firmware usa metade do multiplicador e do divisor: o mesmo clock de
saída, os mesmos 22 quadros por segundo, com o VCO em 900 MHz. É a última linha
da tabela: nestes testes, o que mudou o tempo foi o VCO, e não o ritmo. A linha
`camera.:` do monitor serial mostra o PLL lido da própria câmera, e `pll 180 4`
na bancada volta ao do driver.

Melhorou, mas não resolve: no toque ela continua muito quente em uns 25 s. A
fabricante só garante imagem estável com o chip entre 0 e 50 °C.

Outra coisa que parece calor e não é: a imagem às vezes fica verde, ou muito
clara ou escura, **com a câmera fria**, e volta ao normal com um toque. A
configuração é escrita uma vez, no boot, e não muda com toque: o suspeito é o
contato no cabo flat. Reencaixe o flat com a placa desligada antes de medir
calor.

Para os óculos, perto do rosto, a alternativa é a **OV2640**, a câmera padrão
das placas ESP32-CAM. Ela consome menos, usa o mesmo conector de 24 pinos, o
firmware já a reconhece pelo PID e o app só precisa de 320x240. Falta testar
nesta placa.

## Diagnóstico do stream

No primeiro teste com o celular, a imagem apareceu e congelou, e a conexão caiu
duas vezes em meio minuto, sem erro nenhum da câmera. Para separar as causas, o
monitor serial mostra o sinal do celular a cada 5 segundos enquanto ele está na
rede; enquanto transmite, uma linha a cada 5 segundos; e uma linha no fim do
stream:

```
celular entrou na rede
celular recebeu o IP 192.168.4.N
sinal do celular: N dBm
stream: N quadros/s  N KB/s  envio mais lento N ms
stream acabou: N quadros em N s, depois o envio ficou parado N ms (o Wi-Fi parou de escoar)
stream acabou: N quadros em N s, e o celular fechou a conexao
celular saiu da rede (motivo N)
```

Como ler:

- **Envio mais lento alto ou "o Wi-Fi parou de escoar":** os quadros não estão
  chegando ao celular. Confira o sinal, a alimentação (mau contato no cabo) e o
  celular.
- **"o celular fechou a conexao":** foi o app que desistiu, ou alguém saiu do
  modo óculos.
- **Imagem congelada no app com a linha `stream:` normal:** a placa está
  mandando, e o problema está no app.

### Teste de rádio sem câmera

```powershell
.\compilar.ps1 -Porta COM8 -SemCamera
```

A câmera nem liga (sem clock e sem calor), e o `/stream` manda quadros gerados
na própria placa, no mesmo formato, a uns 25 por segundo: uma faixa clara
atravessando a imagem. No app, faixa andando quer dizer que os quadros estão
chegando.

- **Flui:** o Wi-Fi entre a placa e o celular funciona, e é a câmera que
  atrapalha (interferência ou consumo).
- **Trava também:** o problema está entre o Wi-Fi da placa e o celular, e a
  câmera não tem culpa.

Depois grave de novo **sem** `-SemCamera`.

### O que resolveu: a força do pino do clock

O teste sem câmera fluiu perfeito, então era a câmera atrapalhando o Wi-Fi. Com
ela ligada, o celular tinha sinal ótimo (-20 a -30 dBm) e mesmo assim o envio
travava depois de um ou dois quadros, toda vez; e mesmo com ela dormindo o
celular às vezes nem conseguia pegar IP.

O culpado foi o clock que a placa manda para a câmera, no GPIO 15. Com a força
de pino que o driver deixa, o stream travava; com a força mínima
(`GPIO_DRIVE_CAP_0`), fluiu na hora, a 22 quadros por segundo. A explicação
provável: borda rápida gera harmônicos, e um deles (2420 MHz) cai dentro do
canal 1 do Wi-Fi, a um ou dois centímetros da antena. O firmware usa a força
mínima e canal de 20 MHz por padrão.

Uma coisa a mais: com o celular **colado** na placa (sinal perto de -7 dBm), o
stream engasga. Uns 50 cm de distância bastam.

### Bancada: ajustes pelo monitor serial

Para testar ajustes sem regravar, o firmware aceita comandos pelo monitor
serial, um por linha. Valem até a placa reiniciar:

| Comando    | O que faz                                              |
|------------|--------------------------------------------------------|
| `xclk N`   | clock da câmera em MHz, de 6 a 27 (reinicia a câmera)  |
| `forca N`  | força do pino do clock, de 0 (fraca) a 3 (forte)       |
| `pll M D`  | PLL da câmera: multiplicador e divisor (ver "Calor")   |
| `reg HHHH` | lê um registrador da câmera; o endereço em hexadecimal |
| `canal N`  | canal do Wi-Fi, de 1 a 13 (o celular cai e volta)      |
| `banda N`  | largura do canal: 20 ou 40 MHz                         |
| `tx N`     | potência máxima do Wi-Fi em dBm, de 2 a 20             |
| `pausa`    | encerra o stream e recusa novos, para a câmera esfriar |
| `continua` | volta a aceitar stream                                 |

No boot, e depois de cada comando, o serial mostra o estado:

```
wifi...: canal 1  banda 20 MHz  potencia 20.00 dBm
camera.: clock 20 MHz  forca do pino 0  PLL x90 /2  VCO 900 MHz  saida 180 MHz
```

Nos óculos a câmera e o cabo mudam de lugar, e a interferência muda junto: é
com a bancada que se confere de novo.

## Formato do stream

Estas linhas do `.ino` são um contrato com o `MjpegReader` do app:

```c
#define FRONTEIRA "123456789000000000000987654321"
static const char *TIPO_STREAM = "multipart/x-mixed-replace;boundary=" FRONTEIRA;
static const char *CABECALHO_PARTE =
    "--" FRONTEIRA "\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";
```

Dois detalhes que não são estilo:

- **A fronteira vem antes de cada quadro.** O `CameraWebServer` de fábrica manda
  depois; navegador tolera, mas é o contrário do que diz o formato multipart. O
  app aguenta os dois (há teste para isso), só que perde o primeiro quadro no
  formato de fábrica, porque não há fronteira antes dele para se ancorar.
- **O `Content-Length` é obrigatório.** Sem ele não há como saber onde o JPEG
  termina: os bytes da imagem podem conter qualquer coisa, inclusive algo
  parecido com a fronteira. O leitor recusa a parte em vez de decodificar um
  quadro cortado em silêncio.

## Gravar

A placa tem duas USB-C, **UART** e **USB OTG**. Use a **UART** para tudo:
gravar e ver o monitor serial.

Pela OTG, a porta COM some do Windows toda vez que a placa reinicia, e as
mensagens do boot — que saem uma vez só, no primeiro segundo — se perdem antes
de o monitor reconectar. Pela UART a porta continua lá: abra o monitor, aperte
**RESET**, e aparece tudo desde a primeira linha. É por isso que o
`compilar.ps1` usa `CDCOnBoot=default`, que manda o serial para essa porta.

Se a gravação falhar, entre em modo boot: segure **BOOT**, toque em **RESET**,
solte **BOOT** e grave de novo.

Depois:

```powershell
arduino-cli monitor -p COM5 -c baudrate=115200
```

O boot deve imprimir, em ordem: o motivo do último boot, o PID do sensor, e o
endereço da rede. Se aparecer

```
motivo do boot: 12  <<< BROWNOUT: falta corrente.
```

não é firmware: é o pico de corrente do Wi-Fi derrubando a alimentação. A
correção é o capacitor de 330µF e os 5,0 V do MT3608 — ver o README de cima —,
não desligar o detector de brownout.
