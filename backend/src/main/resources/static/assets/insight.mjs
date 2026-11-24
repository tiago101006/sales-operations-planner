import { esc, date, month, quantity as q, percent, label, icon, button as b } from './ui.mjs';
import { revealContent } from './motion.mjs';

const statusCopy = {
  POR_GERAR: ['Resumo do plano', 'Consulta as conclusões principais e as recomendações para esta avaliação.'],
  EM_CURSO: ['A analisar os resultados…', 'A IA local está a ordenar recomendações compatíveis com os dados. O relatório pode demorar alguns minutos neste computador. Podes continuar a trabalhar e voltar aqui mais tarde.'],
  DESATIVADA: ['A análise ainda não está disponível', 'Pede ao administrador para ativar a análise inteligente nesta instalação. Os indicadores da monitorização continuam disponíveis.'],
  FALHOU: ['Não foi possível concluir a análise', 'Os dados da avaliação estão preservados. Podes voltar a tentar gerar o relatório.']
};

export function renderInsightContent(assessment, report, { error = '', productId = '', page = 0 } = {}) {
  if (error) return `<section class="insight-status" aria-labelledby="insight-status-title"><h2 id="insight-status-title">A ligação à análise foi interrompida</h2><p>A geração pode continuar no servidor. Verifica o estado antes de iniciar outro pedido.</p><p class="info error" role="alert">${esc(error)}</p><div class="actions">${b('Verificar estado', 'insight-refresh', '', 'primary')}<a class="btn" href="#/avaliacao/${esc(assessment.id)}">Voltar aos indicadores</a></div></section>`;
  if (report.estado !== 'CONCLUIDA' || !report.analise) {
    const [title, text] = statusCopy[report.estado] || statusCopy.POR_GERAR;
    const running = report.estado === 'EM_CURSO';
    return `<section class="insight-status" aria-labelledby="insight-status-title" aria-busy="${running}">
      <span class="insight-status-symbol" aria-hidden="true">${icon(running ? 'chart' : 'layers', 28)}</span>
      <h2 id="insight-status-title">${title}</h2><p>${text}</p>
      ${report.mensagem ? `<p class="insight-status-detail">${esc(report.mensagem)}</p>` : ''}
      ${error ? `<p class="info error" role="alert">${esc(error)}</p>` : ''}
      <div class="actions">${report.estado === 'DESATIVADA' ? '' : b(running ? 'A analisar…' : report.estado === 'FALHOU' ? 'Voltar a tentar' : 'Gerar análise', 'insight-generate', running ? 'disabled' : '', 'primary')}${error ? b('Verificar estado', 'insight-refresh') : ''}<a class="btn" href="#/avaliacao/${esc(assessment.id)}">Voltar aos indicadores</a></div>
      <p class="insight-live" role="status">${running ? 'A geração continua mesmo que saias desta página.' : ''}</p>
    </section>`;
  }
  const sections = report.analise.secoes || [];
  const products = report.produtos || [];
  const cells = products.reduce((total, product) => total + (product.celulas?.length || 0), 0);
  return `<div class="insight-report-layout">
    <aside class="insight-outline" aria-label="Navegação do relatório">
      <h2>Neste relatório</h2>
      <details class="insight-outline-menu" open><summary>Neste relatório</summary>
      <nav>${sections.map((section, i) => `<button type="button" data-insight-section="${i}">${esc(section.titulo)}</button>`).join('')}<button type="button" data-insight-section="evidence">Conferir os dados</button></nav>
      <dl class="insight-scope"><div><dt>Âmbito</dt><dd>${esc(products.length)} produtos · ${esc(cells)} células</dd></div><div><dt>Janela</dt><dd>${esc(month(assessment.periodoInicio))} — ${esc(month(assessment.periodoFim))}</dd></div><div><dt>Corte dos dados</dt><dd>${esc(date(assessment.corteRealizados))}</dd></div></dl>
      </details>
    </aside>
    <div class="insight-document">
    <div class="insight-playback"><button type="button" class="btn small" data-insight-skip hidden>Mostrar texto completo</button></div>
    <article class="insight-reading" aria-label="Relatório da análise inteligente">
      <header class="insight-reading-header"><h2 tabindex="-1">${esc(report.analise.titulo)}</h2><p>Gerado em ${esc(date(report.geradoEm))} · avaliação #${esc(assessment.id)}</p></header>
      <div class="insight-review"><p class="insight-review-note">A IA local ordena as recomendações.</p></div>
      ${sections.map((section, i) => `<section class="insight-section" aria-labelledby="insight-section-${i}"><h3 id="insight-section-${i}" tabindex="-1" data-insight-line><span>${esc(section.titulo)}</span></h3>${(section.paragrafos || []).map(paragraph => `<p data-insight-line><span>${esc(paragraph)}</span></p>`).join('')}</section>`).join('')}
      <section class="insight-evidence" aria-labelledby="insight-section-evidence"><h3 id="insight-section-evidence" tabindex="-1">Conferir os dados</h3><p>Valores calculados pela NexoPlan, por produto e mês. Abrangem toda a avaliação, independentemente dos filtros da página de monitorização.</p><div data-insight-evidence>${renderInsightEvidence(products, productId, page)}</div></section>
      <details class="insight-provenance"><summary>Origem e método da análise</summary><dl><div><dt>Modelo local</dt><dd>${esc(report.modelo)}</dd></div><div><dt>Versão da análise</dt><dd>${esc(report.versaoPrompt)}</dd></div><div><dt>Versão do cálculo</dt><dd>${esc(assessment.versaoCalculo)}</dd></div><div><dt>Referência dos dados</dt><dd class="insight-fingerprint">${esc(report.hashDados)}</dd></div></dl><p>Esta análise refere-se à avaliação guardada. Novos dados ou um novo corte exigem outra avaliação. Não são alteradas previsões, quantidades ou aprovações.</p></details>
    </article>
    </div>
  </div>`;
}

const number = value => esc(q(value));
const percentage = value => esc(percent(value));
const pair = (name, value, note = '') => `<div><dt>${esc(name)}</dt><dd>${value}${note ? `<small>${esc(note)}</small>` : ''}</dd></div>`;

export function renderInsightEvidence(products, productId = '', page = 0) {
  const product = products.find(p => p.precisao?.produtoId === productId) || products[0];
  if (!product) return '<p>Esta avaliação não tem células para apresentar.</p>';
  const precision = product.precisao, m = precision.metricas, operation = product.operacao || {};
  const selected = precision.produtoId, cells = product.celulas || [];
  const current = Math.min(Math.max(0, page), Math.max(0, Math.ceil(cells.length / 10) - 1));
  return `<label class="insight-product-label" for="insight-product">Produto a conferir</label><select id="insight-product" name="insightProduct">${products.map(p => `<option value="${esc(p.precisao.produtoId)}"${p.precisao.produtoId === selected ? ' selected' : ''}>${esc(p.precisao.produto)} · ${esc(p.precisao.unidade)}</option>`).join('')}</select>
    <details class="insight-product-detail"><summary>Precisão e cobertura · ${esc(precision.produto)}</summary>
      <dl class="insight-values">${pair('MAE base / aprovado', `${number(m.maeBase)} / ${number(m.maeAprovado)}`, `Unidade: ${precision.unidade}`)}${pair('MAPE base / aprovado', `${percentage(m.mapeBase)} / ${percentage(m.mapeAprovado)}`)}${pair('Cobertura', percentage(m.coberturaPercentagem), `${m.paresMae} pares elegíveis de ${m.candidatos} células`)}${pair('Ganho do ajuste', number(m.ganhoAjuste), 'MAE base − MAE aprovado; positivo significa menor erro')}${pair('População do MAPE', esc(m.paresMape) + ' pares', `${m.zerosExcluidosMape} realizados a zero excluídos apenas do MAPE`)}${pair('Exclusões da precisão', `${esc(m.emFalta)} sem realizado · ${esc(m.realizadosAposCorte)} após o corte<br>${esc(m.planosTardios)} publicações tardias · ${esc(m.periodosNaoFechados)} meses não fechados`)}</dl>
      ${(precision.porHorizonte || []).map(h => `<div class="insight-horizon"><h4>Horizonte ${esc(h.horizonte)} ${h.horizonte === 1 ? 'mês' : 'meses'}</h4><p>MAE base / aprovado: <strong>${number(h.metricas.maeBase)} / ${number(h.metricas.maeAprovado)}</strong> · MAPE: <strong>${percentage(h.metricas.mapeBase)} / ${percentage(h.metricas.mapeAprovado)}</strong> · cobertura: <strong>${percentage(h.metricas.coberturaPercentagem)}</strong> (${esc(h.metricas.paresMae)} de ${esc(h.metricas.candidatos)}).</p></div>`).join('')}
    </details>
    <details class="insight-product-detail"><summary>Resultados operacionais · ${esc(precision.produto)}</summary><dl class="insight-values">${pair('Nível de serviço', percentage(operation.nivelServicoPercentagem), `${operation.paresServico || 0} pares conhecidos`)}${pair('Taxa de rutura', percentage(operation.ruturaPercentagem), `${operation.paresRutura || 0} pares de dias conhecidos`)}${pair('Excesso médio de stock', number(operation.excessoStockMedio), `${operation.paresStock || 0} células com stock e limite · ${precision.unidade}`)}${pair('Unidades perdidas', number(operation.unidadesPerdidasTotal), `${operation.registosPerdas || 0} registos conhecidos · ${precision.unidade}`)}${pair('Registos operacionais conhecidos', esc(operation.registosConhecidos || 0), `${operation.elegiveisTemporais || 0} células temporalmente elegíveis`)}</dl></details>
    <div class="insight-months"><h4>Detalhe mensal</h4>${cells.slice(current * 10, current * 10 + 10).map(c => {
      const op = c.operacional;
      return `<details class="insight-product-detail"><summary>${esc(month(c.periodo))}<span>${c.elegivelPrecisao ? 'Elegível para precisão' : esc(label(c.motivoExclusao))}</span></summary><dl class="insight-values">${pair('Previsão base', number(c.quantidadeBase))}${pair('Quantidade aprovada', number(c.quantidadeAprovada))}${pair('Realizado conhecido', number(c.quantidadeRealizada))}${pair('Disponibilidade do realizado', c.realizadoDisponivelEm ? esc(date(c.realizadoDisponivelEm)) : 'Indisponível')}${pair('Horizonte', esc(c.horizonte) + ' meses')}${pair('Elegibilidade operacional', op ? 'Registo conhecido no corte' : esc(label(c.motivoOperacional) || 'Indisponível'))}${op ? pair('Unidades solicitadas / satisfeitas no prazo', `${number(op.unidadesSolicitadas)} / ${number(op.unidadesSatisfeitasNoPrazo)}`) + pair('Dias observados / com procura sem stock', `${number(op.diasObservadosComProcura)} / ${number(op.diasComProcuraSemStock)}`) + pair('Stock de fecho / limite', `${number(op.stockFecho)} / ${number(op.limiteStock)}`) + pair('Excesso de stock / unidades perdidas', `${number(op.excessoStock)} / ${number(op.unidadesPerdidas)}`) + pair('Prazo médio', number(op.prazoMedioDias), 'Dias; valor observado neste mês') : ''}</dl></details>`;
    }).join('')}${cells.length > 10 ? `<div class="insight-evidence-pagination"><span>${esc(cells.length)} meses · página ${current + 1} de ${Math.ceil(cells.length / 10)}</span><div><button type="button" class="btn small" data-insight-page="${current - 1}"${current === 0 ? ' disabled' : ''}>Anterior</button><button type="button" class="btn small" data-insight-page="${current + 1}"${(current + 1) * 10 >= cells.length ? ' disabled' : ''}>Seguinte</button></div></div>` : ''}</div>`;
}

/** Polling belongs to a visit, while generation/cache belong to the server. */
export function createInsightSession({ api, id, onChange, schedule = setTimeout, cancel = clearTimeout, delay = 4000 }) {
  const path = `/avaliacoes/${encodeURIComponent(id)}/analise-inteligente`;
  let stopped = false, pending = false, timer;
  const clear = () => { if (timer !== undefined) cancel(timer);timer = undefined; };
  const watch = report => {
    clear();
    if (!stopped && report.estado === 'EM_CURSO') timer = schedule(() => { timer = undefined;return load(); }, delay);
  };
  const request = async generate => {
    if (stopped || pending) return;
    pending = true;clear();
    try {
      const report = await (generate ? api.send(path, {}) : api.get(path));
      if (!stopped) { onChange(report);watch(report); }
    } catch (error) { if (!stopped) onChange(null, error); }
    finally { pending = false; }
  };
  const load = () => request(false);
  return { load, generate: () => request(true), watch, dispose() { stopped = true;clear(); } };
}

function typeInsight(host) {
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const skip = host.querySelector('[data-insight-skip]');
  if (!skip || preference.matches || document.hidden) return () => {};

  const segmenter = Intl.Segmenter && new Intl.Segmenter('pt', { granularity: 'grapheme' });
  const layout = skip.closest('.insight-report-layout');
  const outline = new Map([...layout.querySelectorAll('[data-insight-section]')]
    .map(button => [`insight-section-${button.dataset.insightSection}`, button]));
  const lines = [...layout.querySelectorAll('[data-insight-line]')]
    .filter(line => line.textContent)
    .map(line => ({
      line,
      letters: segmenter ? Array.from(segmenter.segment(line.textContent), part => part.segment) : Array.from(line.textContent),
    }));
  if (!lines.length) return () => {};

  let frame, preview, stopped = false;
  let index = 0, lineStarted = performance.now() + 250, shown = 0;
  const restoreLine = line => {
    preview?.remove();
    preview = null;
    line?.classList.remove('insight-line-writing');
  };
  const finish = () => {
    if (stopped) return;
    stopped = true;
    cancelAnimationFrame(frame);
    restoreLine(lines[index]?.line);
    for (const { line } of lines) line.hidden = false;
    for (const button of outline.values()) button.disabled = false;
    layout.classList.remove('insight-writing');
    if (document.activeElement === skip && host.isConnected) {
      layout.querySelector('.insight-reading-header h2')?.focus({ preventScroll: true });
    }
    skip.hidden = true;
    preference.removeEventListener('change', finish);
    document.removeEventListener('visibilitychange', finish);
  };

  for (const { line } of lines) line.hidden = true;
  for (const button of outline.values()) button.disabled = true;
  layout.classList.add('insight-writing', 'insight-has-playback');
  skip.hidden = false;
  preference.addEventListener('change', finish);
  document.addEventListener('visibilitychange', finish);

  const step = now => {
    if (!host.isConnected || index === lines.length) {
      finish();
      return;
    }
    const { line, letters } = lines[index];
    if (now >= lineStarted) {
      if (!preview) {
        preview = document.createElement('span');
        preview.setAttribute('aria-hidden', 'true');
        line.classList.add('insight-line-writing');
        line.append(preview);
        line.hidden = false;
      }
      const count = Math.min(letters.length, 1 + Math.floor((now - lineStarted) / 30));
      if (count !== shown) {
        preview.textContent = letters.slice(0, count).join('');
        shown = count;
      }
      if (count === letters.length) {
        restoreLine(line);
        const topic = outline.get(line.id);
        if (topic) topic.disabled = false;
        index++;
        lineStarted = now + 210;
        shown = 0;
      }
    }
    frame = requestAnimationFrame(step);
  };
  frame = requestAnimationFrame(step);
  return finish;
}

export function mountInsightPage(main, { api, assessment, initial, start = false, settleLayout = () => {} }) {
  const host = main.querySelector('[data-insight-report]');
  if (!host) return () => {};
  let report = initial, selectedProduct = '', page = 0, closed = false;
  let finishTyping = () => {};
  const compact = window.matchMedia('(max-width: 800px)');
  const syncOutline = () => {
    const menu = host.querySelector('.insight-outline-menu');
    if (menu) menu.open = !compact.matches;
  };
  const update = (next, error) => {
    if (closed || !host.isConnected) return;
    if (next) report = next;
    const scrollTop = window.scrollY;
    finishTyping();
    host.innerHTML = renderInsightContent(assessment, report, { error: error?.message, productId: selectedProduct, page });
    syncOutline();
    if (!error) finishTyping = typeInsight(host);
    window.scrollTo({ top: scrollTop, behavior: 'instant' });
    const live = main.querySelector('[data-insight-live]');
    if (live) live.textContent = error ? 'A ligação foi interrompida. Usa Verificar estado para consultar o resultado.' : report.estado === 'CONCLUIDA' ? 'Análise concluída. O relatório está pronto para leitura.' : report.estado === 'FALHOU' ? 'Não foi possível concluir a análise. Podes voltar a tentar.' : '';
  };
  const session = createInsightSession({ api, id: assessment.id, onChange: update });
  const click = event => {
    if (event.target.closest('[data-insight-skip]')) {
      finishTyping();
      return;
    }
    const generate = event.target.closest('[data-action="insight-generate"]');
    if (generate && !generate.disabled) {
      generate.disabled = true;report = { ...report, estado: 'EM_CURSO', mensagem: null };update(report);session.generate();return;
    }
    if (event.target.closest('[data-action="insight-refresh"]')) { session.load();return; }
    const link = event.target.closest('[data-insight-section]');
    if (link) {
      const heading = host.querySelector(`#insight-section-${CSS.escape(link.dataset.insightSection)}`);
      if (link.disabled || !heading || heading.hidden) return;
      settleLayout();
      revealContent(heading, { focus: true, animate: event.detail !== 0 });return;
    }
    const pager = event.target.closest('[data-insight-page]');
    if (pager && !pager.disabled) {
      page = Number(pager.dataset.insightPage);replaceEvidence(pager.dataset.insightPage === '0' ? '[data-insight-page="1"]' : '[data-insight-page]');
    }
  };
  const replaceEvidence = focus => {
    const evidence = host.querySelector('[data-insight-evidence]'), y = window.scrollY;
    if (!evidence) return;
    evidence.innerHTML = renderInsightEvidence(report.produtos || [], selectedProduct, page);
    evidence.querySelector(focus)?.focus({ preventScroll: true });window.scrollTo({ top: y, behavior: 'instant' });
  };
  const change = event => { if (event.target.name === 'insightProduct') { selectedProduct = event.target.value;page = 0;replaceEvidence('[name="insightProduct"]'); } };
  host.addEventListener('click', click);host.addEventListener('change', change);
  compact.addEventListener('change', syncOutline);syncOutline();
  if (start && ['POR_GERAR', 'FALHOU'].includes(report.estado)) {
    report = { ...report, estado: 'EM_CURSO', mensagem: null };update(report);session.generate();
  } else {
    finishTyping = typeInsight(host);
    session.watch(report);
  }
  return () => { closed = true;finishTyping();session.dispose();host.removeEventListener('click', click);host.removeEventListener('change', change);compact.removeEventListener('change', syncOutline); };
}
