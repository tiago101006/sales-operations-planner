export const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const quantity = value => {
  if (value === null || value === undefined || value === '') return 'Indisponível';
  const [integer, decimal] = String(value).split('.');
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + (decimal ? ',' + decimal : '');
};
export const percent = value => value == null ? 'Indisponível' : quantity(value) + '%';
export const date = value => value ? new Intl.DateTimeFormat('pt-PT', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
export const month = value => value ? new Intl.DateTimeFormat('pt-PT', { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(value + '-01T00:00:00Z')) : '—';
export const lastMonth = (now = new Date()) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)).toISOString().slice(0, 7);
export const role = (user, ...roles) => !!user && roles.some(r => user.papeis.includes(r));
export const independent = (user, revision, adjustments) => revision.criadoPor !== user.id && !adjustments.some(a => a.autorId === user.id);
export const label = value => ({ RASCUNHO: 'Rascunho', EM_REVISAO: 'Em revisão', DEVOLVIDA: 'Devolvida', APROVADA: 'Aprovada',
  VALIDADO: 'Validado', REJEITADO: 'Rejeitado', CONCLUIDA: 'Concluída', EM_EXECUCAO: 'Em execução',
  HISTORICO: 'Histórico', REALIZADOS: 'Realizados', OPERACIONAL: 'Operacional', MEDIA_MOVEL: 'Média Móvel', ETS: 'ETS',
  BASE: 'Base', OTIMISTA: 'Otimista', CONSERVADOR: 'Conservador', PERSONALIZADO: 'Personalizado',
  CRIACAO: 'Criação', AJUSTE: 'Ajuste', SUBMISSAO: 'Submissão', APROVACAO: 'Aprovação', DEVOLUCAO: 'Devolução',
  PLANO_PUBLICADO_TARDIAMENTE: 'Plano publicado após o início do mês', PERIODO_NAO_FECHADO: 'Mês ainda não fechado', SEM_REALIZADO: 'Realizado em falta',
  REALIZADO_APOS_CORTE: 'Realizado conhecido após o corte', SEM_LOTE_OPERACIONAL: 'Sem lote operacional', SEM_REGISTO_OPERACIONAL: 'Sem registo operacional',
  OPERACIONAL_APOS_CORTE: 'Operacional conhecido após o corte', PREVISTO: 'Previsto', RECURSO: 'Método de recurso', INSUFICIENTE: 'Histórico insuficiente', ERRO: 'Erro',
  ADMIN: 'Administrador', PLANEADOR: 'Planeador', COMERCIAL: 'Comercial', APROVADOR: 'Aprovador', CONSUMIDOR: 'Consumidor' }[value] || value || '—');
export function badge(value) {
  const tone = ['VALIDADO', 'CONCLUIDA', 'APROVADA', 'PREVISTO'].includes(value) ? 'good' : ['REJEITADO', 'ERRO', 'FALHOU'].includes(value) ? 'bad' : ['EM_REVISAO', 'DEVOLVIDA', 'INSUFICIENTE', 'RECURSO'].includes(value) ? 'warn' : 'neutral';
  return `<span class="badge ${tone}"><span></span>${esc(label(value))}</span>`;
}
const paths = {
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
export const panel = (title, subtitle, content, actions = '') => `<section class="panel"><div class="panel-head"><div><h2>${title}</h2>${subtitle ? `<p>${subtitle}</p>` : ''}</div><div class="actions">${actions}</div></div>${content}</section>`;
export const metric = (name, value, note, tone = '') => `<article class="metric ${tone}"><p>${esc(name)}</p><strong>${esc(value)}</strong><small>${esc(note)}</small></article>`;
export const pager = (data, key) => `<div class="pagination"><span>${esc(data.totalElements)} registos · Página ${data.page + 1} de ${Math.max(1, data.totalPages)}</span><div>${button('Anterior', 'page', `data-key="${key}" data-page="${data.page - 1}" ${data.page <= 0 ? 'disabled' : ''}`)}${button('Seguinte', 'page', `data-key="${key}" data-page="${data.page + 1}" ${data.page + 1 >= data.totalPages ? 'disabled' : ''}`)}</div></div>`;
export function lineChart(periods, series, description = 'Quantidades por mês', { area = false } = {}) {
  const numbers = series.flatMap(s => s.values).filter(v => v !== null && v !== undefined).map(Number).filter(Number.isFinite);
  if (!numbers.length) return empty('Sem valores para representar', 'Consulta a cobertura e os motivos de exclusão.');
  const width = 800, height = 250, left = 70, right = 22, top = 20, bottom = 45;
  const max = Math.max(1, ...numbers) * 1.12, min = Math.min(0, ...numbers);
  const x = i => left + i * (width - left - right) / Math.max(1, periods.length - 1);
  const y = v => height - bottom - (Number(v) - min) * (height - top - bottom) / (max - min);
  const grid = Array.from({ length: 5 }, (_, i) => {
    const v = min + (max - min) * i / 4;
    return `<line x1="${left}" x2="${width - right}" y1="${y(v)}" y2="${y(v)}" stroke="#e8eeec"/><text x="${left - 12}" y="${y(v) + 4}" text-anchor="end">${esc(new Intl.NumberFormat('pt-PT', { maximumFractionDigits: 1 }).format(v))}</text>`;
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
    return fills + `<path d="${path}" fill="none" stroke="${color}" stroke-width="2.5" ${index === 1 ? 'stroke-dasharray="5 4"' : ''}/>` + s.values.map((v, i) => v == null || !Number.isFinite(Number(v)) ? '' : `<circle cx="${x(i)}" cy="${y(v)}" r="4" fill="white" stroke="${color}" stroke-width="2"><title>${esc(s.name)} · ${esc(periods[i])}: ${esc(quantity(v))}</title></circle>`).join('');
  }).join('');
  return `<div class="chart"><div class="legend">${series.map((s, i) => `<span><i class="line-${i % colors.length}"></i>${esc(s.name)}</span>`).join('')}</div><svg viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(description)}">${grid}${periods.map((p, i) => `<text x="${x(i)}" y="${height - 12}" text-anchor="middle">${esc(month(p))}</text>`).join('')}${paths}</svg></div>`;
}
