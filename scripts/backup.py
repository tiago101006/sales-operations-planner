"""Backup MySQL + CSV originais; verifica/restaura sempre num ambiente Docker novo."""
import argparse
import datetime as dt
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
DUMP = ('MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" '
        '--no-tablespaces --set-gtid-purged=OFF --single-transaction '
        '--skip-comments --skip-extended-insert --order-by-primary --hex-blob ')
MYSQL = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=tcp -h127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw --skip-column-names'


def run(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        # O stderr do MySQL pode incluir dados SQL: não o expor no terminal.
        raise RuntimeError(f'Falhou {command[0]} (código {result.returncode}). Confirma os serviços Docker e o espaço em disco.')
    return result.stdout


def sql(container, query):
    return run(['docker', 'exec', '-i', container, 'sh', '-c', MYSQL], input=query.encode()).decode()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_]+', value):
        raise ValueError('Identificador SQL inválido.')
    return value


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def capture(command, destination):
    with Path(destination).open('xb') as output, tempfile.TemporaryFile() as errors:
        process = subprocess.run(command, cwd=ROOT, stdout=output, stderr=errors)
    if process.returncode:
        raise RuntimeError('Falhou a cópia dos dados. O backup não foi concluído.')


def compressed_capture(command, destination):
    raw = Path(str(destination) + '.partial')
    try:
        capture(command, raw)
        with raw.open('rb') as source, gzip.open(destination, 'wb') as output:
            shutil.copyfileobj(source, output)
    finally:
        raw.unlink(missing_ok=True)


def data_hash(container):
    with tempfile.TemporaryFile() as output:
        process = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                                 DUMP + '--no-create-info --skip-triggers "$MYSQL_DATABASE"'],
                                 cwd=ROOT, stdout=output, stderr=subprocess.DEVNULL)
        if process.returncode:
            raise RuntimeError('Não foi possível verificar os dados SQL.')
        output.seek(0)
        return hashlib.file_digest(output, 'sha256').hexdigest()


def database_inventory(container):
    tables = sql(container, "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").splitlines()
    if not tables:
        raise ValueError('A base não contém tabelas.')
    schema, counts = {}, {}
    for table in tables:
        identifier(table)
        schema[table] = sql(container, f'SHOW CREATE TABLE `{table}`;').strip()
        counts[table] = int(sql(container, f'SELECT COUNT(*) FROM `{table}`;').strip())
    return {'schema': schema, 'rows': counts, 'dataSha256': data_hash(container)}


def archive_inventory(path):
    files = {}
    with tarfile.open(path, 'r:*') as archive:
        seen = set()
        for member in archive:
            name = PurePosixPath(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isdir() or member.isfile()):
                raise ValueError('O arquivo contém caminhos ou tipos de ficheiro não permitidos.')
            key = str(name)
            if key in seen:
                raise ValueError('O arquivo contém caminhos duplicados.')
            seen.add(key)
            if member.isfile():
                with archive.extractfile(member) as source:
                    files[key] = {'sha256': hashlib.file_digest(source, 'sha256').hexdigest(), 'size': member.size}
    return files


def volume_tar(image, volume, destination):
    compressed_capture(['docker', 'run', '--rm', '--network', 'none', '--read-only',
                        '--user', '10001:10001', '--mount', f'type=volume,src={volume},dst=/imports,readonly',
                        '--entrypoint', 'tar', image, '-C', '/imports', '-cf', '-', '.'], destination)


def compose_container(service):
    values = run(['docker', 'compose', 'ps', '-a', '-q', service]).decode().splitlines()
    if len(values) != 1:
        raise RuntimeError(f'É necessário existir exatamente um contentor {service}.')
    return values[0]


def image_of(container):
    return run(['docker', 'inspect', '--format', '{{.Image}}', container]).decode().strip()


def validate_references(container, files):
    references = sql(container, 'SELECT localizacao_ficheiro,sha256 FROM lote ORDER BY id').splitlines()
    for row in references:
        path, expected = row.split('\t')
        original = PurePosixPath(path)
        if '..' in original.parts:
            raise ValueError('Caminho de original inválido na base.')
        try:
            relative = str(original.relative_to('/app/imports'))
        except ValueError:
            raise ValueError('Existe um lote cujo original não está em /app/imports. Rever antes do backup.') from None
        if relative not in files or files[relative]['sha256'] != expected:
            raise ValueError('Falta um CSV original, ou o seu SHA-256 difere do lote. O backup não é válido.')
    return len(references)


def create_backup(destination):
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    (ROOT / 'tmp').mkdir(exist_ok=True)
    with (ROOT / 'tmp/.backup.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        backend, database = compose_container('backend'), compose_container('db')
        image, db_image = image_of(backend), image_of(database)
        mounts = json.loads(run(['docker', 'inspect', '--format', '{{json .Mounts}}', backend]))
        imports = [m['Name'] for m in mounts if m['Destination'] == '/app/imports' and m['Type'] == 'volume']
        if len(imports) != 1:
            raise ValueError('Não foi encontrado o volume dos CSV originais.')
        db_name = identifier(sql(database, 'SELECT DATABASE()').strip())
        running = run(['docker', 'inspect', '--format', '{{.State.Running}}', backend]).strip() == b'true'
        name = 'nexoplan-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:6]
        final = destination / name
        with tempfile.TemporaryDirectory(prefix='.partial-', dir=destination) as work:
            folder = Path(work)
            try:
                if running:
                    print('A suspender o backend durante a cópia consistente...', flush=True)
                    run(['docker', 'compose', 'stop', 'backend'])
                inventory = database_inventory(database)
                compressed_capture(['docker', 'exec', database, 'sh', '-c', DUMP + '"$MYSQL_DATABASE"'], folder / 'database.sql.gz')
                volume_tar(image, imports[0], folder / 'imports.tar.gz')
                files = archive_inventory(folder / 'imports.tar.gz')
                references = validate_references(database, files)
                if data_hash(database) != inventory['dataSha256']:
                    raise ValueError('A base mudou durante a cópia. Interrompe os outros escritores e repete.')
                manifest = {'format': 'nexoplan-backup-v1', 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                            'database': db_name, 'backendImage': image, 'mysqlImage': db_image,
                            'inventory': inventory, 'files': files, 'referencedOriginals': references,
                            'payloads': {p: digest(folder / p) for p in ['database.sql.gz', 'imports.tar.gz']}}
                (folder / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
                folder.rename(final)
            finally:
                if running:
                    run(['docker', 'compose', 'start', '--wait', 'backend'])
        print(f'Backup concluído: {final}')
        print(f'{len(inventory["rows"])} tabelas · {len(files)} originais · {references} lotes com SHA-256 confirmado.')
        return final


def validate_backup(folder):
    manifest = json.loads((folder / 'manifest.json').read_text())
    if manifest.get('format') != 'nexoplan-backup-v1':
        raise ValueError('Formato de backup desconhecido.')
    identifier(manifest['database'])
    for key in ['backendImage', 'mysqlImage']:
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', manifest[key]):
            raise ValueError('A imagem do backup não é um identificador Docker válido.')
        run(['docker', 'image', 'inspect', '--format', '{{.Id}}', manifest[key]])
    for name in ['database.sql.gz', 'imports.tar.gz']:
        if digest(folder / name) != manifest['payloads'][name]:
            raise ValueError(f'Integridade inválida: {name}.')
    if archive_inventory(folder / 'imports.tar.gz') != manifest['files']:
        raise ValueError('O inventário dos originais não coincide.')
    return manifest


def wait_database(container):
    for _ in range(90):
        try:
            sql(container, 'SELECT 1')
            return
        except RuntimeError:
            time.sleep(1)
    raise RuntimeError('O MySQL isolado não ficou pronto.')


def verify_backup(folder, keep=False):
    manifest = validate_backup(folder)
    tag = 'nexoplan-restore-' + uuid.uuid4().hex[:12]
    network, volume, database, backend = tag + '-net', tag + '-csv', tag + '-db', tag + '-web'
    created, retain = [], False
    environment = dict(os.environ, MYSQL_DATABASE=manifest['database'], MYSQL_USER='sop_restore',
                       MYSQL_PASSWORD=secrets.token_urlsafe(32), MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(32))
    try:
        run(['docker', 'network', 'create', '--label', 'nexoplan.restore=1', network]);created.append(('network', network))
        run(['docker', 'volume', 'create', '--label', 'nexoplan.restore=1', volume]);created.append(('volume', volume))
        run(['docker', 'run', '-d', '--name', database, '--label', 'nexoplan.restore=1', '--network', network, '--network-alias', 'db',
             '-e', 'MYSQL_DATABASE', '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD', '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'TZ=UTC',
             manifest['mysqlImage'], '--innodb-buffer-pool-size=32M', '--performance-schema=OFF',
             '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci'], env=environment)
        created.append(('container', database))
        print('A restaurar numa base e num volume novos...', flush=True)
        wait_database(database)
        with gzip.open(folder / 'database.sql.gz', 'rb') as source, tempfile.TemporaryFile() as errors:
            with subprocess.Popen(['docker', 'exec', '-i', database, 'sh', '-c', MYSQL], cwd=ROOT,
                                  stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=errors) as process:
                shutil.copyfileobj(source, process.stdin);process.stdin.close()
                if process.wait():
                    raise RuntimeError('O restauro SQL falhou no ambiente isolado.')
        with gzip.open(folder / 'imports.tar.gz', 'rb') as source, tempfile.TemporaryFile() as errors:
            with subprocess.Popen(['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only', '--user', '0:0',
                                   '--mount', f'type=volume,src={volume},dst=/imports', '--entrypoint', 'tar',
                                   manifest['backendImage'], '-C', '/imports', '-xpf', '-'], cwd=ROOT,
                                  stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=errors) as process:
                shutil.copyfileobj(source, process.stdin);process.stdin.close()
                if process.wait():
                    raise RuntimeError('Falhou o restauro dos originais no volume novo.')
        if database_inventory(database) != manifest['inventory']:
            raise ValueError('Esquema, contagens ou dados SQL restaurados diferem do backup.')
        with tempfile.TemporaryDirectory(prefix='nexoplan-restore-check-') as work:
            archive = Path(work) / 'originals.tar.gz'
            volume_tar(manifest['backendImage'], volume, archive)
            if archive_inventory(archive) != manifest['files']:
                raise ValueError('Os CSV restaurados diferem do backup.')
        validate_references(database, manifest['files'])
        run(['docker', 'run', '-d', '--name', backend, '--label', 'nexoplan.restore=1', '--network', network,
             '-p', '127.0.0.1::8080', '--mount', f'type=volume,src={volume},dst=/app/imports',
             '-e', 'MYSQL_DATABASE', '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD', '-e', 'MYSQL_HOST=db',
             '-e', 'MYSQL_PORT=3306', '-e', 'SPRING_CONFIG_IMPORT=', '-e', 'IMPORT_STORAGE_PATH=/app/imports',
             '-e', 'SESSION_COOKIE_SECURE=false', '-e', 'JAVA_TOOL_OPTIONS=-Xmx384m', manifest['backendImage']], env=environment)
        created.append(('container', backend))
        port = run(['docker', 'port', backend, '8080/tcp']).decode().strip().rsplit(':', 1)[1]
        url = 'http://127.0.0.1:' + port
        for _ in range(90):
            try:
                with urllib.request.urlopen(url + '/actuator/health', timeout=2) as response:
                    if json.load(response)['status'] == 'UP':
                        break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('O backend restaurado não ficou saudável.')
        try:
            urllib.request.urlopen(url + '/api/v1/planos', timeout=5)
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise RuntimeError('A API restaurada devolveu um estado inesperado.') from None
        else:
            raise RuntimeError('A API restaurada não exige sessão.')
        if data_hash(database) != manifest['inventory']['dataSha256']:
            raise ValueError('Os dados mudaram durante o arranque do backend restaurado.')
        reports = ROOT / 'tmp/verificacoes-backup'
        reports.mkdir(parents=True, exist_ok=True, mode=0o700)
        report = reports / (tag + '.json')
        report.write_text(json.dumps({'backup': str(folder), 'verifiedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                          'schemaAndDataMatch': True, 'originalsMatch': True, 'backendHealthy': True,
                          'privateApiStatus': 401, 'rows': manifest['inventory']['rows'], 'files': len(manifest['files']),
                          'retained': keep, 'url': url if keep else None, 'resources': created if keep else []}, indent=2) + '\n')
        retain = keep
        print(f'Restauro verificado: dados, tabelas, originais, Flyway e backend. Relatório: {report}')
        if keep:
            print(f'Cópia restaurada disponível em {url} (usa as contas existentes no backup).')
            print(f'Para remover apenas esta cópia: docker rm -fv {backend} {database}')
            print(f'docker volume rm {volume}')
            print(f'docker network rm {network}')
    finally:
        if not retain:
            for kind, name in reversed(created):
                command = ['docker', 'rm', '-fv', name] if kind == 'container' else ['docker', kind, 'rm', name]
                subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('criar', help='Suspender brevemente o backend, copiar e voltar a iniciar')
    create.add_argument('--destino', type=Path, default=ROOT / 'tmp/backups')
    verify = commands.add_parser('verificar', help='Restaurar e comparar num ambiente Docker novo')
    verify.add_argument('backup', type=Path, nargs='?')
    verify.add_argument('--ultimo', action='store_true', help='Usar o backup mais recente em tmp/backups')
    verify.add_argument('--manter', action='store_true', help='Manter a cópia restaurada numa porta nova após verificar')
    args = parser.parse_args()
    try:
        if args.command == 'criar':
            create_backup(args.destino.expanduser().resolve())
        else:
            if args.ultimo:
                if args.backup:
                    parser.error('Escolhe uma pasta ou --ultimo, não ambos.')
                candidates = [p for p in (ROOT / 'tmp/backups').glob('nexoplan-*') if (p / 'manifest.json').is_file()]
                if not candidates:
                    raise ValueError('Não existe um backup concluído em tmp/backups.')
                args.backup = max(candidates, key=lambda p: p.name)
            if not args.backup:
                parser.error('Indica a pasta do backup ou --ultimo.')
            verify_backup(args.backup.expanduser().resolve(), args.manter)
    except (RuntimeError, ValueError, OSError, KeyError, json.JSONDecodeError, tarfile.TarError) as error:
        raise SystemExit(str(error)) from None


if __name__ == '__main__':
    main()
