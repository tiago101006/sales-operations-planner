import { Api, ApiError } from './api.mjs';
import { esc, date, month, quantity as q, factorValue, revisionCaption, label, icon, button as b, field, input, select, role, lastMonth, table, independent } from './ui.mjs';
import { createViews, miniChart } from './views.mjs';

const api = new Api();
const root = document.querySelector('#app');
const modal = document.querySelector('#modal');
const state = { user: null, pages: {}, compareIds: [], comparison: null };
const writer = () => role(state.user, 'ADMIN', 'PLANEADOR');
const internal = () => role(state.user, 'ADMIN', 'PLANEADOR', 'COMERCIAL', 'APROVADOR');
const approver = () => role(state.user, 'APROVADOR');
const views = createViews({ api, state, writer, internal, approver });
let epoch = 0, dialogContext = null, noticeTimer;
let activeChartPoint = null, activeChartTitle = '', touchedChartPoint = null, activeChartIndex = 0;
function hideChartTooltip() {
  if (activeChartPoint) {
    const tooltip = activeChartPoint.closest('.chart')?.querySelector('.chart-tooltip');
    if (tooltip) tooltip.hidden = true;
    const chart = activeChartPoint.closest('.chart');
    chart?.querySelector('.chart-guide')?.setAttribute('visibility', 'hidden');
    chart?.querySelectorAll('.chart-point title').forEach(title => { if (title.dataset.nativeTitle !== undefined) title.textContent = title.dataset.nativeTitle; });
    chart?.querySelectorAll('.chart-point.is-active').forEach(p => p.classList.remove('is-active'));
    activeChartPoint.classList.remove('is-active');activeChartPoint.removeAttribute('aria-describedby');
    const title = activeChartPoint.querySelector('title');if (title) title.textContent = activeChartTitle;
  }
  activeChartPoint = null;activeChartTitle = '';touchedChartPoint = null;
}
function showChartTooltip(point, selectedIndex = Number(point?.dataset.index)) {
  if (!point?.isConnected) return;
  const chart = point.closest('.chart'), tooltip = chart?.querySelector('.chart-tooltip');
  if (!tooltip) return;
  if (activeChartPoint !== point) {
    hideChartTooltip();activeChartPoint = point;
    // Keep the native SVG title as a no-script fallback, without a second delayed popup.
    const title = point.querySelector('title');activeChartTitle = title?.textContent || '';
    if (title) title.textContent = '';
  }
  chart.querySelectorAll('.chart-point title').forEach(title => { if (title.textContent) title.dataset.nativeTitle = title.textContent;title.textContent = ''; });
  activeChartIndex = selectedIndex;
  const model = JSON.parse(chart.dataset.chartModel);
  const index = selectedIndex, plotX = 70 + index * 708 / Math.max(1, model.periods.length - 1);
  tooltip.querySelector('.chart-tooltip-period').textContent = month(model.periods[index]);
  tooltip.querySelector('.chart-tooltip-rows').innerHTML = model.series.map((series, i) => `<div class="chart-tooltip-row" data-series="${esc(series.name)}"><span class="chart-tooltip-series"><i class="line-${i % 5}"></i>${esc(series.name)}</span><strong class="chart-tooltip-value">${esc(q(series.values[index]))}</strong></div>`).join('');
  const guide = chart.querySelector('.chart-guide');
  guide.setAttribute('x1', plotX);guide.setAttribute('x2', plotX);guide.setAttribute('visibility', 'visible');
  chart.querySelectorAll('.chart-point').forEach(p => p.classList.toggle('is-active', Number(p.dataset.index) === index));
  tooltip.hidden = false;point.setAttribute('aria-describedby', tooltip.id);
  const box = chart.getBoundingClientRect(), svg = chart.querySelector('svg');
  const position = svg.createSVGPoint();position.x = plotX;position.y = 20;
  const screen = position.matrixTransform(svg.getScreenCTM());
  const anchor = { left: screen.x, width: 0, top: screen.y, bottom: screen.y };
  const inset = 8, scrollLeft = chart.scrollLeft, scrollTop = chart.scrollTop;
  const minLeft = scrollLeft + inset, maxLeft = Math.max(minLeft, scrollLeft + chart.clientWidth - tooltip.offsetWidth - inset);
  const center = anchor.left + anchor.width / 2 - box.left + scrollLeft;
  const above = anchor.top - box.top + scrollTop - tooltip.offsetHeight - 12;
  const below = anchor.bottom - box.top + scrollTop + 12;
  const maxTop = Math.max(scrollTop + inset, scrollTop + chart.clientHeight - tooltip.offsetHeight - inset);
  tooltip.style.left = Math.max(minLeft, Math.min(maxLeft, center - tooltip.offsetWidth / 2)) + 'px';
  tooltip.style.top = Math.max(scrollTop + inset, Math.min(maxTop, above >= scrollTop + inset ? above : below)) + 'px';
}
const routes = [
  ['visao', 'Visão geral', 'home', () => true], ['produtos', 'Produtos', 'box', () => true],
  ['importacoes', 'Importações', 'upload', internal], ['previsoes', 'Previsões', 'trend', internal],
  ['backtests', 'Comparar modelos', 'chart', internal], ['cenarios', 'Cenários', 'layers', internal],
  ['planos', 'Planos publicados', 'check', () => true], ['monitorizacao', 'Monitorização', 'chart', () => true],
  ['equipa', 'Equipa', 'people', () => role(state.user, 'ADMIN')],
];
const aliases = { lote: 'importacoes', previsao: 'previsoes', backtest: 'backtests', cenario: 'cenarios', revisao: 'cenarios', plano: 'planos', avaliacao: 'monitorizacao' };
const route = () => {
  const url = new URL(location.hash.slice(1) || '/visao', location.origin);
  const [name, id] = url.pathname.replace(/^\//, '').split('/');
  return { name: name || 'visao', id, params: url.searchParams };
};
const navigate = path => { if (location.hash === '#/' + path) render(); else location.hash = '#/' + path; };
function notify(text) {
  const box = document.querySelector('#notice');box.textContent = text;box.classList.add('visible');clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => box.classList.remove('visible'), 6500);
}
function logoutView(message = '') {
  hideChartTooltip();
  document.title = 'Entrar — NexoPlan';
  epoch++;for (const key of Object.keys(state)) delete state[key];Object.assign(state, { user: null, pages: {}, compareIds: [], comparison: null });
  closeDialog();
  root.innerHTML = `<main id="conteudo" class="login" tabindex="-1"><section class="login-story"><a class="brand" href="#/visao"><img src="/assets/brand.svg" width="43" height="43" alt=""><div><strong>NexoPlan</strong><small>PLANEAMENTO S&OP</small></div></a><div><h1>Antecipar a procura.<br><em>Planear com confiança.</em></h1><p>Históricos, previsões e decisões ligadas num único espaço de trabalho.</p>${miniChart}</div><footer>Da previsão à decisão · Com rastreabilidade</footer></section><section class="login-form"><form id="login-form"><div class="eyebrow">Bem-vindo ao teu espaço</div><h2>Entrar na aplicação</h2><p class="intro">Acede aos planos e ferramentas disponíveis para a tua equipa.</p>${field('Email', input('email', 'email', '', 'required maxlength="254" autocomplete="username" placeholder="O teu email"'))}${field('Password', input('password', 'password', '', 'required maxlength="200" autocomplete="current-password" placeholder="A tua password"'))}<div class="form-error" role="alert">${esc(message)}</div><button class="btn primary" type="submit">Entrar ${icon('arrow', 16)}</button><p class="login-footer">O acesso é atribuído pelo administrador da equipa.<br>As decisões e os ajustes ficam associados à tua conta.</p></form></section></main>`;
}
function shell() {
  const navLink = key => {
    const entry = routes.find(r => r[0] === key);
    if (!entry?.[3]()) return '';
    return `<a class="nav-link" href="#/${entry[0]}" data-nav="${entry[0]}">${icon(entry[2], 16)}<span>${entry[1]}</span></a>`;
  };
  const groups = [['01', 'Dados', ['produtos', 'importacoes']], ['02', 'Previsão', ['previsoes', 'backtests']], ['03', 'Decisão', ['cenarios', 'planos']], ['04', 'Resultados', ['monitorizacao']]];
  root.innerHTML = `<div class="shell"><header class="app-header"><div class="topbar"><a class="brand" href="#/visao"><img src="/assets/brand.svg" width="42" height="42" alt=""><div><strong>NexoPlan<span class="brand-period">.</span></strong><small>PROCURA → DECISÃO</small></div></a><span class="workspace-label">PLANEAMENTO DE VENDAS & OPERAÇÕES</span><div class="account"><span class="avatar">${esc(state.user.nome.trim().slice(0, 1).toUpperCase())}</span><div class="account-name">${esc(state.user.nome)}<small>${state.user.papeis.map(p => esc(label(p))).join(' · ')}</small></div>${b(icon('logout', 18), 'logout', 'aria-label="Terminar sessão" title="Terminar sessão"', 'icon-button')}${b('<span></span><span></span>', 'toggle-nav', 'aria-label="Abrir navegação" aria-controls="main-navigation" aria-expanded="false"', 'nav-toggle')}</div></div><div class="sidebar"><nav id="main-navigation" aria-label="Navegação principal"><div class="nav-home">${navLink('visao')}</div>${groups.map(([number, title, keys]) => { const links = keys.map(navLink).join(''); return links ? `<div class="nav-group" aria-label="${title}"><span class="nav-stage"><span>${number}</span>${title}</span><div class="nav-options">${links}</div></div>` : ''; }).join('')}${role(state.user, 'ADMIN') ? `<div class="nav-admin">${navLink('equipa')}</div>` : ''}</nav></div></header><div class="workspace"><div class="location-bar"><span class="location-mark"></span><span>NexoPlan</span><span>/</span><strong id="breadcrumb">Visão geral</strong><span class="location-note">${state.user.papeis.map(p => esc(label(p))).join(' · ')}</span></div><main id="conteudo" class="content" tabindex="-1"></main><footer class="app-footer"><span>NexoPlan<span class="brand-period">.</span></span><span>Dados · Previsões · Decisões</span></footer></div></div>`;
}
function setNavigation(open) {
  document.querySelector('.sidebar')?.classList.toggle('open', open);
  const toggle = document.querySelector('[data-action=toggle-nav]');
  if (toggle) { toggle.setAttribute('aria-expanded', String(open));toggle.setAttribute('aria-label', open ? 'Fechar navegação' : 'Abrir navegação'); }
}
async function render() {
  if (!state.user) return;
  if (!document.querySelector('.shell')) shell();
  const current = route(), active = aliases[current.name] || current.name;
  const permission = routes.find(r => r[0] === active);
  state.tableFilters = {};
  for (const [name, value] of current.params) {
    const match = /^f_([A-Za-z]+)_(busca|desde|ate)$/.exec(name);
    if (match) (state.tableFilters[match[1]] ||= {})[match[2]] = value;
  }
  const main = document.querySelector('#conteudo');const version = ++epoch;
  hideChartTooltip();
  document.querySelectorAll('[data-nav]').forEach(el => { el.classList.toggle('active', el.dataset.nav === active); if (el.dataset.nav === active) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current'); });
  document.querySelector('#breadcrumb').textContent = permission?.[1] || 'Página';
  document.title = `${permission?.[1] || 'Página'} — NexoPlan`;
  main.innerHTML = '<div class="loading" role="status"><span class="spinner"></span>A carregar os dados…</div>';
  try {
    if (!permission || !views[current.name]) throw new Error('Esta página não existe. Escolhe uma opção do menu.');
    if (!permission[3]()) throw new Error('O teu perfil não tem acesso a esta área. Consulta os planos publicados e a monitorização.');
    const html = await views[current.name](current.name === 'cenarios' ? current.params : current.id, current.params);
    if (version === epoch && state.user) { if (typeof html === 'string') main.innerHTML = html; else { Object.assign(state, html.context);main.innerHTML = html.html; } }
  } catch (error) {
    if (version === epoch && state.user) main.innerHTML = `<div class="info error" role="alert">${esc(error.message)}</div>${b('Tentar atualizar', 'refresh')}${Object.keys(state.tableFilters).length ? b('Limpar filtros e voltar à lista', 'reset-search') : ''}`;
  }
}
api.onUnauthorized = () => { if (state.user) logoutView('A sessão terminou. Volta a entrar para continuar.'); };
function closeDialog() {
  if (modal.open) modal.close();modal.innerHTML = '';dialogContext = null;
}
function formError(form, error) {
  const box = form.querySelector('.form-error');
  const text = error.status === 409 && error.problem?.currentVersion !== undefined
    ? `A revisão mudou para a versão ${error.problem.currentVersion}. Fecha este formulário e atualiza os dados antes de decidir novamente. As tuas alterações não foram reenviadas.` : error.message;
  box.textContent = text;
  if (error.status === 409 && error.problem?.currentVersion !== undefined) {
    const refresh = document.createElement('button');refresh.type = 'button';refresh.className = 'btn small';refresh.dataset.action = 'refresh-stale';refresh.textContent = 'Atualizar os dados';box.append(document.createElement('br'), refresh);
  }
  box.scrollIntoView({ block: 'nearest' });
}
function formHtml(kind, title, content, submit, context = {}) {
  hideChartTooltip();
  dialogContext = { kind, ...context };
  modal.innerHTML = `<div class="modal-head"><h2 id="modal-title">${title}</h2><button class="close" type="button" data-action="close-dialog" aria-label="Fechar">×</button></div><form id="command-form" data-kind="${kind}"><div class="form-grid">${content}</div><div class="form-error" role="alert"></div><div class="form-actions">${b('Cancelar', 'close-dialog')}<button class="btn primary" type="submit">${submit}</button></div></form>`;
  modal.querySelectorAll('input[type=datetime-local]').forEach(input => input.dataset.initial = input.value);
  if (!modal.open) modal.showModal();
  modal.querySelector('input:not([type=hidden]),select,textarea,button[type=submit]')?.focus();
}
const textArea = (name, value = '', max = 2000, required = true) => `<textarea name="${name}" maxlength="${max}" ${required ? 'required' : ''}>${esc(value)}</textarea>`;
const decimalInput = (name, value, digits = 14, fraction = 4) => input(name, 'text', value, `required inputmode="decimal" pattern="[0-9]{1,${digits}}([.,][0-9]{1,${fraction}})?"`);
const decimal = value => value.trim().replace(',', '.');
const localNow = () => { const d = new Date();return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19); };
const lotOptions = lots => lots.map(l => [l.id, `#${l.id} · ${l.nomeFicheiro} · ${l.totalLinhas} registos`]);
const searchText = value => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-PT').trim();
const historyInput = () => modal.querySelector('[name=historySearch]');
const historyId = () => modal.querySelector('[name=loteHistoricoId]');
const historyTitle = lot => lot.nomeFicheiro;
const historyDescription = lot => `<strong>${esc(lot.nomeFicheiro)}</strong><span>${esc(lot.origem || 'Sem origem')}</span><small>${esc(date(lot.importadoEm))} · ${esc(lot.totalLinhas)} registos · Lote #${esc(lot.id)}${lot.id === dialogContext?.historyLots?.[0]?.id ? ' · Mais recente' : ''}</small>`;
function updateHistoryPicker() {
  if (!dialogContext?.historyLots) return;
  const input = historyInput(), query = historyId().value ? '' : searchText(input.value);
  const matches = dialogContext.historyLots.filter(l => /^#?\d+$/.test(query)
    ? l.id === query.replace(/^#/, '') : searchText(`${l.origem} ${l.nomeFicheiro}`).includes(query));
  Object.assign(dialogContext, { historyMatches: matches, historyActive: matches.length ? 0 : -1 });
  modal.querySelector('#history-results').innerHTML = matches.map((lot, index) =>
    `<div id="history-option-${index}" class="history-option" role="option" aria-selected="${lot.id === historyId().value}" data-history-id="${esc(lot.id)}">${historyDescription(lot)}</div>`).join('');
  modal.querySelector('#history-results').scrollTop = 0;
  modal.querySelector('#history-results').hidden = !matches.length;
  modal.querySelector('#history-details').hidden = true;
  input.setAttribute('aria-expanded', String(!!matches.length));
  modal.querySelector('#history-matches').textContent = matches.length
    ? `${matches.length} ${matches.length === 1 ? 'histórico disponível' : 'históricos disponíveis'} · Mais recentes primeiro. Escolhe pelo ficheiro ou pela origem.`
    : 'Nenhum histórico encontrado. Altera ou limpa a pesquisa.';
  highlightHistory();
}
function highlightHistory() {
  const index = dialogContext.historyActive;
  modal.querySelectorAll('.history-option').forEach((option, i) => option.classList.toggle('is-active', i === index));
  if (index >= 0) historyInput().setAttribute('aria-activedescendant', `history-option-${index}`);
  else historyInput().removeAttribute('aria-activedescendant');
}
function closeHistoryPicker() {
  const input = historyInput();if (!input) return;
  input.setAttribute('aria-expanded', 'false');input.removeAttribute('aria-activedescendant');
  modal.querySelector('#history-results').hidden = true;
  const lot = dialogContext.historyLots.find(l => l.id === historyId().value);
  const details = modal.querySelector('#history-details');
  details.hidden = !lot;details.innerHTML = lot ? historyDescription(lot) : '';
  modal.querySelector('#history-matches').textContent = lot ? `Lote #${lot.id} selecionado.` : 'Escolhe um resultado da pesquisa para continuar.';
}
function chooseHistory(id) {
  const lot = dialogContext.historyLots.find(l => l.id === id);if (!lot) return;
  historyId().value = lot.id;historyInput().value = historyTitle(lot);historyInput().setCustomValidity('');
  closeHistoryPicker();
  if (dialogContext.kind === 'backtest') suggestBacktest();
}
async function dialog(kind, data = {}) {
  const user = state.user;
  if (!user) return;
  if (['approve', 'return'].includes(kind)) {
    if (!approver() || !independent(user, state.revision, state.adjustments)) throw new Error('A decisão exige um aprovador independente.');
  } else if (kind === 'user' ? !role(user, 'ADMIN') : !writer()) throw new Error('O teu perfil não permite esta operação.');
  const r = state.revision;
  switch (kind) {
    case 'product': {
      const p = data.id ? await api.get('/produtos/' + encodeURIComponent(data.id)) : null;
      formHtml(kind, p ? 'Editar produto' : 'Novo produto', field('Código', input('codigo', 'text', p?.codigo || '', 'required maxlength="64" pattern="[A-Za-z0-9._-]+"')) + field('Unidade', input('unidade', 'text', p?.unidade || 'un', 'required maxlength="20"'), 'Ex.: un, kg ou L. Fica fixa depois de haver dados.') + field('Nome do produto', input('nome', 'text', p?.nome || '', 'required maxlength="160"')) + (p ? `<label class="check-group"><input name="ativo" type="checkbox" ${p.ativo ? 'checked' : ''}>Produto ativo</label>` : ''), 'Guardar produto', { product: p });break;
    }
    case 'import': {
      const lots = (await api.all('/lotes')).filter(l => l.estado === 'VALIDADO');
      formHtml(kind, 'Importar dados CSV', field('Tipo de dados', select('tipo', [['HISTORICO', 'Histórico de vendas / consumo'], ['REALIZADOS', 'Procura realizada'], ['OPERACIONAL', 'Resultados operacionais']], 'HISTORICO')) + field('Origem', input('origem', 'text', '', 'required maxlength="160" placeholder="Ex.: sistema de vendas"')) + field('Ficheiro CSV', '<input name="ficheiro" type="file" accept=".csv,text/csv" required>', 'UTF-8 · separador ; · até 10 MiB. Os produtos devem existir no catálogo.') + field('Corrige um lote anterior', select('substituiLoteId', [], '', false), 'Opcional. Novo snapshot completo do mesmo tipo, sem alterar o anterior.') + `<div class="info csv-help"><details><summary>Consultar o formato esperado</summary><div class="csv-help-content"><p id="csv-description"></p><div class="csv-caption">Cabeçalho (primeira linha)</div><pre class="csv-snippet"><code id="csv-format"></code></pre><div class="csv-caption">Exemplo de uma linha de dados</div><pre class="csv-snippet"><code id="csv-example"></code></pre><p class="csv-note">Exemplos ilustrativos: usa códigos do catálogo, meses AAAA-MM, ponto nas casas decimais e datas de disponibilidade em UTC (Z).</p></div></details></div>`, 'Importar e validar', { lots });updateImportFormat();break;
    }
    case 'forecast': case 'backtest': {
      const lots = (await api.all('/lotes')).filter(l => l.tipo === 'HISTORICO' && l.estado === 'VALIDADO').sort((a, b) => Date.parse(b.importadoEm) - Date.parse(a.importadoEm));
      if (!lots.length) throw new Error('Importa primeiro um histórico validado.');
      const selectedLot = data.history ? lots.find(l => l.id === data.history) : null;
      if (data.history && !selectedLot) throw new Error('Este lote já não está disponível como histórico validado. Escolhe outro na página de previsões.');
      let content = `<div class="history-picker">${field('Histórico validado', input('historySearch', 'text', selectedLot ? historyTitle(selectedLot) : '', 'role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="history-results" aria-describedby="history-matches" placeholder="Escolhe um histórico recente ou pesquisa pelo ficheiro" autocomplete="off" required'))}<input type="hidden" name="loteHistoricoId" value="${esc(selectedLot?.id || '')}"><p id="history-matches" role="status"></p><div id="history-results" class="history-results" role="listbox" aria-label="Históricos disponíveis" hidden></div><div id="history-details" class="history-details" hidden></div></div>` + field('Série', select('tipoSerie', [['VENDAS', 'Vendas'], ['CONSUMO', 'Consumo']], 'VENDAS')) + field('Horizonte (meses)', input('horizonteMeses', 'number', '3', 'required min="1" max="12"'));
      if (kind === 'forecast') content += field('Último mês de treino', input('ultimoPeriodoTreino', 'month', lastMonth(), 'required')) + field('Dados conhecidos até', input('corteTemporal', 'datetime-local', localNow(), 'required step="1"'), 'Hora local. Sem alteração, usa o momento do envio.') + field('Método', select('metodo', [['ETS', 'ETS com tendência aditiva'], ['MEDIA_MOVEL', 'Média Móvel recursiva']], 'ETS')) + `<div data-method="MEDIA_MOVEL">${field('Janela da média móvel', input('janela', 'number', '3', 'required min="1" max="36"'))}</div><div data-method="ETS">${field('Máximo de meses de treino ETS', input('historicoMeses', 'number', '24', 'required min="6" max="120"'))}</div><div data-method="ETS">${field('Recurso para séries curtas', select('recurso', [['SEM_RECURSO', 'Assinalar como insuficiente'], ['MEDIA_MOVEL', 'Usar Média Móvel (janela 3)']], 'SEM_RECURSO'))}</div>`;
      else content += field('Primeiro corte de treino', input('primeiroCorte', 'month', '', 'required')) + field('Último corte de treino', input('ultimoCorte', 'month', '', 'required')) + field('Janela da Média Móvel', input('janelaMediaMovel', 'number', '3', 'required min="1" max="36"')) + field('Máximo de meses ETS', input('historicoEtsMeses', 'number', '24', 'required min="6" max="120"')) + '<div class="info">Os cortes são sugeridos a partir do histórico e do horizonte selecionados. Cada corte identifica o último mês de treino.</div>';
      formHtml(kind, kind === 'forecast' ? 'Gerar previsão' : 'Comparar os modelos', content, kind === 'forecast' ? 'Gerar previsão' : 'Executar backtest', { historyLots: lots });
      updateHistoryPicker();
      if (selectedLot) closeHistoryPicker();
      if (kind === 'forecast') updateMethod();else await suggestBacktest();break;
    }
    case 'scenario': {
      const execution = data.execution || state.executionId;
      formHtml(kind, 'Novo cenário', `<div class="info">Cria outra hipótese de procura a partir da previsão #${esc(execution)}. O cenário começa com a sua primeira revisão.</div><div class="form-span">` + field('Nome', input('nome', 'text', '', 'required maxlength="120" placeholder="Ex.: Campanha de inverno"')) + '</div>' + field('Tipo', select('tipo', [['OTIMISTA', 'Otimista'], ['CONSERVADOR', 'Conservador'], ['BASE', 'Base'], ['PERSONALIZADO', 'Personalizado']], 'OTIMISTA')) + field('Fator', decimalInput('fator', '1.10', 6, 6), '1,10 = mais 10%; 0,90 = menos 10%. Aceita até 6 casas decimais quando necessário.') + field('Pressupostos', textArea('descricao'), 'Explica a hipótese que sustenta este cenário.'), 'Criar cenário', { execution });break;
    }
    case 'copy': formHtml(kind, 'Criar nova revisão', `<div class="info">Vais criar outra versão do cenário <strong>${esc(state.currentScenario.nome)}</strong>, a partir de <strong>${esc(revisionCaption(r))}</strong>. A versão anterior fica guardada. Sem mudar o fator, os valores e a auditoria relevante são conservados.</div><label class="check-group"><input name="recalcular" type="checkbox">Recalcular a partir da base com outro fator</label><div id="new-factor" class="display-none">${field('Novo fator', decimalInput('fator', factorValue(r.fator), 6, 6))}<p class="info warn">Um fator diferente recalcula as quantidades e deixa de herdar os ajustes anteriores.</p></div>${field('Pressupostos', textArea('descricao', r.pressupostos.descricao))}`, 'Criar revisão', { revision: r });modal.querySelector('[name=fator]').disabled = true;break;
    case 'adjust': {
      const cell = data.cell;
      formHtml(kind, 'Ajustar quantidade', `<div class="info"><strong>${esc(cell.produto)} · ${esc(cell.periodo)} · ${esc(cell.unidade)}</strong><br>Base: ${q(cell.quantidadeBase)} · Calculada: ${q(cell.quantidadeCalculada)} · Atual: ${q(cell.quantidadeFinal)}</div>` + field('Nova quantidade final', decimalInput('quantidade', cell.quantidadeFinal), 'Substitui a quantidade atual. Aceita ponto ou vírgula e até quatro casas decimais.') + field('Justificação', textArea('justificacao', '', 1000), 'Indica o motivo de negócio desta alteração.'), 'Guardar ajuste', { revision: r, cell });break;
    }
    case 'submit': case 'return': case 'approve': {
      const title = kind === 'submit' ? 'Enviar para aprovação' : kind === 'return' ? 'Devolver a revisão' : 'Aprovar e publicar';
      const desc = kind === 'submit' ? 'As quantidades ficam congeladas enquanto se aguarda a decisão de um aprovador independente.' : kind === 'return' ? 'A revisão fica preservada com o motivo da devolução. A correção é feita numa revisão nova.' : 'A aprovação publica uma cópia estável das quantidades finais. O plano fica disponível por consulta e CSV.';
      formHtml(kind, title, `<div class="info"><strong>${esc(state.currentScenario.nome)} · ${esc(revisionCaption(r))}</strong><br>${r.totalLinhas} quantidades previstas.<br>${desc}</div>` + (kind === 'return' ? field('Motivo da devolução', textArea('motivo', '', 1000)) : ''), title, { revision: r });break;
    }
    case 'assessment': {
      const [plans, lots] = await Promise.all([api.all('/planos'), api.all('/lotes')]);
      if (!plans.length) throw new Error('Publica primeiro um plano.');
      const realized = lots.filter(l => l.tipo === 'REALIZADOS' && l.estado === 'VALIDADO');
      const ops = lots.filter(l => l.tipo === 'OPERACIONAL' && l.estado === 'VALIDADO');
      if (!realized.length) throw new Error('Importa primeiro um lote de realizados validado.');
      const id = data.plan || plans[0].id;
      formHtml(kind, 'Nova avaliação', field('Plano publicado', select('planoId', plans.map(p => [p.id, `Plano #${p.id} · revisão #${p.revisaoId}`]), id)) + field('Lote de realizados', select('loteRealizadosId', lotOptions(realized), realized[0].id)) + field('Lote operacional', select('loteOperacionalId', lotOptions(ops), '', false)) + field('Resultados conhecidos até', input('corteRealizados', 'datetime-local', localNow(), 'required step="1"'), 'Hora local. Sem alteração, usa o momento do envio; o corte fica preservado.') + field('Primeiro mês', input('periodoInicio', 'month', '', 'required')) + field('Último mês', input('periodoFim', 'month', '', 'required')) + '<div class="info">Meses ainda não fechados, publicação tardia e dados em falta ficam identificados na cobertura. Uma métrica indisponível não equivale a zero.</div>', 'Criar avaliação');await suggestWindow(id);break;
    }
    case 'user': formHtml(kind, 'Criar utilizador', field('Nome', input('nome', 'text', '', 'required maxlength="120" autocomplete="name"')) + field('Email', input('email', 'email', '', 'required maxlength="254" autocomplete="off"')) + field('Password inicial', input('password', 'password', '', 'required minlength="12" maxlength="72" autocomplete="new-password"'), 'Pelo menos 12 caracteres e até 72 bytes UTF-8.') + `<div><label>Papéis atribuídos</label><div class="check-group">${['PLANEADOR', 'COMERCIAL', 'APROVADOR', 'CONSUMIDOR', 'ADMIN'].map(p => `<label><input name="papeis" type="checkbox" value="${p}" ${p === 'PLANEADOR' ? 'checked' : ''}>${label(p)}</label>`).join('')}</div></div>`, 'Criar conta');break;
    default: throw new Error('Operação desconhecida.');
  }
}
function updateImportFormat() {
  const type = modal.querySelector('[name=tipo]').value;
  const old = modal.querySelector('[name=substituiLoteId]');
  old.innerHTML = '<option value="">Nenhum</option>' + dialogContext.lots.filter(l => l.tipo === type).map(l => `<option value="${esc(l.id)}">#${esc(l.id)} · ${esc(l.nomeFicheiro)}</option>`).join('');
  const formats = {
    HISTORICO: {
      header: 'produto;periodo;tipo;quantidade;unidade;disponivel_em',
      example: 'SKU001;2026-10;VENDAS;24.0000;un;2026-11-01T00:00:00Z',
      description: 'Uma linha por produto, mês e tipo de série: VENDAS ou CONSUMO.',
    },
    REALIZADOS: {
      header: 'produto;periodo;quantidade;unidade;disponivel_em',
      example: 'SKU001;2026-10;24.0000;un;2026-11-01T00:00:00Z',
      description: 'Uma linha por produto e mês já fechado, com a procura realizada.',
    },
    OPERACIONAL: {
      header: 'produto;periodo;unidade;unidades_solicitadas;unidades_satisfeitas_no_prazo;dias_observados_com_procura;dias_com_procura_sem_stock;stock_fecho;limite_stock;unidades_perdidas;prazo_medio_dias;disponivel_em',
      example: 'SKU001;2026-10;un;100;90;20;2;30;10;1;2;2026-11-01T00:00:00Z',
      description: 'Uma linha por produto e mês, com os resultados de serviço e stock.',
    },
  };
  const format = formats[type];
  modal.querySelector('.csv-help').hidden = !format;
  if (!format) return;
  modal.querySelector('#csv-format').textContent = format.header;
  modal.querySelector('#csv-example').textContent = format.example;
  modal.querySelector('#csv-description').textContent = format.description;
}
function updateMethod() {
  const method = modal.querySelector('[name=metodo]').value;
  modal.querySelectorAll('[data-method]').forEach(el => { const hidden = el.dataset.method !== method;el.classList.toggle('display-none', hidden);el.querySelectorAll('input,select').forEach(input => input.disabled = hidden); });
}
let suggestionEpoch = 0;
async function suggestBacktest() {
  const form = modal.querySelector('form');if (!form) return;
  const version = ++suggestionEpoch, data = new FormData(form);
  if (!data.get('loteHistoricoId')) {
    form.elements.primeiroCorte.value = '';form.elements.ultimoCorte.value = '';
    form.querySelector('.form-error').textContent = '';return;
  }
  try {
    const result = await api.get('/backtests/sugestao?' + new URLSearchParams({ loteHistoricoId: data.get('loteHistoricoId'), tipoSerie: data.get('tipoSerie'), horizonteMeses: data.get('horizonteMeses') }));
    if (version === suggestionEpoch && form.isConnected) { form.elements.primeiroCorte.value = result.primeiroCorte;form.elements.ultimoCorte.value = result.ultimoCorte;form.querySelector('.form-error').textContent = ''; }
  } catch (error) { if (version === suggestionEpoch && form.isConnected) formError(form, error); }
}
async function suggestWindow(id) {
  const form = modal.querySelector('form');if (!form) return;
  const lines = await api.all(`/planos/${encodeURIComponent(id)}/linhas`);
  if (form.isConnected && form.elements.planoId.value === id && lines.length) { const periods = lines.map(l => l.periodo).sort();form.elements.periodoInicio.value = periods[0];form.elements.periodoFim.value = periods.at(-1); }
}
async function command(form, context) {
  const d = new FormData(form), kind = context.kind;
  const text = name => String(d.get(name) || '').trim();
  const instant = name => form.elements[name].dataset.initial === text(name) ? new Date().toISOString() : new Date(text(name)).toISOString();
  switch (kind) {
    case 'product': {
      const body = { codigo: text('codigo'), nome: text('nome'), unidade: text('unidade') };
      if (context.product) Object.assign(body, { ativo: d.has('ativo'), versaoEsperada: context.product.versao });
      await api.send('/produtos' + (context.product ? '/' + context.product.id : ''), body, context.product ? 'PATCH' : 'POST');return { message: 'Produto guardado.' };
    }
    case 'import': {
      const file = d.get('ficheiro');if (!file || !file.size) throw new Error('Seleciona um ficheiro CSV com conteúdo.');if (file.size > 10 * 1024 * 1024) throw new Error('O ficheiro excede 10 MiB.');
      const body = new FormData();body.set('ficheiro', file);body.set('tipo', text('tipo'));body.set('origem', text('origem'));body.set('versaoEsquema', 'v1');if (text('substituiLoteId')) body.set('substituiLoteId', text('substituiLoteId'));
      const lot = await api.send('/lotes', body);return { message: 'Ficheiro validado e registos importados.', route: 'lote/' + lot.id };
    }
    case 'forecast': {
      const method = text('metodo');const parameters = method === 'MEDIA_MOVEL' ? { janela: Number(text('janela')), multiplosPassos: 'RECURSIVO' } : { variante: 'AAN', historicoMeses: Number(text('historicoMeses')), recurso: text('recurso'), ...(text('recurso') === 'MEDIA_MOVEL' ? { janelaRecurso: 3 } : {}) };
      const value = await api.send('/execucoes-previsao', { loteHistoricoId: text('loteHistoricoId'), tipoSerie: text('tipoSerie'), corteTemporal: instant('corteTemporal'), ultimoPeriodoTreino: text('ultimoPeriodoTreino'), horizonteMeses: Number(text('horizonteMeses')), metodo: method, parametros: parameters });
      return { message: 'Execução de previsão concluída.', route: 'previsao/' + value.id };
    }
    case 'backtest': {
      const value = await api.send('/backtests', { loteHistoricoId: text('loteHistoricoId'), tipoSerie: text('tipoSerie'), primeiroCorte: text('primeiroCorte'), ultimoCorte: text('ultimoCorte'), horizonteMeses: Number(text('horizonteMeses')), janelaMediaMovel: Number(text('janelaMediaMovel')), historicoEtsMeses: Number(text('historicoEtsMeses')) });
      return { message: 'Backtest concluído.', route: 'backtest/' + value.id };
    }
    case 'scenario': {
      const value = await api.send('/cenarios', { execucaoId: context.execution, nome: text('nome'), tipo: text('tipo'), fator: decimal(text('fator')), pressupostos: { descricao: text('descricao') } });
      return { message: 'Cenário criado. Estás na primeira revisão, onde podes ajustar as quantidades.', route: 'revisao/' + value.revisao.id };
    }
    case 'copy': {
      const r = context.revision, body = { revisaoOrigemId: r.id, versaoOrigemEsperada: r.versao, pressupostos: { descricao: text('descricao') } };
      if (d.has('recalcular')) body.fator = decimal(text('fator'));
      const value = await api.send(`/cenarios/${r.cenarioId}/revisoes`, body);return { message: 'Nova revisão criada.', route: 'revisao/' + value.id };
    }
    case 'adjust': {
      const r = context.revision, c = context.cell;
      await api.send(`/revisoes/${r.id}/ajustes`, { produtoId: c.produtoId, periodo: c.periodo, quantidade: decimal(text('quantidade')), justificacao: text('justificacao'), versaoEsperada: r.versao });return { message: 'Ajuste e justificação guardados.' };
    }
    case 'submit': case 'return': case 'approve': {
      const r = context.revision, operation = { submit: 'submissao', return: 'devolucao', approve: 'aprovacao' }[kind];
      const value = await api.send(`/revisoes/${r.id}/${operation}`, { versaoEsperada: r.versao, ...(kind === 'return' ? { motivo: text('motivo') } : {}) });
      return { message: kind === 'submit' ? 'Revisão submetida e quantidades congeladas.' : kind === 'return' ? 'Revisão devolvida com motivo.' : 'Plano aprovado e publicado.', ...(kind === 'approve' ? { route: 'plano/' + value.planoId } : {}) };
    }
    case 'assessment': {
      const value = await api.send('/avaliacoes', { planoId: text('planoId'), loteRealizadosId: text('loteRealizadosId'), ...(text('loteOperacionalId') ? { loteOperacionalId: text('loteOperacionalId') } : {}), periodoInicio: text('periodoInicio'), periodoFim: text('periodoFim'), corteRealizados: instant('corteRealizados') });return { message: 'Avaliação criada com cobertura e exclusões.', route: 'avaliacao/' + value.id };
    }
    case 'user': {
      const password = String(d.get('password') || '');if (new TextEncoder().encode(password).length > 72) throw new Error('A password não pode exceder 72 bytes UTF-8.');
      const roles = d.getAll('papeis');if (!roles.length) throw new Error('Seleciona pelo menos um papel.');
      const user = await api.send('/utilizadores', { nome: text('nome'), email: text('email'), password, papeis: roles });return { message: `Conta criada: ${user.email} · identificador ${user.id}.` };
    }
    default: throw new Error('Operação desconhecida.');
  }
}

document.addEventListener('submit', async event => {
  const form = event.target;if (!(form instanceof HTMLFormElement)) return;
  event.preventDefault();if (form.dataset.busy || !form.reportValidity()) return;
  if (form.dataset.searchKey) {
    const key = form.dataset.searchKey, values = new FormData(form), current = route();
    const from = String(values.get('desde') || ''), to = String(values.get('ate') || '');
    if ([from, to].some(value => value && (!/^[1-9][0-9]{3}-(0[1-9]|1[0-2])$/.test(value) || value === '9999-12'))) { notify('Indica um mês completo no formato AAAA-MM.');return; }
    if (from && to && from > to) { notify('O mês inicial não pode ser posterior ao final.');return; }
    for (const field of ['busca', 'desde', 'ate']) {
      const value = String(values.get(field) || '').trim();
      if (value) current.params.set(`f_${key}_${field}`, value);else current.params.delete(`f_${key}_${field}`);
    }
    state.pages[key] = 0;
    return navigate(current.name + (current.id ? '/' + current.id : '') + (current.params.toString() ? '?' + current.params : ''));
  }
  if (form.id === 'product-search') { state.search = String(new FormData(form).get('q') || '').trim();state.pages.products = 0;return render(); }
  if (form.id === 'user-lookup') {
    try { const value = await api.get('/utilizadores/' + encodeURIComponent(new FormData(form).get('userId')));const box = document.querySelector('#user-result');if (box) box.innerHTML = table(['ID', 'Nome', 'Email', 'Papéis'], [[esc(value.id), esc(value.nome), esc(value.email), value.papeis.map(p => esc(label(p))).join(', ')]]); }
    catch (error) { notify(error.message); }return;
  }
  const context = dialogContext;
  form.dataset.busy = 'true';form.setAttribute('aria-busy', 'true');
  // Build the request before disabling controls; disabled fields are omitted from FormData.
  const submission = form.id === 'login-form' ? new FormData(form) : null;
  const submit = form.querySelector('button[type=submit]');if (submit) submit.disabled = true;
  try {
    if (form.id === 'login-form') {
      const user = await api.send('/auth/login', { email: String(submission.get('email') || '').trim(), password: submission.get('password') });
      form.reset();state.user = user;
      history.replaceState(null, '', '#/visao');
      shell();await render();
    } else if (form.id === 'command-form') {
      const result = await command(form, context);
      closeDialog();notify(result.message);
      if (result.route) navigate(result.route);else await render();
    }
  } catch (error) {
    if (form.isConnected) {
      formError(form, error);
      if (error.problem?.loteId) {
        const link = document.createElement('a');link.href = '#/lote/' + encodeURIComponent(error.problem.loteId);link.textContent = 'Consultar os diagnósticos do lote #' + error.problem.loteId;link.className = 'btn small';link.dataset.action = 'lot-errors';form.querySelector('.form-error').append(document.createElement('br'), link);
      }
    }
  } finally {
    delete form.dataset.busy;form.removeAttribute('aria-busy');if (submit) submit.disabled = false;
    if (form.id === 'login-form') form.querySelector('[name=password]').value = '';
  }
});
document.addEventListener('click', async event => {
  if (event.target.closest('.skip')) { event.preventDefault();document.querySelector('#conteudo')?.focus();return; }
  const target = event.target.closest('[data-action]');if (!target || target.disabled) return;
  const action = target.dataset.action;
  try {
    switch (action) {
      case 'toggle-nav': setNavigation(target.getAttribute('aria-expanded') !== 'true');break;
      case 'close-dialog': if (!modal.querySelector('[data-busy]')) closeDialog();break;
      case 'refresh': await render();break;
      case 'refresh-stale': closeDialog();await render();break;
      case 'lot-errors': closeDialog();break;
      case 'logout': target.disabled = true;await api.send('/auth/logout', {});logoutView();break;
      case 'page': state.pages[target.dataset.key] = Math.max(0, Number(target.dataset.page));await render();break;
      case 'dialog': target.disabled = true;await dialog(target.dataset.kind, { execution: target.dataset.execution, plan: target.dataset.plan, history: target.dataset.history });break;
      case 'edit-product': await dialog('product', { id: target.dataset.id });break;
      case 'adjust': await dialog('adjust', { cell: state.revisionCells[Number(target.dataset.index)] });break;
      case 'download': target.disabled = true;await api.download(target.dataset.id);notify('Download do CSV preparado.');break;
      case 'reset-search': {
        const current = route();
        for (const key of [...current.params.keys()]) if (/^f_/.test(key)) current.params.delete(key);
        state.pages = {};
        navigate(current.name + (current.id ? '/' + current.id : '') + (current.params.toString() ? '?' + current.params : ''));break;
      }
      case 'clear-table-search': {
        const current = route(), key = target.dataset.key;
        for (const field of ['busca', 'desde', 'ate']) current.params.delete(`f_${key}_${field}`);
        state.pages[key] = 0;navigate(current.name + (current.id ? '/' + current.id : '') + (current.params.toString() ? '?' + current.params : ''));break;
      }
      case 'lookup-user': {
        const form = document.querySelector('#user-lookup');form.querySelector('[name=userId]').value = target.dataset.id;form.requestSubmit();break;
      }
      case 'clear-search': state.search = '';state.pages.products = 0;await render();break;
      case 'remove-compare': state.compareIds = state.compareIds.filter(id => id !== target.dataset.id);if (state.compareIds.length < 2) state.comparison = null;await render();break;
      case 'compare': if (state.compareIds.length < 2) throw new Error('Seleciona pelo menos duas revisões.');state.comparison = true;state.pages.comparison = 0;await render();break;
    }
  } catch (error) { notify(error.message); }
  finally { if (target.isConnected) target.disabled = false; }
});
function chartPointer(event) {
  const chart = event.target.closest('.chart[data-chart-model]');
  if (!chart) return null;
  const svg = chart.querySelector('svg'), point = svg.createSVGPoint();
  point.x = event.clientX;point.y = event.clientY;
  const local = point.matrixTransform(svg.getScreenCTM().inverse());
  if (local.x < 69.5 || local.x > 778.5 || local.y < 19.5 || local.y > 205.5) return null;
  const model = JSON.parse(chart.dataset.chartModel);
  const index = Math.max(0, Math.min(model.periods.length - 1, Math.round((local.x - 70) / 708 * (model.periods.length - 1))));
  const anchor = event.target.closest('[data-chart-point]') || chart.querySelector(`[data-chart-point][data-index="${index}"]`) || chart.querySelector('[data-chart-point]');
  return anchor ? { anchor, index } : null;
}
document.addEventListener('pointermove', event => {
  if (event.pointerType === 'touch') return;
  const hit = chartPointer(event);
  if (hit) showChartTooltip(hit.anchor, hit.index);
  else if (!document.activeElement?.matches('[data-chart-point]')) hideChartTooltip();
});
document.addEventListener('pointerout', event => {
  const chart = event.target.closest('.chart');
  if (chart && !chart.contains(event.relatedTarget) && !touchedChartPoint && !document.activeElement?.matches('[data-chart-point]')) hideChartTooltip();
});
document.addEventListener('pointerdown', event => {
  const hit = chartPointer(event);
  if (!hit) { hideChartTooltip();return; }
  showChartTooltip(hit.anchor, hit.index);
  if (event.pointerType === 'touch') touchedChartPoint = hit.anchor;
});
document.addEventListener('focusin', event => {
  const point = event.target.closest('[data-chart-point]');
  if (point) showChartTooltip(point);
});
document.addEventListener('focusout', event => {
  if (event.target.matches('[data-chart-point]')) hideChartTooltip();
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && activeChartPoint) hideChartTooltip();
});
document.addEventListener('scroll', () => {
  if (activeChartPoint && (document.activeElement === activeChartPoint || activeChartPoint.closest('.chart').matches(':hover'))) showChartTooltip(activeChartPoint, activeChartIndex);
  else hideChartTooltip();
}, true);
window.addEventListener('resize', hideChartTooltip);
modal.addEventListener('focusin', event => {
  if (event.target.name === 'historySearch') updateHistoryPicker();
});
modal.addEventListener('focusout', event => {
  if (event.target.name === 'historySearch') closeHistoryPicker();
});
modal.addEventListener('mousedown', event => {
  if (event.target.closest('[data-history-id]')) event.preventDefault();
});
modal.addEventListener('click', event => {
  const option = event.target.closest('[data-history-id]');
  if (option) chooseHistory(option.dataset.historyId);
  else if (event.target.name === 'historySearch') updateHistoryPicker();
});
modal.addEventListener('keydown', event => {
  if (event.target.name !== 'historySearch') return;
  const expanded = historyInput().getAttribute('aria-expanded') === 'true';
  if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
    event.preventDefault();
    if (!expanded) updateHistoryPicker();
    else {
      const count = dialogContext.historyMatches.length;
      dialogContext.historyActive = (dialogContext.historyActive + (event.key === 'ArrowDown' ? 1 : count - 1)) % count;
      highlightHistory();
    }
    modal.querySelector('.history-option.is-active')?.scrollIntoView({ block: 'nearest' });
  } else if (event.key === 'Enter') {
    event.preventDefault();
    if (expanded) chooseHistory(dialogContext.historyMatches[dialogContext.historyActive]?.id);
    else updateHistoryPicker();
  } else if (event.key === 'Escape' && expanded) {
    event.preventDefault();event.stopPropagation();closeHistoryPicker();
  }
});
modal.addEventListener('input', event => {
  if (event.target.name !== 'historySearch') return;
  historyId().value = '';historyInput().setCustomValidity('Escolhe um histórico nos resultados da pesquisa.');
  updateHistoryPicker();
  if (dialogContext.kind === 'backtest') suggestBacktest();
});
document.addEventListener('change', async event => {
  const target = event.target;
  try {
    if (target.dataset.compare) {
      const id = target.dataset.compare;
      if (target.checked && state.compareIds.length >= 5) { target.checked = false;notify('A comparação aceita no máximo cinco revisões.');return; }
      state.compareIds = target.checked ? [...new Set([...state.compareIds, id])] : state.compareIds.filter(i => i !== id);
      state.comparison = null;await render();return;
    }
    if (target.name === 'scenarioExecution') { state.pages.scenarios = 0;navigate('cenarios?execucaoId=' + encodeURIComponent(target.value));return; }
    if (target.name === 'monitorProduct') { state.monitorProduct = target.value;await render();return; }
    if (!modal.contains(target)) return;
    if (target.name === 'tipo' && dialogContext?.kind === 'import') updateImportFormat();
    if (target.name === 'tipo' && dialogContext?.kind === 'scenario') modal.querySelector('[name=fator]').value = { BASE: '1.00', OTIMISTA: '1.10', CONSERVADOR: '0.90', PERSONALIZADO: '1.00' }[target.value] || '1.00';
    if (target.name === 'metodo') updateMethod();
    if (target.name === 'recalcular') { modal.querySelector('#new-factor').classList.toggle('display-none', !target.checked);modal.querySelector('[name=fator]').disabled = !target.checked; }
    if (dialogContext?.kind === 'backtest' && ['loteHistoricoId', 'tipoSerie', 'horizonteMeses'].includes(target.name)) await suggestBacktest();
    if (dialogContext?.kind === 'assessment' && target.name === 'planoId') await suggestWindow(target.value);
  } catch (error) { const form = modal.querySelector('form');if (form) formError(form, error);else notify(error.message); }
});
modal.addEventListener('cancel', event => { if (modal.querySelector('[data-busy]')) event.preventDefault(); });
modal.addEventListener('close', () => { if (!modal.open) { modal.innerHTML = '';dialogContext = null; } });
window.addEventListener('hashchange', () => { if (modal.querySelector('[data-busy]')) return;closeDialog();setNavigation(false);state.pages = {};render(); });
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !modal.open && document.querySelector('.sidebar.open')) {
    setNavigation(false);document.querySelector('[data-action=toggle-nav]')?.focus();
  }
});
try { state.user = await api.get('/auth/me');shell();await render(); }
catch (error) { logoutView(error instanceof ApiError && error.status === 401 ? '' : error.message); }
