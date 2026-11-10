"""Ensaio manual: dois cenarios, um ajuste justificado e uma revisao com auditoria herdada."""
import getpass
import http.cookiejar
import json
from decimal import Decimal, InvalidOperation
import urllib.error
import urllib.parse
import urllib.request


def main():
    base = input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    email = input('Email: ').strip()
    password = getpass.getpass('Password: ')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logged_in = False

    def request(method, path, data=None):
        headers = {'Content-Type': 'application/json'}
        if method not in ('GET', 'HEAD'):
            csrf = request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        req = urllib.request.Request(base.rstrip('/') + path, headers=headers, method=method,
                                     data=json.dumps(data).encode() if data is not None else None)
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
            raise SystemExit('Este perfil nao pode criar cenarios nem ajustes.')
        executions = request('GET', '/api/v1/execucoes-previsao?size=200')['items']
        eligible = [e for e in executions if e['estado'] == 'CONCLUIDA' and e['totalLinhas'] > 0]
        if not eligible:
            raise SystemExit('Cria primeiro uma previsao operacional concluida com linhas.')
        print('Execucoes elegiveis (pagina mais recente):')
        for execution in eligible[:10]:
            print(f"  ID {execution['id']} | {execution['metodo']} | {execution['totalLinhas']} linhas")
        execution_id = input(f"ID da execucao [{eligible[0]['id']}]: ").strip() or eligible[0]['id']
        if not execution_id.isdigit() or int(execution_id) <= 0:
            raise SystemExit('Indica um ID numerico de execucao.')
        # Validate before creating either scenario, including an ID outside this list page.
        source = request('GET', '/api/v1/execucoes-previsao/' + execution_id)
        if source['estado'] != 'CONCLUIDA' or source['totalLinhas'] == 0:
            raise SystemExit('A execucao tem de estar concluida e conter previsoes.')
        print('Este ensaio vai guardar 2 cenarios, 1 ajuste e 1 revisao adicional na base local.')
        cases = []
        for kind, label, factor in [('OTIMISTA', 'Otimista', '1.10'), ('CONSERVADOR', 'Conservador', '0.90')]:
            case = request('POST', '/api/v1/cenarios', {
                'execucaoId': execution_id, 'nome': label + ' (ensaio manual)', 'tipo': kind, 'fator': factor,
                'pressupostos': {'descricao': 'Dados de ensaio manual: variacao hipotetica de 10% face a previsao base.'}})
            cases.append(case)
            print(f"{label}: cenario {case['cenario']['id']}, revisao {case['revisao']['id']}, fator {factor}.")
        optimistic, conservative = cases
        revision = optimistic['revisao']
        path = '/api/v1/revisoes/' + revision['id']
        cell = request('GET', path + '/linhas?size=1')['items'][0]
        print('Primeira celula otimista:')
        show(cell)
        default = Decimal(cell['quantidadeFinal']) + Decimal('1')
        value = input(f'Nova quantidade para {cell["produto"]}/{cell["periodo"]} [{default:.4f}]: ').strip() or str(default)
        try:
            amount = Decimal(value)
            if not amount.is_finite() or amount < 0 or amount > Decimal('99999999999999.9999') or amount.as_tuple().exponent < -4:
                raise ValueError
        except (InvalidOperation, ValueError):
            raise SystemExit('Quantidade invalida: usa ponto decimal, ate 4 casas e um valor nao negativo.') from None
        reason = input('Justificacao [Ensaio manual: alteracao hipotetica da quantidade na celula selecionada.]: ').strip()
        reason = reason or 'Ensaio manual: alteracao hipotetica da quantidade na celula selecionada.'
        adjustment = request('POST', path + '/ajustes', {
            'produtoId': cell['produtoId'], 'periodo': cell['periodo'], 'quantidade': str(amount),
            'justificacao': reason, 'versaoEsperada': revision['versao']})
        print('Ajuste registado com autor e valores anterior/novo:')
        show(adjustment)
        assert adjustment['ajuste']['autorId'] == user['id'], 'Autor do ajuste diferente do utilizador autenticado.'
        assert Decimal(adjustment['ajuste']['valorAnterior']) == Decimal(cell['quantidadeFinal'])
        assert Decimal(adjustment['ajuste']['valorNovo']) == amount
        copied = request('POST', '/api/v1/cenarios/' + optimistic['cenario']['id'] + '/revisoes', {
            'revisaoOrigemId': revision['id'], 'versaoOrigemEsperada': adjustment['versaoAtual']})
        print('Nova revisao: copia dos valores e da origem da auditoria, sem duplicar ajustes:')
        show(copied)
        inherited = request('GET', '/api/v1/revisoes/' + copied['id'] + '/ajustes?incluirHerdados=true')
        print('Auditoria herdada:')
        show(inherited)
        assert inherited['totalElements'] == 1 and inherited['items'][0]['herdado'], 'Esperado um ajuste herdado.'
        assert copied['totalAjustesLocais'] == 0
        comparison = request('GET', '/api/v1/comparacao-cenarios?' + urllib.parse.urlencode({
            'revisaoIds': copied['id'] + ',' + conservative['revisao']['id'], 'size': 1}))
        print('Comparacao (primeiro produto; a previsao base continua visivel):')
        show(comparison)
        first = comparison['produtos']['items'][0]['periodos'][0]
        assert Decimal(first['quantidadeBase']) == Decimal(cell['quantidadeBase'])
        assert Decimal(first['cenarios'][0]['quantidadeFinal']) == amount
        print('Ensaio concluido: criacao, ajuste, copia com auditoria e comparacao verificados.')
    except urllib.error.HTTPError as error:
        try:
            problem = json.loads(error.read())
        except (ValueError, UnicodeError):
            problem = {}
        print(f'Pedido recusado (HTTP {error.code}).')
        show(problem)
        raise SystemExit(1) from None
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta os cenarios antes de repetir; alguns pedidos podem ter sido guardados.') from None
    finally:
        if logged_in:
            try:
                request('POST', '/api/v1/auth/logout', {})
                print('Logout concluido.')
            except (urllib.error.URLError, TimeoutError):
                print('Nao foi possivel confirmar o logout.')


if __name__ == '__main__':
    main()
