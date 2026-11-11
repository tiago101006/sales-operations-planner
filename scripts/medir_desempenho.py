"""Mede guardar/mostrar ajustes com 300 células em Spring/MySQL temporários."""
import csv
from datetime import datetime, timezone
import io
import os
import subprocess
import sys
from testar_interface import main as run_isolated


def prepare(request):
    now = datetime.now(timezone.utc)
    last = now.year * 12 + now.month - 2
    periods = [f'{s // 12:04d}-{s % 12 + 1:02d}' for s in range(last - 23, last + 1)]
    output = io.StringIO(newline='')
    writer = csv.writer(output, delimiter=';', lineterminator='\n')
    writer.writerow(['produto', 'periodo', 'tipo', 'quantidade', 'unidade', 'disponivel_em'])
    for p in range(1, 101):
        code = f'PERF{p:03d}'
        request('POST', '/api/v1/produtos', {'codigo': code, 'nome': f'Produto temporário de desempenho {p}', 'unidade': 'un'})
        for i, period in enumerate(periods):
            year, month = map(int, period.split('-'))
            available = f'{year + month // 12:04d}-{month % 12 + 1:02d}-01T00:00:00Z'
            writer.writerow([code, period, 'VENDAS', str(50 + p * 3 + i % 7), 'un', available])
    lot = request('POST', '/api/v1/lotes', upload=('HISTORICO', output.getvalue()))
    execution = request('POST', '/api/v1/execucoes-previsao', {'loteHistoricoId': lot['id'], 'tipoSerie': 'VENDAS',
        'corteTemporal': datetime.now(timezone.utc).isoformat(), 'ultimoPeriodoTreino': periods[-1], 'horizonteMeses': 3,
        'metodo': 'MEDIA_MOVEL', 'parametros': {'janela': 3, 'multiplosPassos': 'RECURSIVO'}})
    created = request('POST', '/api/v1/cenarios', {'execucaoId': execution['id'], 'nome': 'Ensaio isolado de desempenho',
        'tipo': 'OTIMISTA', 'fator': '1.10', 'pressupostos': {'descricao': 'Fixture temporária: 100 produtos × 3 meses, sem dados da base de desenvolvimento.'}})
    revision = created['revisao']
    if revision['totalLinhas'] != 300 or lot['totalLinhas'] != 2400:
        raise RuntimeError('O cenário de desempenho não tem a dimensão esperada.')
    return {'revisionId': revision['id'], 'products': 100, 'historyMonths': 24, 'historyRows': 2400,
            'horizonMonths': 3, 'revisionCells': 300, 'method': 'MEDIA_MOVEL', 'window': 3,
            'warmups': 5, 'samples': 30, 'targetP95Ms': 2000, 'lastTrainingMonth': periods[-1],
            'quantityPolicy': '35 substituições sequenciais da mesma célula, com valores distintos e justificação',
            'isolated': True}


if __name__ == '__main__':
    os.umask(0o077)
    try:
        run_isolated(performance_seed=prepare)
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
