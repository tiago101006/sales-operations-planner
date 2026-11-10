"""Cria uma execucao de media movel, consulta previsoes e diagnosticos e faz logout."""
import datetime
import getpass
import http.cookiejar
import json
import re
import urllib.error
import urllib.request


def main():
    base = input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    email = input('Email: ').strip()
    password = getpass.getpass('Password: ')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logged_in = False

    def request(method, endpoint, data=None):
        headers = {'Content-Type': 'application/json'}
        if method not in ('GET', 'HEAD'):
            csrf = request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(base.rstrip('/') + endpoint, data=body, headers=headers, method=method)
        with client.open(req, timeout=180) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def show(value):
        print(json.dumps(value, ensure_ascii=False, indent=2))

    try:
        user = request('POST', '/api/v1/auth/login', {'email': email, 'password': password})
        password = None
        logged_in = True
        if not {'ADMIN', 'PLANEADOR'}.intersection(user['papeis']):
            raise SystemExit('Este perfil nao pode criar execucoes de previsao.')
        lots = request('GET', '/api/v1/lotes?size=200')['items']
        valid = [lot for lot in lots if lot['tipo'] == 'HISTORICO' and lot['estado'] == 'VALIDADO']
        print('Historicos validados na pagina mais recente:')
        for lot in valid[:10]:
            print(f"  ID {lot['id']} | {lot['nomeFicheiro']} | {lot['totalLinhas']} registos")
        default_id = valid[0]['id'] if valid else ''
        lot_id = input(f'ID numerico do lote (nao e o SKU) [{default_id}]: ').strip() or default_id
        if not lot_id.isdigit() or int(lot_id) <= 0:
            raise SystemExit('Indica um ID numerico de lote validado. Importa primeiro um historico, se necessario.')
        series = input('Tipo de serie [VENDAS]: ').strip().upper() or 'VENDAS'
        if series not in ('VENDAS', 'CONSUMO'):
            raise SystemExit('Usa VENDAS ou CONSUMO.')
        today = datetime.datetime.now(datetime.timezone.utc).date()
        previous_month = today.replace(day=1) - datetime.timedelta(days=1)
        default_month = previous_month.strftime('%Y-%m')
        month = input(f'Ultimo mes de treino [{default_month}]: ').strip() or default_month
        if not re.fullmatch(r'[1-9][0-9]{3}-(0[1-9]|1[0-2])', month):
            raise SystemExit('O mes deve usar AAAA-MM.')
        window = input('Janela em meses [3]: ').strip() or '3'
        horizon = input('Horizonte em meses [3]: ').strip() or '3'
        if not window.isdigit() or not 1 <= int(window) <= 36 or not horizon.isdigit() or not 1 <= int(horizon) <= 12:
            raise SystemExit('Janela entre 1 e 36; horizonte entre 1 e 12.')
        body = {
            'loteHistoricoId': lot_id, 'tipoSerie': series,
            'corteTemporal': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z'),
            'ultimoPeriodoTreino': month, 'horizonteMeses': int(horizon), 'metodo': 'MEDIA_MOVEL',
            'parametros': {'janela': int(window), 'multiplosPassos': 'RECURSIVO'},
        }
        execution = request('POST', '/api/v1/execucoes-previsao', body)
        print('Execucao:')
        show(execution)
        path = '/api/v1/execucoes-previsao/' + execution['id']
        print('Previsoes (primeira pagina):')
        show(request('GET', path + '/linhas'))
        print('Diagnosticos por produto (primeira pagina):')
        show(request('GET', path + '/diagnosticos'))
        if execution['produtosPrevistos'] == 0:
            print('O processamento terminou sem previsoes. Consulta os meses em falta nos diagnosticos.')
    except urllib.error.HTTPError as error:
        try:
            problem = json.loads(error.read())
        except (ValueError, UnicodeError):
            problem = {}
        print(f'Pedido recusado (HTTP {error.code}).')
        show(problem)
        raise SystemExit(1) from None
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta a lista de execucoes antes de repetir o pedido.') from None
    finally:
        if logged_in:
            try:
                request('POST', '/api/v1/auth/logout', {})
                print('Logout concluido.')
            except (urllib.error.URLError, TimeoutError):
                print('Nao foi possivel confirmar o logout.')


if __name__ == '__main__':
    main()
