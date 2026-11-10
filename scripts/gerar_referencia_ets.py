"""Gera fixtures independentes com statsmodels 0.15.0 (ferramenta opcional de verificacao)."""
import csv
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import warnings
import statsmodels
from statsmodels.tsa.exponential_smoothing.ets import ETSModel


ROOT = Path(__file__).resolve().parents[1]


def smooth(history, alpha, beta):
    trend = history[1] - history[0]
    # statsmodels consome tambem y[0]; o estado pre-y[0] abaixo reproduz l0=y[0], b0=diff.
    model = ETSModel(history, error='add', trend='add', seasonal=None,
                     initialization_method='known', initial_level=history[0] - trend, initial_trend=trend)
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', RuntimeWarning)  # SSE zero em constantes, sem usar log-likelihood.
        return model.smooth([alpha, beta])


def fixture(name, history, fixed=None):
    best = None
    candidates = [fixed] if fixed else [(a / 10, a * b / 100) for a in range(1, 10) for b in range(1, 10)]
    for alpha, beta in candidates:
        result = smooth(history, alpha, beta)
        if best is None or result.sse < best[0]:
            best = (float(result.sse), alpha, beta, result)
    sse, alpha, beta, result = best
    raw = result.forecast(3).tolist()
    values = [str(Decimal(str(max(0, value))).quantize(Decimal('0.0001'), rounding=ROUND_HALF_UP)) for value in raw]
    return {'name': name, 'history': history, 'fixed': fixed is not None, 'alpha': alpha, 'beta': beta,
            'sse': sse, 'rawForecast': raw, 'forecast': values,
            'level': float(result.states[-1][0]), 'trend': float(result.states[-1][1])}


def main():
    if statsmodels.__version__ != '0.15.0':
        raise SystemExit('Usa statsmodels==0.15.0 num ambiente de referencia separado.')
    with (ROOT / 'data/exemplos/historico_sku001_valido.csv').open(encoding='utf-8') as stream:
        sample = [float(row['quantidade']) for row in csv.DictReader(stream, delimiter=';')]
    cases = [fixture('fixed-reference', [10., 12., 13., 12., 15., 18.], (.5, .1)),
             fixture('constant', [20.] * 8), fixture('zero', [0.] * 8),
             fixture('linear', [10., 20., 30., 40., 50., 60.]),
             fixture('noise', [10., 12., 13., 12., 15., 18., 19., 21.]),
             fixture('zeros-mixed', [0., 3., 0., 5., 0., 6., 1., 4.]),
             fixture('decline', [100., 80., 60., 40., 20., 0.]), fixture('sku001', sample)]
    output = ROOT / 'backend/src/test/resources/ets/reference-statsmodels.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'library': 'statsmodels', 'version': statsmodels.__version__,
                                 'model': 'ETS(A,A,N)', 'cases': cases}, indent=2) + '\n', encoding='utf-8')
    print('Geradas', len(cases), 'referencias com statsmodels', statsmodels.__version__)


if __name__ == '__main__':
    main()
