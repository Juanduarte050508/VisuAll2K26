/* ==========================================================================
   VisuAll — carrossel da equipe
   Todos os integrantes ficam na tela ao mesmo tempo, em leque: o do meio é o
   maior, e os outros diminuem e esmaecem conforme se afastam do centro.
   Este arquivo só calcula a distância de cada cartão até o centro e a entrega
   ao CSS em duas variáveis (--d: distância, --s: lado). O visual
   está em CSS/body.css, seção "Equipe".
   Sem ele, a equipe aparece como uma fila que rola para o lado.
   ========================================================================== */

(function () {
    'use strict';

    var palco = document.querySelector('.grade-equipe');
    if (!palco) { return; }

    var cartoes = Array.prototype.slice.call(palco.querySelectorAll('.integrante'));
    var total = cartoes.length;
    if (total < 3) { return; }

    var metade = Math.floor(total / 2);
    var ativo = metade;                 // começa com o cartão do meio em destaque
    var posicoes = [];

    /* Posição de um cartão em relação ao centro, dando a volta: -2 … 0 … +2 */
    function posicao(indice) {
        return ((indice - ativo + total + metade) % total) - metade;
    }

    function criarBotao(classe, rotulo, texto) {
        var botao = document.createElement('button');
        botao.type = 'button';
        botao.className = classe;
        botao.setAttribute('aria-label', rotulo);
        if (texto) { botao.textContent = texto; }
        return botao;
    }

    var controles = document.createElement('div');
    var anterior = criarBotao('carrossel-seta', 'Integrante anterior', '←');
    var proximo = criarBotao('carrossel-seta', 'Próximo integrante', '→');
    var bolinhas = document.createElement('div');

    controles.className = 'carrossel-controles';
    bolinhas.className = 'carrossel-bolinhas';
    cartoes.forEach(function (cartao, i) {
        var nome = cartao.querySelector('h3').textContent.trim();
        var bolinha = criarBotao('carrossel-bolinha', 'Destacar ' + nome);
        bolinha.dataset.indice = i;
        bolinhas.appendChild(bolinha);
    });
    controles.appendChild(anterior);
    controles.appendChild(bolinhas);
    controles.appendChild(proximo);
    palco.insertAdjacentElement('afterend', controles);

    function posicionar(cartao, i, nova) {
        cartao.style.setProperty('--d', Math.abs(nova));           // distância ao centro
        cartao.style.setProperty('--s', Math.sign(nova));          // lado: -1, 0 ou 1
        cartao.classList.toggle('no-centro', nova === 0);
        posicoes[i] = nova;
    }

    function atualizar() {
        cartoes.forEach(function (cartao, i) {
            var nova = posicao(i);
            var deuAVolta = posicoes[i] !== undefined && Math.abs(nova - posicoes[i]) > metade;

            if (!deuAVolta) { posicionar(cartao, i, nova); return; }

            // quem sai por um lado e reaparece no outro some, troca de lugar
            // sem animação e volta a aparecer, em vez de atravessar a tela
            cartao.classList.add('dando-volta');
            window.setTimeout(function () {
                cartao.classList.add('sem-transicao');
                posicionar(cartao, i, posicao(i));
                void cartao.offsetWidth;
                cartao.classList.remove('sem-transicao', 'dando-volta');
            }, 230);
        });

        Array.prototype.forEach.call(bolinhas.children, function (bolinha, i) {
            bolinha.setAttribute('aria-current', i === ativo ? 'true' : 'false');
        });
    }
    function destacar(indice) {
        ativo = (indice + total) % total;
        atualizar();
    }

    anterior.addEventListener('click', function () { destacar(ativo - 1); });
    proximo.addEventListener('click', function () { destacar(ativo + 1); });

    bolinhas.addEventListener('click', function (evento) {
        var bolinha = evento.target.closest('.carrossel-bolinha');
        if (bolinha) { destacar(Number(bolinha.dataset.indice)); }
    });

    // Tocar num cartão lateral o traz para o centro; o link do LinkedIn só
    // abre quando o cartão já está em destaque.
    palco.addEventListener('click', function (evento) {
        var cartao = evento.target.closest('.integrante');
        if (!cartao) { return; }

        // o clique que encerra um arrasto não conta como toque no cartão
        if (arrastou) { arrastou = false; evento.preventDefault(); return; }

        var indice = cartoes.indexOf(cartao);
        if (indice !== ativo) {
            evento.preventDefault();
            destacar(indice);
        }
    });

    // Chegar num cartão pelo teclado (Tab até o link) também o destaca
    palco.addEventListener('focusin', function (evento) {
        var cartao = evento.target.closest('.integrante');
        // só pelo teclado: com o mouse, o clique é quem decide
        if (cartao && evento.target.matches(':focus-visible')) { destacar(cartoes.indexOf(cartao)); }
    });

    palco.addEventListener('keydown', function (evento) {
        if (evento.key === 'ArrowLeft') { destacar(ativo - 1); }
        if (evento.key === 'ArrowRight') { destacar(ativo + 1); }
    });

    // Arrastar para o lado, no celular ou com o mouse
    var inicio = null;
    var arrastou = false;
    palco.addEventListener('pointerdown', function (evento) { inicio = evento.clientX; });
    palco.addEventListener('pointerup', function (evento) {
        if (inicio === null) { return; }
        var deslocamento = evento.clientX - inicio;
        inicio = null;
        if (Math.abs(deslocamento) > 40) {
            arrastou = true;
            destacar(ativo + (deslocamento < 0 ? 1 : -1));
        }
    });

    palco.classList.add('carrossel-vivo');
    atualizar();
})();
