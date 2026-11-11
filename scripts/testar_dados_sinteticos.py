"""Valida agregação diária, repetibilidade, importações reais e previsões isoladas."""
import csv
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import urllib.error
from gerar_dados import generate
from importar_demonstracao import populate
from testar_interface import main as isolated

ROOT = Path(__file__).resolve().parents[1]


def rows(path):
    return list(csv.DictReader(Path(path).open(newline=''), delimiter=';'))


def prepare(request):
    folder = ROOT / 'data/sinteticos'
    manifest = json.loads((folder / 'manifesto.json').read_text())
    with tempfile.TemporaryDirectory() as tmp:
        generate(tmp)
        for path in folder.rglob('*'):
            if path.is_file(): assert path.read_bytes() == (Path(tmp) / path.relative_to(folder)).read_bytes(), str(path)
    daily = {}
    for row in rows(folder / 'evidencia_diaria.csv'):
        key = (row['produto'], row['data'][:7])
        sums = daily.setdefault(key, [0, 0, 0, 0, 0])
        q = int(row['solicitadas']);sat = int(row['satisfeitas_no_prazo']);out = int(row['dia_sem_stock_com_procura'])
        assert 0 <= sat <= q and (not out or q > 0)
        for i, value in enumerate([q, sat, int(q > 0), out, int(row['perdidas'])]): sums[i] += value
    for row in rows(folder / 'operacionais.csv'):
        expected = daily[(row['produto'], row['periodo'])]
        assert expected == [int(row[k]) for k in ['unidades_solicitadas', 'unidades_satisfeitas_no_prazo', 'dias_observados_com_procura', 'dias_com_procura_sem_stock', 'unidades_perdidas']]
    first = populate(request, folder);second = populate(request, folder)
    assert first['produtosCriados'] == 100 and second['produtosCriados'] == 0
    assert first['lotes'] == second['lotes']
    for filename, count in [('historico_treino.csv', 3600), ('historico_atual.csv', 3600), ('realizados.csv', 299), ('operacionais.csv', 300)]:
        lot = request('GET', '/api/v1/lotes/' + first['lotes'][filename]);assert lot['totalLinhas'] == count
    from urllib.parse import quote
    # Search must find products beyond the first page and count the full filtered set.
    history_path = '/api/v1/lotes/' + first['lotes']['historico_atual.csv'] + '/observacoes'
    filtered = request('GET', history_path + '?busca=DEMO100&size=20&page=1')
    assert filtered['totalElements'] == 36 and len(filtered['items']) == 16
    assert all(row['produto'] == 'DEMO100' for row in filtered['items'])
    named = request('GET', history_path + '?busca=' + quote('embalagem 5') + '&size=200')
    assert named['totalElements'] == 20 * 36
    assert request('GET', history_path + '?busca=%25')['totalElements'] == 0
    assert request('GET', history_path + '?busca=' + quote("' OR '1'='1"))['totalElements'] == 0
    op_path = '/api/v1/lotes/' + first['lotes']['operacionais.csv'] + '/operacionais'
    last_month = manifest['atual']['fim']
    ops = request('GET', op_path + '?busca=DEMO100&desde=' + last_month + '&ate=' + last_month)
    assert ops['totalElements'] == 1 and ops['items'][0]['periodo'] == last_month
    for query in ['desde=2026-13', 'desde=2026-10&ate=2026-01']:
        try: request('GET', op_path + '?' + query)
        except urllib.error.HTTPError as error: assert error.code == 422
        else: raise AssertionError('Intervalo inválido aceite')
    team = request('GET', '/api/v1/utilizadores?busca=admin')
    assert team['totalElements'] == 1
    assert set(team['items'][0]) == {'id', 'nome', 'email', 'papeis'}
    now = datetime.now(timezone.utc).isoformat()
    executions = {}
    for method, params in [('MEDIA_MOVEL', {'janela': 3, 'multiplosPassos': 'RECURSIVO'}), ('ETS', {'variante': 'AAN', 'historicoMeses': 24, 'recurso': 'SEM_RECURSO'})]:
        execution = request('POST', '/api/v1/execucoes-previsao', {'loteHistoricoId': first['lotes']['historico_atual.csv'], 'tipoSerie': 'VENDAS', 'corteTemporal': now, 'ultimoPeriodoTreino': manifest['atual']['fim'], 'horizonteMeses': 3, 'metodo': method, 'parametros': params})
        assert execution['estado'] == 'CONCLUIDA' and execution['totalLinhas'] == 300
        assert execution['produtosPrevistos'] == 100
        executions[method] = execution['id']
    for path in sorted((folder / 'invalidos').glob('*.csv')):
        try: request('POST', '/api/v1/lotes', upload=('HISTORICO', path.read_text()))
        except urllib.error.HTTPError as error:
            assert error.code == 422
            problem = json.loads(error.read());lot_id = problem['loteId']
            lot = request('GET', '/api/v1/lotes/' + str(lot_id));assert lot['estado'] == 'REJEITADO' and lot['totalErros'] > 0
        else: raise AssertionError('Fixture inválida aceite: ' + path.name)
    result = {'sintetico': True, 'isolado': True, 'produtos': 100, 'historicos': [3600, 3600], 'realizados': 299, 'operacionais': 300, 'previsoesPorMetodo': 300, 'metodos': list(executions), 'invalidosRejeitados': 5, 'repeticaoSemDuplicados': True, 'agregacaoDiariaConferida': True}
    target = ROOT / 'tmp/demonstracao/validacao.json';target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    isolated(validation_seed=prepare)
