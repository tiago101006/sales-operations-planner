import { esc, quantity as q, factorValue, revisionCaption, percent, date, month, label, badge, icon, button as b, field, select, input, table, panel, metric, pager, empty, lineChart, independent, role } from './ui.mjs';

const rowAction = (text, action, id) => b(text, action, `data-id="${esc(id)}"`, 'small');
const meta = values => `<div class="detail-meta">${values.map(([name, value]) => `<div><small>${name}</small><p>${value}</p></div>`).join('')}</div>`;
const info = (text, warn = false) => `<div class="info ${warn ? 'warn' : ''}">${text}</div>`;
const scenarioHelp = () => `<details class="info scenario-help"><summary>Cenário ou revisão: qual é a diferença?</summary><p><strong>Cenário:</strong> uma hipótese de procura, como “Otimista” ou “Conservador”. Usa <strong>Novo cenário</strong> para explorar outra hipótese.</p><p><strong>Revisão:</strong> uma versão guardada do mesmo cenário. Usa <strong>Criar nova revisão</strong> para preparar alterações e conservar a anterior.</p><p><strong>Enviar para aprovação:</strong> congela essa revisão para decisão de um aprovador independente. Depois de aprovada, origina um plano publicado.</p></details>`;
const miniChart = `<svg class="mini-chart" viewBox="0 0 420 180" fill="none" aria-hidden="true"><path d="M10 150H410M10 110H410M10 70H410M10 30H410" stroke="currentColor" opacity=".12"/><path d="M10 144 68 122 125 132 183 84 240 98 298 55 355 66 410 24V165H10Z" fill="currentColor" opacity=".08"/><path d="M10 144 68 122 125 132 183 84 240 98 298 55 355 66 410 24" stroke="currentColor" stroke-width="2.5"/><path d="m240 98 58-15 57 10 55-25" stroke="currentColor" opacity=".45" stroke-width="2" stroke-dasharray="6 5"/><circle cx="410" cy="24" r="5" fill="currentColor"/></svg>`;
export { miniChart };

export function createViews(ctx) {
  const { api, state, writer } = ctx;
  const noMonths = new Set(['products', 'diagnostics', 'lotRowsErrors', 'backtestGroups', 'team']);
  const filterParams = key => {
    const values = state.tableFilters?.[key] || {};
    const params = new URLSearchParams();
    for (const field of ['busca', 'desde', 'ate']) if (values[field]) params.set(field, values[field]);
    return params.toString();
  };
  const searchable = (data, key) => ({ ...data, search: key === 'products' ? null : { key, values: state.tableFilters?.[key] || {}, months: !noMonths.has(key) } });
  const page = async (key, path) => searchable(await api.get(`${path}${path.includes('?') ? '&' : '?'}size=20&page=${state.pages[key] || 0}&${filterParams(key)}`), key);
  const heading = (eyebrow, title, subtitle, actions = '', attributes = '') => `<div class="page-heading" ${attributes}><div><div class="eyebrow">${eyebrow}</div><h1>${title}</h1>${subtitle ? (/^(Cenário #|Origem:|Otimista ·|Conservador ·|Base ·|Personalizado ·)/.test(subtitle) ? `<p class="heading-context">${subtitle}</p>` : `<details class="page-context"><summary>Sobre esta área</summary><p>${subtitle}</p></details>`) : ''}</div><div class="actions">${actions}</div></div>`;
  const newButton = (text, kind, extra = '') => writer() ? b(icon('plus', 16) + text, 'dialog', `data-kind="${kind}" ${extra}`, 'primary') : '';
  const back = (route, text) => `<a class="back" href="#/${route}">← ${text}</a>`;

  async function overview() {
    if (role(state.user, 'ADMIN')) return adminOverview();
    if (ctx.approver()) return approvalOverview();
    if (writer()) return planningOverview();
    return consultationOverview();
  }

  const overviewDate = () => new Intl.DateTimeFormat('pt-PT', { day: 'numeric', month: 'long' }).format(new Date());
  const planningRoute = stages => `<nav class="planning-route" aria-label="Ciclo de planeamento">${stages.map(([route, name, count, unit], i) => `<a href="#/${route}"><span class="route-number">0${i + 1}</span><span class="route-label">${esc(name)}</span><span class="route-count"><strong>${esc(q(count))}</strong> ${esc(String(count) === '1' ? ({ lotes: 'lote', execuções: 'execução', hipóteses: 'hipótese', publicados: 'publicado' }[unit] || unit) : unit)}</span>${icon('arrow', 16)}</a>`).join('')}</nav>`;
  const workSummary = values => `<div class="work-summary">${values.map(([name, value, tone = '']) => `<div class="${tone}"><span>${esc(name)}</span><strong>${esc(q(value))}</strong></div>`).join('')}</div>`;
  const hero = (kicker, title, description, action, signal, signalLabel, tone = '') => `<section class="planning-hero ${tone}" aria-label="Em foco"><div class="hero-copy"><span class="eyebrow">${esc(kicker)}</span><h2>${title}</h2><p>${description}</p><div class="actions">${action}</div></div><div class="hero-signal"><span class="hero-date">${icon('calendar', 15)} ${esc(overviewDate())}</span><strong>${esc(q(signal))}</strong><span>${esc(signalLabel)}</span><svg viewBox="0 0 180 42" aria-hidden="true" fill="none"><path d="M0 31H180M0 12H180" stroke="currentColor" opacity=".12"/><path d="M0 36H32L55 21H91L115 7H180" stroke="currentColor" stroke-width="2"/><circle cx="115" cy="7" r="4" fill="currentColor"/></svg></div></section>`;
  const planRows = plans => plans.items.length ? `<div class="work-list">${plans.items.map(p => `<a class="work-item" href="#/plano/${esc(p.id)}"><span class="work-symbol">${icon('check', 21)}</span><span class="work-copy"><strong>Plano #${esc(p.id)}</strong><small>Revisão #${esc(p.revisaoId)} · ${esc(date(p.publicadoEm))}</small></span><span class="work-meta">${esc(p.totalLinhas)} quantidades</span><span class="work-open">${icon('arrow', 18)}</span></a>`).join('')}</div>` : empty('Ainda não há planos publicados');
  const revisionRows = (data, decision = false) => data.items.length ? `<div class="work-list">${data.items.map(item => {
    const r = item.revisao;
    return `<a class="work-item" href="#/revisao/${esc(r.id)}"><span class="work-symbol ${r.estado === 'DEVOLVIDA' ? 'copper' : ''}">${icon(decision ? 'check' : 'layers', 21)}</span><span class="work-copy"><strong>${esc(item.cenarioNome)}</strong><small>${esc(revisionCaption(r))} · ${esc(item.autorNome)}</small></span><span class="work-meta">${badge(r.estado)}<small>${esc(date(decision ? r.submetidoEm : r.criadoEm))}</small></span><span class="work-open">${icon('arrow', 18)}</span></a>`;
  }).join('')}</div>` : empty(decision ? 'Sem revisões à espera de decisão' : 'Sem revisões neste estado');
  const overviewHelp = (summary, text) => `<details class="overview-disclosure"><summary>${summary}</summary><p>${text}</p></details>`;

  async function approvalOverview() {
    const [pending, returned, approved, plans] = await Promise.all([
      page('pendingRevisions', '/revisoes?estado=EM_REVISAO'), api.get('/revisoes?estado=DEVOLVIDA&size=1'),
      api.get('/revisoes?estado=APROVADA&size=1'), api.get('/planos?size=5'),
    ]);
    return `<div class="workbench">` + heading('Decidir', 'Centro de aprovações', '', b(icon('refresh', 15) + 'Atualizar fila', 'refresh')) +
      hero('A decisão é tua', pending.totalElements ? 'Revisões<br>por decidir.' : 'A fila está em dia.', 'Analisa os pressupostos e os ajustes antes de publicar.', '<a class="btn" href="#/planos">Consultar planos ' + icon('arrow', 16) + '</a>', pending.totalElements, 'revisões na fila') +
      workSummary([['A aguardar aprovação', pending.totalElements], ['Devolvidas', returned.totalElements, 'copper'], ['Aprovadas', approved.totalElements], ['Planos publicados', plans.totalElements]]) +
      `<div class="overview-columns"><div>${panel('Revisões à espera de decisão', 'Mais antigas primeiro · toda a equipa', revisionRows(pending, true) + pager(pending, 'pendingRevisions'))}</div><aside>${panel('Planos recentes', '', planRows(plans), '<a class="btn link" href="#/planos">Ver todos →</a>')}${overviewHelp('O que posso aprovar?', 'A decisão exige independência: não podes aprovar ou devolver uma revisão criada por ti ou que conserve ajustes teus.')}</aside></div></div>`;
  }

  async function planningOverview() {
    const [drafts, returned, pending, plans, lots, forecasts, cases] = await Promise.all([
      page('draftRevisions', '/revisoes?estado=RASCUNHO'), page('returnedRevisions', '/revisoes?estado=DEVOLVIDA'),
      api.get('/revisoes?estado=EM_REVISAO&size=1'), api.get('/planos?size=1'), api.get('/lotes?size=1'),
      api.get('/execucoes-previsao?size=1'), api.get('/cenarios?size=1'),
    ]);
    const hasDrafts = drafts.totalElements > 0;
    return `<div class="workbench">` + heading('Planear', 'Espaço de planeamento', '', '<a class="btn" href="#/importacoes">Importar dados</a><a class="btn primary" href="#/previsoes">Gerar previsões</a>') +
      hero('Em preparação', hasDrafts ? 'O planeamento<br>continua aqui.' : 'Prepara<br>a primeira previsão.', hasDrafts ? 'Retoma um rascunho ou explora outra hipótese.' : 'Começa com os dados. Constrói as tuas alternativas.', (hasDrafts ? '<a class="btn" href="#/cenarios">Explorar cenários ' : '<a class="btn" href="#/previsoes">Gerar previsões ') + icon('arrow', 16) + '</a>', drafts.totalElements, 'rascunhos da equipa') +
      planningRoute([['importacoes', 'Dados', lots.totalElements, 'lotes'], ['previsoes', 'Previsão', forecasts.totalElements, 'execuções'], ['cenarios', 'Cenários', cases.totalElements, 'hipóteses'], ['planos', 'Plano', plans.totalElements, 'publicados']]) +
      `<div class="overview-columns"><div>${panel('Revisões em preparação', 'Mais recentes primeiro · toda a equipa', revisionRows(drafts) + pager(drafts, 'draftRevisions'))}</div><aside>${panel('Revisões devolvidas', returned.totalElements ? 'A corrigir numa nova revisão' : '', revisionRows(returned) + pager(returned, 'returnedRevisions'))}${workSummary([['A aguardar aprovação', pending.totalElements], ['Planos publicados', plans.totalElements]])}${overviewHelp('Como continuo uma revisão devolvida?', 'Abre a revisão, consulta o motivo e cria uma nova revisão para corrigir. A anterior fica preservada.')}</aside></div></div>`;
  }

  async function consultationOverview() {
    const commercial = role(state.user, 'COMERCIAL');
    const [plans, assessments, forecasts, cases, products] = await Promise.all([
      api.get('/planos?size=5'), api.get('/avaliacoes?size=5'),
      commercial ? api.get('/execucoes-previsao?size=5') : null,
      commercial ? api.get('/cenarios?size=1') : null, commercial ? null : api.get('/produtos?size=1'),
    ]);
    const latestPlan = plans.items[0];
    const focus = commercial ? panel('Previsões recentes', '', forecasts.items.length ? `<div class="work-list">${forecasts.items.map(e => `<a class="work-item" href="#/previsao/${esc(e.id)}"><span class="work-symbol">${icon('trend', 21)}</span><span class="work-copy"><strong>${esc(label(e.metodo))} · #${esc(e.id)}</strong><small>${esc(e.horizonteMeses)} meses · ${esc(month(e.ultimoPeriodoTreino))}</small></span><span class="work-meta">${badge(e.estado)}</span><span class="work-open">${icon('arrow', 18)}</span></a>`).join('')}</div>` : empty('Ainda não há previsões'), '<a class="btn link" href="#/backtests">Comparar modelos →</a>')
      : panel('Avaliações recentes', '', assessments.items.length ? `<div class="work-list">${assessments.items.map(a => `<a class="work-item" href="#/avaliacao/${esc(a.id)}"><span class="work-symbol">${icon('chart', 21)}</span><span class="work-copy"><strong>Avaliação #${esc(a.id)} · Plano #${esc(a.planoId)}</strong><small>${esc(month(a.periodoInicio))} — ${esc(month(a.periodoFim))}</small></span><span class="work-open">${icon('arrow', 18)}</span></a>`).join('')}</div>` : empty('Ainda não há avaliações'), '<a class="btn link" href="#/monitorizacao">Monitorização →</a>');
    return `<div class="workbench">` + heading(commercial ? 'Acompanhar' : 'Consultar', commercial ? 'Acompanhamento comercial' : 'Planos e resultados', '', commercial ? '<a class="btn primary" href="#/cenarios">Comparar cenários</a>' : '<a class="btn primary" href="#/planos">Consultar planos</a>') +
      hero('Decisões publicadas', latestPlan ? 'Plano publicado.<br>Resultados a acompanhar.' : 'À espera<br>do primeiro plano.', latestPlan ? 'Quantidades aprovadas, disponíveis para consulta.' : 'As decisões da equipa vão aparecer aqui.', '<a class="btn" href="#/monitorizacao">Acompanhar resultados ' + icon('arrow', 16) + '</a>', latestPlan ? '#' + latestPlan.id : '—', 'plano mais recente') +
      workSummary(commercial ? [['Previsões', forecasts.totalElements], ['Cenários', cases.totalElements], ['Planos publicados', plans.totalElements], ['Avaliações', assessments.totalElements]] : [['Produtos', products.totalElements], ['Planos publicados', plans.totalElements], ['Avaliações', assessments.totalElements]]) +
      `<div class="overview-columns"><div>${panel('Planos recentes', '', planRows(plans), '<a class="btn link" href="#/planos">Ver todos →</a>')}</div><aside>${focus}</aside></div></div>`;
  }

  async function adminOverview() {
    const [products, executions, cases, plans, lots] = await Promise.all([
      api.get('/produtos?size=1'), api.get('/execucoes-previsao?size=5'),
      api.get('/cenarios?size=1'), api.get('/planos?size=5'), api.get('/lotes?size=4'),
    ]);
    const latestPlan = plans.items[0];
    const execution = executions.items.find(e => e.estado === 'CONCLUIDA' && e.totalLinhas > 0);
    let chart = empty('O teu planeamento começa aqui', 'Importa um histórico e gera uma previsão.');
    let chartSubtitle = 'Previsão base e quantidades aprovadas';
    let chartAction = '', chartFoot = '';
    if (latestPlan || execution) {
      const lines = await api.get(latestPlan ? `/planos/${latestPlan.id}/linhas?size=12` : `/execucoes-previsao/${execution.id}/linhas?size=12`);
      const first = lines.items[0];
      if (first) {
        const points = lines.items.filter(l => l.produtoId === first.produtoId);
        const series = [{ name: 'Previsão base', values: points.map(l => l.quantidadeBase) }];
        if (latestPlan) series.push({ name: 'Plano aprovado', values: points.map(l => l.quantidade) });
        chartSubtitle = `${esc(first.produto)} · ${esc(first.unidade)} · ${latestPlan ? 'Plano #' + esc(latestPlan.id) : 'Execução #' + esc(execution.id)}`;
        chart = lineChart(points.map(l => l.periodo), series, `Previsão e planeamento de ${first.produto}, em ${first.unidade}`, { area: true });
        chartAction = `<a class="btn small" href="#/${latestPlan ? 'plano/' + esc(latestPlan.id) : 'previsao/' + esc(execution.id)}">Ver detalhe ${icon('arrow', 14)}</a>`;
        chartFoot = `<div class="chart-context"><span>${icon('box', 15)} ${esc(first.produto)} · ${esc(first.unidade)}</span><span>${esc(month(points[0].periodo))} — ${esc(month(points.at(-1).periodo))}</span><span>Primeiro produto · ${points.length} meses</span></div>`;
      }
    }
    return `<div class="workbench admin-dashboard">` + heading('Coordenar', 'Visão geral', '', newButton('Importar CSV', 'import')) +
      hero('NexoPlan · S&OP', 'Da Procura<br>à Decisão.', 'Dados, alternativas e decisões no mesmo percurso.', '<a class="btn" href="#/cenarios">Explorar cenários ' + icon('arrow', 16) + '</a>', products.totalElements, 'produtos no catálogo') +
      planningRoute([['importacoes', 'Dados', lots.totalElements, 'lotes'], ['previsoes', 'Previsão', executions.totalElements, 'execuções'], ['cenarios', 'Cenários', cases.totalElements, 'hipóteses'], ['planos', 'Plano', plans.totalElements, 'publicados']]) +
      `<div class="overview-columns"><div class="dashboard-chart">${panel('Da previsão ao plano', chartSubtitle, chart + chartFoot, chartAction)}</div><aside>${panel('Planos recentes', '', planRows(plans), '<a class="btn link" href="#/planos">Ver todos →</a>')}</aside></div>` +
      `<details class="overview-disclosure overview-imports"><summary>Últimas importações <span>${esc(lots.totalElements)} lotes</span></summary>${lots.items.length ? `<div class="work-list">${lots.items.map(l => `<a class="work-item" href="#/lote/${esc(l.id)}"><span class="work-symbol">${icon('upload', 20)}</span><span class="work-copy"><strong>${esc(l.nomeFicheiro)}</strong><small>${esc(label(l.tipo))} · ${esc(date(l.importadoEm))}</small></span><span class="work-meta">${badge(l.estado)}</span><span class="work-open">${icon('arrow', 18)}</span></a>`).join('')}</div>` : empty('Ainda não há importações')}</details></div>`;
  }

  async function products() {
    const data = await page('products', '/produtos' + (state.search ? '?q=' + encodeURIComponent(state.search) : ''));
    return heading('Dados de base', 'Catálogo de produtos', 'Códigos, unidades e estado dos produtos utilizados no planeamento.', newButton('Novo produto', 'product')) +
      panel('Produtos', `${data.totalElements} produtos no resultado`, `<form id="product-search" class="toolbar">${field('Procurar no catálogo', input('q', 'search', state.search || '', 'placeholder="Código ou nome do produto" maxlength="160"'))}<button class="btn" type="submit">Pesquisar</button>${state.search ? b('Limpar', 'clear-search') : ''}</form>` +
        table(['Código / produto', 'Unidade', 'Estado', ''], data.items.map(p => [`<strong>${esc(p.codigo)}</strong><small>${esc(p.nome)}</small>`, esc(p.unidade), badge(p.ativo ? 'VALIDADO' : 'RASCUNHO').replace('Validado', 'Ativo').replace('Rascunho', 'Inativo'), writer() ? rowAction('Editar', 'edit-product', p.id) : 'Consulta'])) + pager(data, 'products'));
  }

  async function imports() {
    const data = await page('lots', '/lotes');
    return heading('Qualidade e proveniência', 'Importação de dados', 'Importa CSV e acompanha a validação antes de utilizar os registos.', newButton('Importar CSV', 'import')) +
      overviewHelp('Que dados posso importar?', 'Históricos alimentam as previsões. Realizados e operacionais permitem avaliar os planos publicados. Um lote com erros mantém os diagnósticos e não publica registos parciais.') +
      panel('Lotes recebidos', 'O original e o histórico de cada importação são conservados.', table(['Ficheiro / origem', 'Tipo', 'Registos', 'Estado', 'Recebido', ''], data.items.map(l => [`<strong>${esc(l.nomeFicheiro)}</strong><small>#${esc(l.id)} · ${esc(l.origem)}</small>`, esc(label(l.tipo)), esc(l.totalLinhas), badge(l.estado), esc(date(l.importadoEm)), `<a class="btn small" href="#/lote/${esc(l.id)}">Detalhes</a>`])) + pager(data, 'lots'));
  }
  async function lot(id) {
    const lot = await api.get(`/lotes/${id}`);
    const rejected = lot.estado === 'REJEITADO';
    const kind = rejected ? 'erros' : lot.tipo === 'OPERACIONAL' ? 'operacionais' : 'observacoes';
    const data = await page(rejected ? 'lotRowsErrors' : 'lotRows', `/lotes/${id}/${kind}`);
    let rows, headers;
    if (rejected) { headers = ['Registo CSV', 'Campo', 'Diagnóstico']; rows = data.items.map(e => [esc(e.numeroLinha), esc(e.campo), `<strong>${esc(e.codigo)}</strong><small>${esc(e.mensagem)}</small>`]); }
    else if (kind === 'operacionais') {
      headers = ['Produto', 'Mês', 'Solicitadas / satisfeitas', 'Dias observados / sem stock', 'Stock / limite', 'Perdas', 'Disponível'];
      rows = data.items.map(r => [esc(r.produto), esc(month(r.periodo)), `${q(r.valores.unidades_solicitadas)} / ${q(r.valores.unidades_satisfeitas_no_prazo)}`, `${q(r.valores.dias_observados_com_procura)} / ${q(r.valores.dias_com_procura_sem_stock)}`, `${q(r.valores.stock_fecho)} / ${q(r.valores.limite_stock)}`, q(r.valores.unidades_perdidas), esc(date(r.disponivelEm))]);
    } else { headers = ['Produto', 'Mês', 'Tipo', 'Quantidade', 'Unidade', 'Disponível']; rows = data.items.map(r => [esc(r.produto), esc(month(r.periodo)), esc(r.tipo), `<span class="num">${q(r.quantidade)}</span>`, esc(r.unidade), esc(date(r.disponivelEm))]); }
    const forecastAction = lot.tipo === 'HISTORICO' && lot.estado === 'VALIDADO' ? newButton('Gerar previsão', 'forecast', `data-history="${esc(lot.id)}"`) : '';
    return back('importacoes', 'Importações') + heading('Lote #' + esc(id), esc(lot.nomeFicheiro), 'Origem: ' + esc(lot.origem), badge(lot.estado) + forecastAction) +
      panel('Proveniência', '', meta([['Tipo', esc(label(lot.tipo))], ['Recebido', esc(date(lot.importadoEm))], ['Registos', esc(lot.totalLinhas)], ['Erros', esc(lot.totalErros)], ['Corrige lote', esc(lot.substituiLoteId || '—')]]) + `<div class="detail-description"><small>SHA-256</small><br><code>${esc(lot.sha256)}</code></div>`) +
      panel(rejected ? 'Diagnósticos da importação' : 'Registos validados', rejected ? 'Corrige o ficheiro e importa um lote novo.' : 'Consulta dos dados, sem alterar o lote.', table(headers, rows) + pager(data, rejected ? 'lotRowsErrors' : 'lotRows'));
  }

  async function forecasts() {
    const data = await page('forecasts', '/execucoes-previsao');
    return heading('Antecipar a procura', 'Previsões', 'Gera uma base reproduzível a partir de um histórico validado.', newButton('Gerar previsão', 'forecast') + '<a class="btn" href="#/backtests">Comparar modelos</a>') +
      panel('Execuções de previsão', 'Cada execução conserva o histórico, a configuração e os resultados.', table(['Execução', 'Método', 'Último mês de treino', 'Horizonte', 'Produtos previstos', 'Estado', ''], data.items.map(e => [`<strong>#${esc(e.id)}</strong><small>Lote #${esc(e.loteHistoricoId)} · ${esc(e.tipoSerie)}</small>`, esc(label(e.metodo)), esc(month(e.ultimoPeriodoTreino)), esc(e.horizonteMeses) + ' meses', esc(e.produtosPrevistos), badge(e.estado), `<a class="btn small" href="#/previsao/${esc(e.id)}">Consultar</a>`])) + pager(data, 'forecasts'));
  }
  async function forecast(id) {
    const [e, lines, diagnostics] = await Promise.all([api.get(`/execucoes-previsao/${id}`), page('forecastLines', `/execucoes-previsao/${id}/linhas`), page('diagnostics', `/execucoes-previsao/${id}/diagnosticos`)]);
    const first = lines.items[0]; const points = lines.items.filter(l => l.produtoId === first?.produtoId);
    return back('previsoes', 'Previsões') + heading('Previsão #' + esc(id), esc(label(e.metodo)) + ' · ' + e.horizonteMeses + ' meses', 'Histórico e parâmetros fixos para esta execução.', badge(e.estado) + `<a class="btn primary" href="#/cenarios?execucaoId=${esc(id)}">Explorar cenários ${icon('arrow', 16)}</a>`) +
      panel('Configuração da execução', '', meta([['Histórico', 'Lote #' + esc(e.loteHistoricoId)], ['Série', esc(e.tipoSerie)], ['Dados conhecidos até', esc(date(e.corteTemporal))], ['Último mês de treino', esc(month(e.ultimoPeriodoTreino))], ['Gerada', esc(date(e.criadoEm))]])) +
      panel('Procura prevista' + (first ? ' · ' + esc(first.produto) : ''), first ? 'Primeiro produto da página · ' + esc(first.unidade) : '', first ? lineChart(points.map(p => p.periodo), [{ name: 'Previsão base', values: points.map(p => p.quantidadeBase) }]) : empty('Sem previsões disponíveis')) +
      panel('Quantidades base', 'Valores por produto e mês.', table(['Produto', 'Mês', 'Horizonte', 'Quantidade', 'Unidade'], lines.items.map(l => [esc(l.produto), esc(month(l.periodo)), esc(l.horizonte), q(l.quantidadeBase), esc(l.unidade)])) + pager(lines, 'forecastLines')) +
      panel('Qualidade do cálculo', 'Históricos insuficientes e métodos de recurso ficam identificados.', table(['Produto', 'Estado', 'Método aplicado', 'Diagnóstico'], diagnostics.items.map(d => [esc(d.produto), badge(d.estado), esc(d.metodoAplicado || '—'), esc(d.diagnostico || 'Sem restrições assinaladas')])) + pager(diagnostics, 'diagnostics'));
  }

  async function backtests() {
    const data = await page('backtests', '/backtests');
    return heading('Avaliação temporal', 'Comparação de modelos', 'Média Móvel e ETS sobre os mesmos cortes históricos, sem confundir simulação com plano publicado.', newButton('Novo backtest', 'backtest')) +
      panel('Simulações retrospetivas', 'Parâmetros estimados apenas com os dados anteriores a cada corte.', table(['Simulação', 'Histórico', 'Cortes', 'Horizonte', 'Células', 'Estado', ''], data.items.map(t => [`#${esc(t.id)}`, `Lote #${esc(t.loteHistoricoId)}`, `${esc(month(t.primeiroCorte))} → ${esc(month(t.ultimoCorte))}`, esc(t.horizonteMeses), esc(t.totalCelulas), badge(t.estado), `<a class="btn small" href="#/backtest/${esc(t.id)}">Comparar</a>`])) + pager(data, 'backtests'));
  }
  async function backtest(id) {
    const [t, comparison, cells] = await Promise.all([api.get(`/backtests/${id}`), page('backtestGroups', `/backtests/${id}/comparacao`), page('backtestCells', `/backtests/${id}/celulas`)]);
    return back('backtests', 'Comparação de modelos') + heading('Backtest #' + esc(id), 'Precisão dos modelos', 'Cada linha compara os mesmos pares de produto, mês e horizonte.', badge(t.estado)) +
      panel('Cortes e configuração', '', meta([['Histórico', 'Lote #' + esc(t.loteHistoricoId)], ['Primeiro corte', esc(month(t.primeiroCorte))], ['Último corte', esc(month(t.ultimoCorte))], ['Horizonte', t.horizonteMeses + ' meses']])) +
      panel('Média Móvel vs. ETS', 'MAE inclui zeros. MAPE exclui realizados iguais a zero.', table(['Produto / horizonte', 'Pares / candidatos', 'Cobertura', 'MAE MM', 'MAE ETS', 'MAPE MM', 'MAPE ETS'], comparison.items.map(g => [`<strong>${esc(g.produto)}</strong><small>Horizonte ${esc(g.horizonte)} · ${esc(g.unidade)}</small>`, `${g.metricas.paresComparaveis} / ${g.metricas.candidatos}`, percent(g.metricas.coberturaPercentagem), q(g.metricas.maeMediaMovel), q(g.metricas.maeEts), percent(g.metricas.mapeMediaMovel), percent(g.metricas.mapeEts)])) + pager(comparison, 'backtestGroups')) +
      panel('Células da simulação', 'Detalhe do realizado e das previsões por corte.', table(['Produto', 'Corte', 'Mês previsto', 'Realizado', 'Média Móvel', 'ETS', 'Comparável'], cells.items.map(c => [esc(c.produto), esc(month(c.corte)), esc(month(c.periodo)), q(c.realizado), q(c.previsaoMediaMovel), q(c.previsaoEts), c.comparavel ? 'Sim' : 'Não'])) + pager(cells, 'backtestCells'));
  }

  async function scenarios(params) {
    const executions = (await api.all('/execucoes-previsao')).filter(e => e.estado === 'CONCLUIDA' && e.totalLinhas > 0);
    const id = params.get('execucaoId') || state.executionId || executions[0]?.id;
    if (state.executionId !== id) { state.compareIds = []; state.compareNames = {}; state.comparison = null; state.executionId = id; state.pages.scenarios = 0; }
    if (!id) return heading('Explorar alternativas', 'Cenários', 'Uma previsão base, várias decisões possíveis.') + panel('Começar um cenário', '', empty('Ainda não há previsões elegíveis', 'Gera uma previsão concluída com linhas antes de criar cenários.') + '<div class="panel-body"><a class="btn primary" href="#/previsoes">Ir para previsões</a></div>');
    const data = await page('scenarios', `/cenarios?execucaoId=${encodeURIComponent(id)}`);
    state.compareNames ||= {};
    for (const c of data.items) state.compareNames[c.ultimaRevisaoId] = c.nome;
    let result = '';
    if (state.comparison && state.compareIds.length >= 2) {
      const comparison = await api.get(`/comparacao-cenarios?revisaoIds=${state.compareIds.map(encodeURIComponent).join(',')}&size=10&page=${state.pages.comparison || 0}&${filterParams('comparison')}`);
      const revisions = await Promise.all(comparison.revisoes.map(async r => ({ ...r, cenarioNome: state.compareNames[r.id] || (await api.get(`/cenarios/${r.cenarioId}`)).nome })));
      const headers = ['Produto / mês', 'Previsão base', ...revisions.map(r => `${esc(r.cenarioNome)}<br><small>${esc(revisionCaption(r))}</small>`)];
      const first = comparison.produtos.items[0];
      result = panel('Comparação de cenários', 'Compara as quantidades finais das revisões selecionadas. Os desvios são calculados face à previsão base.', (first ? lineChart(first.periodos.map(p => p.periodo), [{ name: 'Previsão base · ' + first.produto, values: first.periodos.map(p => p.quantidadeBase) }, ...revisions.map((r, i) => ({ name: r.cenarioNome + ' · revisão #' + r.id, values: first.periodos.map(p => p.cenarios[i].quantidadeFinal) }))]) : '') +
        `<div class="comparison">${table(headers, comparison.produtos.items.flatMap(p => p.periodos.map(period => [`<strong>${esc(p.produto)}</strong><small>${esc(month(period.periodo))} · ${esc(p.unidade)}</small>`, q(period.quantidadeBase), ...period.cenarios.map(c => `<strong class="num">${q(c.quantidadeFinal)}</strong><small>Desvio ${percent(c.desvioPercentual)}</small>`)])))}</div>` + pager(searchable(comparison.produtos, 'comparison'), 'comparison'));
    }
    return heading('Explorar alternativas', 'Cenários', 'Cria hipóteses de procura e compara-as com a previsão base.', newButton('Novo cenário', 'scenario', `data-execution="${esc(id)}"`)) + scenarioHelp() +
      panel('Cenários da previsão #' + esc(id), 'Seleciona de 2 a 5 cenários. A comparação usa a última revisão de cada um.', `<div class="scenario-source">${field('Previsão de origem', select('scenarioExecution', executions.map(e => [e.id, `#${e.id} · ${label(e.metodo)} · treino até ${e.ultimoPeriodoTreino}`]), id))}<a class="btn small" href="#/previsao/${esc(id)}">Ver previsão</a></div>` + table(['Comparar', 'Cenário', 'Tipo', 'Revisões guardadas', 'Criado', ''], data.items.map(c => [`<input aria-label="Comparar última revisão de ${esc(c.nome)}" type="checkbox" data-compare="${esc(c.ultimaRevisaoId)}" ${state.compareIds.includes(c.ultimaRevisaoId) ? 'checked' : ''}>`, `<strong>${esc(c.nome)}</strong><small>Cenário #${esc(c.id)}</small>`, esc(label(c.tipo)), esc(c.numeroRevisoes), esc(date(c.criadoEm)), `<a class="btn small" href="#/revisao/${esc(c.ultimaRevisaoId)}">Ver cenário</a>`])) + pager(data, 'scenarios') + `<div class="panel-body"><div class="actions">${state.compareIds.map(r => b(esc(state.compareNames[r] || 'Revisão #' + r) + ' ×', 'remove-compare', `data-id="${esc(r)}"`, 'small')).join('')}${b('Comparar selecionadas', 'compare', state.compareIds.length < 2 ? 'disabled' : '', 'primary')}</div></div>`) + result;
  }
  async function scenario(id, params) {
    const [c, revisions] = await Promise.all([api.get(`/cenarios/${id}`), page('revisions', `/cenarios/${id}/revisoes`)]);
    let returnId = c.ultimaRevisaoId;
    const requested = params?.get('revisaoId');
    if (requested && /^[1-9][0-9]{0,18}$/.test(requested)) {
      try {
        const source = revisions.items.find(r => r.id === requested) || await api.get(`/revisoes/${requested}`);
        if (source.cenarioId === c.id) returnId = source.id;
      } catch (error) { if (![400, 404].includes(error.status)) throw error; }
    }
    const actions = `<a class="btn primary" href="#/revisao/${esc(returnId)}">Voltar ao cenário</a>` + (writer() ? b('Novo cenário', 'dialog', `data-kind="scenario" data-execution="${esc(c.execucaoId)}"`) : '');
    return back(`cenarios?execucaoId=${c.execucaoId}`, 'Cenários da mesma previsão') + heading('Cenário #' + esc(id), esc(c.nome), `${esc(label(c.tipo))} · Previsão #${esc(c.execucaoId)} · ${c.numeroRevisoes} revisões guardadas`, actions) + scenarioHelp() +
      panel('Histórico de revisões', 'Cada revisão tem um ID único. A versão indica a ordem em que foi guardada neste cenário.', table(['Revisão', 'Fator', 'Estado', 'Ajustes locais', 'Criada', ''], revisions.items.map(r => [`<strong>Revisão #${esc(r.id)}</strong><small>${r.numero}.ª versão deste cenário</small>`, q(factorValue(r.fator)), badge(r.estado), esc(r.totalAjustesLocais), esc(date(r.criadoEm)), `<a class="btn small" href="#/revisao/${esc(r.id)}">Abrir</a>`])) + pager(revisions, 'revisions'));
  }
  async function revision(id) {
    const [r, lines, adjustments, events] = await Promise.all([api.get(`/revisoes/${id}`), page('revisionLines', `/revisoes/${id}/linhas`), api.all(`/revisoes/${id}/ajustes?incluirHerdados=true`), page('events', `/revisoes/${id}/eventos`)]);
    const c = await api.get(`/cenarios/${r.cenarioId}`);
    const audit = await page('audit', `/revisoes/${id}/ajustes?incluirHerdados=true`);
    const canDecide = ctx.approver() && independent(state.user, r, adjustments);
    const actions = (writer() ? b('Novo cenário', 'dialog', `data-kind="scenario" data-execution="${esc(r.execucaoId)}"`) + b('Criar nova revisão', 'dialog', 'data-kind="copy" title="Guardar outra versão deste mesmo cenário"') : '') + (writer() && r.estado === 'RASCUNHO' ? b('Enviar para aprovação', 'dialog', 'data-kind="submit"', 'primary') : '') +
      (canDecide && r.estado === 'EM_REVISAO' ? b('Devolver', 'dialog', 'data-kind="return"', 'danger') + b('Aprovar e publicar', 'dialog', 'data-kind="approve"', 'primary') : '');
    const first = lines.items[0], points = lines.items.filter(l => l.produtoId === first?.produtoId);
    let note = r.estado === 'RASCUNHO' ? info('Rascunho · Ajusta com justificação e envia para aprovação.') : info('Revisão congelada · Cria uma nova revisão para alterar quantidades.');
    if (ctx.approver() && !canDecide && r.estado === 'EM_REVISAO') note += info('A decisão exige outro aprovador: criaste esta revisão ou participaste nos ajustes que ela conserva.', true);
    return { context: { revision: r, adjustments, revisionCells: lines.items, currentScenario: c }, html: back(`cenarios?execucaoId=${r.execucaoId}`, 'Cenários da mesma previsão') + heading('Revisão #' + esc(r.id), esc(c.nome), `Cenário #${esc(c.id)} · ${r.numero}.ª versão deste cenário · ${esc(label(c.tipo))}`, actions, `data-revision-id="${esc(r.id)}" data-revision-version="${esc(r.versao)}"`) + note + scenarioHelp() +
      panel('Pressupostos desta revisão', '', meta([['Estado', badge(r.estado)], ['Fator', q(factorValue(r.fator))], ['Previsão', '#' + esc(r.execucaoId)], ['Origem', r.revisaoOrigemId ? `<a href="#/revisao/${esc(r.revisaoOrigemId)}">Revisão #${esc(r.revisaoOrigemId)}</a>` : 'Previsão base'], ['Enviada para aprovação', esc(date(r.submetidoEm))]]) + `<p class="detail-description">${esc(r.pressupostos.descricao)}</p><div class="panel-body"><a class="btn small" href="#/cenario/${esc(c.id)}?revisaoId=${esc(r.id)}">Histórico de revisões deste cenário</a></div>`) +
      panel('Evolução do cenário' + (first ? ' · ' + esc(first.produto) : ''), first ? 'Primeiro produto da página · ' + esc(first.unidade) : '', first ? lineChart(points.map(p => p.periodo), [{ name: 'Base', values: points.map(p => p.quantidadeBase) }, { name: 'Calculada', values: points.map(p => p.quantidadeCalculada) }, { name: 'Final', values: points.map(p => p.quantidadeFinal) }]) : empty('Sem linhas')) +
      panel('Quantidades por mês', 'Ajustes disponíveis apenas em rascunho.', table(['Produto / mês', 'Base', 'Calculada', 'Final', 'Desvio', ''], lines.items.map((l, i) => [`<strong>${esc(l.produto)}</strong><small>${esc(month(l.periodo))} · ${esc(l.unidade)}</small>`, q(l.quantidadeBase), q(l.quantidadeCalculada), `<strong class="num">${q(l.quantidadeFinal)}</strong>`, percent(l.desvioPercentual), writer() && r.estado === 'RASCUNHO' ? b('Ajustar', 'adjust', `data-index="${i}"`, 'small') : '<span class="muted">Consulta</span>'])) + pager(lines, 'revisionLines')) +
      panel('Auditoria dos ajustes', 'Inclui os ajustes herdados que foram conservados no momento da cópia.', table(['Mês / produto', 'Anterior → novo', 'Justificação', 'Autor', 'Origem'], audit.items.map(a => [esc(month(a.periodo)) + '<small>Produto #' + esc(a.produtoId) + '</small>', `<span class="num">${q(a.valorAnterior)} → ${q(a.valorNovo)}</span>`, esc(a.justificacao), esc(a.autorNome) + '<small>' + esc(date(a.ocorridoEm)) + '</small>', a.herdado ? `<a href="#/revisao/${esc(a.revisaoId)}">Herdado · #${esc(a.revisaoId)}</a>` : 'Esta revisão'])) + pager(audit, 'audit'), '', ) +
      panel('Linha temporal', 'Eventos locais desta revisão.', (events.items.length ? `<div class="timeline">${events.items.map(e => `<div class="event"><i class="dot"></i><div><p><strong>${esc(label(e.tipo))}</strong> · ${esc(e.autorNome)}</p><small>${esc(e.motivo || '')}</small></div><time>${esc(date(e.ocorridoEm))}</time></div>`).join('')}</div>` : empty('Sem eventos')) + pager(events, 'events')) };
  }

  async function plans() {
    const data = await page('plans', '/planos');
    return heading('Decisões aprovadas', 'Planos publicados', 'Uma versão estável, com aprovação identificada e exportação para outras ferramentas.') +
      panel('Publicações', 'Rascunhos e revisões em preparação não aparecem nesta lista.', table(['Plano', 'Cenário / revisão', 'Publicação', 'Linhas', ''], data.items.map(p => [`<strong>Plano #${esc(p.id)}</strong>`, `Cenário #${esc(p.cenarioId)}<small>Revisão #${esc(p.revisaoId)}</small>`, esc(date(p.publicadoEm)), esc(p.totalLinhas), `<div class="actions"><a class="btn small" href="#/plano/${esc(p.id)}">Consultar</a>${rowAction('CSV', 'download', p.id)}</div>`])) + pager(data, 'plans'));
  }
  async function plan(id) {
    const [p, lines] = await Promise.all([api.get(`/planos/${id}`), page('planLines', `/planos/${id}/linhas`)]);
    return back('planos', 'Planos publicados') + heading('Plano publicado', 'Plano aprovado #' + esc(id), 'Os valores publicados e a exportação usam a mesma cópia preservada.', b('Descarregar CSV', 'download', `data-id="${esc(id)}"`, 'primary') + (writer() ? b('Criar avaliação', 'dialog', `data-kind="assessment" data-plan="${esc(id)}"`) : '')) +
      panel('Informação de aprovação', '', meta([['Revisão', '#' + esc(p.revisaoId)], ['Cenário', '#' + esc(p.cenarioId)], ['Aprovado por', 'Utilizador #' + esc(p.aprovadoPor)], ['Aprovação', esc(date(p.aprovadoEm))], ['Publicação', esc(date(p.publicadoEm))]])) +
      panel('Células publicadas', 'A consulta é paginada; o CSV inclui todas as linhas.', table(['Produto', 'Mês', 'Unidade', 'Previsão base', 'Quantidade aprovada'], lines.items.map(l => [esc(l.produto), esc(month(l.periodo)), esc(l.unidade), q(l.quantidadeBase), `<strong class="num">${q(l.quantidade)}</strong>`])) + pager(lines, 'planLines'));
  }

  async function monitoring() {
    const data = await page('assessments', '/avaliacoes');
    return heading('Fechar o ciclo', 'Monitorização', 'Avalia o plano com realizados conhecidos num corte explícito. Precisão e operação mantêm a sua cobertura.', newButton('Nova avaliação', 'assessment')) +
      panel('Avaliações guardadas', 'Novos lotes ou cortes criam outra avaliação e preservam as anteriores.', table(['Avaliação', 'Plano', 'Janela', 'Corte dos resultados', 'Células', ''], data.items.map(a => [`<strong>#${esc(a.id)}</strong><small>Realizados #${esc(a.loteRealizadosId)}</small>`, `<a href="#/plano/${esc(a.planoId)}">Plano #${esc(a.planoId)}</a>`, `${esc(month(a.periodoInicio))} → ${esc(month(a.periodoFim))}`, esc(date(a.corteRealizados)), esc(a.totalCelulas), `<a class="btn small" href="#/avaliacao/${esc(a.id)}">Analisar</a>`])) + pager(data, 'assessments'));
  }
  async function assessment(id) {
    const a = await api.get(`/avaliacoes/${id}`);
    const groups = await api.all(`/avaliacoes/${id}/precisao`);
    const selected = groups.find(g => g.produtoId === state.monitorProduct) || groups[0];state.monitorProduct = selected?.produtoId;
    const operations = await api.all(`/avaliacoes/${id}/operacao`);const op = operations.find(o => o.produtoId === selected?.produtoId);
    const cells = await page('assessmentCells', `/avaliacoes/${id}/celulas`);
    // A published forecast spans at most 12 months per product; chart data remains complete across list pages.
    const allCells = await api.all(`/avaliacoes/${id}/celulas`);const points = allCells.filter(c => c.produtoId === selected?.produtoId);
    const m = selected?.metricas;
    return back('monitorizacao', 'Monitorização') + heading('Avaliação #' + esc(id), 'Resultados do plano #' + esc(a.planoId), 'Compara base e aprovado sobre os mesmos pares elegíveis. Os dados em falta permanecem visíveis.') +
      panel('Contexto da avaliação', '', meta([['Plano', `<a href="#/plano/${esc(a.planoId)}">Plano #${esc(a.planoId)}</a>`], ['Realizados', 'Lote #' + esc(a.loteRealizadosId)], ['Operacionais', a.loteOperacionalId ? 'Lote #' + esc(a.loteOperacionalId) : 'Não selecionados'], ['Corte', esc(date(a.corteRealizados))]]) + `<div class="toolbar">${field('Produto / unidade', select('monitorProduct', groups.map(g => [g.produtoId, `${g.produto} · ${g.unidade}`]), state.monitorProduct))}</div>`) +
      (m ? `${m.paresMae === 0 ? info('Sem pares elegíveis para medir a precisão. Consulta os motivos de exclusão: um mês pode ainda não estar fechado, o plano ter sido publicado tarde ou faltar o realizado.', true) : ''}<div class="metrics">${metric('MAE base / aprovado', `${q(m.maeBase)} / ${q(m.maeAprovado)}`, `Unidade: ${selected.unidade}`, 'compact')}${metric('MAPE base / aprovado', `${percent(m.mapeBase)} / ${percent(m.mapeAprovado)}`, `${m.paresMape} pares · ${m.zerosExcluidosMape} zeros excluídos`, 'compact')}${metric('Cobertura da precisão', percent(m.coberturaPercentagem), `${m.paresMae} de ${m.candidatos} células`, 'highlight')}${metric('Ganho do ajuste', q(m.ganhoAjuste), 'MAE base − MAE aprovado', 'compact')}</div>` : '') +
      panel('Procura e plano' + (selected ? ' · ' + esc(selected.produto) : ''), 'As faltas não são substituídas por zero. Valores conhecidos após o corte não entram no gráfico.', lineChart(points.map(p => p.periodo), [{ name: 'Base', values: points.map(p => p.quantidadeBase) }, { name: 'Aprovado', values: points.map(p => p.quantidadeAprovada) }, { name: 'Realizado conhecido', values: points.map(p => p.quantidadeRealizada) }])) +
      `<div class="subheading">Resultados operacionais · ${esc(selected?.produto || '')}</div><div class="metrics">${metric('Nível de serviço', percent(op?.nivelServicoPercentagem), `${op?.paresServico || 0} pares conhecidos`, 'compact')}${metric('Taxa de rutura', percent(op?.ruturaPercentagem), `${op?.paresRutura || 0} pares de dias conhecidos`, 'compact')}${metric('Excesso médio de stock', q(op?.excessoStockMedio), `${op?.paresStock || 0} células com stock e limite`, 'compact')}${metric('Unidades perdidas', q(op?.unidadesPerdidasTotal), `${op?.registosPerdas || 0} registos conhecidos`, 'compact')}</div>` +
      panel('Detalhe e cobertura', 'Todas as células da avaliação, incluindo exclusões. Os indicadores operacionais exigem dados próprios.', table(['Produto / mês', 'Base / aprovado', 'Realizado', 'Elegibilidade da precisão', 'Serviço / rutura', 'Excesso / perdas'], cells.items.map(c => [`<strong>${esc(c.produto)}</strong><small>${esc(month(c.periodo))} · ${esc(c.unidade)}</small>`, `${q(c.quantidadeBase)} / ${q(c.quantidadeAprovada)}`, q(c.quantidadeRealizada), c.elegivelPrecisao ? badge('VALIDADO').replace('Validado', 'Elegível') : `<span class="reason">${esc(label(c.motivoExclusao))}</span>`, c.operacional ? `${percent(c.operacional.nivelServicoPercentagem)} / ${percent(c.operacional.ruturaPercentagem)}` : `<span class="reason">${esc(label(c.motivoOperacional))}</span>`, c.operacional ? `${q(c.operacional.excessoStock)} / ${q(c.operacional.unidadesPerdidas)}` : 'Indisponível'])) + pager(cells, 'assessmentCells')) +
      `<details class="secondary-details"><summary>Precisão por horizonte</summary>${table(['Horizonte', 'Pares / candidatos', 'MAE base', 'MAE aprovado', 'MAPE base', 'MAPE aprovado', 'Cobertura'], (selected?.porHorizonte || []).map(h => [esc(h.horizonte) + ' meses', `${h.metricas.paresMae} / ${h.metricas.candidatos}`, q(h.metricas.maeBase), q(h.metricas.maeAprovado), percent(h.metricas.mapeBase), percent(h.metricas.mapeAprovado), percent(h.metricas.coberturaPercentagem)]))}</details>`;
  }

  async function team() {
    const users = await page('team', '/utilizadores');
    return heading('Acesso e responsabilidades', 'Equipa', 'Cria contas com os papéis necessários. A aprovação exige independência de quem preparou ou ajustou a revisão.', b(icon('plus', 16) + 'Criar utilizador', 'dialog', 'data-kind="user"', 'primary')) +
      panel('Membros da equipa', 'Consulta reservada ao administrador.', table(['ID', 'Nome', 'Email', 'Perfis', ''], users.items.map(u => [`<strong>#${esc(u.id)}</strong>`, esc(u.nome), esc(u.email), esc(u.papeis.map(label).join(' · ')), rowAction('Consultar', 'lookup-user', u.id)])) + pager(users, 'team')) +
      `<details class="secondary-details">
        <summary>Responsabilidades por perfil</summary>
        <dl class="team-roles">
          <div><dt>Administrador</dt><dd>Gere contas e prepara dados e planos. O papel de administrador por si só não permite aprovar.</dd></div>
          <div><dt>Planeador</dt><dd>Importa dados, gera previsões, cria cenários e envia revisões para aprovação.</dd></div>
          <div><dt>Comercial</dt><dd>Consulta dados, cenários e resultados.</dd></div>
          <div><dt>Aprovador</dt><dd>Aprova ou devolve revisões em que não interveio.</dd></div>
          <div><dt>Consumidor</dt><dd>Consulta produtos, planos publicados e monitorização.</dd></div>
        </dl>
      </details>`;
  }

  return { visao: overview, produtos: products, importacoes: imports, lote: lot, previsoes: forecasts, previsao: forecast, backtests, backtest, cenarios: scenarios, cenario: scenario, revisao: revision, planos: plans, plano: plan, monitorizacao: monitoring, avaliacao: assessment, equipa: team };
}
