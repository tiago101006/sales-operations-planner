import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';
import path from 'node:path';

const baseURL = process.env.SOP_UI_BASE_URL;
const performanceRun = process.env.SOP_UI_PERFORMANCE === '1';
if (process.env.SOP_UI_ISOLATED !== '1' || !baseURL || !baseURL.startsWith('http://127.0.0.1:') || new URL(baseURL).port === '8080') {
  throw new Error('Executa python3 scripts/testar_interface.py. Estes testes escrevem apenas numa base temporária criada pelo script.');
}
export default defineConfig({
  testDir: '.', testMatch: performanceRun ? 'performance.spec.mjs' : 'workflow.spec.mjs', fullyParallel: false, workers: 1, retries: 0,
  timeout: 180000, expect: { timeout: 12000 }, reporter: [['list'], ['./progress.mjs']],
  outputDir: performanceRun ? path.join(process.env.SOP_PERF_RUN_DIR, 'artifacts') : path.resolve('../tmp/ui-test-results'),
  use: {
    baseURL, headless: true, actionTimeout: 12000, navigationTimeout: 20000, viewport: { width: 1440, height: 1000 },
    launchOptions: { executablePath: process.env.SOP_UI_CHROME_PATH || (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined) },
    screenshot: 'only-on-failure', trace: 'off',
  },
});
