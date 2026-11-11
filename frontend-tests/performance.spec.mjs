import { test, expect } from '@playwright/test';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { summarize } from './performance-stats.mjs';

test('guardar e mostrar ajuste: revisão de 300 células, 30 medições sem concorrência', async ({ page, context, browser }) => {
  const revision = process.env.SOP_PERF_REVISION, directory = process.env.SOP_PERF_RUN_DIR;
  if (process.env.SOP_UI_PERFORMANCE !== '1' || !revision || !directory) throw new Error('Usa python3 scripts/medir_desempenho.py.');
  const metadata = JSON.parse(await readFile(path.join(directory, 'context.json'), 'utf8'));
  const samples = [], errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const report = { ...metadata, createdAt: new Date().toISOString(), browser: browser.version(), headless: true,
    viewport: { width: 1440, height: 1000 }, concurrency: 1,
    measurement: 'Clique em Guardar ajuste até quantidade/versão atualizadas no DOM e dois requestAnimationFrame. Inclui CSRF, POST, releituras e renderização; exclui preencher o formulário.',
    displayedRowsPerPage: 20, samples, errors, completed: false };
  try {
    await page.goto(`/#/revisao/${revision}`);
    await page.getByLabel('Email', { exact: true }).fill('admin@ui.test');
    await page.getByLabel('Password', { exact: true }).fill(process.env.SOP_UI_PASSWORD);
    await page.getByRole('button', { name: 'Entrar', exact: true }).click();
    await expect(page).toHaveURL(/#\/visao$/);
    await page.goto(`/#/revisao/${revision}`);
    await expect(page.getByRole('heading', { name: 'Quantidades por mês', exact: true })).toBeVisible();
    const before = await (await context.request.get(`/api/v1/revisoes/${revision}`)).json();
    expect(before.totalLinhas).toBe(300);expect(before.versao).toBe(0);
    const lines = await (await context.request.get(`/api/v1/revisoes/${revision}/linhas?size=200`)).json();
    expect(lines.totalElements).toBe(300);
    const cell = lines.items[0];
    const baseline = cell.quantidadeBase;
    for (let index = 0; index < 35; index++) {
      const value = `200.${String(index + 1).padStart(4, '0')}`, version = index + 1;
      await page.getByRole('button', { name: 'Ajustar', exact: true }).first().click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Nova quantidade final', { exact: true }).fill(value);
      await dialog.getByLabel('Justificação', { exact: true }).fill(`Ensaio de desempenho: substituição sequencial ${version}.`);
      await page.evaluate(({ expected, version, revision }) => {
        window.__sopPerfResult = null;
        let start = null, scheduled = false;
        const click = event => {
          if (event.target.closest('#command-form button[type=submit]')) start = performance.now();
        };
        const observer = new MutationObserver(() => {
          const button = document.querySelector('[data-action="adjust"][data-index="0"]');
          const quantity = button?.closest('tr')?.querySelectorAll('td')[3]?.textContent.trim();
          const subtitle = document.querySelector('.page-heading p')?.textContent || '';
          if (start == null || scheduled || document.querySelector('#modal').open || quantity !== expected || !subtitle.includes(`Revisão #${revision} · versão ${version} ·`)) return;
          scheduled = true;
          requestAnimationFrame(() => requestAnimationFrame(() => {
            window.__sopPerfResult = { durationMs: performance.now() - start, version };
            observer.disconnect();document.removeEventListener('click', click, true);
          }));
        });
        document.addEventListener('click', click, true);
        observer.observe(document.querySelector('#conteudo'), { childList: true, subtree: true, characterData: true });
      }, { expected: value.replace('.', ','), version, revision });
      await dialog.getByRole('button', { name: 'Guardar ajuste', exact: true }).click();
      await page.waitForFunction(() => window.__sopPerfResult !== null, null, { timeout: 15000 });
      const timing = await page.evaluate(() => window.__sopPerfResult);
      const sample = { index: version, warmup: index < 5, durationMs: timing.durationMs, quantity: value, version: timing.version };
      samples.push(sample);
      console.log(`${sample.warmup ? 'Aquecimento' : 'Medição ' + (index - 4)}: ${sample.durationMs.toFixed(1)} ms`);
    }
    const after = await (await context.request.get(`/api/v1/revisoes/${revision}`)).json();
    const audits = await (await context.request.get(`/api/v1/revisoes/${revision}/ajustes?size=200`)).json();
    const updated = await (await context.request.get(`/api/v1/revisoes/${revision}/linhas?size=1`)).json();
    expect(after.versao).toBe(35);expect(after.totalLinhas).toBe(300);expect(audits.totalElements).toBe(35);
    expect(updated.items[0].quantidadeBase).toBe(baseline);expect(updated.items[0].quantidadeFinal).toBe('200.0035');
    expect(errors).toEqual([]);
    report.summary = summarize(samples.filter(s => !s.warmup).map(s => s.durationMs));
    report.passedTarget = report.summary.p95Ms <= metadata.targetP95Ms;
    report.completed = true;
    console.log(`p95: ${report.summary.p95Ms.toFixed(1)} ms; meta: ${metadata.targetP95Ms} ms.`);
    expect(report.summary.p95Ms).toBeLessThanOrEqual(metadata.targetP95Ms);
  } finally {
    await writeFile(path.join(directory, 'result.json'), JSON.stringify(report, null, 2) + '\n');
    await writeFile(path.join(directory, 'samples.csv'), 'index;warmup;duration_ms;version\n' + samples.map(s => `${s.index};${s.warmup};${s.durationMs.toFixed(3)};${s.version}`).join('\n') + '\n');
  }
});
