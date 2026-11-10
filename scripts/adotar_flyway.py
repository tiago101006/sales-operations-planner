"""Compara V1 com o MySQL do Compose; --baseline adota a base apos verificacao.

Requer Docker, JDK 17 e Python 3. Nao altera tabelas de negocio nem imprime segredos.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
V1 = ROOT / 'backend/src/main/resources/db/migration/V1__schema_inicial.sql'


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, **kwargs).stdout


def target(sql):
    return run(['docker', 'compose', 'exec', '-T', 'db', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'],
               input=sql.encode()).decode()


def schema(query):
    extra = query('SELECT '
                  '(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()) + '
                  '(SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()) + '
                  '(SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE());').strip()
    if extra != '0':
        raise SystemExit('A base tem triggers, rotinas ou eventos fora de V1. Rever manualmente.')
    names = query('SHOW TABLES;').splitlines()
    result = {}
    for name in names:
        if not re.fullmatch(r'[A-Za-z0-9_]+', name):
            raise SystemExit('Nome de tabela inesperado; rever manualmente.')
        # O contador AUTO_INCREMENT e dado de execucao, nao estrutura da tabela.
        result[name] = re.sub(r' AUTO_INCREMENT=\d+', '', query(f'SHOW CREATE TABLE `{name}`;'))
    return result


def data_dump():
    return run(['docker', 'compose', 'exec', '-T', 'db', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --no-tablespaces '
                '--skip-comments --single-transaction --no-create-info --skip-extended-insert '
                '--ignore-table="$MYSQL_DATABASE.flyway_schema_history" "$MYSQL_DATABASE"'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', action='store_true', help='Registar explicitamente baseline V1 apos comparar e criar backup')
    args = parser.parse_args()
    current = schema(target)
    if 'flyway_schema_history' in current:
        print('A base ja tem historico Flyway. Nao foi executado outro baseline.')
        print(target('SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank;'))
        return

    config = json.loads(run(['docker', 'compose', 'config', '--format', 'json']).decode())
    service = config['services']['db']
    env = service['environment']
    database = env['MYSQL_DATABASE']
    if not re.fullmatch(r'[A-Za-z0-9_]+', database):
        raise SystemExit('Nome de base inesperado; rever manualmente.')
    name = 'sop-flyway-reference-' + uuid.uuid4().hex[:10]
    created = False
    try:
        run(['docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql',
             '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '-e', f'MYSQL_DATABASE={database}',
             service['image'], '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci'])
        created = True
        for _ in range(60):
            # Executar uma consulta real: mysqladmin ping tambem aceita o servidor temporario do entrypoint.
            ready = subprocess.run(['docker', 'exec', name, 'mysql', '--protocol=tcp', '-h127.0.0.1', '-uroot', database, '-e', 'SELECT 1'],
                                   capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise SystemExit('O MySQL de referencia nao arrancou.')

        def reference(sql):
            return run(['docker', 'exec', '-i', name, 'mysql', '-uroot', database,
                        '--batch', '--skip-column-names'], input=sql.encode()).decode()

        reference(V1.read_text())
        expected = schema(reference)
        if current != expected:
            differences = sorted(k for k in current.keys() | expected.keys() if current.get(k) != expected.get(k))
            raise SystemExit('Baseline recusado: estrutura diferente de V1 em ' + ', '.join(differences))
        print(f'OK: {len(expected)} tabelas iguais a V1 (colunas, indices, FKs e CHECKs).', flush=True)
    finally:
        if created:
            run(['docker', 'rm', '-f', name])

    if not args.baseline:
        print('Apenas verificacao. Para adotar: python3 scripts/adotar_flyway.py --baseline')
        return

    # Parar comandos da aplicacao antes da copia e do registo da versao.
    run(['docker', 'compose', 'stop', 'backend'])
    if schema(target) != expected:
        raise SystemExit('A estrutura mudou durante a verificacao. Baseline recusado.')
    before = data_dump()
    backup_dir = ROOT / 'tmp' / 'backups'
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / ('antes-flyway-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.sql')
    dump = run(['docker', 'compose', 'exec', '-T', 'db', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --no-tablespaces '
                '--single-transaction --set-gtid-purged=OFF "$MYSQL_DATABASE"'])
    with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as handle:
        handle.write(dump)
    print(f'Backup local: {backup}', flush=True)
    port = next(p['published'] for p in service['ports'] if p['target'] == 3306)
    process_env = os.environ.copy()
    process_env.update(SOP_FLYWAY_URL=f'jdbc:mysql://127.0.0.1:{port}/{database}?allowPublicKeyRetrieval=true&sslMode=DISABLED',
                       SOP_FLYWAY_USER=env['MYSQL_USER'], SOP_FLYWAY_PASSWORD=env['MYSQL_PASSWORD'])
    # Os segredos sao transmitidos pelo ambiente, sem argumentos de linha de comandos.
    subprocess.run(['./mvnw', '-B', 'flyway:baseline'], cwd=ROOT / 'backend', env=process_env, check=True)
    if hashlib.sha256(before).digest() != hashlib.sha256(data_dump()).digest():
        raise SystemExit('Dados diferentes depois do baseline; rever o backup antes de continuar.')
    print('OK: baseline V1 registado; dados de negocio preservados. Arrancar: docker compose up -d --build --wait')


if __name__ == '__main__':
    main()
