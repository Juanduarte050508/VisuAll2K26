// Firmware dos oculos: ESP32-S3-CAM + OV5640.
//
// Faz uma coisa so -- cria a propria rede Wi-Fi e serve a camera em MJPEG no
// mesmo formato que o oculos/mock/mock.py serve. Do lado do app nada muda:
// so o endereco.
//
// A rede e criada PELA PLACA (SoftAP). Nao depende de roteador, de internet
// nem de nada instalado no lugar -- que e o requisito do projeto: funcionar em
// qualquer lugar, inclusive onde nao ha Wi-Fi nenhum. O celular entra nesta
// rede e fala direto com a placa.
//
// Gravar precisa da placa; compilar, nao. Ver oculos/firmware/README.md.

#include <WiFi.h>
#include "driver/gpio.h"
#include "esp_camera.h"
#include "esp_http_server.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "img_converters.h"

// Qual placa. Os pinos vem do camera_pins.h do proprio core do ESP32, copiado
// para esta pasta sem alteracao -- digitar numero de pino a mao e como se
// perdem tardes inteiras achando que a camera queimou.
#define CAMERA_MODEL_ESP32S3_EYE
#include "camera_pins.h"

// Teste de radio sem camera. Com 1 a camera nem liga -- sem clock, sem calor --
// e o /stream manda quadros gerados na propria placa, no mesmo formato. Separa
// "o Wi-Fi entre a placa e o celular nao funciona" de "a camera atrapalha o
// Wi-Fi". O compilar.ps1 liga com -SemCamera. Ver "Diagnostico".
#ifndef TESTE_SEM_CAMERA
#define TESTE_SEM_CAMERA 0
#endif

// --- A rede que a placa cria ----------------------------------------------
static const char *REDE_NOME = "VisuAll-Oculos";
// WPA2 exige 8 caracteres no minimo. Sem senha, qualquer um por perto entra na
// rede e ve a camera.
static const char *REDE_SENHA = "visuall2026";
// Fixo pra o endereco no app nunca mudar: http://192.168.4.1/stream
static const IPAddress IP_PLACA(192, 168, 4, 1);
static const IPAddress MASCARA(255, 255, 255, 0);
// 20 MHz de largura, e nao os 40 que o AP usa por padrao: o stream precisa de
// uns 100 KB/s, e canal largo so pega mais interferencia. A bancada troca os
// dois sem regravar. Ver "Bancada".
static int canal_wifi = 1;
static wifi_bandwidth_t banda_wifi = WIFI_BW_HT20;

// --- O formato, que TEM de bater com o app --------------------------------
//
// Nao mexa nestas linhas sem mexer no MjpegReader do app.
//
// A fronteira vem ANTES de cada quadro, nao depois. O CameraWebServer de
// fabrica manda depois; navegador tolera, mas e o contrario do que diz o
// formato multipart, e o app foi testado contra este aqui.
//
// O Content-Length nao e enfeite: o leitor do app exige. Sem ele nao ha como
// saber onde termina o JPEG, porque os bytes da imagem podem conter qualquer
// coisa, inclusive algo parecido com a fronteira.
#define FRONTEIRA "123456789000000000000987654321"
static const char *TIPO_STREAM = "multipart/x-mixed-replace;boundary=" FRONTEIRA;
static const char *CABECALHO_PARTE =
    "--" FRONTEIRA "\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static httpd_handle_t servidor = NULL;

// --- Camera ---------------------------------------------------------------

// Clock que a placa manda pra camera, e a forca do pino dele.
//
// A forca e o que fez o Wi-Fi funcionar com a camera ligada. Com a que o
// driver deixa (2), o sinal do celular chegava otimo e mesmo assim o envio
// travava depois de um ou dois quadros, toda vez; com 0, a mais fraca, o
// stream fluiu na hora, a 22 quadros/s e com o envio mais lento em 8 ms. A
// explicacao provavel: pino forte faz borda rapida, e borda rapida e cheia de
// harmonicos -- o 121o de 20 MHz cai em 2420 MHz, dentro do canal 1, a um ou
// dois centimetros da antena. Borda mais lenta, menos harmonico.
//
// A bancada troca os dois sem regravar. Ver "Bancada".
static int xclk_mhz = 20;
static int forca_xclk = 0;

// PLL da camera: multiplicador e divisor. So a OV5640 usa.
//
// Pra JPEG em QVGA o driver usa 180 e 4, e com clock de 20 MHz isso poe o VCO
// do PLL em 1800 MHz; o driver do Linux pra esta mesma camera avisa que ele nao
// pode passar de 1 GHz. Metade dos dois da o mesmo clock de saida -- os mesmos
// 22 quadros/s -- com o VCO em 900 MHz.
//
// O motivo e o calor (ver "Calor"): com clock de 10 MHz a imagem demorou 50 s
// pra dividir em vez de 32 s, mas ali caiam juntos o VCO e o ritmo. Assim cai
// so o VCO -- e deu os mesmos 50 s, a 22 quadros/s. "pll 180 4" na bancada
// volta ao do driver.
static int pll_multiplicador = 90;
static int pll_divisor = 2;

// Os outros argumentos sao os que o driver usa pra JPEG, na ordem do sensor_t:
// bypass, multiplicador, divisor, root_2x, pre-divisor, seld5, pclk manual e
// divisor do pclk.
static void aplica_pll(sensor_t *s) {
  s->set_pll(s, 0, pll_multiplicador, pll_divisor, 0, 2, 2, 1, 4);
}

static bool inicia_camera() {
  camera_config_t cfg = {};
  cfg.ledc_channel = LEDC_CHANNEL_0;
  cfg.ledc_timer = LEDC_TIMER_0;
  cfg.pin_d0 = Y2_GPIO_NUM;
  cfg.pin_d1 = Y3_GPIO_NUM;
  cfg.pin_d2 = Y4_GPIO_NUM;
  cfg.pin_d3 = Y5_GPIO_NUM;
  cfg.pin_d4 = Y6_GPIO_NUM;
  cfg.pin_d5 = Y7_GPIO_NUM;
  cfg.pin_d6 = Y8_GPIO_NUM;
  cfg.pin_d7 = Y9_GPIO_NUM;
  cfg.pin_xclk = XCLK_GPIO_NUM;
  cfg.pin_pclk = PCLK_GPIO_NUM;
  cfg.pin_vsync = VSYNC_GPIO_NUM;
  cfg.pin_href = HREF_GPIO_NUM;
  cfg.pin_sccb_sda = SIOD_GPIO_NUM;
  cfg.pin_sccb_scl = SIOC_GPIO_NUM;
  cfg.pin_pwdn = PWDN_GPIO_NUM;
  cfg.pin_reset = RESET_GPIO_NUM;
  cfg.xclk_freq_hz = xclk_mhz * 1000000;
  cfg.frame_size = FRAMESIZE_QVGA;    // 320x240, o mesmo do mock e do app
  cfg.pixel_format = PIXFORMAT_JPEG;  // comprimido no proprio chip da camera
  cfg.jpeg_quality = 12;              // 0-63, MENOR e melhor
  cfg.grab_mode = CAMERA_GRAB_LATEST;
  cfg.fb_location = CAMERA_FB_IN_PSRAM;
  cfg.fb_count = 2;

  if (!psramFound()) {
    // Sem PSRAM cabe um buffer so, e na RAM interna. A N16R8 tem 8MB, entao
    // cair aqui e sinal de PSRAM desligada na hora de compilar -- vale mais
    // avisar que seguir devagar em silencio.
    Serial.println("AVISO: PSRAM nao encontrada; confira a placa escolhida na IDE");
    cfg.fb_location = CAMERA_FB_IN_DRAM;
    cfg.fb_count = 1;
    cfg.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  }

  esp_err_t erro = esp_camera_init(&cfg);
  if (erro != ESP_OK) {
    Serial.printf("esp_camera_init falhou: 0x%x (%s)\n", erro, esp_err_to_name(erro));
    Serial.println("  0x105 (ESP_ERR_NOT_FOUND): a camera nao respondeu.");
    Serial.println("  Quase sempre e o modelo de placa errado la em cima,");
    Serial.println("  ou o cabo flat mal encaixado / invertido.");
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  // O PID diz QUAL sensor respondeu. Se a camera inicia mas a imagem sai
  // preta, esta linha separa "pino errado" de "sensor diferente do esperado".
  // Valores de sensor.h do esp32-camera; o OV5640 da placa real respondeu 0x5640.
  Serial.printf("sensor detectado: PID 0x%02x  (OV5640=0x5640, OV2640=0x26, OV3660=0x3660)\n",
                s->id.PID);

  // Sem espelho e sem virar de cabeca pra baixo.
  //
  // Nos oculos prontos quem os usa e o ouvinte, e a camera aponta pra quem
  // sinaliza: ela ve outra pessoa de frente, que e a mesma geometria da camera
  // traseira do celular. O app nao espelha essa, e o reconhecimento ja foi
  // conferido assim.
  s->set_hmirror(s, 0);
  s->set_vflip(s, 0);

  // Depois do esp_camera_init, que e quem configura o pino do clock e o PLL.
  gpio_set_drive_capability((gpio_num_t)XCLK_GPIO_NUM, (gpio_drive_cap_t)forca_xclk);
  if (s->id.PID == OV5640_PID) aplica_pll(s);
  return true;
}

// --- Calor ----------------------------------------------------------------
//
// A OV5640 esquenta muito. No primeiro teste na placa real, depois de uns seis
// minutos ligada no USB, ficou quente a ponto de nao dar pra manter o dedo
// atras dela -- e sem ninguem recebendo imagem: o sensor liga no
// esp_camera_init e fica capturando sem parar dali em diante.
//
// Nos oculos a camera vai perto do rosto. Entao ela so trabalha enquanto tem
// alguem recebendo o stream, e no resto do tempo fica em power down por
// software (registrador 0x3008 da OV5640, bit 6). Esta placa nao liga pino de
// PWDN na camera (PWDN_GPIO_NUM = -1), entao o registrador e o unico jeito.
//
// Isto tambem vira um teste: se a camera esquentar muito mesmo DORMINDO, o
// calor nao e do trabalho do sensor, e sim de algum problema eletrico.
//
// Dormindo ela ficou fria. Transmitindo, ainda esquenta: partindo fria, em
// menos de um minuto a imagem se divide, metade esquerda clara e direita
// escura, e so volta ao normal esfriando. Ver o PLL em "Camera".
//
// So para a OV5640, que e a desta placa. Outros sensores tem o power down em
// outro registrador, e escrever 0x3008 neles seria mexer em coisa aleatoria.
static const int OV5640_SYSTEM_CTROL0 = 0x3008;
static const int OV5640_DORMINDO = 0x42;  // bit 6 ligado: power down
static const int OV5640_ACORDADA = 0x02;  // valor padrao do registrador

static bool sensor_e_ov5640() {
  sensor_t *s = esp_camera_sensor_get();
  return s && s->id.PID == OV5640_PID;
}

static void sensor_dorme() {
  if (!sensor_e_ov5640()) return;
  sensor_t *s = esp_camera_sensor_get();
  s->set_reg(s, OV5640_SYSTEM_CTROL0, 0xff, OV5640_DORMINDO);
  Serial.println("camera dormindo (ninguem recebendo imagem)");
}

static void sensor_acorda() {
  if (!sensor_e_ov5640()) return;
  sensor_t *s = esp_camera_sensor_get();
  s->set_reg(s, OV5640_SYSTEM_CTROL0, 0xff, OV5640_ACORDADA);
  Serial.println("camera acordada (stream comecou)");
  // Tempo pra exposicao e balanco de branco se ajustarem, e descarte dos
  // quadros que ficaram nos buffers de antes de dormir. O app espera ate 5s
  // pelo primeiro quadro (MjpegClient.TIMEOUT_LEITURA_MS), entao sobra folga.
  delay(300);
  for (int i = 0; i < 3; i++) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);
  }
}

// --- Diagnostico ----------------------------------------------------------
//
// No primeiro teste com o celular a imagem apareceu e congelou, e a conexao
// caiu duas vezes em meio minuto -- sem nenhum erro da camera. Imagem
// congelada tem causas que pedem consertos diferentes, e o serial precisa
// separar uma da outra:
//
//   - o Wi-Fi parou de escoar: um envio fica parado ate o servidor desistir
//     (send_wait_timeout do HTTPD_DEFAULT_CONFIG, 5s por tentativa). Sinal,
//     alimentacao ou o proprio celular;
//   - o celular fechou a conexao: o envio falha na hora;
//   - a placa manda normal e a imagem congela mesmo assim: o problema e do app.
//
// Enquanto transmite, sai uma linha a cada 5s com o ritmo e o envio mais
// lento; quando o stream acaba, qual dos fins foi e quanto passou antes dele.
// E enquanto o celular esta na rede, o loop() diz o sinal dele.
//
// No teste seguinte o sinal veio forte (-16 a -32 dBm) e mesmo assim o
// celular nao trocava dados com a placa. Dai o TESTE_SEM_CAMERA: com a camera
// desligada, sobra so o Wi-Fi.
static const int64_t JANELA_DIAGNOSTICO_US = 5000000;
static const int64_t ENVIO_DESISTIU_US = 4500000;  // perto dos 5s de uma tentativa

// Sinal do celular em dBm, ou 0 se ninguem esta na rede. A rede aceita um
// cliente so (ver setup), entao o primeiro da lista e o celular.
static int sinal_do_celular() {
  wifi_sta_list_t lista;
  if (esp_wifi_ap_get_sta_list(&lista) != ESP_OK || lista.num == 0) return 0;
  return lista.sta[0].rssi;
}

// Entrar e sair da rede nao aparece no stream: o celular pode sair da rede
// com o app ainda esperando quadro. E entrar nao basta: sem receber IP ele
// fica na rede sem conseguir falar com a placa.
static void avisa_rede(arduino_event_id_t evento, arduino_event_info_t info) {
  if (evento == ARDUINO_EVENT_WIFI_AP_STACONNECTED) {
    Serial.println("celular entrou na rede");
  } else if (evento == ARDUINO_EVENT_WIFI_AP_STAIPASSIGNED) {
    Serial.printf("celular recebeu o IP " IPSTR "\n", IP2STR(&info.wifi_ap_staipassigned.ip));
  } else if (evento == ARDUINO_EVENT_WIFI_AP_STADISCONNECTED) {
    Serial.printf("celular saiu da rede (motivo %u)\n",
                  (unsigned)info.wifi_ap_stadisconnected.reason);
  }
}

// Quadros do TESTE_SEM_CAMERA: uma faixa clara que atravessa a imagem. Andando
// na tela do celular, os quadros estao chegando; parada, o stream travou. O
// fundo tem textura pra o JPEG ter tamanho de foto, e os quadros saem a uns
// 25 por segundo: o Wi-Fi carrega o que carregaria com a camera.
static const int QUADROS_TESTE = 16;
static const int INTERVALO_TESTE_MS = 40;
static uint8_t *quadro_teste[QUADROS_TESTE];
static size_t tamanho_teste[QUADROS_TESTE];

static bool gera_quadros_de_teste() {
  const int largura = 320, altura = 240;  // o mesmo QVGA da camera
  size_t bytes_rgb = largura * altura * 3;
  uint8_t *rgb = (uint8_t *)ps_malloc(bytes_rgb);
  if (!rgb) return false;

  size_t total = 0;
  for (int q = 0; q < QUADROS_TESTE; q++) {
    int faixa = q * largura / QUADROS_TESTE;
    for (int y = 0; y < altura; y++) {
      for (int x = 0; x < largura; x++) {
        uint8_t v = 40 + ((x / 8 + y / 8) % 2) * 50 + (x * 31 + y * 17) % 23;
        if (x >= faixa && x < faixa + 24) v = 240;
        uint8_t *p = rgb + (y * largura + x) * 3;
        p[0] = p[1] = p[2] = v;
      }
    }
    if (!fmt2jpg(rgb, bytes_rgb, largura, altura, PIXFORMAT_RGB888, 50,
                 &quadro_teste[q], &tamanho_teste[q])) {
      free(rgb);
      return false;
    }
    total += tamanho_teste[q];
  }
  free(rgb);
  Serial.printf("teste sem camera: camera desligada, %d quadros de %u bytes em media\n",
                QUADROS_TESTE, (unsigned)(total / QUADROS_TESTE));
  return true;
}

// --- Bancada --------------------------------------------------------------
//
// O TESTE_SEM_CAMERA fechou a questao: com a camera desligada o stream fluiu
// 3 minutos seguidos, a 23 quadros/s e com o envio mais lento em 30 ms; com
// ela ligada trava em segundos, e ate com ela dormindo o celular nao pegava
// IP. A camera atrapalha o Wi-Fi. O suspeito era o clock que a placa manda pra
// ela: 20 MHz sem parar, a um ou dois centimetros da antena, ligado mesmo com
// a camera dormindo.
//
// Pra achar o ajuste sem regravar a cada tentativa, estes comandos chegam pelo
// monitor serial, um por linha, e valem ate a placa reiniciar:
//
//   xclk N     clock da camera, em MHz, de 6 a 27 (reinicia a camera)
//   forca N    forca do pino do clock: 0 (fraca) a 3 (forte)
//   pll M D    PLL da camera: multiplicador (4 a 252) e divisor (1 a 15)
//   reg HHHH   le um registrador da camera, endereco em hexadecimal
//   canal N    canal do Wi-Fi, 1 a 13 (o celular cai e volta)
//   banda N    largura do canal: 20 ou 40 MHz
//   tx N       potencia maxima do Wi-Fi, em dBm, de 2 a 20
//   pausa      encerra o stream e recusa novos, pra camera esfriar
//   continua   volta a aceitar stream
//
// Foi assim que apareceu a forca do pino (ver "Camera"): com a banda ja em
// 20 MHz o envio ainda travava em um ou dois quadros, e "forca 0" fez o stream
// fluir na hora. A bancada fica porque, nos oculos, a camera e o cabo mudam de
// lugar, e a interferencia muda junto.
static volatile bool interromper_stream = false;
static volatile bool pausado = false;
// Quem mexe na camera: o stream, enquanto transmite, e a bancada, pra
// reiniciar. Os dois ao mesmo tempo derrubariam a placa.
static SemaphoreHandle_t trava_camera = NULL;

static void informa_wifi() {
  wifi_bandwidth_t banda = WIFI_BW_HT20;
  int8_t potencia = 0;
  esp_wifi_get_bandwidth(WIFI_IF_AP, &banda);
  esp_wifi_get_max_tx_power(&potencia);
  // A potencia vem em quartos de dBm.
  Serial.printf("wifi...: canal %d  banda %d MHz  potencia %.2f dBm\n", canal_wifi,
                banda == WIFI_BW_HT40 ? 40 : 20, potencia / 4.0f);
}

// O PLL da OV5640 (ver "Camera"), lido direto dos registradores e nao das
// variaveis: aparece o que a camera usa de fato. 0x3036 e o multiplicador,
// 0x3035 o divisor no nibble de cima, 0x3037 o pre-divisor. As contas sao as
// do calc_sysclk do driver: o VCO e o clock dividido pelo pre-divisor e
// multiplicado; o clock de saida e o VCO dividido pelo divisor e por 2,5.
static void informa_camera() {
  gpio_drive_cap_t forca = GPIO_DRIVE_CAP_DEFAULT;
  gpio_get_drive_capability((gpio_num_t)XCLK_GPIO_NUM, &forca);
  sensor_t *s = esp_camera_sensor_get();
  if (!s) {
    Serial.printf("camera.: clock %d MHz  forca do pino %d\n", xclk_mhz, (int)forca);
    return;
  }
  static const float PRE_DIVISOR[] = {1, 1, 2, 3, 4, 1.5f, 6, 2.5f, 8};  // pll_pre_div2x_map
  int multiplicador = s->get_reg(s, 0x3036, 0xff);
  int divisor = s->get_reg(s, 0x3035, 0xff) >> 4;
  int pre = s->get_reg(s, 0x3037, 0x0f);
  float vco = xclk_mhz / PRE_DIVISOR[pre < 9 ? pre : 0] * multiplicador;
  float saida = divisor > 0 ? vco / divisor / 2.5f : 0;
  Serial.printf("camera.: clock %d MHz  forca do pino %d  PLL x%d /%d  VCO %.0f MHz  saida %.0f MHz\n",
                xclk_mhz, (int)forca, multiplicador, divisor, vco, saida);
}

static void reinicia_camera() {
  // O stream larga a camera no proximo quadro; se estiver com o envio
  // travado, pode levar os 10 s dos timeouts.
  interromper_stream = true;
  bool pegou = xSemaphoreTake(trava_camera, pdMS_TO_TICKS(15000)) == pdTRUE;
  interromper_stream = false;
  if (!pegou) {
    Serial.println("bancada: o stream nao largou a camera");
    return;
  }
  esp_camera_deinit();
  if (inicia_camera()) {
    sensor_dorme();
  } else {
    Serial.println("bancada: a camera nao voltou");
  }
  xSemaphoreGive(trava_camera);
  informa_camera();
}

static void atende_bancada() {
  if (!Serial.available()) return;
  String linha = Serial.readStringUntil('\n');
  linha.trim();
  if (linha.length() == 0) return;
  int espaco = linha.indexOf(' ');
  int espaco2 = espaco < 0 ? -1 : linha.indexOf(' ', espaco + 1);
  String comando = espaco < 0 ? linha : linha.substring(0, espaco);
  int valor = espaco < 0 ? -1 : linha.substring(espaco + 1).toInt();
  int valor2 = espaco2 < 0 ? -1 : linha.substring(espaco2 + 1).toInt();
  Serial.printf("bancada: %s\n", linha.c_str());
  sensor_t *s = esp_camera_sensor_get();

  if (comando == "xclk" && valor >= 6 && valor <= 27 && !TESTE_SEM_CAMERA) {
    xclk_mhz = valor;
    reinicia_camera();
  } else if (comando == "forca" && valor >= 0 && valor <= 3) {
    forca_xclk = valor;
    gpio_set_drive_capability((gpio_num_t)XCLK_GPIO_NUM, (gpio_drive_cap_t)valor);
    informa_camera();
  } else if (comando == "pll" && s && valor >= 4 && valor <= 252 && valor2 >= 1 &&
             valor2 <= 15) {
    // Nas variaveis, pra valer tambem depois de um "xclk".
    pll_multiplicador = valor;
    pll_divisor = valor2;
    aplica_pll(s);
    informa_camera();
  } else if (comando == "reg" && s && espaco > 0) {
    int endereco = (int)strtol(linha.substring(espaco + 1).c_str(), NULL, 16);
    Serial.printf("camera.: registrador 0x%04x = 0x%02x\n", endereco,
                  s->get_reg(s, endereco, 0xff));
  } else if (comando == "canal" && valor >= 1 && valor <= 13) {
    canal_wifi = valor;
    WiFi.softAP(REDE_NOME, REDE_SENHA, canal_wifi, 0, 1);
    esp_wifi_set_bandwidth(WIFI_IF_AP, banda_wifi);
    informa_wifi();
  } else if (comando == "banda" && (valor == 20 || valor == 40)) {
    banda_wifi = valor == 40 ? WIFI_BW_HT40 : WIFI_BW_HT20;
    esp_wifi_set_bandwidth(WIFI_IF_AP, banda_wifi);
    informa_wifi();
  } else if (comando == "tx" && valor >= 2 && valor <= 20) {
    esp_wifi_set_max_tx_power((int8_t)(valor * 4));
    informa_wifi();
  } else if (comando == "pausa") {
    pausado = true;
  } else if (comando == "continua") {
    pausado = false;
  } else {
    Serial.println("bancada: comando ou valor invalido");
  }
}

// --- HTTP -----------------------------------------------------------------

static esp_err_t pagina(httpd_req_t *req) {
  static const char *HTML =
      "<!doctype html><meta charset=\"utf-8\"><title>VisuAll Oculos</title>"
      "<body style=\"background:#111;color:#eee;font-family:sans-serif;text-align:center\">"
      "<h2>VisuAll &mdash; camera dos oculos</h2>"
      "<img src=\"/stream\" style=\"width:640px;image-rendering:pixelated\">"
      "<p>O app consome <code>/stream</code></p>";
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  return httpd_resp_send(req, HTML, HTTPD_RESP_USE_STRLEN);
}

// De onde vem cada quadro: da camera, ou dos quadros gerados do teste sem
// camera. Devolve null se nao veio nada. *fb e o que precisa voltar pra
// camera depois do envio; no teste fica null.
static const uint8_t *pega_quadro(size_t *tamanho, camera_fb_t **fb) {
  if (TESTE_SEM_CAMERA) {
    static unsigned proximo = 0;
    delay(INTERVALO_TESTE_MS);
    unsigned i = proximo++ % QUADROS_TESTE;
    *fb = NULL;
    *tamanho = tamanho_teste[i];
    return quadro_teste[i];
  }
  *fb = esp_camera_fb_get();
  if (!*fb) return NULL;
  *tamanho = (*fb)->len;
  return (*fb)->buf;
}

static esp_err_t transmite(httpd_req_t *req) {
  esp_err_t res = httpd_resp_set_type(req, TIPO_STREAM);
  if (res != ESP_OK) return res;
  httpd_resp_set_hdr(req, "Cache-Control", "no-cache");

  char cabecalho[96];
  // Contadores do diagnostico: os do stream inteiro e os da janela de 5s.
  int64_t inicio_stream = esp_timer_get_time();
  unsigned total_quadros = 0;
  unsigned quadros = 0;
  size_t bytes = 0;
  int64_t envio_mais_lento = 0;
  int64_t inicio_janela = inicio_stream;

  while (true) {
    if (interromper_stream || pausado) {
      Serial.println("stream interrompido pela bancada");
      return ESP_FAIL;
    }

    size_t tamanho;
    camera_fb_t *fb;
    const uint8_t *jpeg = pega_quadro(&tamanho, &fb);
    if (!jpeg) {
      Serial.println("esp_camera_fb_get devolveu nada; encerrando o stream");
      return ESP_FAIL;
    }

    size_t n = snprintf(cabecalho, sizeof(cabecalho), CABECALHO_PARTE, (unsigned)tamanho);
    int64_t antes = esp_timer_get_time();
    res = httpd_resp_send_chunk(req, cabecalho, n);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)jpeg, tamanho);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, "\r\n", 2);
    int64_t agora = esp_timer_get_time();
    int64_t envio = agora - antes;

    if (fb) esp_camera_fb_return(fb);

    // Erro aqui e o fim do stream. Falhar na hora e o celular fechando a
    // conexao -- que e tambem como termina quem sai do modo oculos. Ficar
    // parado ate o limite e o Wi-Fi que parou de escoar. Um printf so por
    // linha: o loop() tambem escreve no serial, e duas chamadas deixariam a
    // linha dele cair no meio desta.
    if (res != ESP_OK) {
      float antes_de_parar = (antes - inicio_stream) / 1e6f;
      if (envio >= ENVIO_DESISTIU_US) {
        Serial.printf("stream acabou: %u quadros em %.1f s, depois o envio ficou parado "
                      "%ld ms (o Wi-Fi parou de escoar)\n",
                      total_quadros, antes_de_parar, (long)(envio / 1000));
      } else {
        Serial.printf("stream acabou: %u quadros em %.1f s, e o celular fechou a conexao\n",
                      total_quadros, antes_de_parar);
      }
      return res;
    }

    total_quadros++;
    quadros++;
    bytes += tamanho;
    if (envio > envio_mais_lento) envio_mais_lento = envio;

    if (agora - inicio_janela >= JANELA_DIAGNOSTICO_US) {
      float segundos = (agora - inicio_janela) / 1e6f;
      Serial.printf("stream: %.1f quadros/s  %.0f KB/s  envio mais lento %ld ms\n",
                    quadros / segundos, bytes / 1024.0f / segundos,
                    (long)(envio_mais_lento / 1000));
      quadros = 0;
      bytes = 0;
      envio_mais_lento = 0;
      inicio_janela = agora;
    }
  }
}

// A camera so fica acordada enquanto alguem recebe imagem. Ver "Calor".
static esp_err_t stream(httpd_req_t *req) {
  if (pausado) {
    // Ver "Bancada". O app tenta de novo sozinho.
    httpd_resp_set_status(req, "503 Service Unavailable");
    return httpd_resp_send(req, "pausado", HTTPD_RESP_USE_STRLEN);
  }
  xSemaphoreTake(trava_camera, portMAX_DELAY);
  sensor_acorda();
  esp_err_t res = transmite(req);
  sensor_dorme();
  xSemaphoreGive(trava_camera);
  return res;
}

static void inicia_servidor() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  // O servidor atende numa tarefa so, e o stream nunca termina: enquanto um
  // cliente recebe imagem, nenhum outro pedido e atendido, nem a pagina de
  // teste. E por isso tambem que o stream() pode dormir a camera ao sair sem
  // atrapalhar outro cliente -- nao existe outro ao mesmo tempo. Na pratica:
  // feche o navegador antes de abrir o app.
  cfg.max_open_sockets = 4;

  if (httpd_start(&servidor, &cfg) != ESP_OK) {
    Serial.println("httpd_start falhou");
    return;
  }
  httpd_uri_t rota_raiz = {"/", HTTP_GET, pagina, NULL};
  httpd_uri_t rota_stream = {"/stream", HTTP_GET, stream, NULL};
  httpd_register_uri_handler(servidor, &rota_raiz);
  httpd_register_uri_handler(servidor, &rota_stream);
}

// --- Ligar ----------------------------------------------------------------

static void conta_por_que_reiniciou() {
  // Brownout e o defeito classico desta montagem: o pico de corrente do Wi-Fi
  // derruba a alimentacao e a placa reinicia sozinha, parecendo travamento ou
  // firmware ruim. Dito aqui, vira uma linha no monitor serial em vez de uma
  // tarde de investigacao. A correcao e o capacitor de 330uF, nao desligar o
  // detector de brownout.
  esp_reset_reason_t r = esp_reset_reason();
  Serial.printf("motivo do boot: %d", (int)r);
  if (r == ESP_RST_BROWNOUT) {
    Serial.print("  <<< BROWNOUT: falta corrente. Confira o capacitor de 330uF");
    Serial.print(" e os 5,0V do MT3608");
  }
  Serial.println();
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println();
  conta_por_que_reiniciou();
  trava_camera = xSemaphoreCreateMutex();

  if (TESTE_SEM_CAMERA) {
    // A camera fica desligada de proposito: sem clock e sem calor. Ver
    // "Diagnostico".
    if (!gera_quadros_de_teste()) {
      Serial.println("teste sem camera: nao deu pra gerar os quadros");
      return;
    }
  } else {
    if (!inicia_camera()) {
      Serial.println("sem camera; nada a servir");
      return;
    }
    // Ninguem esta recebendo imagem ainda. Ver "Calor".
    sensor_dorme();
  }

  WiFi.onEvent(avisa_rede);  // ver "Diagnostico"
  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(IP_PLACA, IP_PLACA, MASCARA);
  // max_connection 1: so o celular. Cada cliente a mais divide a banda de um
  // radio que ja e o gargalo.
  WiFi.softAP(REDE_NOME, REDE_SENHA, canal_wifi, 0 /* visivel */, 1 /* clientes */);
  // Depois do softAP: a largura so pode ser trocada com o Wi-Fi ligado.
  esp_wifi_set_bandwidth(WIFI_IF_AP, banda_wifi);
  // Sem isto o radio dorme entre pacotes e o stream engasga.
  WiFi.setSleep(false);

  inicia_servidor();

  Serial.println();
  Serial.printf("rede...: %s   senha: %s\n", REDE_NOME, REDE_SENHA);
  Serial.printf("no app.: http://%s/stream\n", WiFi.softAPIP().toString().c_str());
  Serial.printf("no nav.: http://%s/\n", WiFi.softAPIP().toString().c_str());
  informa_wifi();
  if (!TESTE_SEM_CAMERA) informa_camera();
}

void loop() {
  // O servidor HTTP roda na propria tarefa. Aqui ficam os comandos da bancada
  // e o sinal do celular, a cada 5 s enquanto ele esta na rede -- com ou sem
  // stream, porque no segundo teste o celular entrava e saia da rede sem
  // chegar a pedir imagem. Ver "Diagnostico" e "Bancada".
  atende_bancada();
  static unsigned long ultimo_sinal = 0;
  if (millis() - ultimo_sinal >= 5000) {
    ultimo_sinal = millis();
    int sinal = sinal_do_celular();
    if (sinal != 0) Serial.printf("sinal do celular: %d dBm\n", sinal);
  }
  delay(50);
}
