# Compila (e opcionalmente grava) o firmware dos oculos.
#
#   .\compilar.ps1                 so compila
#   .\compilar.ps1 -Porta COM5     compila e grava na placa
#   .\compilar.ps1 -Limpo          recompila do zero
#
# Existe por causa da linha de opcoes da placa. Ela e comprida e uma das
# opcoes -- PSRAM=opi -- e o erro classico desta placa: a N16R8 tem PSRAM
# octal, e compilar com o padrao (desligada) gera um firmware que SOBE, mas
# que so consegue um buffer de imagem na RAM interna. O sintoma nao e um erro
# de compilacao: e o stream engasgando, que manda procurar na antena, na
# bateria, no cabo -- em tudo, menos aqui.
#
# O firmware avisa no monitor serial se subir sem PSRAM.

param(
    [string]$Porta = "",
    [switch]$Limpo,
    # Teste de radio: a camera nao liga e o /stream manda quadros gerados na
    # propria placa. Ver TESTE_SEM_CAMERA no .ino. Depois, grave de novo sem.
    [switch]$SemCamera
)

$ErrorActionPreference = "Stop"

# O sketch e esta propria pasta: o Arduino exige que o .ino tenha o nome da
# pasta em que esta, e por isso o arquivo se chama firmware.ino.
$sketch = $PSScriptRoot

# A IDE 2.x traz o arduino-cli embutido; usar o dela evita pedir mais uma
# instalacao a quem so quer compilar.
$cli = Get-Command arduino-cli -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $cli) {
    $embutido = "$env:LOCALAPPDATA\Programs\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe"
    if (Test-Path $embutido) { $cli = $embutido }
}
if (-not $cli) {
    Write-Error "arduino-cli nao encontrado. Instale a Arduino IDE 2.x ou o arduino-cli."
}

# ESP32-S3-CAM N16R8: 16MB de flash, 8MB de PSRAM octal.
$fqbn = "esp32:esp32:esp32s3:" + (@(
    "PSRAM=opi",                    # octal: e o que a N16R8 tem
    "FlashSize=16M",
    "FlashMode=qio",
    "PartitionScheme=huge_app",     # 3MB pro app; camera + Wi-Fi nao cabem no padrao de 1,2MB
    # Serial pela porta UART (a do chip conversor), e nao pela USB OTG.
    #
    # A placa tem duas USB-C. Com "cdc" o serial ia pela OTG, que e a USB do
    # proprio ESP32-S3 -- e essa porta SOME do Windows toda vez que a placa
    # reinicia. As mensagens do boot (camera achada ou nao, PSRAM, brownout)
    # saem uma vez so, no primeiro segundo, e se perdiam antes de o monitor
    # serial reconectar. Pela UART a porta COM continua la enquanto a placa
    # reinicia: abre o monitor, aperta RESET, e aparece tudo desde a primeira
    # linha. De bonus, as mensagens da ROM, de antes do nosso codigo, tambem
    # saem por ela.
    "CDCOnBoot=default"
) -join ",")

Write-Host "sketch: $sketch"
Write-Host "placa.: $fqbn"
Write-Host ""

$argumentos = @("compile", "--fqbn", $fqbn, "--warnings", "all")
if ($Limpo) { $argumentos += "--clean" }
if ($SemCamera) {
    # compiler.cpp.extra_flags vem vazio do core. build.extra_flags, nao: e onde
    # a placa ja poe o CDCOnBoot, e sobrescrever apagaria.
    $argumentos += @("--build-property", "compiler.cpp.extra_flags=-DTESTE_SEM_CAMERA=1")
    Write-Host "modo..: TESTE SEM CAMERA (depois grave de novo sem -SemCamera)"
    Write-Host ""
}
$argumentos += $sketch

& $cli @argumentos
if ($LASTEXITCODE -ne 0) { Write-Error "falhou a compilacao" }

if ($Porta -ne "") {
    Write-Host ""
    Write-Host "gravando em $Porta..."
    & $cli upload --fqbn $fqbn --port $Porta $sketch
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "Se nao gravou: segure BOOT, toque em RESET, solte BOOT e rode de novo."
        Write-Error "falhou a gravacao"
    }
    Write-Host ""
    Write-Host "Pra ver o monitor serial:  $cli monitor -p $Porta -c baudrate=115200"
}
