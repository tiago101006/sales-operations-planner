"""Importa exemplos sinteticos de meses fechados e avalia um plano publicado."""
import csv
import getpass
import http.cookiejar
import io
import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
import urllib.error
import urllib.request
import uuid


def next_month(month):
    year, value = map(int, month.split('-'))
    return f'{year + value // 12:04d}-{value % 12 + 1:02d}'


def csv_bytes(header, rows):
    output = io.StringIO(newline='')
    writer = csv.writer(output, delimiter=';', lineterminator='\n')
    writer.writerow(header)
    writer.writerows(rows)
    return output.getvalue().encode('utf-8')


def main():
    base = input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    email = input('Email ADMIN/PLANEADOR [admin@admin.com]: ').strip() or 'admin@admin.com'
    password = getpass.getpass('Password: ')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logged_in = False

    def request(method, path, data=None, file=None):
        headers = {}
        if method != 'GET':
            csrf = request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        if file:
            kind, filename, content = file
            boundary = 'sop' + uuid.uuid4().hex
            prefix = ''.join(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'
                             for k, v in [('tipo', kind), ('origem', 'ensaio-sintetico-monitorizacao')])
            prefix += f'--{boundary}\r\nContent-Disposition: form-data; name="ficheiro"; filename="{filename}"\r\nContent-Type: text/csv\r\n\r\n'
            body = prefix.encode() + content + f'\r\n--{boundary}--\r\n'.encode()
            headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
        else:
            body = json.dumps(data).encode() if data is not None else None
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers, method=method)
        with client.open(req, timeout=180) as response:
            value = response.read()
            return json.loads(value) if value else None

    def show(value):
        print(json.dumps(value, ensure_ascii=False, indent=2))

    try:
        user = request('POST', '/api/v1/auth/login', {'email': email, 'password': password})
        password = None
        logged_in = True
        if not {'ADMIN', 'PLANEADOR'}.intersection(user['papeis']):
            raise SystemExit('Usa ADMIN ou PLANEADOR para criar importacoes e avaliacoes.')
        plans = request('GET', '/api/v1/planos?size=200')['items']
        if not plans:
            raise SystemExit('Publica primeiro um plano com o ensaio de aprovacao.')
        for plan in plans[:10]:
            print(f"  Plano {plan['id']} | publicado {plan['publicadoEm']} | {plan['totalLinhas']} linhas")
        plan_id = input(f"ID do plano [{plans[0]['id']}]: ").strip() or plans[0]['id']
        if not plan_id.isdigit() or int(plan_id) <= 0:
            raise SystemExit('Indica um ID numerico de plano.')
        lines = []
        page = 0
        while True:
            result = request('GET', f'/api/v1/planos/{plan_id}/linhas?size=200&page={page}')
            lines.extend(result['items'])
            page += 1
            if page >= result['totalPages']:
                break
        if not lines:
            raise SystemExit('O plano nao contem linhas.')
        current = datetime.now(timezone.utc).strftime('%Y-%m')
        first = lines[0]
        sample = [line for line in lines if line['produtoId'] == first['produtoId'] and line['periodo'] < current][:3]
        if not sample:
            year, month = map(int, current.split('-'))
            previous = f'{year - (month == 1):04d}-{12 if month == 1 else month - 1:02d}'
            sample = [dict(first, periodo=previous)]
            print(f'O plano ainda nao tem meses fechados. Os CSV de exemplo usam {previous}.')
            print('As celulas do plano vao mostrar exclusoes temporais e precisao indisponivel; isto e esperado.')
        print('Dados SINTETICOS de ensaio: serao guardados dois lotes e uma avaliacao, sem alterar o plano.')
        realized, operational = [], []
        for i, line in enumerate(sample):
            if i == 2:
                continue  # Preserve a missing actual when three closed periods exist.
            month = line['periodo']
            available = next_month(month) + '-01T00:00:00Z'
            quantity = '0.0000' if i == 1 else f"{min(Decimal(line['quantidadeBase']) + Decimal('1'), Decimal('99999999999999.9999')):.4f}"
            realized.append([line['produto'], month, quantity, line['unidade'], available])
            operational.append([line['produto'], month, line['unidade'], '100', '90', '20', '2', '30', '10', '1', '2', available])
        real_csv = csv_bytes(['produto', 'periodo', 'quantidade', 'unidade', 'disponivel_em'], realized)
        op_csv = csv_bytes(['produto', 'periodo', 'unidade', 'unidades_solicitadas', 'unidades_satisfeitas_no_prazo',
                            'dias_observados_com_procura', 'dias_com_procura_sem_stock', 'stock_fecho', 'limite_stock',
                            'unidades_perdidas', 'prazo_medio_dias', 'disponivel_em'], operational)
        folder = Path(__file__).resolve().parents[1] / 'tmp' / 'monitorizacao'
        folder.mkdir(parents=True, exist_ok=True)
        (folder / f'realizados_plano_{plan_id}.csv').write_bytes(real_csv)
        (folder / f'operacional_plano_{plan_id}.csv').write_bytes(op_csv)
        actual_lot = request('POST', '/api/v1/lotes', file=('REALIZADOS', 'realizados_ensaio.csv', real_csv))
        op_lot = request('POST', '/api/v1/lotes', file=('OPERACIONAL', 'operacional_ensaio.csv', op_csv))
        assert actual_lot['estado'] == op_lot['estado'] == 'VALIDADO'
        actual_rows = request('GET', '/api/v1/lotes/' + actual_lot['id'] + '/observacoes')
        op_rows = request('GET', '/api/v1/lotes/' + op_lot['id'] + '/operacionais')
        assert actual_rows['totalElements'] == len(realized) and op_rows['totalElements'] == len(operational)
        assessment = request('POST', '/api/v1/avaliacoes', {
            'planoId': plan_id, 'loteRealizadosId': actual_lot['id'], 'loteOperacionalId': op_lot['id'],
            'periodoInicio': min(line['periodo'] for line in lines), 'periodoFim': max(line['periodo'] for line in lines),
            'corteRealizados': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')})
        assert assessment['totalCelulas'] == len(lines)
        print('Avaliacao criada:')
        show(assessment)
        path = '/api/v1/avaliacoes/' + assessment['id']
        precision = request('GET', path + '/precisao')
        print('Precisao por produto e horizonte (primeira pagina):')
        show(precision)
        for group in precision['items']:
            metrics = group['metricas']
            assert metrics['candidatos'] == (metrics['paresMae'] + metrics['emFalta'] + metrics['realizadosAposCorte'] + metrics['planosTardios'] + metrics['periodosNaoFechados'])
            if metrics['paresMae'] == 0:
                assert metrics['maeBase'] is None and metrics['maeAprovado'] is None
            if metrics['paresMape'] == 0:
                assert metrics['mapeBase'] is None and metrics['mapeAprovado'] is None
        print('Indicadores operacionais (primeira pagina):')
        show(request('GET', path + '/operacao'))
        print('Celulas e motivos de exclusao (primeira pagina):')
        show(request('GET', path + '/celulas'))
        print(f'CSV sinteticos guardados em: {folder}')
        print('Ensaio concluido: realizados, operacionais, avaliacao, cobertura e exclusoes verificados.')
    except urllib.error.HTTPError as error:
        try:
            problem = json.loads(error.read())
        except (ValueError, UnicodeError):
            problem = {}
        print(f'Pedido recusado (HTTP {error.code}).')
        show(problem)
        raise SystemExit(1) from None
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta lotes/avaliacoes antes de repetir; alguns pedidos podem estar guardados.') from None
    finally:
        if logged_in:
            try:
                request('POST', '/api/v1/auth/logout', {})
                print('Logout concluido.')
            except (urllib.error.URLError, TimeoutError):
                print('Nao foi possivel confirmar o logout.')


if __name__ == '__main__':
    main()
