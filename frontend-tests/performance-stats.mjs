export function summarize(values) {
  if (values.length !== 30 || values.some(v => !Number.isFinite(v) || v < 0)) throw new Error('São necessárias 30 medições válidas, sem excluir amostras.');
  const sorted = [...values].sort((a, b) => a - b);
  return { count: values.length, minMs: sorted[0], medianMs: (sorted[14] + sorted[15]) / 2,
    meanMs: values.reduce((sum, n) => sum + n, 0) / values.length,
    p95Ms: sorted[Math.ceil(0.95 * sorted.length) - 1], maxMs: sorted.at(-1), percentileMethod: 'nearest-rank' };
}
