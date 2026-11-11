"""Importa os dados sintéticos pela API local; pede credenciais sem as guardar."""
import csv
import getpass
import hashlib
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def load_dataset(folder):
    folder = Path(folder)
    manifest = json.loads((folder / 'manifesto.json').read_text())
    if manifest.get('sintetico') is not True or manifest.get('versao') != 1:
        raise ValueError('Manifesto sintético v1 obrigatório.')
    for name, expected in manifest['sha256'].items():
        target = (folder / name).resolve()
        if not target.is_relative_to(folder.resolve()) or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise ValueError('Ficheiro alterado ou caminho inválido no manifesto: ' + name)
    return manifest, json.loads((folder / 'produtos.json').read_text())


def populate(request, folder):
    """Usado também no teste isolado. Não altera produtos ou lotes anteriores."""
    folder = Path(folder);manifest, products = load_dataset(folder)
    def all_items(path):
        items = [];page = 0
        while True:
            result = request('GET', f'{path}?size=200&page={page}')
            items.extend(result['items']);page += 1
            if page >= result['totalPages']: return items
    existing = {p['codigo']: p for p in all_items('/api/v1/produtos')}
    # Check all conflicts before creating anything.
    for product in products:
        old = existing.get(product['codigo'])
        if old and (old['nome'] != product['nome'] or old['unidade'] != product['unidade'] or not old['ativo']):
            raise ValueError('Código existente incompatível: ' + product['codigo'])
    created = 0
    for product in products:
        if product['codigo'] not in existing:
            request('POST', '/api/v1/produtos', product);created += 1
    lots = all_items('/api/v1/lotes');ids = {}
    for filename, kind in [('historico_treino.csv', 'HISTORICO'), ('historico_atual.csv', 'HISTORICO'), ('realizados.csv', 'REALIZADOS'), ('operacionais.csv', 'OPERACIONAL')]:
        match = next((l for l in lots if l['tipo'] == kind and l['estado'] == 'VALIDADO' and l['sha256'] == manifest['sha256'][filename]), None)
        if match is None:
            match = request('POST', '/api/v1/lotes', upload=(kind, (folder / filename).read_text()))
        if match['estado'] != 'VALIDADO': raise ValueError('Lote não validado: ' + filename)
        ids[filename] = match['id']
    return {'sintetico': True, 'produtosCriados': created, 'lotes': ids, 'ultimoMesTreinoAtual': manifest['atual']['fim'], 'ultimoMesTreinoRetrospectivo': manifest['treino']['fim']}


def main():
    folder = ROOT / 'data/sinteticos'
    manifest, _ = load_dataset(folder)
    base = input('URL da cópia de demonstração [http://127.0.0.1:32770]: ').strip() or 'http://127.0.0.1:32770'
    url = urllib.parse.urlsplit(base)
    if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', '::1') or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        raise ValueError('Este importador destina-se apenas a uma instalação HTTP local.')
    print('Serão criados até 100 produtos e quatro lotes sintéticos. Dados existentes são preservados.')
    email = input('Email ADMIN/PLANEADOR: ').strip()
    password = getpass.getpass('Password: ')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(method, path, value=None, upload=None):
        headers = {};body = None
        if method != 'GET':
            csrf = request('GET', '/api/v1/auth/csrf');headers[csrf['headerName']] = csrf['token']
        if upload:
            kind, content = upload;boundary = uuid.uuid4().hex
            digest = hashlib.sha256(content.encode()).hexdigest()
            filename = next(name for name, sha in manifest['sha256'].items() if sha == digest and '/' not in name)
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="tipo"\r\n\r\n{kind}\r\n--{boundary}\r\nContent-Disposition: form-data; name="origem"\r\n\r\nDEMONSTRACAO SINTETICA NexoPlan\r\n--{boundary}\r\nContent-Disposition: form-data; name="ficheiro"; filename="{filename}"\r\nContent-Type: text/csv\r\n\r\n{content}\r\n--{boundary}--\r\n').encode()
            headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
        elif value is not None:
            body = json.dumps(value).encode();headers['Content-Type'] = 'application/json'
        with client.open(urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers, method=method), timeout=180) as response:
            raw = response.read();return json.loads(raw) if raw else None
    logged_in = False
    try:
        user = request('POST', '/api/v1/auth/login', {'email': email, 'password': password});password = None;logged_in = True
        if not {'ADMIN', 'PLANEADOR'}.intersection(user['papeis']): raise ValueError('É necessário ADMIN ou PLANEADOR.')
        report = populate(request, folder)
        path = ROOT / 'tmp/demonstracao/importacao.json';path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2));print('Importação concluída. Próximo passo: gerar previsão a partir do lote historico_atual.csv indicado acima.')
    finally:
        if logged_in:
            try: request('POST', '/api/v1/auth/logout', {})
            except (urllib.error.URLError, TimeoutError): print('Não foi possível confirmar o logout.')


if __name__ == '__main__':
    try: main()
    except urllib.error.HTTPError as error:
        print(f'Pedido recusado (HTTP {error.code}). Consulta os lotes antes de repetir.');raise SystemExit(1) from None
    except (ValueError, OSError, TimeoutError) as error:
        print(str(error));raise SystemExit(1) from None
