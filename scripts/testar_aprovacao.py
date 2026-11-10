"""Ensaio com duas contas: submeter, devolver, corrigir, aprovar e comparar API/CSV."""
import csv
import getpass
import http.cookiejar
import io
import json
from decimal import Decimal
from pathlib import Path
import urllib.error
import urllib.request


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.logged_in = False

    def request(self, method, path, data=None, raw=False):
        headers = {'Content-Type': 'application/json'}
        if method not in ('GET', 'HEAD'):
            csrf = self.request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=json.dumps(data).encode() if data is not None else None)
        with self.opener.open(req, timeout=180) as response:
            value = response.read()
            return value if raw else json.loads(value) if value else None

    def login(self, email, password):
        user = self.request('POST', '/api/v1/auth/login', {'email': email, 'password': password})
        self.logged_in = True
        return user


def show(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


def expected_error(client, path, body, status):
    try:
        client.request('POST', path, body)
    except urllib.error.HTTPError as error:
        if error.code != status:
            raise
        error.read()
        return
    raise AssertionError(f'Esperado HTTP {status} em {path}.')


def main():
    base = input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    admin, approver = Client(base), Client(base)
    try:
        print('Conta ADMIN existente: prepara o plano e pode criar uma conta APROVADOR separada.')
        email = input('Email ADMIN [admin@admin.com]: ').strip() or 'admin@admin.com'
        password = getpass.getpass('Password ADMIN: ')
        author = admin.login(email, password)
        password = None
        if 'ADMIN' not in author['papeis']:
            raise SystemExit('Usa a conta ADMIN neste script de preparacao do ensaio.')
        executions = admin.request('GET', '/api/v1/execucoes-previsao?size=200')['items']
        eligible = [e for e in executions if e['estado'] == 'CONCLUIDA' and e['totalLinhas'] > 0]
        if not eligible:
            raise SystemExit('Cria primeiro uma previsao operacional concluida com linhas.')
        for execution in eligible[:10]:
            print(f"  Execucao {execution['id']} | {execution['metodo']} | {execution['totalLinhas']} linhas")
        source = input(f"ID da execucao [{eligible[0]['id']}]: ").strip() or eligible[0]['id']
        if not source.isdigit() or int(source) <= 0:
            raise SystemExit('Indica um ID numerico de execucao.')
        execution = admin.request('GET', '/api/v1/execucoes-previsao/' + source)
        if execution['estado'] != 'CONCLUIDA' or execution['totalLinhas'] == 0:
            raise SystemExit('A execucao deve estar concluida e conter previsoes.')

        create_user = input('Criar uma nova conta APROVADOR? [S/n]: ').strip().lower()
        if create_user not in ('', 's', 'n'):
            raise SystemExit('Escolhe s para criar ou n para usar uma conta existente.')
        approver_email = input('Email APROVADOR [aprovador@exemplo.local]: ').strip() or 'aprovador@exemplo.local'
        approver_password = getpass.getpass('Password APROVADOR (12 caracteres no minimo): ')
        if create_user != 'n':
            repeated = getpass.getpass('Repete a password APROVADOR: ')
            if repeated != approver_password:
                raise SystemExit('As passwords nao coincidem.')
            repeated = None
            if not 12 <= len(approver_password) <= 72 or len(approver_password.encode()) > 72:
                raise SystemExit('A password exige 12 caracteres no minimo e ate 72 bytes UTF-8.')
            try:
                admin.request('POST', '/api/v1/utilizadores', {
                    'nome': 'Aprovador de ensaios', 'email': approver_email,
                    'password': approver_password, 'papeis': ['APROVADOR']})
            except urllib.error.HTTPError as error:
                if error.code == 409:
                    error.read()
                    raise SystemExit('Este email ja existe. Repete o script, escolhe n e usa a password dessa conta.') from None
                raise
            print('Conta criada apenas com o papel APROVADOR. Conserva as credenciais para os proximos ensaios.')
        decision_maker = approver.login(approver_email, approver_password)
        approver_password = None
        if 'APROVADOR' not in decision_maker['papeis'] or decision_maker['id'] == author['id']:
            raise SystemExit('A decisao exige outra conta com o papel APROVADOR.')

        print('O ensaio guarda 1 cenario, 2 revisoes, 1 ajuste e 1 plano publicado de teste.')
        case = admin.request('POST', '/api/v1/cenarios', {
            'execucaoId': source, 'nome': 'Fluxo de aprovacao (ensaio manual)', 'tipo': 'OTIMISTA', 'fator': '1.10',
            'pressupostos': {'descricao': 'Hipotese sintetica para testar devolucao, correcao e publicacao.'}})
        original = case['revisao']
        root = '/api/v1/revisoes/' + original['id']
        submitted = admin.request('POST', root + '/submissao', {'versaoEsperada': original['versao']})
        assert submitted['estado'] == 'EM_REVISAO'
        cell = admin.request('GET', root + '/linhas?size=1')['items'][0]
        new_value = f"{Decimal(cell['quantidadeFinal']) + Decimal('1'):.4f}"
        edit = {'produtoId': cell['produtoId'], 'periodo': cell['periodo'], 'quantidade': new_value,
                'justificacao': 'Ensaio manual: correcao hipotetica de uma unidade.', 'versaoEsperada': submitted['versao']}
        expected_error(admin, root + '/ajustes', edit, 409)
        print('Congelamento verificado: ajuste apos submissao recusado com HTTP 409 esperado.')
        returned = approver.request('POST', root + '/devolucao', {
            'versaoEsperada': submitted['versao'], 'motivo': 'Ensaio manual: rever a primeira celula antes de publicar.'})
        assert returned['estado'] == 'DEVOLVIDA'
        print(f"Revisao {returned['id']} devolvida com motivo; continua congelada.")
        corrected = admin.request('POST', '/api/v1/cenarios/' + case['cenario']['id'] + '/revisoes', {
            'revisaoOrigemId': returned['id'], 'versaoOrigemEsperada': returned['versao']})
        path = '/api/v1/revisoes/' + corrected['id']
        edit['versaoEsperada'] = corrected['versao']
        adjustment = admin.request('POST', path + '/ajustes', edit)
        submitted = admin.request('POST', path + '/submissao', {'versaoEsperada': adjustment['versaoAtual']})
        published = approver.request('POST', path + '/aprovacao', {'versaoEsperada': submitted['versao']})
        assert published['versaoAtual'] == submitted['versao'] + 1
        plan_id = published['planoId']
        header = admin.request('GET', '/api/v1/planos/' + plan_id)
        assert header['aprovadoPor'] == decision_maker['id'] and header['revisaoId'] == corrected['id']
        assert admin.request('GET', root)['estado'] == 'DEVOLVIDA'
        assert admin.request('GET', path)['estado'] == 'APROVADA'
        expected_error(approver, path + '/aprovacao', {'versaoEsperada': submitted['versao']}, 409)
        print('Publicacao unica verificada: repetir a aprovacao com versao antiga devolve HTTP 409 esperado.')
        lines = []
        page = 0
        while True:
            result = admin.request('GET', f'/api/v1/planos/{plan_id}/linhas?page={page}&size=200')
            lines.extend(result['items'])
            page += 1
            if page >= result['totalPages']:
                break
        assert len(lines) == header['totalLinhas']
        assert lines[0]['quantidade'] == new_value
        download = admin.request('GET', '/api/v1/planos/' + plan_id + '/exportacao.csv', raw=True)
        reader = csv.DictReader(io.StringIO(download.decode('utf-8'), newline=''), delimiter=';')
        expected = [{'plano_id': plan_id, 'produto': row['produto'], 'periodo': row['periodo'],
                     'unidade': row['unidade'], 'quantidade_base': row['quantidadeBase'], 'quantidade': row['quantidade']}
                    for row in lines]
        assert list(reader) == expected, 'CSV diferente das linhas da API.'
        destination = Path(__file__).resolve().parents[1] / 'tmp' / 'exports' / f'plano-{plan_id}.csv'
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(download)
        print('Plano publicado:')
        show(header)
        print(f'API e CSV coincidem nas {len(lines)} linhas. Ficheiro: {destination}')
        print('Ensaio concluido: submissao, devolucao, correcao, aprovacao e API/CSV verificados.')
    except urllib.error.HTTPError as error:
        try:
            problem = json.loads(error.read())
        except (ValueError, UnicodeError):
            problem = {}
        print(f'Pedido recusado (HTTP {error.code}).')
        show(problem)
        raise SystemExit(1) from None
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta as revisoes e planos antes de repetir; alguns pedidos podem estar guardados.') from None
    finally:
        for label, client in [('ADMIN', admin), ('APROVADOR', approver)]:
            if client.logged_in:
                try:
                    client.request('POST', '/api/v1/auth/logout', {})
                    print(f'Logout {label} concluido.')
                except (urllib.error.URLError, TimeoutError):
                    print(f'Nao foi possivel confirmar o logout {label}.')


if __name__ == '__main__':
    main()
