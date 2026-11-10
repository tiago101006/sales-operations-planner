"""Verifica constraints num contentor MySQL temporário sem password e sem portas.

Uso: python3 database/verify_schema.py sop-schema-check-NOME
Aplicar primeiro a migração numa base `sop` vazia. Todas as fixtures são revertidas.
Não testa serviços Java, permissões nem concorrência do workflow.
"""

import subprocess
import sys


FIXTURE = """
START TRANSACTION;
INSERT INTO utilizador (id,nome,email,password_hash) VALUES
 (1,'Planeador','planeador@example.test','fixture-nao-autenticavel'),
 (2,'Aprovador','aprovador@example.test','fixture-nao-autenticavel');
INSERT INTO produto (id,codigo,nome,unidade) VALUES (1,'SKU01','Produto de teste','un');
INSERT INTO lote (id,tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em) VALUES
 (1,'HISTORICO','VALIDADO','teste','h.csv','fixture/h.csv',REPEAT('a',64),'v1',1,'2026-10-31 12:00:00'),
 (2,'REALIZADOS','VALIDADO','teste','r.csv','fixture/r.csv',REPEAT('b',64),'v1',1,'2026-12-01 12:00:00'),
 (3,'REALIZADOS','VALIDADO','teste','r2.csv','fixture/r2.csv',REPEAT('c',64),'v1',1,'2026-12-02 12:00:00');
INSERT INTO observacao (id,lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES
 (1,1,1,'2026-10-01','VENDAS',100,'2026-10-31 12:00:00'),
 (2,2,1,'2026-11-01','PROCURA_REALIZADA',115,'2026-12-01 12:00:00'),
 (3,3,1,'2026-11-01','PROCURA_REALIZADA',116,'2026-12-02 12:00:00');
INSERT INTO execucao_previsao
 (id,lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por) VALUES
 (1,1,'VENDAS','2026-10-31 23:59:59','2026-10-01',1,'MEDIA_MOVEL','{}','teste','teste','CONCLUIDA',1),
 (2,1,'VENDAS','2026-10-31 23:59:59','2026-10-01',1,'MEDIA_MOVEL','{}','teste','teste','CONCLUIDA',1);
INSERT INTO execucao_produto (execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado) VALUES
 (1,1,'MEDIA_MOVEL','{}','PREVISTO'), (2,1,'MEDIA_MOVEL','{}','PREVISTO');
INSERT INTO linha_previsao VALUES (1,1,'2026-11-01',100), (2,1,'2026-11-01',100);
INSERT INTO cenario (id,execucao_id,nome,tipo,criado_por) VALUES
 (1,1,'Otimista','OTIMISTA',1), (2,2,'Outra execucao','BASE',1);
INSERT INTO revisao_cenario (id,cenario_id,execucao_id,numero,fator,pressupostos,estado,criado_por) VALUES
 (1,1,1,1,1.1,'{}','APROVADA',1), (2,2,2,1,1,'{}','RASCUNHO',1),
 (3,1,1,2,1.1,'{}','RASCUNHO',1);
INSERT INTO linha_cenario VALUES
 (1,1,1,'2026-11-01',110,120), (2,2,1,'2026-11-01',100,100),
 (3,1,1,'2026-11-01',110,110);
INSERT INTO evento_revisao (id,revisao_id,tipo,autor_id,motivo) VALUES
 (1,1,'AJUSTE',1,'Campanha confirmada'), (2,3,'AJUSTE',1,'Outro motivo'),
 (3,1,'APROVACAO',2,NULL), (4,2,'APROVACAO',2,NULL);
INSERT INTO ajuste (id,revisao_id,produto_id,periodo,sequencia,evento_id,valor_anterior,valor_novo,justificacao)
 VALUES (1,1,1,'2026-11-01',1,1,110,120,'Campanha confirmada');
INSERT INTO plano_publicado (id,revisao_id,evento_aprovacao_id) VALUES (1,1,3);
INSERT INTO linha_plano VALUES (1,1,1,'2026-11-01','SKU01','un',100,120);
INSERT INTO avaliacao
 (id,plano_id,lote_realizados_id,periodo_inicio,periodo_fim,corte_realizados,versao_calculo,criado_por)
 VALUES (1,1,2,'2026-11-01','2026-11-01','2026-12-03 12:00:00','teste',1);
"""

CASES = [
    ("duplicado no lote", 1062, "INSERT INTO observacao (lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (1,1,'2026-10-01','VENDAS',10,NOW());"),
    ("quantidade negativa", 3819, "INSERT INTO observacao (lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (1,1,'2026-09-01','VENDAS',-1,NOW());"),
    ("mes nao normalizado", 3819, "INSERT INTO observacao (lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (1,1,'2026-09-02','VENDAS',1,NOW());"),
    ("justificacao vazia", 3819, "INSERT INTO ajuste (revisao_id,produto_id,periodo,sequencia,evento_id,valor_anterior,valor_novo,justificacao) VALUES (3,1,'2026-11-01',1,2,110,120,'   ');"),
    ("previsao de outra execucao", 1452, "UPDATE linha_cenario SET execucao_id=2 WHERE revisao_id=3;"),
    ("evento de outra revisao", 1452, "INSERT INTO ajuste (revisao_id,produto_id,periodo,sequencia,evento_id,valor_anterior,valor_novo,justificacao) VALUES (1,1,'2026-11-01',2,2,120,125,'Motivo');"),
    ("aprovacao de outra revisao", 1452, "INSERT INTO plano_publicado (revisao_id,evento_aprovacao_id) VALUES (3,4);"),
    ("publicacao repetida", 1062, "INSERT INTO plano_publicado (revisao_id,evento_aprovacao_id) VALUES (1,4);"),
    ("realizado de outro lote", 1452, "INSERT INTO avaliacao_celula VALUES (1,1,2,1,'2026-11-01',3,TRUE,NULL);"),
    ("servico incompleto", 3819, "INSERT INTO resultado_operacional (lote_id,produto_id,periodo,unidades_solicitadas,disponivel_em) VALUES (1,1,'2026-11-01',10,NOW());"),
    ("dias acima do calendario", 3819, "INSERT INTO resultado_operacional (lote_id,produto_id,periodo,dias_observados_com_procura,dias_com_procura_sem_stock,disponivel_em) VALUES (1,1,'2026-11-01',31,1,NOW());"),
]


def main():
    if len(sys.argv) != 2 or not sys.argv[1].startswith("sop-schema-check-"):
        raise SystemExit("Indica apenas um contentor temporario sop-schema-check-NOME.")
    container = sys.argv[1]

    def sql(statement):
        return subprocess.run(
            ["docker", "exec", "-i", container, "mysql", "--batch", "--skip-column-names", "-uroot", "sop"],
            input=statement, text=True, capture_output=True, check=False,
        )

    count = sql("SELECT COUNT(*) FROM utilizador;")
    if count.returncode != 0 or count.stdout.strip() != "0":
        raise SystemExit("A base de verificacao deve estar vazia e acessivel.")

    valid = sql(FIXTURE + """
INSERT INTO avaliacao_celula VALUES (1,1,2,1,'2026-11-01',2,TRUE,NULL);
SELECT lp.quantidade_base, lc.quantidade_calculada, lp.quantidade_aprovada,
       a.valor_anterior, a.valor_novo, u.nome
FROM linha_plano lp
JOIN linha_cenario lc ON lc.revisao_id=lp.revisao_id AND lc.produto_id=lp.produto_id AND lc.periodo=lp.periodo
JOIN ajuste a ON a.revisao_id=lc.revisao_id AND a.produto_id=lc.produto_id AND a.periodo=lc.periodo
JOIN evento_revisao e ON e.id=a.evento_id
JOIN utilizador u ON u.id=e.autor_id;
ROLLBACK;
""")
    expected = "100.0000\t110.0000\t120.0000\t110.0000\t120.0000\tPlaneador"
    if valid.returncode != 0 or valid.stdout.strip() != expected:
        raise SystemExit(f"Falhou o percurso valido: {valid.stdout} {valid.stderr}")
    print("OK: base 100 -> cenario 110 -> ajuste 120, com autoria e avaliacao rastreaveis")

    for label, error, statement in CASES:
        # Num erro, mysql termina a ligacao e o InnoDB reverte a transacao aberta.
        result = sql(FIXTURE + statement + "\nROLLBACK;\n")
        if result.returncode == 0 or f"ERROR {error} " not in result.stderr:
            raise SystemExit(f"Falhou {label}: esperado erro {error}; {result.stderr}")
        print(f"OK: rejeitado {label} (MySQL {error})")

    empty = sql("SELECT COUNT(*) FROM utilizador;")
    if empty.returncode != 0 or empty.stdout.strip() != "0":
        raise SystemExit("As fixtures nao foram revertidas.")
    print(f"PASS: 1 percurso valido + {len(CASES)} rejeicoes; fixtures revertidas")


if __name__ == "__main__":
    main()
