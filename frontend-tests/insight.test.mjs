import test from 'node:test';
import assert from 'node:assert/strict';
import { createInsightSession, renderInsightContent, renderInsightEvidence } from '../backend/src/main/resources/static/assets/insight.mjs';
import { createViews } from '../backend/src/main/resources/static/assets/views.mjs';

const assessment = { id: '12', planoId: '8', periodoInicio: '2026-01', periodoFim: '2026-12', corteRealizados: '2027-01-01T00:00:00Z', versaoCalculo: 'monitoring-common-pairs-v1' };
const metrics = { candidatos: 12, paresMae: 0, paresMape: 0, zerosExcluidosMape: 0, emFalta: 12, realizadosAposCorte: 0, planosTardios: 0, periodosNaoFechados: 0, coberturaPercentagem: '0.0000', maeBase: null, maeAprovado: null, mapeBase: null, mapeAprovado: null, ganhoAjuste: null };
const product = id => ({
  precisao: { produtoId: String(id), produto: 'PROD' + id, unidade: 'kg', metricas: metrics, porHorizonte: [] },
  operacao: { paresServico: 1, nivelServicoPercentagem: '0.0000', unidadesPerdidasTotal: null },
  celulas: Array.from({ length: 12 }, (_, i) => ({ produtoId: String(id), produto: 'PROD' + id, unidade: 'kg', periodo: `2026-${String(i + 1).padStart(2, '0')}`, horizonte: i + 1, quantidadeBase: '23.1234', quantidadeAprovada: '25.0000', quantidadeRealizada: null, elegivelPrecisao: false, motivoExclusao: 'SEM_REALIZADO', operacional: null, motivoOperacional: 'SEM_LOTE_OPERACIONAL' }))
});
const completed = {
  estado: 'CONCLUIDA', geradoEm: '2027-01-02T00:00:00Z', modelo: 'local-test', versaoPrompt: 'v1', hashDados: 'abc123',
  analise: { titulo: 'Análise do plano', secoes: [{ titulo: 'Precisão', paragrafos: ['O realizado está em falta. Não é possível concluir que o plano melhorou.'] }] },
  produtos: Array.from({ length: 25 }, (_, i) => product(i + 1))
};

test('report escapes all model text, product labels and provenance instead of rendering model HTML', () => {
  const report = { ...completed, modelo: '<img src=x>', analise: { titulo: '<script>bad()</script>', secoes: [{ titulo: '<svg onload=bad()>', paragrafos: ['<img src=x onerror=bad()>'] }] } };
  const html = renderInsightContent(assessment, report);
  assert.match(html, /&lt;script&gt;bad\(\)&lt;\/script&gt;/);
  assert.match(html, /&lt;svg onload=bad\(\)&gt;/);
  assert.match(html, /&lt;img src=x onerror=bad\(\)&gt;/);
  assert.doesNotMatch(html, /<script|<svg onload|<img/);
});

test('report includes all products, fixed context and an exact evidence selector beyond the first page', () => {
  const html = renderInsightContent(assessment, completed);
  assert.match(html, /25 produtos · 300 células/);
  assert.match(html, /value="25">PROD25 · kg/);
  assert.match(html, /todos os produtos|toda a avaliação/);
  assert.match(html, /2027/);
  assert.match(html, /abc123/);
  assert.match(html, /Não é possível concluir/);
});

test('evidence preserves missing values, true zeros, exact decimals and MAPE population', () => {
  const html = renderInsightEvidence([product(1)]);
  assert.match(html, /Indisponível \/ Indisponível/);
  assert.match(html, /0,0000%/);
  assert.match(html, /23,1234/);
  assert.match(html, /12 células/);
  assert.match(html, /excluídos apenas do MAPE/);
  assert.match(html, /kg/);
});

test('monthly evidence has at most ten rows, and the next page keeps the final months accessible', () => {
  const first = renderInsightEvidence([product(1)]);
  const last = renderInsightEvidence([product(1)], '1', 1);
  assert.equal((first.match(/<summary>\d{2}\/2026/g) || []).length, 10);
  assert.match(first, /10\/2026/);
  assert.doesNotMatch(first, /11\/2026/);
  assert.match(last, /11\/2026/);
  assert.match(last, /12\/2026/);
  assert.match(last, /página 2 de 2/);
});

test('states provide safe actions without fabricated progress or automatic retry after connection loss', () => {
  for (const estado of ['POR_GERAR', 'EM_CURSO', 'DESATIVADA', 'FALHOU']) {
    const html = renderInsightContent(assessment, { estado, mensagem: '<broken>' });
    assert.match(html, /&lt;broken&gt;/);
    assert.doesNotMatch(html, /\d+%/);
    if (estado === 'EM_CURSO') assert.match(html, /disabled/);
    if (estado === 'DESATIVADA') assert.doesNotMatch(html, /data-action="insight-generate"/);
  }
  const lost = renderInsightContent(assessment, { estado: 'EM_CURSO' }, { error: 'Ligação interrompida' });
  assert.match(lost, /Verificar estado/);
  assert.doesNotMatch(lost, /data-action="insight-generate"/);
});

function sessionFixture(api = {}) {
  let next = 0;
  const scheduled = new Map(), changes = [], calls = [];
  const session = createInsightSession({ id: '12', api: {
    get: async path => { calls.push(['GET', path]);return { estado: 'CONCLUIDA' }; },
    send: async (path, body) => { calls.push(['POST', path, body]);return { estado: 'EM_CURSO' }; },
    ...api
  }, onChange: (...args) => changes.push(args), schedule: task => { scheduled.set(++next, task);return next; }, cancel: id => scheduled.delete(id) });
  const tick = async () => { const first = scheduled.entries().next().value;if (first) { scheduled.delete(first[0]);await first[1](); } };
  return { session, scheduled, changes, calls, tick };
}

test('entering or refreshing a report never generates it; only explicit generation sends POST', async () => {
  const { session, calls, scheduled } = sessionFixture();
  session.watch({ estado: 'POR_GERAR' });
  assert.equal(scheduled.size, 0);
  await session.load();
  assert.deepEqual(calls.map(c => c[0]), ['GET']);
  await session.generate();
  assert.deepEqual(calls[1], ['POST', '/avaliacoes/12/analise-inteligente', {}]);
  session.dispose();
});

test('generation polls status until completion, then stops without regenerating', async () => {
  const { session, calls, scheduled, tick } = sessionFixture();
  await session.generate();
  assert.equal(scheduled.size, 1);
  await tick();
  assert.equal(scheduled.size, 0);
  assert.deepEqual(calls.map(c => c[0]), ['POST', 'GET']);
});

test('leaving the page cancels timers and discards late responses, preventing cross-account display', async () => {
  let finish;
  const { session, scheduled, changes } = sessionFixture({ get: () => new Promise(resolve => { finish = resolve; }) });
  session.watch({ estado: 'EM_CURSO' });
  assert.equal(scheduled.size, 1);
  const pending = session.load();
  session.dispose();finish(completed);await pending;
  assert.equal(scheduled.size, 0);
  assert.deepEqual(changes, []);
});

test('concurrent clicks produce one generation request and failure stops background polling', async () => {
  let finish, calls = 0;
  const { session, changes, scheduled } = sessionFixture({ send: () => { calls++;return new Promise((resolve, reject) => { finish = reject; }); } });
  const pending = session.generate();
  await session.generate();
  assert.equal(calls, 1);
  finish(new Error('Modelo indisponível'));await pending;
  assert.equal(scheduled.size, 0);
  assert.equal(changes[0][0], null);
  assert.equal(changes[0][1].message, 'Modelo indisponível');
});

test('dedicated view reads only the assessment and cached report, without inheriting table filters', async () => {
  const calls = [];
  const views = createViews({ api: { get: async path => { calls.push(path);return path.endsWith('analise-inteligente') ? completed : assessment; } }, state: { pages: {}, tableFilters: { assessmentCells: { busca: 'PROD1', desde: '2026-02' } } }, writer: () => false });
  const result = await views.analise('12');
  assert.deepEqual(calls, ['/avaliacoes/12', '/avaliacoes/12/analise-inteligente']);
  assert.match(result.html, /Análise Inteligente/);
  assert.equal(result.context.insightReport, completed);
});
