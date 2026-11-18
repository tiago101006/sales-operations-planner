"""Prepara uma empresa fictícia isolada e substitui uma instalação local após backup."""
import argparse
import datetime as dt
import gzip
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

import backup

ROOT = Path(__file__).resolve().parents[1]


def inspect(container):
    return json.loads(backup.run(['docker', 'inspect', container]))[0]


def wait_web(url):
    for _ in range(90):
        try:
            with urllib.request.urlopen(url + '/actuator/health', timeout=2) as response:
                if json.load(response)['status'] == 'UP':
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise RuntimeError('O backend não ficou saudável.')


def originals_volume(info):
    mounts = [m['Name'] for m in info['Mounts']
              if m['Destination'] == '/app/imports' and m['Type'] == 'volume']
    if len(mounts) != 1:
        raise ValueError('É necessário um volume Docker exclusivo de originais em /app/imports.')
    return mounts[0]


def local_target(web, database):
    app, db = inspect(web), inspect(database)
    ports = app['NetworkSettings']['Ports'].get('8080/tcp') or []
    if len(ports) != 1 or ports[0]['HostIp'] != '127.0.0.1':
        raise ValueError('Este comando só substitui uma instalação HTTP ligada a 127.0.0.1.')
    env = dict(item.split('=', 1) for item in app['Config']['Env'])
    db_env = dict(item.split('=', 1) for item in db['Config']['Env'])
    if env.get('MYSQL_DATABASE') != db_env.get('MYSQL_DATABASE'):
        raise ValueError('A base indicada não coincide com a configuração do backend.')
    common = app['NetworkSettings']['Networks'].keys() & db['NetworkSettings']['Networks'].keys()
    if len(common) != 1:
        raise ValueError('O backend e a base precisam de partilhar uma rede Docker exclusiva.')
    db_net = db['NetworkSettings']['Networks'][next(iter(common))]
    if env.get('MYSQL_HOST') not in [database, *(db_net.get('Aliases') or [])]:
        raise ValueError('MYSQL_HOST não identifica a base indicada.')
    url = 'http://127.0.0.1:' + ports[0]['HostPort']
    with urllib.request.urlopen(url + '/assets/brand.svg', timeout=5) as response:
        if response.read() != (ROOT / 'backend/src/main/resources/static/assets/brand.svg').read_bytes():
            raise ValueError('O backend indicado não corresponde a esta instalação NexoPlan.')
    return app, db, url, originals_volume(app)


def snapshot(web, database, volume, image, folder):
    """O chamador mantém o backend parado até terminar a troca ou recuperação."""
    folder.mkdir(mode=0o700)
    inventory = backup.database_inventory(database)
    backup.compressed_capture(['docker', 'exec', database, 'sh', '-c',
                               backup.DUMP + '"$MYSQL_DATABASE"'], folder / 'database.sql.gz')
    backup.volume_tar(image, volume, folder / 'imports.tar.gz')
    files = backup.archive_inventory(folder / 'imports.tar.gz')
    refs = backup.validate_references(database, files)
    if backup.data_hash(database) != inventory['dataSha256']:
        raise RuntimeError('A base mudou durante o backup. A substituição foi cancelada.')
    manifest = {'format': 'nexoplan-backup-v1', 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'database': backup.sql(database, 'SELECT DATABASE()').strip(),
                'backendImage': image, 'mysqlImage': inspect(database)['Image'],
                'inventory': inventory, 'files': files, 'referencedOriginals': refs,
                'payloads': {name: backup.digest(folder / name)
                             for name in ['database.sql.gz', 'imports.tar.gz']}}
    (folder / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    backup.validate_backup(folder)
    return manifest


def replace_originals(image, volume, archive):
    # /imports é o único ponto de montagem gravável deste contentor sem rede.
    with gzip.open(archive, 'rb') as stream:
        backup.run(['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only',
                    '--user', '0:0', '--mount', f'type=volume,src={volume},dst=/imports',
                    '--entrypoint', 'sh', image, '-c',
                    'find /imports -mindepth 1 -delete && tar -xpf - -C /imports'], input=stream.read())


def business_dump(database):
    tables = backup.sql(database, "SELECT TABLE_NAME FROM information_schema.tables "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' "
                        "ORDER BY TABLE_NAME").splitlines()
    for table in tables:
        backup.identifier(table)
    dump = backup.run(['docker', 'exec', database, 'sh', '-c',
                       backup.DUMP + '--no-create-info --skip-triggers --skip-add-locks '
                       '--skip-disable-keys "$MYSQL_DATABASE" ' + ' '.join(tables)])
    return tables, dump


def replace_business_data(database, tables, dump):
    # DELETE e INSERT partilham a transação. O histórico de migrações é conservado.
    prefix = 'SET FOREIGN_KEY_CHECKS=0; START TRANSACTION;\n'
    prefix += ''.join('DELETE FROM `' + backup.identifier(t) + '`;\n' for t in reversed(tables))
    backup.run(['docker', 'exec', '-i', database, 'sh', '-c', backup.MYSQL],
               input=prefix.encode() + dump + b'\nCOMMIT; SET FOREIGN_KEY_CHECKS=1;\n')
    # Os identificadores da simulação começam em 1, independentemente da base anterior.
    for table in tables:
        column = backup.sql(database, "SELECT COLUMN_NAME FROM information_schema.columns "
                            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table +
                            "' AND EXTRA LIKE '%auto_increment%'").strip()
        if column:
            backup.identifier(column)
            next_id = int(backup.sql(database, f'SELECT COALESCE(MAX(`{column}`),0)+1 FROM `{table}`').strip())
            backup.sql(database, f'ALTER TABLE `{table}` AUTO_INCREMENT={next_id}')


def restore_snapshot(database, volume, image, folder):
    backup.validate_backup(folder)
    with gzip.open(folder / 'database.sql.gz', 'rb') as stream:
        backup.run(['docker', 'exec', '-i', database, 'sh', '-c', backup.MYSQL], input=stream.read())
    replace_originals(image, volume, folder / 'imports.tar.gz')
    original = json.loads((folder / 'manifest.json').read_text())
    if backup.database_inventory(database) != original['inventory']:
        raise RuntimeError('A recuperação não coincide com o backup. Conserva a cópia e o backend parado.')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web-container', required=True)
    parser.add_argument('--database-container', required=True)
    parser.add_argument('--substituir', action='store_true', help='Substituir os dados após validar a simulação isolada')
    args = parser.parse_args()
    app, db, target_url, target_volume = local_target(args.web_container, args.database_container)
    image = app['Image']
    tag = 'nexoplan-empresa-' + secrets.token_hex(6)
    folder = ROOT / 'tmp/empresa' / tag
    folder.mkdir(parents=True, mode=0o700)
    created = []
    network, database, web, volume = [tag + suffix for suffix in ['-net', '-db', '-web', '-csv']]
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, MYSQL_DATABASE='sop_empresa', MYSQL_USER='empresa',
               MYSQL_PASSWORD=password, MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(32),
               MYSQL_HOST='db', MYSQL_PORT='3306', SPRING_CONFIG_IMPORT='',
               IMPORT_STORAGE_PATH='/app/imports', SESSION_COOKIE_SECURE='false', TZ='UTC')
    common = ['--init', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
              '--pids-limit', '256', '--memory', '1g', '--cpus', '2',
              '--tmpfs', '/tmp:rw,noexec,nosuid,size=134217728,mode=1777',
              '--network', network, '--mount', f'type=volume,src={volume},dst=/app/imports']
    app_env = [part for key in ['MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_HOST', 'MYSQL_PORT',
                               'SPRING_CONFIG_IMPORT', 'IMPORT_STORAGE_PATH', 'SESSION_COOKIE_SECURE', 'TZ']
               for part in ['-e', key]]
    stopped = False
    replaced = False
    snapshot_folder = folder / 'antes-da-substituicao'
    try:
        from povoar_empresa import ACCOUNTS, populate_company
        from verificar_empresa import verify_company
        accounts = [dict(person, password='NexoPlan!' + person['key'].title() + '26') for person in ACCOUNTS]
        (folder / 'contas.json').write_text(json.dumps(accounts, ensure_ascii=False, indent=2) + '\n')
        access = ['# Acessos locais da simulação NexoPlan', '', 'Dados e pessoas fictícios. Não publicar este ficheiro.', '',
                  '| Nome | Email | Perfis | Palavra-passe |', '|---|---|---|---|']
        access += ['| ' + ' | '.join([p['nome'], p['email'], ', '.join(p['papeis']), p['password']]) + ' |' for p in accounts]
        (folder / 'acessos.md').write_text('\n'.join(access) + '\n')
        backup.run(['docker', 'network', 'create', '--label', 'nexoplan.empresa=1', network]);created.append(('network', network))
        backup.run(['docker', 'volume', 'create', '--label', 'nexoplan.empresa=1', volume]);created.append(('volume', volume))
        print('A preparar MySQL e backend isolados para a empresa fictícia...', flush=True)
        backup.run(['docker', 'run', '-d', '--name', database, '--label', 'nexoplan.empresa=1',
                    '--network', network, '--network-alias', 'db',
                    '-e', 'MYSQL_DATABASE', '-e', 'MYSQL_USER', '-e', 'MYSQL_PASSWORD', '-e', 'MYSQL_ROOT_PASSWORD',
                    db['Image'], '--innodb-buffer-pool-size=64M', '--performance-schema=OFF',
                    '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci'], env=env)
        created.append(('container', database))
        backup.wait_database(database)
        bootstrap = accounts[0]
        bootstrap_env = dict(env, SOP_ADMIN_EMAIL=bootstrap['email'], SOP_ADMIN_PASSWORD=bootstrap['password'])
        output = backup.run(['docker', 'run', '--rm', *common, *app_env,
                             '-e', 'SOP_ADMIN_EMAIL', '-e', 'SOP_ADMIN_PASSWORD',
                             '-e', 'SPRING_PROFILES_ACTIVE=bootstrap-admin', '-e', 'SPRING_MAIN_WEB_APPLICATION_TYPE=none',
                             image], env=bootstrap_env)
        (folder / 'bootstrap.log').write_bytes(output)
        backup.run(['docker', 'run', '-d', '--name', web, '--label', 'nexoplan.empresa=1', *common,
                    *app_env, '-p', '127.0.0.1::8080', image], env=env)
        created.append(('container', web))
        port = backup.run(['docker', 'port', web, '8080/tcp']).decode().strip().rsplit(':', 1)[1]
        stage_url = 'http://127.0.0.1:' + port
        wait_web(stage_url)
        fixture_sql = lambda query: backup.sql(database, query)
        report = populate_company(stage_url, accounts, ROOT / 'data/empresa', fixture_sql)
        report['verificacao'] = verify_company(fixture_sql, stage_url, accounts, report)
        (folder / 'simulacao.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        backup.run(['docker', 'stop', web])
        tables, dump = business_dump(database)
        stage_inventory = backup.database_inventory(database)
        backup.volume_tar(image, volume, folder / 'empresa-imports.tar.gz')
        stage_files = backup.archive_inventory(folder / 'empresa-imports.tar.gz')
        backup.validate_references(database, stage_files)
        if not args.substituir:
            print('Simulação validada. Sem --substituir, a instalação existente foi conservada.')
            return
        print('A guardar backup completo e a substituir a instalação local...', flush=True)
        backup.run(['docker', 'stop', args.web_container]);stopped = True
        snapshot(args.web_container, args.database_container, target_volume, image, snapshot_folder)
        # O esquema precisa de ser o mesmo; diferenças de AUTO_INCREMENT são esperadas.
        actual = backup.database_inventory(args.database_container)
        import re
        normalize = lambda value: re.sub(r'AUTO_INCREMENT=\d+ ?', '', value)
        if {k: normalize(v) for k, v in actual['schema'].items()} != {k: normalize(v) for k, v in stage_inventory['schema'].items()}:
            raise ValueError('Esquemas diferentes. Atualiza o backend/Flyway antes de substituir os dados.')
        replaced = True
        replace_originals(image, target_volume, folder / 'empresa-imports.tar.gz')
        replace_business_data(args.database_container, tables, dump)
        for table in tables:
            count = int(backup.sql(args.database_container, f'SELECT COUNT(*) FROM `{table}`').strip())
            if count != stage_inventory['rows'][table]:
                raise ValueError('Contagem divergente após a substituição: ' + table)
        # Hash inclui também Flyway, cuja história tem timestamps diferentes: comparar só negócio.
        if business_dump(args.database_container)[1] != dump:
            raise ValueError('Os dados de negócio não coincidem com a simulação validada.')
        backup.validate_references(args.database_container, stage_files)
        backup.run(['docker', 'start', args.web_container]);stopped = False
        wait_web(target_url)
        report['verificacaoFinal'] = verify_company(lambda q: backup.sql(args.database_container, q), target_url, accounts, report)
        report.update(url=target_url, backup=str(snapshot_folder), acessos=str(folder / 'acessos.md'))
        (folder / 'resultado.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('Empresa fictícia instalada e verificada: ' + target_url)
        print('Backup: ' + str(snapshot_folder))
        print('Acessos privados: ' + str(folder / 'acessos.md'))
        print('Relatório: ' + str(folder / 'resultado.json'))
    except BaseException:
        if replaced:
            print('A recuperar o estado anterior a partir do backup...', flush=True)
            backup.run(['docker', 'stop', args.web_container]);stopped = True
            try:
                restore_snapshot(args.database_container, target_volume, image, snapshot_folder)
            except BaseException:
                # Não expor uma recuperação incompleta através do backend.
                stopped = False
                raise
        raise
    finally:
        if stopped:
            backup.run(['docker', 'start', args.web_container])
        for kind, name in reversed(created):
            command = ['docker', 'rm', '-fv', name] if kind == 'container' else ['docker', kind, 'rm', name]
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
