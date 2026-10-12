/* ==========================================================================
   VisuAll — vídeo sob demanda
   A página mostra só a capa do vídeo. O player do YouTube, que é pesado, só
   é carregado quando a pessoa aperta o play.
   Sem este arquivo, a capa continua sendo um link que abre o vídeo no YouTube.
   ========================================================================== */

(function () {
    'use strict';

    var capa = document.querySelector('.video-capa[data-video]');
    if (!capa) { return; }

    // aberto direto do arquivo, o YouTube recusa o player embutido (erro 153):
    // nesse caso o link comum abre o vídeo em outra aba
    if (window.location.protocol === 'file:') { return; }

    capa.addEventListener('click', function (evento) {
        evento.preventDefault();

        var player = document.createElement('iframe');
        player.src = 'https://www.youtube-nocookie.com/embed/' + capa.dataset.video + '?autoplay=1';
        player.title = 'Demonstração do aplicativo VisuAll';
        player.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
        player.referrerPolicy = 'strict-origin-when-cross-origin';
        player.allowFullscreen = true;

        capa.replaceWith(player);
        player.focus();
    });
})();
