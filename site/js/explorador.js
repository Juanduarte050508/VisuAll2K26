/* ==========================================================================
   VisuAll — simulador do app
   Guarda o estado do aplicativo (tela, modo, flash, zoom, texto reconhecido,
   histórico...) e o reflete em atributos data-* da raiz .app; o visual de cada
   estado está em CSS/app.css. Cada botão do celular tem um data-acao, tratado
   na tabela ACOES, e uma explicação na tabela TEXTOS, mostrada ao lado.
   ========================================================================== */

(function () {
    'use strict';

    // o simulador é o .app dentro do explorador (há uma miniatura estática no hero)
    var app = document.querySelector('.explorador .app');
    if (!app) { return; }

    var explorador = app.closest('.explorador');
    var info = explorador.querySelector('.x-info');
    var $ = function (seletor) { return app.querySelector(seletor); };

    /* ----------------------------------------------------------------------
       Estado
       ---------------------------------------------------------------------- */

    var estado = {
        tela: 'camera',          // camera | libras | resposta
        flash: 'auto',           // auto | on | off
        formato: '4:3',          // 4:3 | 16:9
        timer: 0,                // 0 | 3 | 10 (segundos)
        grade: false,
        zoom: '1',
        captura: 'foto',         // foto | video | pro
        gravando: false,
        fotos: 0,
        modo: 'alfabeto',        // alfabeto | corpo
        linhas: false,
        maoNoQuadro: true,
        oculos: false,
        bloqueado: false,
        historicoAberto: false,
        reconhecido: [],         // letras (alfabeto) ou palavras (corpo)
        historico: [
            { hora: '11:58', texto: 'O computador ajuda a pessoa surda a conversar', tipo: 'libras' },
            { hora: '11:57', texto: 'JOV', tipo: 'libras' },
            { hora: '11:57', texto: 'Z', tipo: 'libras' }
        ]
    };

    /* O que o app "reconhece" enquanto a mão está no quadro */
    var ROTEIRO = {
        alfabeto: ['V', 'I', 'S', 'U', 'A', 'L', 'L'],
        corpo: ['computador', 'ajuda', 'pessoa', 'surda', 'conversar']
    };

    var FLASH = { auto: ['A', 'on'], on: ['', 'off'], off: ['', 'auto'] };
    var relogios = { reconhecer: 0, toast: 0, gravar: 0, segurar: 0 };
    var segurou = false;

    /* ----------------------------------------------------------------------
       Explicações mostradas ao lado do celular
       ---------------------------------------------------------------------- */

    var TEXTOS = {
        'inicio':           ['Câmera', 'Comece por aqui', 'Toque em qualquer botão: ele reage como no aplicativo, e a explicação aparece aqui.', 'Comece pelo botão redondo amarelo, no canto de cima.'],
        'flash':            ['Câmera', 'Flash', 'Alterna entre automático, ligado e desligado.'],
        'formato':          ['Câmera', 'Formato da imagem', 'Troca a proporção entre 4:3 e 16:9. Repare no visor mudando de altura.'],
        'timer':            ['Câmera', 'Timer', 'Define uma contagem regressiva antes do disparo: desligado, 3 ou 10 segundos.'],
        'grade':            ['Câmera', 'Grade', 'Mostra linhas-guia em terços para ajudar no enquadramento.'],
        'zoom':             ['Câmera', 'Zoom', 'Afasta ou aproxima a imagem: 0.6x, 1x, 2x ou 5x.'],
        'captura':          ['Câmera', 'Foto, vídeo e Pro', 'Escolhe o modo de captura. Em vídeo, o botão de disparo fica vermelho.'],
        'galeria':          ['Câmera', 'Galeria', 'Abre as fotos e os vídeos já capturados.'],
        'disparo':          ['Câmera', 'Disparo', 'Tira a foto ou começa e encerra a gravação do vídeo.'],
        'inverter':         ['Câmera', 'Trocar câmera', 'Alterna entre a câmera frontal e a traseira.'],
        'abrir-libras':     ['Modo Libras', 'Você abriu o modo Libras', 'O atalho de acessibilidade abre o reconhecimento de sinais direto da câmera, sem instalar outro aplicativo.', 'Toque no visor para tirar a mão do quadro, ou em LINHAS para ver os pontos rastreados.'],
        'voltar':           ['Câmera', 'Voltar', 'Volta para a câmera comum.'],
        'linhas':           ['Modo Libras', 'Linhas: on / off', 'Liga ou desliga a marcação dos pontos detectados no corpo, no rosto e nas mãos. São os pontos que o MediaPipe rastreia.'],
        'mao':              ['Modo Libras', 'Enquadramento', 'Quando a mão sai da área da câmera, o quadro fica vermelho e o app avisa que não consegue captar o sinal.'],
        'lixeira':          ['Modo Libras', 'Lixeira', 'Um toque apaga a última letra (ou palavra, no modo Corpo). Segurar apaga a frase toda.'],
        'alfabeto':         ['Modo Libras', 'Alfabeto', 'Reconhece a soletração letra por letra, de A a Z, incluindo as letras feitas com movimento.'],
        'corpo':            ['Modo Corpo', 'Corpo', 'Reconhece cinco palavras-chave pelos pontos do corpo, do rosto e das mãos: computador, ajuda, pessoa, surda e conversar.'],
        'oculos':           ['Óculos', 'Câmera dos óculos', 'Troca a câmera do celular pela câmera dos óculos (ESP32), para a leitura acontecer com as mãos livres. O cadeado aparece ao lado.'],
        'oculos-fora':      ['Modo Libras', 'Câmera do celular', 'A leitura volta a ser feita pela câmera do celular.'],
        'cadeado':          ['Óculos', 'Tela bloqueada', 'O cadeado bloqueia a tela para evitar toques acidentais e poupar bateria: o celular vai para o bolso e os óculos leem os sinais sozinhos.', 'Toque na tela para deslizar e desbloquear.'],
        'desbloquear':      ['Óculos', 'Desbloqueado', 'Deslizar para cima devolve o controle da tela.'],
        'historico':        ['Histórico', 'Histórico da conversa', 'Tudo o que foi traduzido fica registrado, com o horário de cada frase. As respostas de quem ouve aparecem com contorno dourado.', 'Toque na alça no alto da folha para fechar.'],
        'fechar-historico': ['Modo Libras', 'Histórico fechado', 'A folha desce e a câmera volta a aparecer.'],
        'resposta':         ['Resposta', 'Tela de resposta', 'Aqui quem ouve responde para a pessoa surda ler: digitando no campo do meio ou falando.', 'O campo é de verdade: toque nele e escreva.'],
        'falar':            ['Resposta', 'Falar', 'Ativa o microfone e transforma a fala em texto. Aqui no site a fala é simulada.'],
        'limpar':           ['Resposta', 'Limpar', 'Apaga o texto digitado ou falado.'],
        'pronto':           ['Resposta', 'Pronto', 'Confirma a resposta e a deixa na tela para leitura. Ela também entra no histórico.'],
        'pronto-vazio':     ['Resposta', 'Pronto', 'Escreva ou use FALAR antes de confirmar a resposta.'],
        'virar':            ['Resposta', 'Virar a tela', 'Deita a tela e aumenta a letra, para a resposta ser lida com facilidade. É assim que fica no aplicativo:', '', '../images/app/resposta-ampliada.jpg', 'Tela de resposta do VisuAll na horizontal, com a letra ampliada'],
        'fechar-resposta':  ['Modo Libras', 'Fechar', 'Sai da resposta e volta para a captura pela câmera.'],
        'atalho':           ['Atalho', 'Direto para a tela', 'Os atalhos acima levam a cada parte do app. Dentro do celular, os botões fazem o mesmo caminho que no aplicativo.']
    };

    /* Escreve uma explicação num cartão (o de verdade ou a cópia de medição) */
    function preencher(cartao, t) {
        cartao.querySelector('.x-info-rotulo').textContent = t[0];
        cartao.querySelector('h3').textContent = t[1];
        cartao.querySelector('.x-info-texto').textContent = t[2];
        var dica = cartao.querySelector('.x-info-dica');
        dica.textContent = t[3] || '';
        dica.hidden = !t[3];

        // algumas explicações trazem uma captura do app (t[4] = arquivo, t[5] = descrição)
        var imagem = cartao.querySelector('.x-info-imagem');
        imagem.hidden = !t[4];
        if (t[4] && cartao === info) { imagem.src = t[4]; imagem.alt = t[5]; }
    }

    function explicar(chave) {
        var t = TEXTOS[chave];
        if (!t || !info) { return; }
        preencher(info, t);
        info.classList.remove('trocando');
        void info.offsetWidth;                // recomeça a animação de entrada
        info.classList.add('trocando');
    }

    /* No celular a explicação fica acima do aparelho. Para o aparelho não
       subir nem descer a cada toque, o cartão ganha de uma vez a altura da
       explicação mais longa: mede todas numa cópia invisível e fixa a maior.
       No desktop o cartão fica ao lado e não precisa disso. */
    var telaEstreita = window.matchMedia('(max-width: 900px)');
    var larguraMedida = 0;

    function fixarAltura() {
        if (!telaEstreita.matches) { info.style.minHeight = ''; larguraMedida = 0; return; }
        if (info.offsetWidth === larguraMedida) { return; }
        larguraMedida = info.offsetWidth;

        var copia = info.cloneNode(true);
        var maior = 0;
        copia.removeAttribute('aria-live');
        copia.setAttribute('aria-hidden', 'true');
        copia.classList.remove('trocando');
        copia.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;min-height:0;width:' + larguraMedida + 'px';
        info.parentNode.appendChild(copia);
        Object.keys(TEXTOS).forEach(function (chave) {
            preencher(copia, TEXTOS[chave]);
            maior = Math.max(maior, copia.offsetHeight);
        });
        copia.remove();
        info.style.minHeight = maior + 'px';
    }

    /* ----------------------------------------------------------------------
       Do estado para a tela
       ---------------------------------------------------------------------- */

    function alternar(atributo, ligado) {
        if (ligado) { app.setAttribute(atributo, ''); } else { app.removeAttribute(atributo); }
    }

    function pressionar(seletor, ligado) {
        var el = $(seletor);
        if (el) { el.setAttribute('aria-pressed', ligado ? 'true' : 'false'); }
    }

    function aviso() {
        if (estado.oculos) { return 'PROCURANDO OCULOS'; }
        if (!estado.maoNoQuadro) { return estado.modo === 'corpo' ? 'ENQUADRE CORPO E MAO' : 'MAO FORA DO QUADRO'; }
        return 'PRONTO - FAÇA O SINAL';
    }

    function desenhar() {
        app.dataset.tela = estado.tela;
        app.dataset.formato = estado.formato;
        app.dataset.captura = estado.captura;
        app.dataset.mao = estado.maoNoQuadro ? 'dentro' : 'fora';
        alternar('data-grade', estado.grade);
        alternar('data-gravando', estado.gravando);
        alternar('data-linhas', estado.linhas);
        alternar('data-oculos', estado.oculos);
        alternar('data-bloqueado', estado.bloqueado);
        alternar('data-historico', estado.historicoAberto);
        app.style.setProperty('--zoom', estado.zoom);

        // câmera
        var flash = $('.cam-flash');
        flash.dataset.modo = estado.flash;
        flash.querySelector('b').textContent = FLASH[estado.flash][0];
        $('[data-acao="formato"]').textContent = estado.formato;
        $('[data-acao="timer"]').textContent = estado.timer ? estado.timer + 's' : 'OFF';
        pressionar('[data-acao="timer"]', estado.timer > 0);
        pressionar('[data-acao="grade"]', estado.grade);
        app.querySelectorAll('[data-acao="zoom"]').forEach(function (b) {
            b.setAttribute('aria-pressed', b.dataset.valor === estado.zoom ? 'true' : 'false');
        });
        app.querySelectorAll('[data-acao="captura"]').forEach(function (b) {
            b.setAttribute('aria-pressed', b.dataset.valor === estado.captura ? 'true' : 'false');
        });
        if (!estado.gravando) { $('.cam-etiqueta').textContent = estado.captura === 'video' ? 'VÍDEO' : estado.captura.toUpperCase(); }
        $('.cam-galeria b').textContent = estado.fotos || '';

        // modo Libras
        $('.lib-linhas').textContent = 'LINHAS: ' + (estado.linhas ? 'ON' : 'OFF');
        pressionar('.lib-linhas', estado.linhas);
        $('.lib-titulo').textContent = estado.modo === 'corpo' ? 'Modo Corpo' : 'Modo Libras';
        pressionar('[data-acao="alfabeto"]', estado.modo === 'alfabeto');
        pressionar('[data-acao="corpo"]', estado.modo === 'corpo');
        pressionar('[data-acao="oculos"]', estado.oculos);
        $('.lib-aviso').textContent = aviso();
        $('.lib-traducao').textContent = estado.reconhecido.join(estado.modo === 'corpo' ? ' ' : '');

        // histórico
        var lista = $('.folha-lista');
        lista.textContent = '';
        estado.historico.forEach(function (item) {
            var li = document.createElement('li');
            var hora = document.createElement('time');
            var texto = document.createElement('p');
            li.dataset.tipo = item.tipo;
            hora.textContent = item.hora;
            texto.textContent = item.texto;
            li.appendChild(hora);
            li.appendChild(texto);
            lista.appendChild(li);
        });
        $('.folha-total').textContent = estado.historico.length + ' mensagens';
        $('.app-folha').inert = !estado.historicoAberto;

        // atalhos fora do celular
        var atual = estado.tela === 'libras'
            ? (estado.oculos ? 'oculos' : (estado.modo === 'corpo' ? 'corpo' : 'libras'))
            : estado.tela;
        explorador.querySelectorAll('[data-atalho]').forEach(function (b) {
            b.setAttribute('aria-pressed', b.dataset.atalho === atual ? 'true' : 'false');
        });

        agendarReconhecimento();
    }

    /* ----------------------------------------------------------------------
       Comportamentos com tempo
       ---------------------------------------------------------------------- */

    function agora() {
        var d = new Date();
        return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    }

    function guardarNoHistorico(texto, tipo) {
        estado.historico.unshift({ hora: agora(), texto: texto, tipo: tipo });
    }

    /* Enquanto a mão está no quadro, o app vai "lendo" o roteiro do modo atual */
    function agendarReconhecimento() {
        window.clearTimeout(relogios.reconhecer);

        var roteiro = ROTEIRO[estado.modo];
        var lendo = estado.tela === 'libras' && estado.maoNoQuadro && !estado.oculos &&
                    !estado.bloqueado && !estado.historicoAberto;
        if (!lendo || estado.reconhecido.length >= roteiro.length) { return; }

        relogios.reconhecer = window.setTimeout(function () {
            estado.reconhecido.push(roteiro[estado.reconhecido.length]);
            if (estado.reconhecido.length === roteiro.length) {
                guardarNoHistorico(estado.reconhecido.join(estado.modo === 'corpo' ? ' ' : ''), 'libras');
            }
            desenhar();
        }, estado.modo === 'corpo' ? 1500 : 1000);
    }

    function toast(texto) {
        var el = $('.lib-toast');
        el.textContent = texto;
        el.classList.add('visivel');
        window.clearTimeout(relogios.toast);
        relogios.toast = window.setTimeout(function () { el.classList.remove('visivel'); }, 1600);
    }

    function animar(classe, duracao) {
        app.classList.remove(classe);
        void app.offsetWidth;
        app.classList.add(classe);
        window.setTimeout(function () { app.classList.remove(classe); }, duracao);
    }

    function fotografar() {
        animar('disparando', 500);
        estado.fotos++;
        desenhar();
    }

    function contagemRegressiva(segundos, aoFim) {
        var visor = $('.cam-contagem');
        var passos = segundos > 3 ? [10, 5, 3, 2, 1] : [3, 2, 1];   // os 10s são abreviados aqui
        (function proximo() {
            if (!passos.length || estado.tela !== 'camera') { visor.textContent = ''; if (passos.length === 0) { aoFim(); } return; }
            visor.textContent = passos.shift();
            window.setTimeout(proximo, 650);
        })();
    }

    function gravar(ligar) {
        var etiqueta = $('.cam-etiqueta');
        var segundos = 0;
        window.clearInterval(relogios.gravar);
        estado.gravando = ligar;
        if (ligar) {
            etiqueta.textContent = 'REC 00:00';
            relogios.gravar = window.setInterval(function () {
                segundos++;
                etiqueta.textContent = 'REC 00:' + ('0' + segundos).slice(-2);
            }, 1000);
        } else {
            estado.fotos++;
        }
        desenhar();
    }

    function irParaLibras(modo, oculos) {
        estado.tela = 'libras';
        estado.oculos = !!oculos;
        estado.historicoAberto = false;
        estado.bloqueado = false;
        if (modo && modo !== estado.modo) { estado.modo = modo; estado.reconhecido = []; }
    }

    /* ----------------------------------------------------------------------
       Ações dos botões. Cada função pode devolver a chave da explicação a
       mostrar; sem retorno, vale a explicação com o mesmo nome da ação.
       ---------------------------------------------------------------------- */

    var ACOES = {
        'flash': function () { estado.flash = FLASH[estado.flash][1]; },
        'formato': function () { estado.formato = estado.formato === '4:3' ? '16:9' : '4:3'; },
        'timer': function () { estado.timer = estado.timer === 0 ? 3 : (estado.timer === 3 ? 10 : 0); },
        'grade': function () { estado.grade = !estado.grade; },
        'zoom': function (botao) { estado.zoom = botao.dataset.valor; },
        'captura': function (botao) {
            if (estado.gravando) { gravar(false); }
            estado.captura = botao.dataset.valor;
        },
        'galeria': function () {},
        'inverter': function () { animar('virando', 520); },
        'disparo': function () {
            if (estado.captura === 'video') { gravar(!estado.gravando); return; }
            if (estado.timer) { contagemRegressiva(estado.timer, fotografar); return; }
            fotografar();
        },
        'abrir-libras': function () { irParaLibras(); },
        'voltar': function () {
            estado.tela = 'camera';
            estado.oculos = false;
        },
        'linhas': function () { estado.linhas = !estado.linhas; },
        'mao': function () {
            if (estado.oculos) { return 'oculos'; }
            estado.maoNoQuadro = !estado.maoNoQuadro;
        },
        'lixeira': function () {
            if (segurou) { estado.reconhecido = []; } else { estado.reconhecido.pop(); }
        },
        'alfabeto': function () {
            if (estado.modo !== 'alfabeto') { estado.modo = 'alfabeto'; estado.reconhecido = []; toast('Modo Libras ativo'); }
        },
        'corpo': function () {
            if (estado.modo !== 'corpo') { estado.modo = 'corpo'; estado.reconhecido = []; toast('Modo corpo ativo'); }
        },
        'oculos': function () {
            estado.oculos = !estado.oculos;
            return estado.oculos ? 'oculos' : 'oculos-fora';
        },
        'cadeado': function () { estado.bloqueado = true; },
        'desbloquear': function () {
            var tela = $('.app-bloqueio');
            tela.classList.add('saindo');
            window.setTimeout(function () {
                tela.classList.remove('saindo');
                estado.bloqueado = false;
                desenhar();
            }, 340);
        },
        'historico': function () { estado.historicoAberto = true; },
        'fechar-historico': function () {
            estado.historicoAberto = false;
            return estado.tela === 'resposta' ? 'resposta' : 'fechar-historico';
        },
        'resposta': function () { estado.tela = 'resposta'; },
        'fechar-resposta': function () { estado.tela = 'libras'; },
        'virar': function () {},          // só mostra a captura da tela deitada, ao lado
        'limpar': function () {
            var campo = $('.res-texto');
            campo.value = '';
            campo.readOnly = false;
        },
        'falar': function () {
            var campo = $('.res-texto');
            app.setAttribute('data-ouvindo', '');
            campo.readOnly = false;
            campo.value = '';
            campo.placeholder = 'Ouvindo...';
            window.setTimeout(function () {
                app.removeAttribute('data-ouvindo');
                campo.placeholder = 'Toque para escrever, ou use FALAR';
                campo.value = 'Oi! Posso te ajudar?';
            }, 1700);
        },
        'pronto': function () {
            var campo = $('.res-texto');
            var texto = campo.value.trim();
            if (!texto) { return 'pronto-vazio'; }
            campo.readOnly = true;                 // vira tela de leitura
            campo.blur();
            guardarNoHistorico(texto, 'resposta');
        }
    };

    var ATALHOS = {
        camera: function () { estado.tela = 'camera'; estado.oculos = false; },
        libras: function () { irParaLibras('alfabeto', false); },
        corpo: function () { irParaLibras('corpo', false); },
        oculos: function () { irParaLibras(null, true); },
        resposta: function () { estado.tela = 'resposta'; }
    };

    /* ----------------------------------------------------------------------
       Eventos
       ---------------------------------------------------------------------- */

    app.addEventListener('click', function (evento) {
        var botao = evento.target.closest('[data-acao]');
        if (!botao || !app.contains(botao)) { return; }

        var acao = botao.dataset.acao;
        if (!ACOES[acao]) { return; }

        // os toques da demonstração automática não contam como toque da pessoa
        if (evento.isTrusted) { assumir(); }
        var chave = ACOES[acao](botao) || acao;
        segurou = false;
        desenhar();
        explicar(chave);
    });

    // Segurar a lixeira apaga a frase toda
    app.addEventListener('pointerdown', function (evento) {
        if (!evento.target.closest('[data-acao="lixeira"]')) { return; }
        segurou = false;
        relogios.segurar = window.setTimeout(function () { segurou = true; }, 550);
    });

    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (tipo) {
        app.addEventListener(tipo, function () { window.clearTimeout(relogios.segurar); });
    });

    // Deslizar para cima na tela bloqueada também desbloqueia
    var inicioDoToque = null;
    $('.app-bloqueio').addEventListener('pointerdown', function (evento) { inicioDoToque = evento.clientY; });
    $('.app-bloqueio').addEventListener('pointerup', function (evento) {
        if (inicioDoToque !== null && inicioDoToque - evento.clientY > 30) { $('.app-bloqueio').click(); }
        inicioDoToque = null;
    });

    // Voltar a escrever depois do PRONTO libera o campo de novo
    $('.res-texto').addEventListener('pointerdown', function () { this.readOnly = false; });

    explorador.querySelectorAll('[data-atalho]').forEach(function (botao) {
        botao.addEventListener('click', function () {
            if (estado.gravando) { gravar(false); }
            estado.historicoAberto = false;
            estado.bloqueado = false;
            ATALHOS[botao.dataset.atalho]();
            assumir();
            desenhar();
            explicar({ camera: 'voltar', libras: 'alfabeto', corpo: 'corpo', oculos: 'oculos', resposta: 'resposta' }[botao.dataset.atalho]);
        });
    });

    /* ----------------------------------------------------------------------
       Demonstração automática
       Quando o explorador aparece na tela, o celular faz sozinho um caminho
       curto (abre o modo Libras, lê a palavra, mostra as linhas e volta), como
       um aparelho de vitrine. Qualquer toque da pessoa encerra a demonstração
       e devolve o controle. Um aviso no alto da explicação diz o que está
       acontecendo.
       ---------------------------------------------------------------------- */

    var convite = document.createElement('p');
    var demo = { relogios: [], rodando: false, feita: false };
    var dedo = document.createElement('span');

    convite.className = 'x-info-aviso';
    convite.textContent = 'Toque nos botões do celular, é de verdade';
    info.insertBefore(convite, info.firstChild);

    dedo.className = 'app-toque';
    dedo.setAttribute('aria-hidden', 'true');
    app.appendChild(dedo);

    function pararDemo() {
        demo.relogios.forEach(window.clearTimeout);
        demo.relogios = [];
        demo.rodando = false;
    }

    /* A pessoa tocou: a demonstração para e o aviso some de vez */
    function assumir() {
        pararDemo();
        demo.feita = true;
        convite.hidden = true;
        app.classList.add('tocado');
    }

    function tocarSozinho(seletor) {
        var botao = $(seletor);
        if (!botao || !demo.rodando) { return; }
        var caixa = botao.getBoundingClientRect();
        var base = app.getBoundingClientRect();
        dedo.style.left = (caixa.left + caixa.width / 2 - base.left) + 'px';
        dedo.style.top = (caixa.top + caixa.height / 2 - base.top) + 'px';
        dedo.classList.remove('tocando');
        void dedo.offsetWidth;
        dedo.classList.add('tocando');
        demo.relogios.push(window.setTimeout(function () { botao.click(); }, 260));
    }

    function iniciarDemo() {
        if (demo.feita || demo.rodando) { return; }
        demo.rodando = true;
        demo.feita = true;
        convite.textContent = 'Demonstração automática · toque para assumir';

        var passos = [
            [1400, '[data-acao="abrir-libras"]'],
            [5600, '[data-acao="linhas"]'],
            [9800, '[data-acao="linhas"]'],
            [11200, '[data-acao="voltar"]']
        ];
        passos.forEach(function (passo) {
            demo.relogios.push(window.setTimeout(function () { tocarSozinho(passo[1]); }, passo[0]));
        });

        // fim: volta ao começo e troca o aviso por um convite
        demo.relogios.push(window.setTimeout(function () {
            pararDemo();
            estado.reconhecido = [];
            estado.linhas = false;
            desenhar();
            explicar('inicio');
            convite.textContent = 'Sua vez: toque nos botões do celular';
        }, 12600));
    }

    explorador.addEventListener('pointerdown', function () { if (demo.rodando) { assumir(); } });
    explorador.addEventListener('keydown', function () { if (demo.rodando) { assumir(); } });

    var poucoMovimento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!poucoMovimento && 'IntersectionObserver' in window) {
        var vigia = new IntersectionObserver(function (entradas) {
            if (entradas[0].isIntersecting) {
                vigia.disconnect();
                iniciarDemo();
            }
        }, { threshold: 0.7 });
        vigia.observe(app);
    }

    fixarAltura();
    window.addEventListener('resize', fixarAltura);
    window.addEventListener('load', function () { larguraMedida = 0; fixarAltura(); });
    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(function () { larguraMedida = 0; fixarAltura(); });
    }

    desenhar();
})();
