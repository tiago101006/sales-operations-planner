import { esc, quantity as q, percent, date, month, label, badge, icon, button as b, field, select, input, table, panel, metric, pager, empty, lineChart, independent, role } from './ui.mjs';

const rowAction = (text, action, id) => b(text, action, `data-id="${esc(id)}"`, 'small');
const meta = values => `<div class="detail-meta">${values.map(([name, value]) => `<div><small>${name}</small><p>${value}</p></div>`).join('')}</div>`;
const info = (text, warn = false) => `<div class="info ${warn ? 'warn' : ''}">${text}</div>`;
const miniChart = `<svg class="mini-chart" viewBox="0 0 420 180" fill="none" aria-hidden="true"><path d="M10 150H410M10 110H410M10 70H410M10 30H410" stroke="currentColor" opacity=".12"/><path d="M10 144 68 122 125 132 183 84 240 98 298 55 355 66 410 24V165H10Z" fill="currentColor" opacity=".08"/><path d="M10 144 68 122 125 132 183 84 240 98 298 55 355 66 410 24" stroke="currentColor" stroke-width="2.5"/><path d="m240 98 58-15 57 10 55-25" stroke="currentColor" opacity=".45" stroke-width="2" stroke-dasharray="6 5"/><circle cx="410" cy="24" r="5" fill="currentColor"/></svg>`;
export { miniChart };

export function createViews(ctx) {
  const { api, state, writer } = ctx;
  const page = (key, path) => api.get(`${path}${path.includes('?') ? '&' : '?'}size=20&page=${state.pages[key] || 0}`);
  const heading = (eyebrow, title, subtitle, actions = '') => `<div class="page-heading"><div><div class="eyebrow">${eyebrow}</div><h1>${title}</h1><p>${subtitle}</p></div><div class="actions">${actions}</div></div>`;
  const newButton = (text, kind, extra = '') => writer() ? b(icon('plus', 16) + text, 'dialog', `data-kind="${kind}" ${extra}`, 'primary') : '';
  const back = (route, text) => `<a class="back" href="#/${route}">← ${text}</a>`;

  async function overview() {
    if (role(state.user, 'ADMIN')) return adminOverview();
    const internal = ctx.internal();
    const [plans, assessments, cases, lots] = await Promise.all([
      api.get('/planos?size=5'), api.get('/avaliacoes?size=5'),
      internal ? api.get('/cenarios?size=1') : null, internal ? api.get('/lotes?size=1') : null,
    ]);
    const cards = [metric('Planos publicados', plans.totalElements, 'Versões aprovadas e preservadas', 'highlight'),
      ...(internal ? [metric('Cenários', cases.totalElements, 'Alternativas para o planeamento'), metric('Lotes de dados', lots.totalElements, 'Históricos e resultados recebidos')] : []),
      metric('Avaliações', assessments.totalElements, 'Precisão e resultados operacionais')];
    const workflow = [['importacoes', 'Organizar os dados', 'Importa históricos e consulta a qualidade dos registos.'], ['previsoes', 'Antecipar a procura', 'Gera previsões base e compara os modelos.'], ['cenarios', 'Construir alternativas', 'Revê pressupostos e justifica cada ajuste.'], ['planos', 'Publicar o plano', 'Aprovação independente e uma versão estável.'], ['monitorizacao', 'Aprender com os resultados', 'Acompanha a precisão, cobertura e operação.']];
    return heading('O teu espaço de planeamento', 'Visão geral', 'Da procura prevista à decisão aprovada. Tudo ligado, com histórico.') +
      `<section class="hero"><div class="hero-copy"><div class="eyebrow">Planeamento com contexto</div><h2>Melhores decisões começam<br>com uma visão de conjunto.</h2><p>Transforma históricos em previsões, explora cenários e acompanha o resultado de cada plano.</p><a class="btn dark" href="#/${internal ? 'cenarios' : 'planos'}">${internal ? 'Explorar cenários' : 'Consultar planos'}${icon('arrow', 16)}</a></div><div class="hero-art">${miniChart}<div class="hero-note">Antecipar · Planear · Acompanhar</div></div></section>` +
      `<div class="metrics">${cards.join('')}</div><div class="two-columns">` + panel('Planos recentes', 'As últimas decisões publicadas.', table(['Plano', 'Publicação', 'Linhas', ''], plans.items.map(p => [`<strong>Plano #${esc(p.id)}</strong><small>Revisão #${esc(p.revisaoId)}</small>`, esc(date(p.publicadoEm)), esc(p.totalLinhas), `<a class="btn small" href="#/plano/${esc(p.id)}">Consultar</a>`])), '<a class="btn link" href="#/planos">Ver todos →</a>') +
      panel('O ciclo de planeamento', 'Um percurso, com decisões rastreáveis.', `<div class="workflow">${workflow.filter(([route]) => internal || ['planos', 'monitorizacao'].includes(route)).map(([route, title, desc], i) => `<a class="workflow-step" href="#/${route}"><span class="step-number">${String(i + 1).padStart(2, '0')}</span><div><strong>${title}</strong><p>${desc}</p></div><span class="next">${icon('arrow', 16)}</span></a>`).join('')}</div>`) + '</div>';
  }

  async function adminOverview() {
    const [products, executions, cases, plans, lots] = await Promise.all([
      api.get('/produtos?size=1'), api.get('/execucoes-previsao?size=5'),
      api.get('/cenarios?size=1'), api.get('/planos?size=5'), api.get('/lotes?size=4'),
    ]);
    const latestPlan = plans.items[0];
    const execution = executions.items.find(e => e.estado === 'CONCLUIDA' && e.totalLinhas > 0);
    let chart = empty('O teu planeamento começa aqui', 'Importa um histórico e gera uma previsão. O gráfico aparece assim que existirem quantidades previstas.');
    let chartSubtitle = 'Previsão base e quantidades aprovadas, por produto e mês.';
    let chartAction = '', chartFoot = '';
    if (latestPlan || execution) {
      const lines = await api.get(latestPlan ? `/planos/${latestPlan.id}/linhas?size=12` : `/execucoes-previsao/${execution.id}/linhas?size=12`);
      const first = lines.items[0];
      if (first) {
        const points = lines.items.filter(l => l.produtoId === first.produtoId);
        const series = [{ name: 'Previsão base', values: points.map(l => l.quantidadeBase) }];
        if (latestPlan) series.push({ name: 'Plano aprovado', values: points.map(l => l.quantidade) });
        chartSubtitle = `${esc(first.produto)} · ${esc(first.unidade)} · ${latestPlan ? 'Plano #' + esc(latestPlan.id) : 'Execução #' + esc(execution.id)} · primeiro produto`;
        chart = lineChart(points.map(l => l.periodo), series, `Previsão e planeamento de ${first.produto}, em ${first.unidade}`, { area: true });
        chartAction = `<a class="btn small" href="#/${latestPlan ? 'plano/' + esc(latestPlan.id) : 'previsao/' + esc(execution.id)}">Ver detalhe ${icon('arrow', 14)}</a>`;
        chartFoot = `<div class="chart-context"><span>${icon('box', 15)} ${esc(first.produto)} · ${esc(first.unidade)}</span><span>${esc(month(points[0].periodo))} — ${esc(month(points.at(-1).periodo))}</span><span>${points.length} meses representados</span></div>`;
      }
    }
    const dashboardMetric = (title, value, note, glyph, tone, route) => `<a class="dashboard-metric ${tone}" href="#/${route}"><div class="metric-top"><span>${title}</span><span class="metric-symbol">${icon(glyph, 22)}</span></div><strong>${esc(q(value))}</strong><div class="metric-bottom"><span>${note}</span>${icon('arrow', 15)}</div></a>`;
    const today = new Intl.DateTimeFormat('pt-PT', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date());
    const shortcuts = [['importacoes', 'upload', 'Organizar os dados', 'Importar e validar históricos'], ['previsoes', 'trend', 'Antecipar a procura', 'Gerar e consultar previsões'], ['cenarios', 'layers', 'Explorar cenários', 'Comparar alternativas e ajustes'], ['monitorizacao', 'chart', 'Acompanhar resultados', 'Consultar precisão e operação']];
    return `<div class="admin-dashboard">` + heading('Painel de administração', 'Visão geral', 'O teu planeamento, numa só perspetiva.', `<span class="dashboard-date">${icon('calendar', 16)}${esc(today)}</span>${newButton('Importar CSV', 'import')}`) +
      `<div class="dashboard-metrics">${dashboardMetric('Produtos', products.totalElements, 'No catálogo da equipa', 'box', 'sage', 'produtos')}${dashboardMetric('Previsões', executions.totalElements, 'Execuções registadas', 'trend', 'sand', 'previsoes')}${dashboardMetric('Cenários', cases.totalElements, 'Alternativas de planeamento', 'layers', 'clay', 'cenarios')}${dashboardMetric('Planos publicados', plans.totalElements, 'Decisões aprovadas', 'check', 'forest', 'planos')}</div>` +
      `<div class="dashboard-main"><div class="dashboard-chart">${panel('Da previsão ao plano', chartSubtitle, chart + chartFoot, chartAction)}</div><aside class="dashboard-guide"><div class="guide-heading"><span class="guide-mark">${icon('layers', 22)}</span><span>O teu espaço S&OP</span></div><h2>Mais clareza.<br>Melhores decisões.</h2><p>Cada etapa aproxima os dados de um plano com confiança.</p><div class="dashboard-shortcuts">${shortcuts.map(([route, glyph, title, note]) => `<a href="#/${route}"><span class="shortcut-symbol">${icon(glyph, 18)}</span><span><strong>${title}</strong><small>${note}</small></span>${icon('arrow', 16)}</a>`).join('')}</div><a class="guide-team" href="#/equipa">${icon('people', 16)} Gerir a equipa ${icon('arrow', 15)}</a></aside></div>` +
      `<div class="dashboard-bottom">${panel('Planos recentes', 'As últimas decisões publicadas pela equipa.', table(['Plano', 'Publicado em', 'Linhas', 'Estado', ''], plans.items.map(p => [`<span class="plan-name"><span class="plan-symbol">${icon('check', 18)}</span><span><strong>Plano #${esc(p.id)}</strong><small>Revisão #${esc(p.revisaoId)}</small></span></span>`, esc(date(p.publicadoEm)), esc(p.totalLinhas), badge('APROVADA').replace('Aprovada', 'Publicado'), `<a class="btn small" href="#/plano/${esc(p.id)}">Consultar</a>`])), '<a class="btn link" href="#/planos">Ver todos →</a>')}` +
      panel('Últimas importações', 'Qualidade dos dados à entrada.', lots.items.length ? `<div class="dashboard-imports">${lots.items.map(l => `<a href="#/lote/${esc(l.id)}"><span class="import-symbol ${l.estado === 'REJEITADO' ? 'rejected' : ''}">${icon('upload', 18)}</span><span class="import-name"><strong>${esc(l.nomeFicheiro)}</strong><small>${esc(label(l.tipo))} · ${esc(date(l.importadoEm))}</small></span>${badge(l.estado)}</a>`).join('')}</div>` : empty('Ainda não há importações', 'Começa por importar um histórico CSV.'), '<a class="btn link" href="#/importacoes">Ver todas →</a>') + `</div><footer class="dashboard-footer"><span><span class="status-dot"></span>Dados da equipa · Consulta atualizada ao abrir o painel</span><span>NexoPlan / Planeamento S&OP</span></footer></div>`;
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
      info('Históricos alimentam as previsões. Realizados e operacionais permitem avaliar os planos publicados. Um lote com erros mantém os diagnósticos e não publica registos parciais.') +
      panel('Lotes recebidos', 'O original e o histórico de cada importação são conservados.', table(['Ficheiro / origem', 'Tipo', 'Registos', 'Estado', 'Recebido', ''], data.items.map(l => [`<strong>${esc(l.nomeFicheiro)}</strong><small>#${esc(l.id)} · ${esc(l.origem)}</small>`, esc(label(l.tipo)), esc(l.totalLinhas), badge(l.estado), esc(date(l.importadoEm)), `<a class="btn small" href="#/lote/${esc(l.id)}">Detalhes</a>`])) + pager(data, 'lots'));
  }
  async function lot(id) {
    const lot = await api.get(`/lotes/${id}`);
    const rejected = lot.estado === 'REJEITADO';
    const kind = rejected ? 'erros' : lot.tipo === 'OPERACIONAL' ? 'operacionais' : 'observacoes';
    const data = await page('lotRows', `/lotes/${id}/${kind}`);
    let rows, headers;
    if (rejected) { headers = ['Registo CSV', 'Campo', 'Diagnóstico']; rows = data.items.map(e => [esc(e.numeroLinha), esc(e.campo), `<strong>${esc(e.codigo)}</strong><small>${esc(e.mensagem)}</small>`]); }
    else if (kind === 'operacionais') {
      headers = ['Produto', 'Mês', 'Solicitadas / satisfeitas', 'Dias observados / sem stock', 'Stock / limite', 'Perdas', 'Disponível'];
      rows = data.items.map(r => [esc(r.produto), esc(month(r.periodo)), `${q(r.valores.unidades_solicitadas)} / ${q(r.valores.unidades_satisfeitas_no_prazo)}`, `${q(r.valores.dias_observados_com_procura)} / ${q(r.valores.dias_com_procura_sem_stock)}`, `${q(r.valores.stock_fecho)} / ${q(r.valores.limite_stock)}`, q(r.valores.unidades_perdidas), esc(date(r.disponivelEm))]);
    } else { headers = ['Produto', 'Mês', 'Tipo', 'Quantidade', 'Unidade', 'Disponível']; rows = data.items.map(r => [esc(r.produto), esc(month(r.periodo)), esc(r.tipo), `<span class="num">${q(r.quantidade)}</span>`, esc(r.unidade), esc(date(r.disponivelEm))]); }
    return back('importacoes', 'Importações') + heading('Lote #' + esc(id), esc(lot.nomeFicheiro), 'Origem: ' + esc(lot.origem), badge(lot.estado)) +
      panel('Proveniência', '', meta([['Tipo', esc(label(lot.tipo))], ['Recebido', esc(date(lot.importadoEm))], ['Registos', esc(lot.totalLinhas)], ['Erros', esc(lot.totalErros)], ['Corrige lote', esc(lot.substituiLoteId || '—')]]) + `<div class="detail-description"><small>SHA-256</small><br><code>${esc(lot.sha256)}</code></div>`) +
      panel(rejected ? 'Diagnósticos da importação' : 'Registos validados', rejected ? 'Corrige o ficheiro e importa um lote novo.' : 'Consulta dos dados, sem alterar o lote.', table(headers, rows) + pager(data, 'lotRows'));
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
    if (state.executionId !== id) { state.compareIds = []; state.comparison = null; state.executionId = id; state.pages.scenarios = 0; }
    if (!id) return heading('Explorar alternativas', 'Cenários', 'Uma previsão base, várias decisões possíveis.') + panel('Começar um cenário', '', empty('Ainda não há previsões elegíveis', 'Gera uma previsão concluída com linhas antes de criar cenários.') + '<div class="panel-body"><a class="btn primary" href="#/previsoes">Ir para previsões</a></div>');
    const data = await page('scenarios', `/cenarios?execucaoId=${encodeURIComponent(id)}`);
    let result = '';
    if (state.comparison && state.compareIds.length >= 2) {
      const comparison = await api.get(`/comparacao-cenarios?revisaoIds=${state.compareIds.map(encodeURIComponent).join(',')}&size=10&page=${state.pages.comparison || 0}`);
      const headers = ['Produto / mês', 'Base', ...comparison.revisoes.map(r => `Cenário #${esc(r.cenarioId)}<br>Revisão ${r.numero} · v${r.versao}`)];
      const first = comparison.produtos.items[0];
      result = panel('Comparação de revisões', 'Quantidades finais; o desvio é calculado face à base. Todos os valores mantêm a unidade do produto.', (first ? lineChart(first.periodos.map(p => p.periodo), [{ name: 'Base · ' + first.produto, values: first.periodos.map(p => p.quantidadeBase) }, ...comparison.revisoes.map((r, i) => ({ name: 'Revisão #' + r.id, values: first.periodos.map(p => p.cenarios[i].quantidadeFinal) }))]) : '') +
        `<div class="comparison">${table(headers, comparison.produtos.items.flatMap(p => p.periodos.map(period => [`<strong>${esc(p.produto)}</strong><small>${esc(month(period.periodo))} · ${esc(p.unidade)}</small>`, q(period.quantidadeBase), ...period.cenarios.map(c => `<strong class="num">${q(c.quantidadeFinal)}</strong><small>Desvio ${percent(c.desvioPercentual)}</small>`)])))}</div>` + pager(comparison.produtos, 'comparison'));
    }
    return heading('Explorar alternativas', 'Cenários', 'Define pressupostos, compara alternativas e conserva a previsão base.', newButton('Novo cenário', 'scenario', `data-execution="${esc(id)}"`)) +
      panel('Base de planeamento', '', `<div class="toolbar">${field('Execução de previsão', select('scenarioExecution', executions.map(e => [e.id, `#${e.id} · ${label(e.metodo)} · treino até ${e.ultimoPeriodoTreino}`]), id))}</div>`) +
      panel('Cenários da execução #' + esc(id), 'Seleciona de 2 a 5 revisões da mesma execução para comparar.', table(['Comparar', 'Cenário', 'Tipo', 'Revisões', 'Criado', ''], data.items.map(c => [`<input aria-label="Comparar última revisão de ${esc(c.nome)}" type="checkbox" data-compare="${esc(c.ultimaRevisaoId)}" ${state.compareIds.includes(c.ultimaRevisaoId) ? 'checked' : ''}>`, `<strong>${esc(c.nome)}</strong><small>Cenário #${esc(c.id)}</small>`, esc(label(c.tipo)), esc(c.numeroRevisoes), esc(date(c.criadoEm)), `<a class="btn small" href="#/cenario/${esc(c.id)}">Ver revisões</a>`])) + pager(data, 'scenarios') + `<div class="panel-body"><div class="actions">${state.compareIds.map(r => b('Revisão #' + esc(r) + ' ×', 'remove-compare', `data-id="${esc(r)}"`, 'small')).join('')}${b('Comparar selecionadas', 'compare', state.compareIds.length < 2 ? 'disabled' : '', 'primary')}</div></div>`) + result;
  }
  async function scenario(id) {
    const [c, revisions] = await Promise.all([api.get(`/cenarios/${id}`), page('revisions', `/cenarios/${id}/revisoes`)]);
    return back(`cenarios?execucaoId=${c.execucaoId}`, 'Cenários') + heading('Cenário #' + esc(id), esc(c.nome), `${esc(label(c.tipo))} · Execução #${esc(c.execucaoId)} · ${c.numeroRevisoes} revisões`) +
      panel('Histórico de revisões', 'Cada revisão conserva o fator, os pressupostos e a proveniência.', table(['Revisão', 'Fator', 'Estado', 'Ajustes locais', 'Criada', ''], revisions.items.map(r => [`<strong>Revisão ${r.numero}</strong><small>#${esc(r.id)} · versão ${r.versao}</small>`, q(r.fator), badge(r.estado), esc(r.totalAjustesLocais), esc(date(r.criadoEm)), `<a class="btn small" href="#/revisao/${esc(r.id)}">Abrir</a>`])) + pager(revisions, 'revisions'));
  }
  async function revision(id) {
    const [r, lines, adjustments, events] = await Promise.all([api.get(`/revisoes/${id}`), page('revisionLines', `/revisoes/${id}/linhas`), api.all(`/revisoes/${id}/ajustes?incluirHerdados=true`), page('events', `/revisoes/${id}/eventos`)]);
    const c = await api.get(`/cenarios/${r.cenarioId}`);
    const canDecide = ctx.approver() && independent(state.user, r, adjustments);
    const actions = (writer() ? b('Criar nova revisão', 'dialog', 'data-kind="copy"') : '') + (writer() && r.estado === 'RASCUNHO' ? b('Submeter para revisão', 'dialog', 'data-kind="submit"', 'primary') : '') +
      (canDecide && r.estado === 'EM_REVISAO' ? b('Devolver', 'dialog', 'data-kind="return"', 'danger') + b('Aprovar e publicar', 'dialog', 'data-kind="approve"', 'primary') : '');
    const first = lines.items[0], points = lines.items.filter(l => l.produtoId === first?.produtoId);
    let note = r.estado === 'RASCUNHO' ? info('Os ajustes substituem a quantidade final de uma célula. A justificação é obrigatória e a previsão base é preservada.') : info('Esta revisão está congelada. Para alterar quantidades, cria uma nova revisão.');
    if (ctx.approver() && !canDecide && r.estado === 'EM_REVISAO') note += info('A decisão exige outro aprovador: criaste esta revisão ou participaste nos ajustes que ela conserva.', true);
    return { context: { revision: r, adjustments, revisionCells: lines.items, currentScenario: c }, html: back(`cenario/${r.cenarioId}`, esc(c.nome)) + heading('Cenário / Revisão ' + r.numero, esc(c.nome), `Revisão #${esc(id)} · versão ${r.versao} · ${esc(label(c.tipo))}`, actions) + note +
      panel('Pressupostos desta revisão', '', meta([['Estado', badge(r.estado)], ['Fator', q(r.fator)], ['Execução', '#' + esc(r.execucaoId)], ['Origem', r.revisaoOrigemId ? `<a href="#/revisao/${esc(r.revisaoOrigemId)}">Revisão #${esc(r.revisaoOrigemId)}</a>` : 'Previsão base'], ['Submetida', esc(date(r.submetidoEm))]]) + `<p class="detail-description">${esc(r.pressupostos.descricao)}</p>`) +
      panel('Evolução do cenário' + (first ? ' · ' + esc(first.produto) : ''), first ? 'Primeiro produto da página · ' + esc(first.unidade) : '', first ? lineChart(points.map(p => p.periodo), [{ name: 'Base', values: points.map(p => p.quantidadeBase) }, { name: 'Calculada', values: points.map(p => p.quantidadeCalculada) }, { name: 'Final', values: points.map(p => p.quantidadeFinal) }]) : empty('Sem linhas')) +
      panel('Quantidades por mês', 'Ajustes disponíveis apenas em rascunho.', table(['Produto / mês', 'Base', 'Calculada', 'Final', 'Desvio', ''], lines.items.map((l, i) => [`<strong>${esc(l.produto)}</strong><small>${esc(month(l.periodo))} · ${esc(l.unidade)}</small>`, q(l.quantidadeBase), q(l.quantidadeCalculada), `<strong class="num">${q(l.quantidadeFinal)}</strong>`, percent(l.desvioPercentual), writer() && r.estado === 'RASCUNHO' ? b('Ajustar', 'adjust', `data-index="${i}"`, 'small') : '<span class="muted">Consulta</span>'])) + pager(lines, 'revisionLines')) +
      panel('Auditoria dos ajustes', 'Inclui os ajustes herdados que foram conservados no momento da cópia.', table(['Mês / produto', 'Anterior → novo', 'Justificação', 'Autor', 'Origem'], adjustments.map(a => [esc(month(a.periodo)) + '<small>Produto #' + esc(a.produtoId) + '</small>', `<span class="num">${q(a.valorAnterior)} → ${q(a.valorNovo)}</span>`, esc(a.justificacao), esc(a.autorNome) + '<small>' + esc(date(a.ocorridoEm)) + '</small>', a.herdado ? `<a href="#/revisao/${esc(a.revisaoId)}">Herdado · #${esc(a.revisaoId)}</a>` : 'Esta revisão'])), '', ) +
      panel('Linha temporal', 'Eventos locais desta revisão.', events.items.length ? `<div class="timeline">${events.items.map(e => `<div class="event"><i class="dot"></i><div><p><strong>${esc(label(e.tipo))}</strong> · ${esc(e.autorNome)}</p><small>${esc(e.motivo || '')}</small></div><time>${esc(date(e.ocorridoEm))}</time></div>`).join('')}</div>` + pager(events, 'events') : empty('Sem eventos')) };
  }

  async function plans() {
    const data = await page('plans', '/planos');
    return heading('Decisões aprovadas', 'Planos publicados', 'Uma versão estável, com aprovação identificada e exportação para outras ferramentas.') +
      panel('Publicações', 'Rascunhos e revisões em preparação não aparecem nesta lista.', table(['Plano', 'Cenário / revisão', 'Publicação', 'Linhas', ''], data.items.map(p => [`<strong>Plano #${esc(p.id)}</strong>`, `Cenário #${esc(p.cenarioId)}<small>Revisão #${esc(p.revisaoId)}</small>`, esc(date(p.publicadoEm)), esc(p.totalLinhas), `<div class="actions"><a class="btn small" href="#/plano/${esc(p.id)}">Consultar</a>${rowAction('CSV', 'download', p.id)}</div>`])) + pager(data, 'plans'));
  }
  async function plan(id) {
    const [p, lines] = await Promise.all([api.get(`/planos/${id}`), page('planLines', `/planos/${id}/linhas`)]);
    return back('planos', 'Planos publicados') + heading('Publicação #' + esc(id), 'Plano aprovado', 'Os valores publicados e a exportação usam a mesma cópia preservada.', b('Descarregar CSV', 'download', `data-id="${esc(id)}"`, 'primary') + (writer() ? b('Criar avaliação', 'dialog', `data-kind="assessment" data-plan="${esc(id)}"`) : '')) +
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
      panel('Precisão por horizonte', selected ? esc(selected.produto) + ' · ' + esc(selected.unidade) : '', table(['Horizonte', 'Pares / candidatos', 'MAE base', 'MAE aprovado', 'MAPE base', 'MAPE aprovado', 'Cobertura'], (selected?.porHorizonte || []).map(h => [esc(h.horizonte) + ' meses', `${h.metricas.paresMae} / ${h.metricas.candidatos}`, q(h.metricas.maeBase), q(h.metricas.maeAprovado), percent(h.metricas.mapeBase), percent(h.metricas.mapeAprovado), percent(h.metricas.coberturaPercentagem)]))) +
      `<div class="subheading">Resultados operacionais · ${esc(selected?.produto || '')}</div><div class="metrics">${metric('Nível de serviço', percent(op?.nivelServicoPercentagem), `${op?.paresServico || 0} pares conhecidos`, 'compact')}${metric('Taxa de rutura', percent(op?.ruturaPercentagem), `${op?.paresRutura || 0} pares de dias conhecidos`, 'compact')}${metric('Excesso médio de stock', q(op?.excessoStockMedio), `${op?.paresStock || 0} células com stock e limite`, 'compact')}${metric('Unidades perdidas', q(op?.unidadesPerdidasTotal), `${op?.registosPerdas || 0} registos conhecidos`, 'compact')}</div>` +
      panel('Detalhe e cobertura', 'Todas as células da avaliação, incluindo exclusões. Os indicadores operacionais exigem dados próprios.', table(['Produto / mês', 'Base / aprovado', 'Realizado', 'Elegibilidade da precisão', 'Serviço / rutura', 'Excesso / perdas'], cells.items.map(c => [`<strong>${esc(c.produto)}</strong><small>${esc(month(c.periodo))} · ${esc(c.unidade)}</small>`, `${q(c.quantidadeBase)} / ${q(c.quantidadeAprovada)}`, q(c.quantidadeRealizada), c.elegivelPrecisao ? badge('VALIDADO').replace('Validado', 'Elegível') : `<span class="reason">${esc(label(c.motivoExclusao))}</span>`, c.operacional ? `${percent(c.operacional.nivelServicoPercentagem)} / ${percent(c.operacional.ruturaPercentagem)}` : `<span class="reason">${esc(label(c.motivoOperacional))}</span>`, c.operacional ? `${q(c.operacional.excessoStock)} / ${q(c.operacional.unidadesPerdidas)}` : 'Indisponível'])) + pager(cells, 'assessmentCells'));
  }

  async function team() {
    return heading('Acesso e responsabilidades', 'Equipa', 'Cria contas com os papéis necessários. A aprovação exige independência de quem preparou ou ajustou a revisão.', b(icon('plus', 16) + 'Criar utilizador', 'dialog', 'data-kind="user"', 'primary')) +
      panel('Consultar utilizador', 'A consulta é feita por identificador.', `<form id="user-lookup" class="toolbar">${field('Identificador', input('userId', 'text', '', 'required pattern="[1-9][0-9]*" inputmode="numeric" placeholder="Ex.: 2"'))}<button class="btn" type="submit">Consultar</button></form><div id="user-result"></div>`) +
      panel('Responsabilidades', '', table(['Papel', 'Responsabilidade'], [['Administrador', 'Gere contas e prepara dados e planos. Não aprova automaticamente.'], ['Planeador', 'Importa, prevê, cria cenários, ajusta e submete revisões.'], ['Comercial', 'Consulta dados, cenários e resultados.'], ['Aprovador', 'Aprova ou devolve revisões em que não interveio.'], ['Consumidor', 'Consulta catálogo, planos publicados e monitorização.']].map(r => r.map(esc))));
  }

  return { visao: overview, produtos: products, importacoes: imports, lote: lot, previsoes: forecasts, previsao: forecast, backtests, backtest, cenarios: scenarios, cenario: scenario, revisao: revision, planos: plans, plano: plan, monitorizacao: monitoring, avaliacao: assessment, equipa: team };
}
