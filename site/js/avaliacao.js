/* Formulário "Avalie o VisuAll" e avaliações aprovadas (seção Contato).

   As respostas vão para uma planilha Google da equipe, por um Apps Script
   publicado como app da web. Como configurar: site/avaliacao/README.md.
   Cada resposta entra com "Publicar" desmarcado; só as que a equipe marca
   aparecem em "Quem já avaliou".

   Se ENDERECO estiver vazio, o envio abre o app de e-mail da pessoa com
   tudo já preenchido para visuall2k26@gmail.com, então nenhuma avaliação se
   perde. */
(function(){
    // URL do app da web (termina em /exec)
    var ENDERECO = 'https://script.google.com/macros/s/AKfycbz260NGIILIpovMa0tGFyQFsJQi0IKUiFDEyacSgCvXvMA2zEprQ1loBmd7qmuTjmLg/exec';
    var EMAIL = 'visuall2k26@gmail.com';

    mostraAprovadas();

    var form = document.querySelector('[data-avaliacao]');
    if(!form) return;
    var status = form.querySelector('.form-status');
    var botao = form.querySelector('button[type="submit"]');

    function avisa(texto, erro){
        status.textContent = texto;
        status.classList.toggle('form-status--erro', !!erro);
    }

    function porEmail(dados){
        var corpo = 'Nota: ' + dados.get('nota') + ' de 5\n' +
                    'Nome: ' + dados.get('nome') + '\n' +
                    'Contato: ' + (dados.get('contato') || '-') + '\n\n' +
                    dados.get('mensagem');
        window.location.href = 'mailto:' + EMAIL +
            '?subject=' + encodeURIComponent('Avaliação do VisuAll') +
            '&body=' + encodeURIComponent(corpo);
        avisa('Abrimos o seu app de e-mail com a avaliação pronta. É só enviar.');
    }

    form.addEventListener('submit', function(evento){
        evento.preventDefault();
        if(!form.reportValidity()) return;

        var dados = new FormData(form);
        // Robô preencheu o campo escondido: finge que deu certo e não envia
        if(dados.get('site')){
            form.reset();
            avisa('Obrigado pela avaliação!');
            return;
        }
        if(!ENDERECO){
            porEmail(dados);
            return;
        }

        dados.append('pagina', location.href);
        botao.disabled = true;
        avisa('Enviando...');

        // no-cors: o Apps Script responde de outro domínio e não libera a
        // leitura da resposta. Se a rede falhar, o fetch rejeita e caímos
        // no catch; se chegou, a linha já está na planilha.
        fetch(ENDERECO, { method: 'POST', mode: 'no-cors', body: new URLSearchParams(dados) })
            .then(function(){
                form.reset();
                avisa('Obrigado pela avaliação! Ela aparece aqui depois que a equipe ler.');
            })
            .catch(function(){
                avisa('Não conseguimos enviar agora. Tente de novo ou escreva para ' + EMAIL + '.', true);
            })
            .then(function(){ botao.disabled = false; });
    });

    // "Quem já avaliou": pede à planilha só as aprovadas. Sem nenhuma, ou se a
    // planilha não responder, o bloco continua escondido.
    function mostraAprovadas(){
        var bloco = document.querySelector('[data-depoimentos]');
        if(!bloco || !ENDERECO || !window.fetch) return;
        var lista = bloco.querySelector('.depoimentos-lista');

        fetch(ENDERECO)
            .then(function(r){ return r.ok ? r.json() : null; })
            .then(function(dados){
                if(!dados || !dados.avaliacoes || !dados.avaliacoes.length) return;
                dados.avaliacoes.forEach(function(av){
                    lista.appendChild(cartao(av));
                });
                montaFiltro(bloco, lista);
                bloco.hidden = false;
            })
            .catch(function(){});
    }

    // Filtro por estrelas: "Todas" e um botão para cada nota que existe na
    // lista. Com todas as avaliações na mesma nota, filtrar não muda nada e
    // os botões nem aparecem.
    function montaFiltro(bloco, lista){
        var filtro = bloco.querySelector('.depoimentos-filtro');
        var cartoes = lista.querySelectorAll('.depoimento');
        var notas = [];
        cartoes.forEach(function(c){
            if(notas.indexOf(c.dataset.nota) < 0) notas.push(c.dataset.nota);
        });
        if(!filtro || notas.length < 2) return;
        notas.sort().reverse();

        function botao(valor, texto, rotulo){
            var b = document.createElement('button');
            b.type = 'button';
            b.dataset.nota = valor;
            b.textContent = texto;
            if(rotulo) b.setAttribute('aria-label', rotulo);
            b.setAttribute('aria-pressed', valor === '' ? 'true' : 'false');
            filtro.appendChild(b);
        }
        botao('', 'Todas');
        notas.forEach(function(n){
            botao(n, n + ' ★', n === '1' ? '1 estrela' : n + ' estrelas');
        });

        filtro.addEventListener('click', function(evento){
            var escolhido = evento.target.closest('button');
            if(!escolhido) return;
            filtro.querySelectorAll('button').forEach(function(b){
                b.setAttribute('aria-pressed', b === escolhido ? 'true' : 'false');
            });
            cartoes.forEach(function(c){
                c.hidden = escolhido.dataset.nota !== '' && c.dataset.nota !== escolhido.dataset.nota;
            });
        });
        filtro.hidden = false;
    }

    // Nome, estrelas e mensagem. Tudo por textContent: o texto vem de
    // visitantes e nunca vira HTML.
    function cartao(av){
        var nota = Math.max(1, Math.min(5, Math.round(av.nota) || 5));
        var item = document.createElement('li');
        item.className = 'depoimento';
        item.dataset.nota = String(nota);

        var autor = document.createElement('p');
        autor.className = 'depoimento-autor';
        autor.textContent = av.nome;

        var estrelas = document.createElement('p');
        estrelas.className = 'depoimento-nota';
        estrelas.setAttribute('aria-label', 'Nota ' + nota + ' de 5');
        estrelas.textContent = '★★★★★'.slice(0, nota) + '☆☆☆☆☆'.slice(0, 5 - nota);

        var texto = document.createElement('p');
        texto.className = 'depoimento-texto';
        texto.textContent = av.mensagem;

        item.append(autor, estrelas, texto);
        return item;
    }
})();
