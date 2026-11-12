export const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const quantity = value => {
  if (value === null || value === undefined || value === '') return 'Indisponível';
  const [integer, decimal] = String(value).split('.');
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + (decimal ? ',' + decimal : '');
};
export const percent = value => value == null ? 'Indisponível' : quantity(value) + '%';
// Only remove padding: a precise factor must never be rounded for display/editing.
export const factorValue = value => {
  if (value == null || value === '') return '';
  const [integer, fraction = ''] = String(value).split('.');
  return integer + '.' + fraction.replace(/0+$/, '').padEnd(2, '0');
};
export const revisionCaption = revision => `Revisão #${revision.id} · ${revision.numero}.ª versão`;
export const date = value => value ? new Intl.DateTimeFormat('pt-PT', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
export const month = value => value ? new Intl.DateTimeFormat('pt-PT', { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(value + '-01T00:00:00Z')) : '—';
export const lastMonth = (now = new Date()) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)).toISOString().slice(0, 7);
export const role = (user, ...roles) => !!user && roles.some(r => user.papeis.includes(r));
export const independent = (user, revision, adjustments) => revision.criadoPor !== user.id && !adjustments.some(a => a.autorId === user.id);
export const label = value => ({ RASCUNHO: 'Rascunho', EM_REVISAO: 'A aguardar aprovação', DEVOLVIDA: 'Devolvida', APROVADA: 'Aprovada',
  ATIVO: 'Ativo', INATIVO: 'Inativo', POR_ATIVAR: 'Por ativar',
  VALIDADO: 'Validado', REJEITADO: 'Rejeitado', CONCLUIDA: 'Concluída', EM_EXECUCAO: 'Em execução',
  HISTORICO: 'Histórico', REALIZADOS: 'Realizados', OPERACIONAL: 'Operacional', MEDIA_MOVEL: 'Média Móvel', ETS: 'ETS',
  BASE: 'Base', OTIMISTA: 'Otimista', CONSERVADOR: 'Conservador', PERSONALIZADO: 'Personalizado',
  CRIACAO: 'Criação', AJUSTE: 'Ajuste', SUBMISSAO: 'Submissão', APROVACAO: 'Aprovação', DEVOLUCAO: 'Devolução',
  PLANO_PUBLICADO_TARDIAMENTE: 'Plano publicado após o início do mês', PERIODO_NAO_FECHADO: 'Mês ainda não fechado', SEM_REALIZADO: 'Realizado em falta',
  REALIZADO_APOS_CORTE: 'Realizado conhecido após o corte', SEM_LOTE_OPERACIONAL: 'Sem lote operacional', SEM_REGISTO_OPERACIONAL: 'Sem registo operacional',
  OPERACIONAL_APOS_CORTE: 'Operacional conhecido após o corte', PREVISTO: 'Previsto', RECURSO: 'Método de recurso', INSUFICIENTE: 'Histórico insuficiente', ERRO: 'Erro',
  ADMIN: 'Administrador', PLANEADOR: 'Planeador', COMERCIAL: 'Comercial', APROVADOR: 'Aprovador', CONSUMIDOR: 'Consumidor' }[value] || value || '—');
export function badge(value) {
  const tone = ['ATIVO', 'VALIDADO', 'CONCLUIDA', 'APROVADA', 'PREVISTO'].includes(value) ? 'good' : ['INATIVO', 'REJEITADO', 'DEVOLVIDA', 'ERRO', 'FALHOU'].includes(value) ? 'bad' : ['POR_ATIVAR', 'EM_REVISAO', 'INSUFICIENTE', 'RECURSO'].includes(value) ? 'warn' : 'neutral';
  return `<span class="badge ${tone}"><span></span>${esc(label(value))}</span>`;
}
const paths = {
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  box: '<path d="m12 3 9 5-9 5-9-5 9-5Zm-9 5v10l9 5 9-5V8M12 13v10M7.5 5.5l9 5"/>',
  upload: '<path d="M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5"/>',
  trend: '<path d="M3 20h18M4 16l5-6 5 3 7-9m-6 0h6v6"/>',
  layers: '<path d="m12 3 10 5-10 5L2 8l10-5Zm-10 9 10 5 10-5M2 16l10 5 10-5"/>',
  check: '<path d="M9 3H5v18h14V3h-4M9 2h6v4H9zM8 13l3 3 5-6"/>',
  chart: '<path d="M3 3v18h18M8 16V9m5 7V5m5 11v-5"/>',
  people: '<circle cx="9" cy="7" r="4"/><path d="M1 21v-3a8 8 0 0 1 16 0v3m0-18a4 4 0 0 1 0 8m4 10v-3a8 8 0 0 0-3-6"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  logout: '<path d="M9 3H3v18h6m5-4 5-5-5-5M8 12h12"/>',
  plus: '<path d="M12 4v16M4 12h16"/>',
  refresh: '<path d="M20 8a9 9 0 1 0 1 8M20 2v6h-6"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 11h18m-13 4h2m4 0h2"/>',
};
export const icon = (name, size = 20) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.chart}</svg>`;
export const button = (text, action, data = '', style = '') => `<button type="button" class="btn ${style}" data-action="${action}" ${data}>${text}</button>`;
let fieldId = 0;
export const field = (text, input, help = '') => {
  const id = 'field-' + (++fieldId);
  const control = input.replace(/^<(input|select|textarea)\b/, `<$1 id="${id}"${help ? ` aria-describedby="${id}-help"` : ''}`);
  return `<div class="field"><label for="${id}">${text}</label>${control}${help ? `<small id="${id}-help">${help}</small>` : ''}</div>`;
};
export const input = (name, type = 'text', value = '', attrs = '') => `<input name="${name}" type="${type}" value="${esc(value)}" ${attrs}>`;
export const select = (name, items, selected = '', required = true) => `<select name="${name}" ${required ? 'required' : ''}><option value="">${required ? 'Selecionar…' : 'Nenhum'}</option>${items.map(([id, text]) => `<option value="${esc(id)}" ${String(id) === String(selected) ? 'selected' : ''}>${esc(text)}</option>`).join('')}</select>`;
export const empty = (title, detail = '') => `<div class="empty">${icon('layers', 30)}<h3>${esc(title)}</h3><p>${esc(detail)}</p></div>`;
export const table = (headers, rows, title = 'Ainda não há registos', detail = 'Os registos aparecem aqui depois de serem criados.') => rows.length ? `<div class="table-wrap"><table><thead><tr>${headers.map(h => `<th scope="col">${h}</th>`).join('')}</tr></thead><tbody>${rows.map(row => `<tr>${row.map(cell => `<td>${cell ?? '—'}</td>`).join('')}</tr>`).join('')}</tbody></table></div>` : empty(title, detail);
export const panel = (title, subtitle, content, actions = '') => {
  const context = subtitle && subtitle.length <= 80 && !/[.!?]$/.test(subtitle);
  const filters = [];
  content = content.replace(/<form class="table-search"[\s\S]*?<\/form>/g, form => { filters.push(form);return ''; });
  if (filters.some(form => /value="[^"<>]+"/.test(form)) && content.includes('class="empty"')) {
    content = content.replace(/<div class="empty">[\s\S]*?<\/div>/g, empty('Nenhum resultado para estes filtros', 'Altera a pesquisa ou usa Limpar filtros.'));
  }
  return `<section class="panel"><div class="panel-head"><div><h2>${title}</h2>${context ? `<p class="panel-caption">${subtitle}</p>` : ''}</div><div class="actions">${subtitle && !context ? `<details class="panel-context"><summary aria-label="Sobre esta secção: ${esc(title)}" title="Sobre esta secção">${icon('info', 15)}<span>Sobre esta secção</span></summary><p>${subtitle}</p></details>` : ''}${actions}</div></div>${filters.join('')}${content}</section>`;
};
export const metric = (name, value, note, tone = '') => `<article class="metric ${tone}"><p>${esc(name)}</p><strong>${esc(value)}</strong><small>${esc(note)}</small></article>`;
const tableSearch = search => {
  if (!search) return '';
  const { key, values, months } = search;
  const hint = key === 'lots' ? 'N.º do lote (ex.: #1), ficheiro ou origem' : key === 'team' ? 'Nome, email, perfil ou ID (ex.: #1)' : ['forecastLines', 'lotRows', 'revisionLines', 'planLines', 'comparison', 'backtestCells', 'backtestGroups', 'assessmentCells', 'diagnostics', 'audit'].includes(key) ? 'Código de produto (ex.: SKU001 ou DEMO100), nome ou texto' : 'Número (ex.: #1), nome ou texto';
  return `<form class="table-search" data-search-key="${esc(key)}">${field('Pesquisar nesta tabela', input('busca', 'search', values.busca || '', `maxlength="160" placeholder="${esc(hint)}"`))}${months ? field('Mês inicial', input('desde', 'month', values.desde || '')) + field('Mês final', input('ate', 'month', values.ate || '')) : ''}<button type="submit" class="btn">Pesquisar</button>${button('Limpar filtros', 'clear-table-search', `data-key="${esc(key)}"`)}<span class="search-scope">Pesquisa em todas as páginas</span></form>`;
};
export const pager = (data, key) => `${tableSearch(data.search)}<div class="pagination"><span>${esc(data.totalElements)} registos · Página ${data.page + 1} de ${Math.max(1, data.totalPages)}</span><div>${button('Anterior', 'page', `data-key="${key}" data-page="${data.page - 1}" ${data.page <= 0 ? 'disabled' : ''}`)}${button('Seguinte', 'page', `data-key="${key}" data-page="${data.page + 1}" ${data.page + 1 >= data.totalPages ? 'disabled' : ''}`)}</div></div>`;
let chartId = 0;
export function chartScale(numbers) {
  const low = Math.min(...numbers), high = Math.max(...numbers);
  const span = high - low || Math.max(Math.abs(high) * .02, 1);
  let min = low - span * .15, max = high + span * .15;
  if (low >= 0) min = Math.max(0, min);
  const raw = (max - min) / 4, power = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].find(n => n * power >= raw) * power;
  min = Math.floor(min / step) * step;max = Math.ceil(max / step) * step;
  return { min, max, step, ticks: Array.from({ length: Math.round((max - min) / step) + 1 }, (_, i) => min + i * step) };
}
export function lineChart(periods, series, description = 'Quantidades por mês', { area = false } = {}) {
  const numbers = series.flatMap(s => s.values).filter(v => v !== null && v !== undefined).map(Number).filter(Number.isFinite);
  if (!numbers.length) return empty('Sem valores para representar', 'Consulta a cobertura e os motivos de exclusão.');
  const tooltipId = 'chart-tooltip-' + (++chartId);
  const width = 800, height = 250, left = 70, right = 22, top = 20, bottom = 45;
  const { min, max, step, ticks } = chartScale(numbers);
  const x = i => left + i * (width - left - right) / Math.max(1, periods.length - 1);
  const y = v => height - bottom - (Number(v) - min) * (height - top - bottom) / (max - min);
  const grid = ticks.map(v => {
    return `<line x1="${left}" x2="${width - right}" y1="${y(v)}" y2="${y(v)}" stroke="#e8eeec"/><text x="${left - 12}" y="${y(v) + 4}" text-anchor="end">${esc(new Intl.NumberFormat('pt-PT', { maximumFractionDigits: Math.min(10, Math.max(0, 1 - Math.floor(Math.log10(step)))) }).format(v))}</text>`;
  }).join('');
  const colors = ['#23786a', '#b28142', '#7b9b92', '#7769ad', '#be6670'];
  const paths = series.map((s, index) => {
    let path = '', gap = true;
    const color = colors[index % colors.length];
    s.values.forEach((v, i) => { if (v == null || !Number.isFinite(Number(v))) { gap = true; return; } path += `${gap ? 'M' : 'L'}${x(i)} ${y(v)} `; gap = false; });
    const fills = area && index === 0 ? path.trim().split('M').filter(Boolean).map(segment => {
      const coords = segment.trim().split(/[ L]+/).map(Number);
      return `<path d="M${segment}L${coords.at(-2)} ${height - bottom}L${coords[0]} ${height - bottom}Z" fill="${color}" opacity=".07"/>`;
    }).join('') : '';
    return fills + `<path d="${path}" fill="none" stroke="${color}" stroke-width="2.5" ${index === 1 ? 'stroke-dasharray="5 4"' : ''}/>` + s.values.map((v, i) => {
      if (v == null || !Number.isFinite(Number(v))) return '';
      const period = month(periods[i]), value = quantity(v);
      return `<circle class="chart-point" data-chart-point data-index="${i}" data-period="${esc(period)}" data-series="${esc(s.name)}" data-value="${esc(value)}" data-color="${color}" cx="${x(i)}" cy="${y(v)}" r="4" fill="white" stroke="${color}" stroke-width="2" tabindex="0" role="img" aria-label="${esc(s.name)} · ${esc(period)}: ${esc(value)}"><title>${esc(s.name)} · ${esc(periods[i])}: ${esc(value)}</title></circle>`;
    }).join('');
  }).join('');
  return `<div class="chart" data-chart-model="${esc(JSON.stringify({ periods, series }))}"><div class="chart-scale-note">Escala vertical ajustada aos valores</div><div class="legend">${series.map((s, i) => `<span><i class="line-${i % colors.length}"></i>${esc(s.name)}</span>`).join('')}</div><svg viewBox="0 0 ${width} ${height}" role="group" aria-label="${esc(description)}"><rect class="chart-hit-area" x="70" y="20" width="708" height="185" fill="transparent"/>${grid}${periods.map((p, i) => `<text x="${x(i)}" y="${height - 12}" text-anchor="middle">${esc(month(p))}</text>`).join('')}${paths}<line class="chart-guide" x1="70" x2="70" y1="20" y2="205" visibility="hidden" pointer-events="none"/></svg><div class="chart-tooltip" id="${tooltipId}" role="tooltip" hidden><span class="chart-tooltip-period"></span><div class="chart-tooltip-rows"></div></div></div>`;
}
