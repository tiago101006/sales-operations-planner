"""Experimenta login, sessao e logout; passwords e cookies apenas em memoria."""
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

    def request(path, data=None, csrf=None):
        headers = {'Content-Type': 'application/json'}
        if csrf:
            headers[csrf['headerName']] = csrf['token']
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers)
        with client.open(req, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else None

    try:
        csrf = request('/api/v1/auth/csrf')
        request('/api/v1/auth/login', {'email': email, 'password': password}, csrf)
        password = None
        print('Login concluido. Utilizador autenticado:')
        print(json.dumps(request('/api/v1/auth/me'), ensure_ascii=False, indent=2))
        csrf = request('/api/v1/auth/csrf')
        request('/api/v1/auth/logout', {}, csrf)
        print('Logout concluido; sessao invalidada.')
    except urllib.error.HTTPError as error:
        raise SystemExit(f'Pedido recusado (HTTP {error.code}). Confirma a conta e as credenciais.') from None
    except urllib.error.URLError:
        raise SystemExit('Nao foi possivel contactar o backend. Confirma docker compose ps.') from None


if __name__ == '__main__':
    main()
