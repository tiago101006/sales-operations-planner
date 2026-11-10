"""Consulta o catalogo autenticado; permite criar um produto por escolha explicita."""
import getpass
import http.cookiejar
import json
import urllib.error
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
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers, method=method)
        with client.open(req, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else None

    try:
        user = request('POST', '/api/v1/auth/login', {'email': email, 'password': password})
        password = None
        logged_in = True
        print('Autenticado como:', user['nome'], '/', ', '.join(user['papeis']))
        print('Catalogo atual:')
        print(json.dumps(request('GET', '/api/v1/produtos?page=0&size=20'), indent=2, ensure_ascii=False))
        if not {'ADMIN', 'PLANEADOR'}.intersection(user['papeis']):
            print('Este perfil tem acesso de consulta ao catalogo.')
            return
        if input('Queres criar um produto? [s/N]: ').strip().lower() == 's':
            product = request('POST', '/api/v1/produtos', {
                'codigo': input('Codigo unico (ex.: SKU001): ').strip(),
                'nome': input('Nome: ').strip(),
                'unidade': input('Unidade (ex.: un, kg, L): ').strip(),
            })
            print('Produto criado. Consulta por ID:')
            print(json.dumps(request('GET', '/api/v1/produtos/' + product['id']), indent=2, ensure_ascii=False))
    except urllib.error.HTTPError as error:
        try:
            problem = json.loads(error.read())
        except (ValueError, UnicodeError):
            problem = {}
        print(f'Pedido recusado (HTTP {error.code}).', problem.get('detail', problem.get('title', '')))
        raise SystemExit(1) from None
    except urllib.error.URLError:
        raise SystemExit('Nao foi possivel contactar o backend. Confirma docker compose ps.') from None
    finally:
        if logged_in:
            try:
                request('POST', '/api/v1/auth/logout', {})
                print('Logout concluido.')
            except (urllib.error.URLError, TimeoutError):
                print('Nao foi possivel confirmar o logout; a sessao expira por inatividade.')


if __name__ == '__main__':
    main()
