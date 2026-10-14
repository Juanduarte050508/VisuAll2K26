# Avaliações do site

O formulário **Avalie o VisuAll** (seção Contato do site) grava cada resposta
numa planilha Google da equipe. Não tem servidor nosso no meio: um Apps Script
ligado à planilha recebe o envio e escreve a linha.

```
site (js/avaliacao.js)  ──POST──►  Apps Script (Code.gs)  ──►  planilha "Avaliações"
                                                          └──►  e-mail de aviso
site "Quem já avaliou"  ◄──GET───  Apps Script  ◄── só as linhas com "Publicar" marcado
```

Enquanto isso não estiver configurado, o formulário continua funcionando: o
botão abre o app de e-mail da pessoa com a avaliação já escrita para
visuall2k26@gmail.com.

## Configurar (uma vez só, uns 5 minutos)

Use a conta **visuall2k26@gmail.com**, para a planilha ficar com a equipe.

1. Crie uma planilha nova no Google Planilhas (ex.: `VisuAll - Avaliações`).
2. Menu **Extensões → Apps Script**.
3. Apague o que estiver no editor, cole o conteúdo de [Code.gs](Code.gs) e salve.
4. **Implantar → Nova implantação** → engrenagem → **App da Web**:
   - Executar como: **Eu**
   - Quem pode acessar: **Qualquer pessoa** (não "Qualquer pessoa com conta
     Google": aí o site leva 403 e nenhuma avaliação chega)
5. Autorize quando o Google pedir (ele avisa que o app "não foi verificado":
   **Avançado → Acessar**). É o seu próprio script.
6. Copie a **URL do app da web** (termina em `/exec`).
7. Cole essa URL em [`../js/avaliacao.js`](../js/avaliacao.js), na linha
   `var ENDERECO = '';`, e suba para a `main`.

A aba **Avaliações** aparece sozinha na primeira resposta, com o cabeçalho
`Data | Nome | Contato | Nota | Mensagem | Página | Publicar`. A média das notas é uma
fórmula na própria planilha: `=MÉDIA(D2:D)`.

## Aprovar uma avaliação para o site

Toda avaliação chega com a caixa **Publicar** desmarcada, e o e-mail de aviso
traz o link da planilha. Marque a caixa e ela aparece em "Quem já avaliou" na
próxima vez que alguém abrir o site; desmarque e ela some. O site mostra nome,
nota e mensagem das 12 aprovadas mais recentes. O contato nunca sai da
planilha.

Para conferir o que o site está recebendo, abra a URL `/exec` no navegador:
aparece o JSON com as aprovadas.

## Mudou o Code.gs?

**Implantar → Gerenciar implantações → editar → Versão: Nova versão.** Assim a
URL continua a mesma e o site não precisa mudar.

## Dados pessoais

O formulário pede nome e, se a pessoa quiser, e-mail ou telefone. O site avisa
que o contato serve só para responder à avaliação. Não compartilhe a planilha
fora da equipe.
