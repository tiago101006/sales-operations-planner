"""Associa as imagens sintéticas do manifesto aos produtos locais ainda sem foto.

Com --atualizar-geradas, substitui também as fotos cujo SHA-256 corresponde à
versão anterior registada no manifesto. Fotografias diferentes são preservadas.
Cria uma cópia dos produtos abrangidos antes de escrever. A importação inteira
usa uma transação MySQL e recusa dados alterados entre a leitura e o bloqueio.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import re
from pathlib import Path
import secrets

import backup


ROOT = Path(__file__).resolve().parents[1]


def sql_text(value):
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def load_manifest(folder):
    folder = folder.resolve()
    manifest = json.loads((folder / "manifesto.json").read_text(encoding="utf-8"))
    if manifest.get("versao") != 1 or manifest.get("sintetico") is not True:
        raise ValueError("É necessário um manifesto sintético de versão 1.")
    images, codes = [], set()
    for entry in manifest.get("imagens", []):
        path = (folder / entry["ficheiro"]).resolve()
        if not path.is_relative_to(folder) or not path.is_file():
            raise ValueError("Uma imagem não existe ou está fora da pasta do manifesto.")
        image = path.read_bytes()
        if not 1 <= len(image) <= 262144 or not image.startswith(b"\xff\xd8\xff") or not image.endswith(b"\xff\xd9"):
            raise ValueError("As imagens devem ser JPEG com até 256 KiB.")
        digest = hashlib.sha256(image).hexdigest()
        if digest != entry.get("sha256"):
            raise ValueError("O SHA-256 de uma imagem difere do manifesto.")
        assigned = entry.get("codigos", [])
        if not isinstance(assigned, list) or not assigned:
            raise ValueError("Cada imagem deve identificar pelo menos um produto.")
        for code in assigned:
            if not isinstance(code, str) or not code.strip() or len(code) > 64 or code in codes:
                raise ValueError("O manifesto tem códigos inválidos ou repetidos.")
            codes.add(code)
        previous_sha = entry.get("sha256_anterior")
        if previous_sha is not None and (not isinstance(previous_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", previous_sha)):
            raise ValueError("O SHA-256 anterior de uma imagem é inválido.")
        images.append({"ficheiro": entry["ficheiro"], "imagem": image,
                       "sha256": digest, "sha256_anterior": previous_sha, "codigos": assigned})
    if not images:
        raise ValueError("O manifesto não contém imagens.")
    return manifest, images, codes


def read_products(database, codes):
    query = """
        SET NAMES utf8mb4;
        SELECT JSON_OBJECT(
            'id', p.id, 'codigo', p.codigo, 'nome', p.nome, 'unidade', p.unidade,
            'ativo', p.ativo, 'criado_em', DATE_FORMAT(p.criado_em, '%Y-%m-%d %H:%i:%s.%f'),
            'versao_lock', p.versao_lock, 'revisao_foto', p.revisao_foto,
            'foto_hex', HEX(f.imagem))
        FROM produto p LEFT JOIN foto_produto f ON f.produto_id=p.id
        WHERE BINARY p.codigo IN (""" + ",".join("BINARY " + sql_text(code) for code in sorted(codes)) + ") ORDER BY p.id;"
    products = [json.loads(line) for line in backup.sql(database, query).splitlines()]
    if {product["codigo"] for product in products} != codes:
        raise ValueError("Faltam na base local produtos referidos no manifesto.")
    for product in products:
        if (product["revisao_foto"] > 0) != (product["foto_hex"] is not None):
            raise ValueError("O estado de uma foto existente é inconsistente. A importação foi interrompida.")
    return products


def snapshot(products, manifest):
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    folder = ROOT / "tmp" / ("fotos-catalogo-" + timestamp + "-" + secrets.token_hex(3))
    folder.mkdir(mode=0o700, parents=True)
    rows = []
    for product in products:
        row = dict(product)
        image = row.pop("foto_hex")
        if image is not None:
            name = "produto-" + str(row["id"]) + ".jpg"
            path = folder / name
            path.write_bytes(bytes.fromhex(image))
            path.chmod(0o600)
            row["foto_ficheiro"] = name
        else:
            row["foto_ficheiro"] = None
        rows.append(row)
    path = folder / "antes.json"
    path.write_text(json.dumps({"produtos": rows, "manifesto": manifest}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)
    return folder


def planned_changes(products, images, update_generated=False):
    by_code = {code: image for image in images for code in image["codigos"]}
    changes = {}
    for product in products:
        image = by_code[product["codigo"]]
        existing = product["foto_hex"]
        current_sha = None if existing is None else hashlib.sha256(bytes.fromhex(existing)).hexdigest()
        new_sha = hashlib.sha256(image["imagem"]).hexdigest()
        replace_generated = (update_generated and image.get("sha256_anterior") is not None
                             and current_sha == image["sha256_anterior"] and current_sha != new_sha)
        if existing is None or replace_generated:
            changes[product["id"]] = image
    return changes


def import_images(database, products, images, update_generated=False):
    by_code = {code: index for index, image in enumerate(images) for code in image["codigos"]}
    changes = planned_changes(products, images, update_generated)
    expected_count = len(changes)
    expected_insertions = sum(product["foto_hex"] is None for product in products)
    expected_replacements = expected_count - expected_insertions
    statements = ["SET NAMES utf8mb4;", "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;", """
        CREATE TEMPORARY TABLE foto_import_expected (
            id BIGINT PRIMARY KEY, codigo VARCHAR(64), nome VARCHAR(160), unidade VARCHAR(20),
            ativo BOOLEAN, criado_em DATETIME(6), versao_lock BIGINT, revisao_foto BIGINT,
            foto_sha CHAR(64), imagem_id INT, alterar BOOLEAN NOT NULL
        ) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
        CREATE TEMPORARY TABLE foto_import_images (id INT PRIMARY KEY, imagem MEDIUMBLOB NOT NULL);
        CREATE TEMPORARY TABLE foto_import_changes (produto_id BIGINT PRIMARY KEY, imagem MEDIUMBLOB NOT NULL);
        CREATE TEMPORARY TABLE foto_import_guard (ok TINYINT NOT NULL CHECK (ok=1));
    """]
    for index, image in enumerate(images):
        statements.append(f"INSERT INTO foto_import_images VALUES ({index},0x{image['imagem'].hex()});")
    for product in products:
        existing = product["foto_hex"]
        image_sha = "NULL" if existing is None else sql_text(hashlib.sha256(bytes.fromhex(existing)).hexdigest())
        values = [str(product["id"]), sql_text(product["codigo"]), sql_text(product["nome"]),
                  sql_text(product["unidade"]), str(product["ativo"]), sql_text(product["criado_em"]),
                  str(product["versao_lock"]), str(product["revisao_foto"]), image_sha,
                  str(by_code[product["codigo"]]), str(int(product["id"] in changes))]
        statements.append("INSERT INTO foto_import_expected VALUES (" + ",".join(values) + ");")
    metadata_equal = """BINARY p.codigo=BINARY e.codigo AND BINARY p.nome=BINARY e.nome
        AND BINARY p.unidade=BINARY e.unidade AND p.ativo=e.ativo AND p.criado_em=e.criado_em"""
    statements.append(f"""
        START TRANSACTION;
        SELECT p.id FROM produto p JOIN foto_import_expected e ON e.id=p.id ORDER BY p.id FOR UPDATE;
        SELECT f.produto_id FROM foto_produto f JOIN foto_import_expected e ON e.id=f.produto_id FOR UPDATE;
        INSERT INTO foto_import_guard SELECT COUNT(*)={len(products)}
        FROM foto_import_expected e JOIN produto p ON p.id=e.id LEFT JOIN foto_produto f ON f.produto_id=p.id
        WHERE {metadata_equal} AND p.versao_lock=e.versao_lock AND p.revisao_foto=e.revisao_foto
            AND (SHA2(f.imagem,256) <=> e.foto_sha);
        INSERT INTO foto_import_changes
            SELECT p.id,i.imagem FROM produto p JOIN foto_import_expected e ON e.id=p.id
            JOIN foto_import_images i ON i.id=e.imagem_id LEFT JOIN foto_produto f ON f.produto_id=p.id
            WHERE e.alterar=1;
        INSERT INTO foto_import_guard SELECT COUNT(*)={expected_count} FROM foto_import_changes;
        INSERT INTO foto_produto (produto_id,imagem)
            SELECT n.produto_id,n.imagem FROM foto_import_changes n JOIN foto_import_expected e ON e.id=n.produto_id
            WHERE e.foto_sha IS NULL;
        INSERT INTO foto_import_guard VALUES (ROW_COUNT()={expected_insertions});
        UPDATE foto_produto f JOIN foto_import_changes n ON n.produto_id=f.produto_id
            JOIN foto_import_expected e ON e.id=f.produto_id SET f.imagem=n.imagem
            WHERE e.foto_sha IS NOT NULL;
        INSERT INTO foto_import_guard VALUES (ROW_COUNT()={expected_replacements});
        UPDATE produto p JOIN foto_import_changes n ON n.produto_id=p.id
            SET p.revisao_foto=p.revisao_foto+1,p.versao_lock=p.versao_lock+1;
        INSERT INTO foto_import_guard VALUES (ROW_COUNT()={expected_count});
        INSERT INTO foto_import_guard SELECT COUNT(*)={len(products)}
        FROM foto_import_expected e JOIN produto p ON p.id=e.id JOIN foto_produto f ON f.produto_id=p.id
        LEFT JOIN foto_import_changes n ON n.produto_id=p.id
        WHERE {metadata_equal} AND p.versao_lock=e.versao_lock+(n.produto_id IS NOT NULL)
            AND p.revisao_foto=e.revisao_foto+(n.produto_id IS NOT NULL)
            AND SHA2(f.imagem,256)=CASE WHEN n.produto_id IS NOT NULL THEN SHA2(n.imagem,256) ELSE e.foto_sha END;
        COMMIT;
    """)
    # O cliente mysql termina ao primeiro erro. Fechar a ligação reverte a transação aberta.
    backup.sql(database, "\n".join(statements))
    return expected_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pasta", nargs="?", type=Path, default=ROOT / "data/catalogo-imagens")
    parser.add_argument("--verificar", action="store_true", help="Verifica imagens e produtos sem gravar dados.")
    parser.add_argument("--atualizar-geradas", action="store_true",
                        help="Substitui apenas fotos que correspondam ao SHA-256 anterior do manifesto.")
    args = parser.parse_args()
    manifest, images, codes = load_manifest(args.pasta)
    database = backup.compose_container("db")
    before = read_products(database, codes)
    changes = planned_changes(before, images, args.atualizar_geradas)
    insertions = sum(product["foto_hex"] is None for product in before)
    replacements = len(changes) - insertions
    if args.verificar:
        print(f"{len(before)} produtos encontrados; {insertions} fotos por carregar; {replacements} fotos geradas por atualizar; {len(before)-len(changes)} fotos preservadas.")
        return
    folder = snapshot(before, manifest)
    changed = import_images(database, before, images, args.atualizar_geradas)
    after = read_products(database, codes)
    for old, new in zip(before, after, strict=True):
        changed_photo = old["id"] in changes
        for key in ("id", "codigo", "nome", "unidade", "ativo", "criado_em"):
            if old[key] != new[key]:
                raise RuntimeError("A verificação dos produtos após importação falhou. Consulta " + str(folder))
        expected_bytes = changes[old["id"]]["imagem"] if changed_photo else bytes.fromhex(old["foto_hex"])
        expected_sha = hashlib.sha256(expected_bytes).hexdigest()
        actual_sha = hashlib.sha256(bytes.fromhex(new["foto_hex"])).hexdigest()
        if (actual_sha != expected_sha or new["versao_lock"] != old["versao_lock"] + changed_photo
                or new["revisao_foto"] != old["revisao_foto"] + changed_photo):
            raise RuntimeError("A verificação das fotos após importação falhou. Consulta " + str(folder))
    print(f"{insertions} fotos carregadas; {replacements} fotos geradas atualizadas e verificadas; {len(before)-changed} fotos existentes preservadas.")
    print("Cópia anterior guardada em " + str(folder))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
