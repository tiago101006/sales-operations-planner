"""Envia um historico CSV e consulta o lote/diagnosticos com sessao e CSRF."""
import getpass
import http.cookiejar
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import uuid


def main():
    base = input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    email = input('Email: ').strip()
    password = getpass.getpass('Password: ')
    default = Path(__file__).resolve().parents[1] / 'data/exemplos/historico_sku001_valido.csv'
    chosen = input('Ficheiro CSV [Enter = exemplo valido SKU001]: ').strip()
    path = Path(chosen).expanduser() if chosen else default
    if not path.is_file():
        raise SystemExit('Ficheiro nao encontrado: ' + str(path))
    if path.stat().st_size > 10 * 1024 * 1024:
        raise SystemExit('O ficheiro excede 10 MiB.')
    data = path.read_bytes()
    origin = input('Origem [historico-sintetico]: ').strip() or 'historico-sintetico'
    previous = input('ID de lote anterior a corrigir [Enter = novo lote]: ').strip()
    if previous and (not previous.isdigit() or int(previous) <= 0):
        raise SystemExit('O ID anterior deve ser um inteiro positivo.')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logged_in = False

    def request(method, endpoint, body=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if method not in ('GET', 'HEAD'):
            csrf = request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
        req = urllib.request.Request(base.rstrip('/') + endpoint, data=body, headers=headers, method=method)
        with client.open(req, timeout=180) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def show(value):
        print(json.dumps(value, ensure_ascii=False, indent=2))

    try:
        request('POST', '/api/v1/auth/login', {'email': email, 'password': password})
        password = None
        logged_in = True
        boundary = 'sop' + uuid.uuid4().hex
        parts = []
        fields = {'tipo': 'HISTORICO', 'origem': origin, 'versaoEsquema': 'v1'}
        if previous:
            fields['substituiLoteId'] = previous
        for name, value in fields.items():
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n').encode())
        filename = re.sub(r'[^A-Za-z0-9._-]', '_', path.name)
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="ficheiro"; filename="{filename}"\r\nContent-Type: text/csv\r\n\r\n').encode())
        parts.extend([data, f'\r\n--{boundary}--\r\n'.encode()])
        try:
            lot = request('POST', '/api/v1/lotes', b''.join(parts), f'multipart/form-data; boundary={boundary}')
        except urllib.error.HTTPError as error:
            problem = json.loads(error.read())
            print(f'Importacao recusada (HTTP {error.code}).')
            show(problem)
            if problem.get('loteId'):
                print('Diagnosticos (primeira pagina):')
                show(request('GET', '/api/v1/lotes/' + problem['loteId'] + '/erros'))
            raise SystemExit(1) from None
        print('Lote validado:')
        show(lot)
        print('Observacoes (primeira pagina):')
        show(request('GET', '/api/v1/lotes/' + lot['id'] + '/observacoes'))
    except urllib.error.HTTPError as error:
        raise SystemExit(f'Pedido recusado (HTTP {error.code}). Confirma a conta e o seu perfil.') from None
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta o estado do backend e a lista de lotes antes de repetir o envio.') from None
    finally:
        if logged_in:
            try:
                request('POST', '/api/v1/auth/logout', {})
                print('Logout concluido.')
            except (urllib.error.URLError, TimeoutError):
                print('Nao foi possivel confirmar o logout.')


if __name__ == '__main__':
    main()
