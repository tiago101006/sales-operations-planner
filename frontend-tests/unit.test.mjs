import test from 'node:test';
import assert from 'node:assert/strict';
import { esc, quantity, percent, independent, role, lineChart, lastMonth } from '../backend/src/main/resources/static/assets/ui.mjs';
import { Api, ApiError } from '../backend/src/main/resources/static/assets/api.mjs';

test('escapes text and attributes without interpreting user HTML', () => {
  assert.equal(esc('<img src="x" onerror=\'bad()\'>&'), '&lt;img src=&quot;x&quot; onerror=&#39;bad()&#39;&gt;&amp;');
  assert.equal(esc(null), '');
});
test('preserves all decimal digits and distinguishes missing quantities from zero', () => {
  assert.equal(quantity('99999999999999.9999'), '99 999 999 999 999,9999');
  assert.equal(quantity('0.0000'), '0,0000');assert.equal(quantity(null), 'Indisponível');
  assert.equal(percent(null), 'Indisponível');assert.equal(percent('0.0000'), '0,0000%');
});
test('separates approver permission from independence of revision and inherited adjustments', () => {
  const user = { id: '9', papeis: ['ADMIN', 'APROVADOR'] };
  assert.equal(role(user, 'APROVADOR'), true);assert.equal(role({ papeis: ['ADMIN'] }, 'APROVADOR'), false);
  assert.equal(independent(user, { criadoPor: '9' }, []), false);
  assert.equal(independent(user, { criadoPor: '1' }, [{ autorId: '9', herdado: true }]), false);
  assert.equal(independent(user, { criadoPor: '1' }, [{ autorId: '2' }]), true);
});
test('uses UTC month boundaries, including the change of year', () => {
  assert.equal(lastMonth(new Date('2026-01-01T00:01:00Z')), '2025-12');
  assert.equal(lastMonth(new Date('2026-11-10T12:00:00Z')), '2026-10');
});
test('chart does not turn missing values into zero or join across gaps', () => {
  const html = lineChart(['2026-11', '2026-12', '2027-01'], [{ name: '<Realizado>', values: ['1.0000', null, '3.0000'] }]);
  assert.equal((html.match(/<circle/g) || []).length, 2);assert.ok(!html.includes('<Realizado>'));assert.ok(html.includes('&lt;Realizado&gt;'));
  assert.match(html, /<path d="M[^L]*M/);assert.ok(!html.includes('NaN'));
  assert.ok(lineChart(['2026-11'], [{ name: 'Realizado', values: [null] }]).includes('Sem valores para representar'));
  const area = lineChart(['2026-11', '2026-12', '2027-01'], [{ name: 'Plano', values: ['1', null, '3'] }], 'Plano por mês', { area: true });
  assert.equal((area.match(/opacity=".07"/g) || []).length, 2);
  assert.ok(!area.includes('NaN'));
});
test('API obtains a fresh CSRF token for each mutation and never retries a conflict', async () => {
  const calls = [];
  const api = new Api(async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'fresh-' + calls.length });
    return Response.json({ detail: 'Versão alterada.', currentVersion: 3 }, { status: 409 });
  });
  await assert.rejects(api.send('/revisoes/1/ajustes', { quantidade: '12345678901234.5678', versaoEsperada: 2 }), e => e instanceof ApiError && e.problem.currentVersion === 3);
  assert.equal(calls.length, 2);assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'fresh-1');
  assert.equal(calls[1].options.credentials, 'same-origin');assert.match(calls[1].options.body, /12345678901234.5678/);
});
test('selectors retrieve later API pages and authentication expiry is propagated', async () => {
  const api = new Api(async url => Response.json(url.includes('page=0') ? { items: ['a'], totalPages: 2 } : { items: ['b'], totalPages: 2 }));
  assert.deepEqual(await api.all('/lotes?tipo=HISTORICO'), ['a', 'b']);
  let expired = 0;const other = new Api(async () => Response.json({}, { status: 401 }));other.onUnauthorized = () => expired++;
  await assert.rejects(other.get('/planos'), ApiError);assert.equal(expired, 1);
  await assert.rejects(other.send('/auth/login', {}), ApiError); // The public CSRF request is mocked as expired too.
});
test('network interruption reports an uncertain write without repeating the request', async () => {
  let calls = 0;
  const api = new Api(async url => { calls++;if (url.endsWith('/csrf')) return Response.json({ headerName: 'x-csrf', token: 't' });throw new Error('offline'); });
  await assert.rejects(api.send('/cenarios', {}), /pode ter sido guardado/);assert.equal(calls, 2);
});
