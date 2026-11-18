"""Renova exclusivamente as sete contas do protótipo local com uma equipa fictícia.

Sem --aplicar, valida o manifesto e mostra o plano sem escrever na base.
Conserva identificadores/autoria e todos os dados de negócio. A aplicação para
durante o backup e a transação; as sessões e os convites anteriores são revogados.
Os acessos de demonstração são gerados em ficheiros privados fora do Git.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets

import backup
import preparar_empresa

ROOT = Path(__file__).resolve().parents[1]
USER_TABLES = {"utilizador", "utilizador_papel", "foto_perfil", "convite_utilizador"}
ROLES = {"ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR", "CONSUMIDOR"}
MAPPING = {"admin": 1, "aprovador": 2, "planeador": 3, "consumidor": 4,
           "comercial": 5, "antigo": 6, "consumidor2": 7,
           "admin2": None, "planeador2": None, "aprovador2": None}
ORIGINAL_ROLES = {1: ["ADMIN"], 2: ["APROVADOR"], 3: ["PLANEADOR"],
                  4: ["CONSUMIDOR"], 5: ["COMERCIAL"], 6: ["COMERCIAL"], 7: ["COMERCIAL"]}
ORIGINAL_EMAILS = {1: "admin@admin.com", 2: "aprovador@exemplo.local", 3: "piloto@exemplo.local",
                   4: "consumidor@exemplo.local", 5: "comercial@exemplo.local",
                   6: "comercial2@exemplo.local", 7: "comercial3@exemplo.local"}


def sql_text(value):
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def load_manifest(folder):
    try:
        from PIL import Image
    except ImportError:
        raise ValueError("A validação dos retratos requer Pillow neste Python.") from None
    folder = Path(folder).resolve()
    manifest = json.loads((folder / "manifesto.json").read_text(encoding="utf-8"))
    if manifest.get("versao") != 1 or manifest.get("sintetico") is not True or manifest.get("dominio") != "nexoplan.com":
        raise ValueError("É necessário um manifesto sintético v1 para nexoplan.com.")
    people = manifest.get("pessoas", [])
    if len(people) != len(MAPPING) or {p.get("key") for p in people} != set(MAPPING):
        raise ValueError("O manifesto deve conter exatamente as dez pessoas fictícias previstas.")
    emails, names, pictures = set(), set(), set()
    result = []
    for person in people:
        key = person["key"]
        if person.get("idExistente") != MAPPING[key]:
            raise ValueError("O mapeamento de identidades históricas não corresponde ao protótipo.")
        name, email, roles, active = person.get("nome"), person.get("email"), person.get("papeis"), person.get("ativo")
        if not isinstance(name, str) or len(name.strip()) < 3 or len(name) > 120 or name in names:
            raise ValueError("Nome fictício inválido ou repetido.")
        if not isinstance(email, str) or not re.fullmatch(r"[a-z]+(?:[.-][a-z]+)+@nexoplan\.com", email) or email in emails:
            raise ValueError("Cada pessoa deve ter um email empresarial próprio em nexoplan.com.")
        if not isinstance(roles, list) or not roles or len(set(roles)) != len(roles) or not set(roles) <= ROLES:
            raise ValueError("Papéis inválidos na equipa fictícia.")
        expected_role = {"admin": "ADMIN", "admin2": "ADMIN", "planeador": "PLANEADOR", "planeador2": "PLANEADOR",
                         "comercial": "COMERCIAL", "antigo": "COMERCIAL", "aprovador": "APROVADOR",
                         "aprovador2": "APROVADOR", "consumidor": "CONSUMIDOR", "consumidor2": "CONSUMIDOR"}[key]
        if roles != [expected_role] or type(active) is not bool or active != (key != "antigo"):
            raise ValueError("A distribuição dos cinco perfis/conta inativa não corresponde à simulação.")
        filename = person.get("foto")
        if not isinstance(filename, str):
            raise ValueError("Falta o retrato de uma pessoa.")
        path = (folder / filename).resolve()
        if not path.is_relative_to(folder) or not path.is_file() or path in pictures:
            raise ValueError("Retrato inválido, repetido ou fora da pasta do manifesto.")
        image = path.read_bytes()
        digest = hashlib.sha256(image).hexdigest()
        if not 1 <= len(image) <= 262144 or digest != person.get("sha256"):
            raise ValueError("O tamanho ou SHA-256 de um retrato difere do manifesto.")
        try:
            with Image.open(io.BytesIO(image)) as photo:
                if photo.format != "JPEG" or photo.size != (256, 256):
                    raise ValueError("Os retratos finais devem ser JPEG de 256 × 256 píxeis.")
                photo.verify()
        except (OSError, SyntaxError):
            raise ValueError("Um retrato não é um JPEG válido.") from None
        emails.add(email);names.add(name);pictures.add(path)
        result.append(dict(person, imagem=image))
    return manifest, result


def read_users(database):
    query = """
        SET NAMES utf8mb4;
        SELECT JSON_OBJECT('id',u.id,'nome',u.nome,'email',u.email,'password_hash',u.password_hash,
            'ativo',u.ativo,'password_definida',u.password_definida,'versao',u.versao_acesso,
            'criado_em',DATE_FORMAT(u.criado_em,'%Y-%m-%d %H:%i:%s.%f'),
            'papeis',(SELECT GROUP_CONCAT(papel ORDER BY papel) FROM utilizador_papel WHERE utilizador_id=u.id),
            'foto_sha',(SELECT SHA2(imagem,256) FROM foto_perfil WHERE utilizador_id=u.id))
        FROM utilizador u ORDER BY u.id;
    """
    rows = [json.loads(line) for line in backup.sql(database, query).splitlines()]
    for row in rows:
        row["papeis"] = row["papeis"].split(",") if row["papeis"] else []
    return rows


def classify_users(users, people):
    if len(users) == len(people):
        indexed = {u["email"]: u for u in users}
        if set(indexed) == {p["email"] for p in people}:
            for person in people:
                user = indexed[person["email"]]
                if (user["nome"] != person["nome"] or user["papeis"] != sorted(person["papeis"])
                        or bool(user["ativo"]) != person["ativo"] or not user["password_definida"]
                        or user["foto_sha"] != person["sha256"]
                        or (person["idExistente"] is not None and user["id"] != person["idExistente"])):
                    raise ValueError("A equipa já usa estes emails mas foi alterada. Não será sobrescrita.")
            return "renovada"
    if {u["id"] for u in users} != set(ORIGINAL_ROLES) or len(users) != 7:
        raise ValueError("O destino não contém exclusivamente as sete contas conhecidas do protótipo.")
    for user in users:
        number = user["id"]
        if (user["papeis"] != ORIGINAL_ROLES[number] or user["email"] != ORIGINAL_EMAILS[number]
                or bool(user["ativo"]) != (number != 6) or bool(user["password_definida"]) != (number != 7)):
            raise ValueError("Uma conta do protótipo foi alterada. Rever o plano antes de a substituir.")
    return "original"


def business_fingerprint(database):
    tables = backup.sql(database, "SELECT TABLE_NAME FROM information_schema.tables "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").splitlines()
    tables = [backup.identifier(table) for table in tables if table not in USER_TABLES]
    if not tables or "produto" not in tables or "flyway_schema_history" not in tables:
        raise ValueError("O destino não contém o esquema NexoPlan esperado.")
    data = backup.run(["docker", "exec", database, "sh", "-c", backup.DUMP
                       + '--no-create-info --skip-triggers --skip-add-locks --skip-disable-keys "$MYSQL_DATABASE" '
                       + " ".join(tables)])
    return hashlib.sha256(data).hexdigest()


def create_passwords(people):
    try:
        import bcrypt
    except ImportError:
        raise ValueError("A renovação requer bcrypt neste Python para gerar hashes compatíveis.") from None
    words = ["Plano", "Tejo", "Verde", "Rumo", "Cais", "Nexo", "Atlas", "Praia", "Venda", "Lima", "Porto", "Norte"]
    used, result = set(), []
    for person in people:
        while True:
            first = secrets.choice(words)
            second = secrets.choice([word for word in words if word != first])
            password = first + second + str(1000 + secrets.randbelow(9000)) + "!"
            if len(password) >= 12 and password not in used:
                break
        used.add(password)
        result.append(dict(person, password=password,
                           password_hash=bcrypt.hashpw(password.encode("ascii"), bcrypt.gensalt(rounds=12)).decode("ascii")))
    return result


def private_access(folder, people):
    accounts = [{key: value for key, value in person.items()
                 if key not in {"imagem", "password_hash", "sha256"}} for person in people]
    file = folder / "contas.json"
    file.write_text(json.dumps(accounts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8");file.chmod(0o600)
    lines = ["# Equipa fictícia NexoPlan — acessos locais", "", "Pessoas e retratos fictícios. Acessos apenas de demonstração local; não publicar este ficheiro.", "",
             "| Nome | Email | Perfil | Estado | Palavra-passe |", "| --- | --- | --- | --- | --- |"]
    for person in people:
        lines.append("| " + " | ".join([person["nome"], person["email"], ", ".join(person["papeis"]),
                                         "Ativo" if person["ativo"] else "Inativo — acesso recusado", person["password"]]) + " |")
    file = folder / "acessos.md";file.write_text("\n".join(lines) + "\n", encoding="utf-8");file.chmod(0o600)


def renewal_sql(before, people):
    statements = ["SET NAMES utf8mb4;", "SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;", """
        CREATE TEMPORARY TABLE equipa_expected (
          id BIGINT PRIMARY KEY,nome VARCHAR(120),email VARCHAR(254),password_hash VARCHAR(255),
          ativo BOOLEAN,password_definida BOOLEAN,versao BIGINT,criado_em DATETIME(6),papeis VARCHAR(120),foto_sha CHAR(64));
        CREATE TEMPORARY TABLE equipa_target (
          chave VARCHAR(30) PRIMARY KEY,id_existente BIGINT NULL,nome VARCHAR(120),email VARCHAR(254) UNIQUE,
          password_hash VARCHAR(255),ativo BOOLEAN,papel VARCHAR(30),imagem MEDIUMBLOB,sha CHAR(64));
        CREATE TEMPORARY TABLE equipa_guard (ok TINYINT NOT NULL CHECK (ok=1));
    """]
    for user in before:
        values = [str(user["id"]), sql_text(user["nome"]), sql_text(user["email"]), sql_text(user["password_hash"]),
                  str(int(user["ativo"])), str(int(user["password_definida"])), str(user["versao"]), sql_text(user["criado_em"]),
                  sql_text(",".join(user["papeis"])), "NULL" if user["foto_sha"] is None else sql_text(user["foto_sha"])]
        statements.append("INSERT INTO equipa_expected VALUES (" + ",".join(values) + ");")
    for person in people:
        values = [sql_text(person["key"]), "NULL" if person["idExistente"] is None else str(person["idExistente"]),
                  sql_text(person["nome"]), sql_text(person["email"]), sql_text(person["password_hash"]), str(int(person["ativo"])),
                  sql_text(person["papeis"][0]), "0x" + person["imagem"].hex(), sql_text(person["sha256"])]
        statements.append("INSERT INTO equipa_target VALUES (" + ",".join(values) + ");")
    statements.append(f"""
        START TRANSACTION;
        SELECT id FROM utilizador ORDER BY id FOR UPDATE;
        SELECT utilizador_id FROM utilizador_papel ORDER BY utilizador_id FOR UPDATE;
        SELECT utilizador_id FROM foto_perfil ORDER BY utilizador_id FOR UPDATE;
        INSERT INTO equipa_guard SELECT COUNT(*)={len(before)} FROM utilizador;
        INSERT INTO equipa_guard SELECT COUNT(*)={len(before)} FROM utilizador u JOIN equipa_expected e ON e.id=u.id
          WHERE BINARY u.nome=BINARY e.nome AND BINARY u.email=BINARY e.email AND BINARY u.password_hash=BINARY e.password_hash
          AND u.ativo=e.ativo AND u.password_definida=e.password_definida AND u.versao_acesso=e.versao AND u.criado_em=e.criado_em
          AND BINARY (SELECT GROUP_CONCAT(papel ORDER BY papel) FROM utilizador_papel WHERE utilizador_id=u.id)=BINARY e.papeis
          AND ((SELECT SHA2(imagem,256) FROM foto_perfil WHERE utilizador_id=u.id) <=> e.foto_sha);
        UPDATE convite_utilizador SET revogado_em=UTC_TIMESTAMP(6) WHERE usado_em IS NULL AND revogado_em IS NULL;
        UPDATE utilizador u JOIN equipa_target t ON t.id_existente=u.id
          SET u.nome=t.nome,u.email=t.email,u.password_hash=t.password_hash,u.ativo=t.ativo,
              u.password_definida=TRUE,u.versao_acesso=u.versao_acesso+1;
        INSERT INTO equipa_guard VALUES (ROW_COUNT()={len(before)});
        INSERT INTO utilizador (nome,email,password_hash,ativo,password_definida,versao_acesso)
          SELECT nome,email,password_hash,ativo,TRUE,0 FROM equipa_target WHERE id_existente IS NULL ORDER BY chave;
        INSERT INTO equipa_guard VALUES (ROW_COUNT()={len(people)-len(before)});
        DELETE p FROM utilizador_papel p JOIN utilizador u ON u.id=p.utilizador_id JOIN equipa_target t ON BINARY t.email=BINARY u.email;
        INSERT INTO utilizador_papel(utilizador_id,papel)
          SELECT u.id,t.papel FROM utilizador u JOIN equipa_target t ON BINARY t.email=BINARY u.email;
        INSERT INTO foto_perfil (utilizador_id,imagem)
          SELECT u.id,t.imagem FROM utilizador u JOIN equipa_target t ON BINARY t.email=BINARY u.email
          ON DUPLICATE KEY UPDATE imagem=VALUES(imagem);
        INSERT INTO equipa_guard SELECT COUNT(*)={len(people)} FROM utilizador;
        INSERT INTO equipa_guard SELECT COUNT(*)={len(people)} FROM utilizador u JOIN equipa_target t ON BINARY t.email=BINARY u.email
          LEFT JOIN equipa_expected e ON e.id=t.id_existente
          WHERE BINARY u.nome=BINARY t.nome AND BINARY u.password_hash=BINARY t.password_hash AND u.ativo=t.ativo
          AND u.password_definida=TRUE AND u.versao_acesso=IF(t.id_existente IS NULL,0,e.versao+1)
          AND (t.id_existente IS NULL OR (u.id=t.id_existente AND u.criado_em=e.criado_em))
          AND (SELECT COUNT(*) FROM utilizador_papel WHERE utilizador_id=u.id)=1
          AND EXISTS (SELECT 1 FROM utilizador_papel WHERE utilizador_id=u.id AND papel=t.papel)
          AND (SELECT SHA2(imagem,256) FROM foto_perfil WHERE utilizador_id=u.id)=t.sha;
        INSERT INTO equipa_guard SELECT COUNT(*)=0 FROM convite_utilizador WHERE usado_em IS NULL AND revogado_em IS NULL;
        COMMIT;
    """)
    return "\n".join(statements)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pasta", nargs="?", type=Path, default=ROOT / "data/equipa-demo")
    parser.add_argument("--aplicar", action="store_true", help="Gravar a renovação após validar e guardar backup completo")
    args = parser.parse_args()
    _, people = load_manifest(args.pasta)
    web, database = backup.compose_container("backend"), backup.compose_container("db")
    app, _, url, volume = preparar_empresa.local_target(web, database)
    before = read_users(database)
    state = classify_users(before, people)
    if state == "renovada":
        print("A equipa fictícia já corresponde ao manifesto. Nenhuma conta ou password foi alterada.")
        return
    print("Plano: renovar sete identidades conservando os IDs e criar três contas; nove ativas e uma inativa.")
    for person in people:
        print(person["nome"] + " | " + person["email"] + " | " + person["papeis"][0])
    if not args.aplicar:
        print("Pré-visualização concluída. Para gravar com backup: acrescentar --aplicar.")
        return
    accounts = create_passwords(people)
    run_folder = ROOT / "tmp/equipa-demo" / ("nexoplan-equipa-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + secrets.token_hex(3))
    run_folder.mkdir(parents=True, mode=0o700)
    private_access(run_folder, accounts)
    stopped = changed = False
    (ROOT / "tmp").mkdir(exist_ok=True)
    with (ROOT / "tmp/.backup.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            backup.run(["docker", "stop", web]);stopped = True
            if read_users(database) != before:
                raise ValueError("As contas mudaram depois da leitura. Renovação cancelada.")
            fingerprint = business_fingerprint(database)
            preparar_empresa.snapshot(web, database, volume, app["Image"], run_folder / "antes")
            # Uma falha da ligação pode deixar o resultado do COMMIT desconhecido.
            # Nesse caso recuperamos sempre o snapshot enquanto o backend está parado.
            changed = True
            backup.sql(database, renewal_sql(before, accounts))
            after = read_users(database)
            if classify_users(after, people) != "renovada" or business_fingerprint(database) != fingerprint:
                raise RuntimeError("A verificação após renovação falhou.")
            public = {"sintetico": True, "identidadesPreservadas": 7, "contasCriadas": 3,
                      "contasAtivas": 9, "contasInativas": 1, "retratos": 10,
                      "negocioSha256Antes": fingerprint, "negocioSha256Depois": business_fingerprint(database),
                      "utilizadores": [{key: value for key, value in row.items() if key not in {"password_hash"}} for row in after]}
            file = run_folder / "resultado.json";file.write_text(json.dumps(public, ensure_ascii=False, indent=2) + "\n");file.chmod(0o600)
        except Exception:
            if changed:
                try:
                    preparar_empresa.restore_snapshot(database, volume, app["Image"], run_folder / "antes")
                except Exception:
                    # Não servir uma base cuja recuperação não foi confirmada.
                    stopped = False
                    raise RuntimeError("A recuperação do backup falhou. O backend ficou parado; conserva " + str(run_folder / "antes")) from None
            raise
        finally:
            if stopped:
                backup.run(["docker", "start", web])
                preparar_empresa.wait_web(url)
    print("Equipa renovada e verificada; dados de negócio e autoria preservados.")
    print("Acessos privados: " + str(run_folder / "acessos.md"))
    print("Backup anterior: " + str(run_folder / "antes"))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
