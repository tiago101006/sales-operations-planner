"""Executa o navegador contra Spring Boot/MySQL temporários; não usa a base de desenvolvimento."""
import csv
import http.cookiejar
import io
import json
import os
import hashlib
import platform
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def main(performance_seed=None, validation_seed=None):
    performance = performance_seed is not None
    for executable in ['java', 'docker', 'node', 'npm']:
        if shutil.which(executable) is None:
            raise SystemExit(f'Falta {executable} no PATH.')
    if not (ROOT / 'frontend-tests/node_modules/@playwright/test').exists():
        raise SystemExit('Instala primeiro as dependencias de teste: cd frontend-tests && npm ci')
    jar = ROOT / 'backend/target/sales-operations-planner-0.0.1-SNAPSHOT.jar'
    if not jar.exists():
        raise SystemExit('Constroi primeiro o backend: cd backend && ./mvnw verify')
    db_name = 'sop_performance_test' if performance else 'sop_ui_test'
    password = 'Ui-test-' + secrets.token_hex(16)
    database = None
    server = None
    work = tempfile.TemporaryDirectory(prefix='sop-interface-')
    logs = ROOT / 'tmp' / ('performance-tests' if performance else 'dataset-tests' if validation_seed is not None else 'interface-tests')
    if performance:
        logs = logs / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + secrets.token_hex(3))
    logs.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, MYSQL_DATABASE=db_name, MYSQL_USER='sop_ui', MYSQL_PASSWORD=password,
                       MYSQL_ROOT_PASSWORD=password, TZ='UTC', SOP_UI_PERFORMANCE='1' if performance else '0')
    try:
        print('A criar um MySQL temporario exclusivo dos testes de interface...', flush=True)
        mysql_options = ['--innodb-buffer-pool-size=128M'] if performance else ['--innodb-buffer-pool-size=32M', '--performance-schema=OFF']
        database = subprocess.check_output(['docker', 'run', '-d', '--rm', '--label', 'sop-ui-test=1',
                    '-p', '127.0.0.1::3306', '-e', 'MYSQL_DATABASE', '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD',
                    '-e', 'MYSQL_ROOT_PASSWORD', 'mysql:8.4', *mysql_options], env=environment, text=True).strip()
        mapped = subprocess.check_output(['docker', 'port', database, '3306/tcp'], text=True).strip()
        db_port = mapped.rsplit(':', 1)[1]
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = subprocess.run(['docker', 'exec', database, 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT 1"'], capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('O MySQL temporario nao ficou pronto.')
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            ui_port = sock.getsockname()[1]
        assert ui_port != 8080 and db_port != '3307'
        base = f'http://127.0.0.1:{ui_port}'
        environment.update(MYSQL_HOST='127.0.0.1', MYSQL_PORT=db_port,
            SPRING_CONFIG_IMPORT='', SPRING_DATASOURCE_URL=f'jdbc:mysql://127.0.0.1:{db_port}/{db_name}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED',
            SPRING_DATASOURCE_USERNAME='sop_ui', SPRING_DATASOURCE_PASSWORD=password,
            IMPORT_STORAGE_PATH=str(Path(work.name) / 'imports'), SESSION_COOKIE_SECURE='false',
            SPRING_WEB_RESOURCES_STATIC_LOCATIONS=(ROOT / 'backend/src/main/resources/static').as_uri() + '/',
            SOP_ADMIN_EMAIL='admin@ui.test', SOP_ADMIN_PASSWORD=password,
            SPRING_PROFILES_ACTIVE='bootstrap-admin', SPRING_MAIN_WEB_APPLICATION_TYPE='none')
        with (logs / 'bootstrap.log').open('w') as log:
            subprocess.run(['java', '-Xmx384m', '-jar', str(jar)], env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
        environment.update(SPRING_PROFILES_ACTIVE='', SPRING_MAIN_WEB_APPLICATION_TYPE='servlet', SERVER_PORT=str(ui_port), SERVER_ADDRESS='127.0.0.1')
        with (logs / 'server.log').open('w') as log:
            server = subprocess.Popen(['java', '-Xmx384m', '-jar', str(jar)], env=environment, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError('O backend temporario terminou; consulta ' + str(logs / 'server.log'))
            try:
                with urllib.request.urlopen(base + '/actuator/health', timeout=2) as response:
                    if json.load(response)['status'] == 'UP':
                        break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('O backend temporario nao ficou pronto.')

        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def request(method, path, value=None, upload=None):
            headers = {}
            if method != 'GET':
                csrf = request('GET', '/api/v1/auth/csrf')
                headers[csrf['headerName']] = csrf['token']
            if upload:
                kind, content = upload
                boundary = secrets.token_hex(12)
                body = (f'--{boundary}\r\nContent-Disposition: form-data; name="tipo"\r\n\r\n{kind}\r\n--{boundary}\r\nContent-Disposition: form-data; name="ficheiro"; filename="fixture.csv"\r\nContent-Type: text/csv\r\n\r\n{content}\r\n--{boundary}--\r\n').encode()
                headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
            else:
                body = json.dumps(value).encode() if value is not None else None
                headers['Content-Type'] = 'application/json'
            with client.open(urllib.request.Request(base + path, data=body, headers=headers, method=method), timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None

        request('POST', '/api/v1/auth/login', {'email': 'admin@ui.test', 'password': password})
        if validation_seed is not None:
            validation_seed(request)
            print('Conjunto sintético verificado na API/MySQL isolados.', flush=True)
            return
        if performance:
            metadata = performance_seed(request)
            mysql = subprocess.check_output(['docker', 'exec', database, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names -e "SELECT VERSION(),@@innodb_buffer_pool_size,@@performance_schema"'], text=True).strip().split('\t')
            cpu = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')), platform.machine())
            java = subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.splitlines()[0]
            metadata['environment'] = {'os': platform.platform(), 'cpu': cpu, 'logicalCpus': os.cpu_count(),
                'memoryTotalKiB': int(Path('/proc/meminfo').read_text().splitlines()[0].split()[1]),
                'loadAverageBeforeBrowser': list(os.getloadavg()), 'java': java, 'jvmMaxHeapMiB': 384,
                'mysqlVersion': mysql[0], 'mysqlBufferPoolBytes': int(mysql[1]), 'mysqlPerformanceSchema': int(mysql[2]),
                'node': subprocess.check_output(['node', '--version'], text=True).strip(),
                'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'trackedChangesPresent': bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=ROOT)),
                'backendJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
            metadata['environment']['frontendSha256'] = hashlib.sha256(b''.join(p.read_bytes() for p in sorted((ROOT / 'backend/src/main/resources/static').rglob('*')) if p.is_file())).hexdigest()
            (logs / 'context.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
            request('POST', '/api/v1/auth/logout', {})
            environment.update(SOP_UI_ISOLATED='1', SOP_UI_PERFORMANCE='1', SOP_UI_BASE_URL=base,
                SOP_UI_PASSWORD=password, SOP_PERF_REVISION=metadata['revisionId'], SOP_PERF_RUN_DIR=str(logs))
            print('Cenário com 300 células pronto. A medir no navegador...', flush=True)
            result = subprocess.run(['npm', 'run', 'test:performance'], cwd=ROOT / 'frontend-tests', env=environment)
            if result.returncode:
                raise RuntimeError('A medição falhou ou ultrapassou a meta. Consulta ' + str(logs))
            print('Medição concluída; relatório em ' + str(logs / 'result.json'), flush=True)
            return
        for name, role in [('approver', 'APROVADOR'), ('consumer', 'CONSUMIDOR'), ('commercial', 'COMERCIAL')]:
            invite = request('POST', '/api/v1/utilizadores', {'nome': name.title(), 'email': name + '@ui.test', 'papeis': [role]})
            request('POST', '/api/v1/auth/ativacao', {'token': invite['token'], 'password': password, 'confirmacao': password})
        request('POST', '/api/v1/produtos', {'codigo': 'SKU_UI_ONE', 'nome': 'Produto de ensaio', 'unidade': 'un'})
        now = datetime.now(timezone.utc)
        serial = now.year * 12 + now.month - 2
        periods = [f'{s // 12:04d}-{s % 12 + 1:02d}' for s in range(serial - 23, serial + 1)]
        rows = []
        for i, period in enumerate(periods):
            year, month = map(int, period.split('-'))
            available = f'{year + month // 12:04d}-{month % 12 + 1:02d}-01T00:00:00Z'
            rows.append(['SKU_UI_ONE', period, 'VENDAS', str(20 + i % 7), 'un', available])
        output = io.StringIO(newline='')
        writer = csv.writer(output, delimiter=';', lineterminator='\n')
        writer.writerow(['produto', 'periodo', 'tipo', 'quantidade', 'unidade', 'disponivel_em'])
        writer.writerows(rows)
        history = output.getvalue()
        previous = periods[-1]
        available = f'{now.year:04d}-{now.month:02d}-01T00:00:00Z'
        request('POST', '/api/v1/lotes', upload=('REALIZADOS', f'produto;periodo;quantidade;unidade;disponivel_em\nSKU_UI_ONE;{previous};100;un;{available}\n'))
        request('POST', '/api/v1/lotes', upload=('OPERACIONAL', f'produto;periodo;unidade;unidades_solicitadas;unidades_satisfeitas_no_prazo;dias_observados_com_procura;dias_com_procura_sem_stock;stock_fecho;limite_stock;unidades_perdidas;prazo_medio_dias;disponivel_em\nSKU_UI_ONE;{previous};un;100;90;20;2;30;10;1;2;{available}\n'))
        history_file = Path(work.name) / 'historico.csv'
        history_file.write_text(history)
        duplicate_file = Path(work.name) / 'duplicado.csv'
        duplicate_file.write_text(history + ';'.join(rows[-1]) + '\n')
        request('POST', '/api/v1/auth/logout', {})
        print('Backend temporario pronto. A verificar o percurso no navegador...', flush=True)
        environment.update(SOP_UI_ISOLATED='1', SOP_UI_BASE_URL=base, SOP_UI_PASSWORD=password,
                           SOP_UI_HISTORY=str(history_file), SOP_UI_DUPLICATE=str(duplicate_file))
        result = subprocess.run(['npm', 'run', 'test:e2e'], cwd=ROOT / 'frontend-tests', env=environment)
        if result.returncode != 0:
            raise RuntimeError('Falhou a verificacao no navegador; consulta tmp/ui-test-results/.')
        print('Interface verificada num navegador real. Os dados de desenvolvimento foram preservados.', flush=True)
    finally:
        if server and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        if database:
            subprocess.run(['docker', 'rm', '-f', database], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        work.cleanup()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
