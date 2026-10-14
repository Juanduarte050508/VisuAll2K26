/* Data e tamanho do APK publicado, ao lado do botão de baixar.
   O arquivo é substituído a cada mudança no app (release "app" do GitHub,
   ver .github/workflows/android-build.yml), então a data mostra quão nova é a
   versão que a pessoa vai receber. Se o GitHub não responder, o texto
   simplesmente não aparece: o botão funciona do mesmo jeito. */
(function(){
    var alvos = document.querySelectorAll('[data-versao-apk]');
    if(!alvos.length || !window.fetch) return;

    fetch('https://api.github.com/repos/Juanduarte050508/VisuAll/releases/tags/app')
        .then(function(r){ return r.ok ? r.json() : null; })
        .then(function(release){
            if(!release || !release.assets) return;
            var apk = release.assets.filter(function(a){ return a.name === 'VisuAll.apk'; })[0];
            if(!apk) return;
            var data = new Date(apk.updated_at).toLocaleDateString('pt-BR');
            var mb = Math.round(apk.size / 1048576);
            alvos.forEach(function(el){
                el.textContent = 'Versão de ' + data + ' · ' + mb + ' MB';
            });
        })
        .catch(function(){});
})();
