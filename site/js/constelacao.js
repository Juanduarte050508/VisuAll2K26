/* ==========================================================================
   VisuAll — animações de enfeite
   1. Constelação: triângulos que se reúnem para desenhar o olho da logo,
      desviam do ponteiro e se dispersam quando o desenho sai da tela.
   2. Títulos: as palavras sobem uma a uma quando o título entra na tela.
   Nada aqui é essencial: sem este arquivo o site funciona igual, com a logo
   estática no lugar da constelação.
   ========================================================================== */

(function () {
    'use strict';

    var raiz = document.documentElement;

    raiz.classList.add('js');

    /* ----------------------------------------------------------------------
       1. Títulos — revelação palavra por palavra
       ---------------------------------------------------------------------- */

    function fatiarPalavras(no, contador) {
        Array.prototype.slice.call(no.childNodes).forEach(function (filho) {
            if (filho.nodeType === Node.TEXT_NODE) {
                var partes = filho.textContent.split(/(\s+)/);
                var fragmento = document.createDocumentFragment();

                partes.forEach(function (parte) {
                    if (parte === '') { return; }
                    if (/^\s+$/.test(parte)) {
                        fragmento.appendChild(document.createTextNode(' '));
                        return;
                    }
                    var janela = document.createElement('span');
                    var palavra = document.createElement('span');
                    janela.className = 'palavra';
                    palavra.textContent = parte;
                    palavra.style.setProperty('--i', contador.valor++);
                    janela.appendChild(palavra);
                    fragmento.appendChild(janela);
                });

                no.replaceChild(fragmento, filho);
            } else if (filho.nodeType === Node.ELEMENT_NODE) {
                fatiarPalavras(filho, contador);
            }
        });
    }

    var titulos = document.querySelectorAll('.hero h1, .secao-titulo');

    if ('IntersectionObserver' in window) {
        var vigiaTitulos = new IntersectionObserver(function (entradas) {
            entradas.forEach(function (entrada) {
                if (entrada.isIntersecting) {
                    entrada.target.classList.add('visivel');
                    vigiaTitulos.unobserve(entrada.target);
                }
            });
        }, { threshold: 0.3 });

        titulos.forEach(function (titulo) {
            fatiarPalavras(titulo, { valor: 0 });
            titulo.classList.add('titulo-animado');
            vigiaTitulos.observe(titulo);
        });
    }

    /* ----------------------------------------------------------------------
       2. Constelação
       ---------------------------------------------------------------------- */

    var tela = document.querySelector('.constelacao');
    var palco = document.querySelector('.hero-visual');
    if (!tela || !palco || !tela.getContext) { return; }

    var ctx = tela.getContext('2d');
    var CORES = ['#ffd34d', '#ffd34d', '#ffd34d', '#e8a31f', '#e8a31f', '#ffb829', '#ffffff', '#ffffff'];
    var MEIA_ALTURA = 0.56;          // proporção do olho: largura 2, altura 1.12

    var particulas = [];
    var largura = 0;
    var altura = 0;
    var escala = 1;                  // meio-olho em pixels
    var ponteiro = { x: -9999, y: -9999 };
    var dispersao = 0;               // 0 = olho formado, 1 = espalhado pela rolagem
    var visivel = true;
    var quadro = 0;

    function sorteio(min, max) {
        return min + Math.random() * (max - min);
    }

    /* Distância de um ponto a um segmento, para abrir as lâminas do diafragma */
    function distanciaAoSegmento(px, py, ax, ay, bx, by) {
        var dx = bx - ax;
        var dy = by - ay;
        var t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /* Sorteia um ponto do desenho, em coordenadas de -1 a 1 */
    function pontoDoOlho() {
        var sorte = Math.random();
        var x, y, angulo, raio;

        // Pálpebras: duas curvas que se encontram em ponta nos cantos
        if (sorte < 0.36) {
            x = sorteio(-1, 1);
            y = MEIA_ALTURA * (1 - x * x) * (Math.random() < 0.5 ? 1 : -1);
            return { x: x + sorteio(-0.02, 0.02), y: y + sorteio(-0.03, 0.03) };
        }

        // Traço interno: uma segunda amêndoa menor, levemente girada
        if (sorte < 0.58) {
            x = sorteio(-0.74, 0.74);
            y = 0.4 * (1 - (x / 0.74) * (x / 0.74)) * (Math.random() < 0.5 ? 1 : -1);
            var giro = -0.28;
            return {
                x: x * Math.cos(giro) - y * Math.sin(giro) + sorteio(-0.015, 0.015),
                y: x * Math.sin(giro) + y * Math.cos(giro) + sorteio(-0.015, 0.015)
            };
        }

        // Anel da íris
        if (sorte < 0.74) {
            angulo = sorteio(0, Math.PI * 2);
            raio = 0.3 + sorteio(-0.012, 0.012);
            return { x: Math.cos(angulo) * raio, y: Math.sin(angulo) * raio };
        }

        // Diafragma: disco preenchido, com seis cortes formando as lâminas
        for (var tentativa = 0; tentativa < 30; tentativa++) {
            angulo = sorteio(0, Math.PI * 2);
            raio = Math.sqrt(Math.random()) * 0.23;
            x = Math.cos(angulo) * raio;
            y = Math.sin(angulo) * raio;

            if (raio < 0.06) { continue; }

            var livre = true;
            for (var k = 0; k < 6; k++) {
                var a = (k / 6) * Math.PI * 2;
                var b = a + 1.9;
                var d = distanciaAoSegmento(x, y,
                    Math.cos(a) * 0.23, Math.sin(a) * 0.23,
                    Math.cos(b) * 0.075, Math.sin(b) * 0.075);
                if (d < 0.016) { livre = false; break; }
            }
            if (livre) { return { x: x, y: y }; }
        }
        return { x: x, y: y };
    }

    function criarParticulas() {
        var total = largura < 420 ? 520 : 950;
        var soltas = Math.round(total * 0.08);   // triângulos avulsos, fora do desenho
        particulas = [];

        for (var i = 0; i < total; i++) {
            var avulsa = i < soltas;
            var alvo = avulsa
                ? { x: sorteio(-1.5, 1.5), y: sorteio(-1.5, 1.5) }
                : pontoDoOlho();
            var direcao = sorteio(0, Math.PI * 2);

            particulas.push({
                ax: alvo.x,
                ay: alvo.y,
                x: sorteio(0, largura),
                y: sorteio(0, altura),
                vx: 0,
                vy: 0,
                // para onde ela foge quando a página rola
                fx: Math.cos(direcao) * sorteio(0.4, 1.6),
                fy: Math.sin(direcao) * sorteio(0.4, 1.6),
                tamanho: avulsa ? sorteio(4, 9) : sorteio(2.2, 5.2),
                giro: sorteio(0, Math.PI * 2),
                velGiro: sorteio(-0.012, 0.012),
                mola: sorteio(0.012, 0.045),
                fase: sorteio(0, Math.PI * 2),
                alfa: avulsa ? sorteio(0.15, 0.4) : sorteio(0.45, 1),
                cor: CORES[Math.floor(Math.random() * CORES.length)]
            });
        }
    }

    function redimensionar() {
        var caixa = tela.getBoundingClientRect();
        var densidade = Math.min(window.devicePixelRatio || 1, 2);

        // Ainda sem layout (CSS carregando): tenta de novo quando o tamanho mudar
        if (caixa.width < 2 || caixa.height < 2) { return; }
        if (Math.abs(caixa.width - largura) < 1 && Math.abs(caixa.height - altura) < 1) { return; }

        largura = caixa.width;
        altura = caixa.height;
        tela.width = Math.round(largura * densidade);
        tela.height = Math.round(altura * densidade);
        ctx.setTransform(densidade, 0, 0, densidade, 0, 0);
        escala = palco.getBoundingClientRect().width * 0.36;

        if (!particulas.length) { criarParticulas(); }
    }

    function desenharTriangulo(p) {
        var t = p.tamanho;
        ctx.save();
        ctx.translate(p.x, p.y);
        ctx.rotate(p.giro);
        ctx.globalAlpha = p.alfa;
        ctx.strokeStyle = p.cor;
        ctx.beginPath();
        ctx.moveTo(0, -t);
        ctx.lineTo(t * 0.87, t * 0.5);
        ctx.lineTo(-t * 0.87, t * 0.5);
        ctx.closePath();
        ctx.stroke();
        ctx.restore();
    }

    function animar(agora) {
        quadro = requestAnimationFrame(animar);
        if (!visivel) { return; }

        var cx = largura / 2;
        var cy = altura / 2;
        var tempo = agora / 1000;
        var fuga = dispersao * escala * 1.6;

        ctx.clearRect(0, 0, largura, altura);
        ctx.lineWidth = 1;

        for (var i = 0; i < particulas.length; i++) {
            var p = particulas[i];

            // alvo = ponto do desenho + respiração leve + fuga pela rolagem
            var alvoX = cx + p.ax * escala + Math.cos(tempo * 0.6 + p.fase) * 3 + p.fx * fuga;
            var alvoY = cy + p.ay * escala + Math.sin(tempo * 0.7 + p.fase) * 3 + p.fy * fuga;

            p.vx += (alvoX - p.x) * p.mola;
            p.vy += (alvoY - p.y) * p.mola;

            // o ponteiro empurra os triângulos próximos
            var dx = p.x - ponteiro.x;
            var dy = p.y - ponteiro.y;
            var dist2 = dx * dx + dy * dy;
            if (dist2 < 12100 && dist2 > 0.01) {
                var dist = Math.sqrt(dist2);
                var forca = (1 - dist / 110) * 2.4;
                p.vx += (dx / dist) * forca;
                p.vy += (dy / dist) * forca;
            }

            p.vx *= 0.86;
            p.vy *= 0.86;
            p.x += p.vx;
            p.y += p.vy;
            p.giro += p.velGiro + (Math.abs(p.vx) + Math.abs(p.vy)) * 0.004;

            desenharTriangulo(p);
        }
    }

    /* O olho fica formado enquanto o desenho está no meio da tela e só se
       desfaz quando ele vai saindo por cima. Medir pela posição do desenho,
       e não pela rolagem da página, faz valer também no celular, onde ele
       fica abaixo do texto e só aparece depois de rolar. */
    function medirRolagem() {
        var caixa = palco.getBoundingClientRect();
        var centro = caixa.top + caixa.height / 2;
        var inicio = window.innerHeight * 0.3;
        var percurso = Math.max(1, caixa.height * 0.6);
        dispersao = Math.max(0, Math.min(1, (inicio - centro) / percurso));
    }

    // Ouve no palco inteiro: a tela de desenho fica por baixo de outros elementos
    palco.addEventListener('pointermove', function (evento) {
        var caixa = tela.getBoundingClientRect();
        ponteiro.x = evento.clientX - caixa.left;
        ponteiro.y = evento.clientY - caixa.top;
    });

    palco.addEventListener('pointerleave', function () {
        ponteiro.x = -9999;
        ponteiro.y = -9999;
    });

    palco.classList.add('constelacao-ativa');

    // A resolução do desenho precisa acompanhar o tamanho real na página.
    // Medir uma vez só não basta: se o CSS chegar depois do script, o desenho
    // fica na resolução padrão e o navegador o estica, borrando tudo.
    window.addEventListener('resize', redimensionar);
    window.addEventListener('resize', medirRolagem);
    window.addEventListener('load', redimensionar);
    if ('ResizeObserver' in window) {
        new ResizeObserver(redimensionar).observe(tela);
    }
    redimensionar();

    window.addEventListener('scroll', medirRolagem, { passive: true });
    medirRolagem();

    // Não gasta bateria desenhando fora da tela
    if ('IntersectionObserver' in window) {
        new IntersectionObserver(function (entradas) {
            visivel = entradas[0].isIntersecting;
        }).observe(tela);
    }

    document.addEventListener('visibilitychange', function () {
        if (document.hidden) {
            cancelAnimationFrame(quadro);
        } else {
            quadro = requestAnimationFrame(animar);
        }
    });

    quadro = requestAnimationFrame(animar);
})();
