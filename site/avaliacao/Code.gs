/**
 * Recebe o formulário "Avalie o VisuAll" do site e grava uma linha na planilha.
 * Também entrega ao site as avaliações que a equipe aprovou (coluna Publicar).
 * Passo a passo de instalação: README.md nesta mesma pasta.
 */

var ABA = 'Avaliações';
var CABECALHO = ['Data', 'Nome', 'Contato', 'Nota', 'Mensagem', 'Página', 'Publicar'];
var COL_PUBLICAR = CABECALHO.indexOf('Publicar') + 1;
// Manda um e-mail para a conta dona da planilha a cada avaliação nova.
var AVISAR_POR_EMAIL = true;
// Quantas avaliações aprovadas o site mostra, das mais novas para as mais antigas.
var MAXIMO_NO_SITE = 12;

function doPost(e) {
  var p = (e && e.parameter) || {};

  // Campo escondido do formulário: só robô preenche
  if (p.site) return resposta({ ok: true });

  var nota = Number(p.nota);
  var nome = limpa(p.nome, 80);
  var mensagem = limpa(p.mensagem, 1000);
  if (!(nota >= 1 && nota <= 5) || !nome || !mensagem) return resposta({ ok: false });

  // Entra sempre desmarcada: nada aparece no site sem alguém da equipe aprovar
  var linha = [new Date(), nome, limpa(p.contato, 120), nota, mensagem, limpa(p.pagina, 200), false];

  var trava = LockService.getScriptLock();
  trava.waitLock(10000);
  try {
    var folha = aba();
    folha.appendRow(linha);
    folha.getRange(folha.getLastRow(), COL_PUBLICAR).insertCheckboxes();
  } finally {
    trava.releaseLock();
  }

  if (AVISAR_POR_EMAIL) {
    MailApp.sendEmail(
      Session.getEffectiveUser().getEmail(),
      'VisuAll: nova avaliação (' + nota + ' de 5)',
      'Nome: ' + nome + '\nContato: ' + (linha[2] || '-') + '\nNota: ' + nota + ' de 5\n\n' + mensagem +
      '\n\nPara mostrar no site, marque "Publicar" na planilha:\n' +
      SpreadsheetApp.getActiveSpreadsheet().getUrl()
    );
  }
  return resposta({ ok: true });
}

// Lista pública das aprovadas. Só sai nome, nota, mensagem e data: o contato
// fica na planilha.
function doGet() {
  var folha = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(ABA);
  var lista = [];
  if (folha && folha.getLastRow() > 1) {
    lista = folha.getRange(2, 1, folha.getLastRow() - 1, COL_PUBLICAR).getValues()
      .filter(function (l) { return l[COL_PUBLICAR - 1] === true; })
      .reverse()
      .slice(0, MAXIMO_NO_SITE)
      .map(function (l) {
        return {
          nome: String(l[1]),
          nota: Number(l[3]),
          mensagem: String(l[4]),
          data: l[0] instanceof Date ? l[0].toISOString() : ''
        };
      });
  }
  return resposta({ avaliacoes: lista });
}

function aba() {
  var planilha = SpreadsheetApp.getActiveSpreadsheet();
  var folha = planilha.getSheetByName(ABA);
  if (!folha) {
    folha = planilha.insertSheet(ABA);
    folha.appendRow(CABECALHO);
    folha.setFrozenRows(1);
  } else if (folha.getRange(1, COL_PUBLICAR).getValue() !== 'Publicar') {
    // Aba criada antes de existir a aprovação: ganha a coluna e as caixas,
    // todas desmarcadas
    folha.getRange(1, COL_PUBLICAR).setValue('Publicar');
    if (folha.getLastRow() > 1) {
      folha.getRange(2, COL_PUBLICAR, folha.getLastRow() - 1, 1).insertCheckboxes();
    }
  }
  return folha;
}

// Corta no tamanho máximo e impede que um texto começando com = + - @ vire
// fórmula na planilha.
function limpa(valor, maximo) {
  var texto = String(valor || '').trim().slice(0, maximo);
  return /^[=+\-@]/.test(texto) ? "'" + texto : texto;
}

function resposta(dados) {
  return ContentService
    .createTextOutput(JSON.stringify(dados))
    .setMimeType(ContentService.MimeType.JSON);
}
