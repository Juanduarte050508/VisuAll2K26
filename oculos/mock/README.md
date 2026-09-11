# Mock: a placa de mentira

**Mock** quer dizer imitação. O `mock.py` imita a câmera dos óculos: roda no
PC, pega a webcam e manda a imagem pela rede **do mesmo jeito que a placa
manda**. Para o app não há diferença nenhuma; muda só o endereço.

Serve para mexer no app sem a placa na mão: sem gravar firmware, sem bateria e
sem a câmera esquentando.

## O que tem nesta pasta

| Arquivo | O que é |
|---|---|
| `mock.py` | o mock |
| `README.md` | este guia |

Se aparecer uma pasta `__pycache__` aqui, é cache do Python. Pode apagar; ela
nunca vai para o git, porque está no `.gitignore`.

## Instalar (uma vez)

Precisa de Python 3.9 ou mais novo, e de duas bibliotecas:

```powershell
pip install opencv-python numpy
```

Se o `pip` não for reconhecido: `py -m pip install opencv-python numpy`.

## Rodar

Da pasta `VisuAll`:

```powershell
python oculos\mock\mock.py                  # webcam; sem webcam, gera a imagem
python oculos\mock\mock.py --sintetico      # gera a imagem, sem usar câmera
python oculos\mock\mock.py video.mp4        # um vídeo em loop
python oculos\mock\mock.py --porta 8081     # outra porta, se a 8080 estiver ocupada
```

A saída certa é assim (os endereços do seu PC vão ser outros):

```
abrindo a webcam...
Mock ESP32-CAM  --  fonte: webcam
  320x240 a 15 quadros/s, JPEG qualidade 88

  no proprio PC:  http://localhost:8080/
  no celular:     http://192.168.15.10:8080/

Ctrl+C pra parar.
```

- **`http://localhost:8080/`** abre uma página de teste no navegador do PC, com
  a imagem.
- **`/stream`** no fim do endereço é o vídeo que o app consome, por exemplo
  `http://192.168.15.10:8080/stream`.

O gerador (`--sintetico`) mostra uma barra andando e um contador de quadros.
Não serve para reconhecer sinais, porque não tem mão nenhuma, mas mostra na hora
se o vídeo travou.

## Usar no app

1. Libere a porta no firewall do Windows, uma vez só, num PowerShell aberto
   **como administrador**:

   ```powershell
   New-NetFirewallRule -DisplayName "VisuAll mock ESP32" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
   ```

2. Deixe o celular no **mesmo Wi-Fi** do PC.
3. No app, entre no modo Libras e toque no **ícone de olho**. Digite o endereço
   do mock com `/stream` no fim e toque em **Conectar**.

O passo a passo completo, com o que fazer quando não funciona, está no
[guia dos óculos](../README.md#4-o-mock-a-placa-de-mentira-no-pc).

## O que ele imita, e o que não

| | Mock | Placa |
|---|---|---|
| Endereço | `http://IP-do-PC:8080/stream` | `http://192.168.4.1/stream` |
| Rede | a do PC | a própria (`VisuAll-Oculos`) |
| Imagem | 320x240, JPEG | 320x240, JPEG |
| Quadros por segundo | 15 | uns 22 |
| Formato do vídeo | multipart, fronteira antes de cada quadro, com `Content-Length` | igual |
| Página de teste em `/` | sim | sim |
| Quem pode assistir | vários ao mesmo tempo | um de cada vez |
| Calor, Wi-Fi fraco, bateria | não imita | — |

O que precisa ser igual está no topo do `mock.py` (`LARGURA`, `ALTURA`,
`BOUNDARY`) e tem par no `firmware.ino` e no `MjpegReader` do app. Mudar de um
lado só faz o app ser testado contra uma coisa e rodar contra outra.

## Quando der errado

- **"nao entrega imagem" ao abrir:** outro programa está com a webcam. Quase
  sempre é outra cópia do próprio mock ainda aberta; também pode ser Teams, Meet
  ou o app Câmera. Para achar a cópia esquecida:

  ```powershell
  Get-NetTCPConnection -LocalPort 8080 | Select-Object State, OwningProcess
  ```

  e depois `Stop-Process -Id <numero> -Force`.
- **Demora para abrir a webcam:** no Windows o mock abre a webcam pelo
  DirectShow, que levou 1,5 s num PC onde o modo padrão levou 98 s. Se o
  DirectShow recusar, ele avisa e tenta o modo lento.
- **O navegador do PC mostra a imagem, o celular não:** firewall ou redes
  diferentes. Veja [Quando der errado](../README.md#9-quando-der-errado) no guia
  dos óculos.
