"""Verificação independente e só de leitura da empresa fictícia NexoPlan.

``verify_company(sql, base, accounts, report)`` recebe um callback SQL que devolve
TSV sem cabeçalhos. Não abre ligações MySQL, conhece credenciais de base de dados
ou altera os dados. Na API, os únicos POST são login e logout. O resultado não
contém passwords, hashes, cookies ou tokens e pode integrar o relatório público.
"""
import csv
import http.cookiejar
import io
import json
import re
import urllib.error
import urllib.request
from collections import defaultdict
from decimal import Decimal, ROUND_HALF_UP, localcontext


SCALE = Decimal('0.0001')


def _decimal(value):
    return None if value is None else Decimal(str(value))


def _rounded(value):
    return None if value is None else str(value.quantize(SCALE, rounding=ROUND_HALF_UP))


def _mean(values):
    return None if not values else _rounded(sum(values, Decimal(0)) / len(values))


def _ratio(numerator, denominator):
    return None if not denominator else _rounded(numerator * 100 / denominator)


def _metrics(pairs):
    """Recalcula MM/ETS ou base/aprovado sobre a mesma população de pares."""
    valid = [(a, b, y) for a, b, y in pairs if None not in (a, b, y)]
    nonzero = [(a, b, y) for a, b, y in valid if y != 0]
    return {
        'candidatos': len(pairs), 'paresComparaveis': len(valid),
        'paresMape': len(nonzero), 'zerosExcluidosMape': len(valid) - len(nonzero),
        'realizadosEmFalta': sum(y is None for _, _, y in pairs),
        'semPrevisaoMediaMovel': sum(a is None for a, _, _ in pairs),
        'semPrevisaoEts': sum(b is None for _, b, _ in pairs),
        'coberturaPercentagem': _ratio(Decimal(len(valid)), Decimal(len(pairs))),
        'maeMediaMovel': _mean([abs(a - y) for a, _, y in valid]),
        'maeEts': _mean([abs(b - y) for _, b, y in valid]),
        'mapeMediaMovel': _mean([(abs(a - y) / abs(y)).quantize(Decimal('1e-18'), rounding=ROUND_HALF_UP) * 100
                                for a, _, y in nonzero]),
        'mapeEts': _mean([(abs(b - y) / abs(y)).quantize(Decimal('1e-18'), rounding=ROUND_HALF_UP) * 100
                         for _, b, y in nonzero]),
    }


class _ReadClient:
    """Sessão local; bloqueia qualquer escrita de negócio por construção."""
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, data=None, raw=False, expected=200):
        if method != 'GET' and (method != 'POST' or path not in
                ('/api/v1/auth/login', '/api/v1/auth/logout')):
            raise AssertionError('O verificador não pode alterar dados de negócio.')
        headers = {'Content-Type': 'application/json'}
        if method == 'POST':
            csrf = self.request('GET', '/api/v1/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        body = json.dumps(data).encode() if data is not None else None
        request = urllib.request.Request(self.base + path, data=body,
                                         headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=180) as response:
                status, content = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, content = error.code, error.read()
        if status != expected:
            # Não incluir o pedido, resposta de login ou credenciais na exceção.
            raise AssertionError(f'{method} {path}: HTTP {status}; esperado {expected}.')
        if expected != 200 or raw:
            return content if raw else status
        return json.loads(content) if content else None

    def pages(self, path):
        rows, page = [], 0
        separator = '&' if '?' in path else '?'
        while True:
            result = self.request('GET', f'{path}{separator}size=200&page={page}')
            rows.extend(result['items'])
            page += 1
            if page >= result['totalPages']:
                assert len(rows) == result['totalElements'], f'Paginação inconsistente: {path}'
                return rows

    def logout(self):
        self.request('POST', '/api/v1/auth/logout', {}, expected=204)


def verify_company(sql, base, accounts, report):
    """Assertar proveniência, cronologia, fórmulas, métricas, acessos e CSV.

    ``sql(query) -> str`` deve executar SELECT/WITH na mesma base de ``base``.
    ``accounts`` são os registos privados usados na preparação; ``report`` é o
    retorno de ``populate_company``. Todas as verificações abrangem a base toda,
    incluindo linhas que não estejam referidas no report.
    """
    checked = []

    def check(condition, label):
        if not condition:
            raise AssertionError('Verificação NexoPlan falhou: ' + label)
        checked.append(label)

    def query(statement):
        if not re.match(r'^\s*(SELECT|WITH)\b', statement, re.I) or ';' in statement:
            raise AssertionError('Só SELECT/WITH sem múltiplos comandos é permitido.')
        value = sql(statement)
        return value.decode() if isinstance(value, bytes) else value

    def rows(statement):
        return [line.split('\t') for line in query(statement).splitlines()]

    def scalar(statement):
        return int(query(statement).strip())

    def zero(label, statement):
        count = scalar(statement)
        check(count == 0, f'{label} (violações={count})')

    tables = ['utilizador', 'produto', 'lote', 'observacao', 'resultado_operacional',
              'execucao_previsao', 'execucao_produto', 'linha_previsao', 'cenario',
              'revisao_cenario', 'evento_revisao', 'ajuste', 'linha_cenario',
              'plano_publicado', 'linha_plano', 'avaliacao', 'avaliacao_celula',
              'backtest', 'backtest_celula', 'evento_estado_utilizador',
              'convite_utilizador', 'calendario', 'promocao']
    counts = {table: scalar(f'SELECT COUNT(*) FROM `{table}`') for table in tables}
    check(counts['produto'] == 24, 'Catálogo completo: 24 produtos')
    check(counts['utilizador'] == len(accounts), 'Todas as contas esperadas, sem contas externas')
    zero('Emails de domínio da empresa',
         "SELECT COUNT(*) FROM utilizador WHERE email NOT LIKE '%@nexoplan.com'")
    check(counts['execucao_previsao'] >= 8, 'Oito execuções: média móvel e ETS em quatro ciclos')
    check(counts['cenario'] >= 12, 'Doze cenários nos quatro ciclos')
    check(counts['plano_publicado'] == 4, 'Quatro planos publicados')
    check(counts['avaliacao'] >= 3 and counts['backtest'] >= 1, 'Avaliações e backtest disponíveis')
    if report.get('ciclos'):
        check(len(report['ciclos']) == counts['plano_publicado'], 'Manifesto contém os quatro ciclos publicados')
    if report.get('produtos'):
        check(len(report['produtos']) == counts['produto'], 'Manifesto contém o catálogo completo')
    # Features sem API não são fabricadas para aumentar artificialmente contagens.
    check(counts['calendario'] == counts['promocao'] == 0,
          'Calendário e promoções sem dados artificiais não suportados')

    # A troca de dados usa FOREIGN_KEY_CHECKS=0; validar cada FK incluindo compostas.
    foreign_keys = defaultdict(list)
    for table, constraint, column, target, target_column, position in rows(
            "SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,"
            "REFERENCED_COLUMN_NAME,ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE "
            "WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL "
            "ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION"):
        if not all(re.fullmatch(r'[A-Za-z0-9_]+', identifier)
                   for identifier in (table, constraint, column, target, target_column)):
            raise AssertionError('Identificador SQL inválido na introspeção de FKs.')
        foreign_keys[(table, constraint, target)].append((column, target_column))
    for (table, constraint, target), columns in foreign_keys.items():
        relation = ' AND '.join(f'a.`{left}`=b.`{right}`' for left, right in columns)
        not_null = ' AND '.join(f'a.`{left}` IS NOT NULL' for left, _ in columns)
        zero('Integridade ' + constraint,
             f'SELECT COUNT(*) FROM `{table}` a LEFT JOIN `{target}` b ON {relation} '
             f'WHERE {not_null} AND b.`{columns[0][1]}` IS NULL')

    timestamps = {
        'utilizador': ['criado_em'], 'produto': ['criado_em'],
        'lote': ['importado_em', 'disponivel_em', 'validado_em'],
        'observacao': ['disponivel_em'], 'resultado_operacional': ['disponivel_em'],
        'execucao_previsao': ['corte_temporal', 'criado_em', 'concluido_em'],
        'cenario': ['criado_em'], 'revisao_cenario': ['criado_em', 'submetido_em', 'decidido_em'],
        'evento_revisao': ['ocorrido_em'], 'plano_publicado': ['publicado_em'],
        'avaliacao': ['corte_realizados', 'criado_em'],
        'backtest': ['corte_realizados', 'criado_em', 'concluido_em'],
        'evento_estado_utilizador': ['alterado_em'],
        'convite_utilizador': ['criado_em', 'usado_em', 'revogado_em'],
    }
    for table, columns in timestamps.items():
        zero('Sem instantes futuros em ' + table,
             f'SELECT COUNT(*) FROM `{table}` WHERE ' +
             ' OR '.join(f'`{column}`>UTC_TIMESTAMP(6)' for column in columns))

    sql_checks = {
        'Lotes validados e totais fiáveis': """
            SELECT COUNT(*) FROM lote l WHERE estado='VALIDADO' AND (validado_em IS NULL
            OR validado_em<importado_em OR disponivel_em>validado_em
            OR total_linhas<>(SELECT COUNT(*) FROM observacao o WHERE o.lote_id=l.id)
                              +(SELECT COUNT(*) FROM resultado_operacional o WHERE o.lote_id=l.id)
            OR EXISTS(SELECT 1 FROM erro_importacao e WHERE e.lote_id=l.id))""",
        'Rejeições preservam diagnósticos sem observações parciais': """
            SELECT COUNT(*) FROM lote l WHERE estado NOT IN ('VALIDADO','REJEITADO')
            OR (estado='REJEITADO' AND (validado_em IS NOT NULL
                OR NOT EXISTS(SELECT 1 FROM erro_importacao e WHERE e.lote_id=l.id)
                OR EXISTS(SELECT 1 FROM observacao o WHERE o.lote_id=l.id)
                OR EXISTS(SELECT 1 FROM resultado_operacional o WHERE o.lote_id=l.id)))""",
        'Proveniência das observações': """
            SELECT COUNT(*) FROM observacao o JOIN lote l ON l.id=o.lote_id
            WHERE NOT ((l.tipo='HISTORICO' AND o.tipo IN ('VENDAS','CONSUMO'))
                        OR (l.tipo='REALIZADOS' AND o.tipo='PROCURA_REALIZADA'))
            OR (l.tipo='HISTORICO' AND o.disponivel_em<LAST_DAY(o.periodo))
            OR (l.tipo='REALIZADOS' AND o.disponivel_em<DATE_ADD(o.periodo,INTERVAL 1 MONTH))""",
        'Proveniência e fecho operacional': """
            SELECT COUNT(*) FROM resultado_operacional o JOIN lote l ON l.id=o.lote_id
            WHERE l.tipo<>'OPERACIONAL' OR o.disponivel_em<DATE_ADD(o.periodo,INTERVAL 1 MONTH)""",
        'Execuções e cortes coerentes': """
            SELECT COUNT(*) FROM execucao_previsao e JOIN lote l ON l.id=e.lote_historico_id
            WHERE e.estado<>'CONCLUIDA' OR l.tipo<>'HISTORICO' OR l.estado<>'VALIDADO'
            OR l.disponivel_em>e.corte_temporal OR l.validado_em>e.corte_temporal
            OR LAST_DAY(e.ultimo_periodo_treino)>DATE(e.corte_temporal)
            OR e.criado_em<e.corte_temporal OR e.concluido_em<e.criado_em
            OR e.concluido_em IS NULL""",
        'Horizontes de previsão sem lacunas': """
            SELECT COUNT(*) FROM execucao_produto p JOIN execucao_previsao e ON e.id=p.execucao_id
            WHERE (p.estado IN ('PREVISTO','RECURSO') AND
                (SELECT COUNT(*) FROM linha_previsao l WHERE l.execucao_id=p.execucao_id
                  AND l.produto_id=p.produto_id)<>e.horizonte_meses)
            OR (p.estado IN ('ERRO','INSUFICIENTE') AND EXISTS
                (SELECT 1 FROM linha_previsao l WHERE l.execucao_id=p.execucao_id AND l.produto_id=p.produto_id))""",
        'Meses de previsão pertencem ao horizonte': """
            SELECT COUNT(*) FROM linha_previsao l JOIN execucao_previsao e ON e.id=l.execucao_id
            WHERE l.periodo<=e.ultimo_periodo_treino
            OR l.periodo>DATE_ADD(e.ultimo_periodo_treino,INTERVAL e.horizonte_meses MONTH)""",
        'Cenários posteriores à previsão': """
            SELECT COUNT(*) FROM cenario c JOIN execucao_previsao e ON e.id=c.execucao_id
            WHERE c.criado_em<e.concluido_em""",
        'Revisões e origem coerentes': """
            SELECT COUNT(*) FROM revisao_cenario r JOIN cenario c ON c.id=r.cenario_id
            LEFT JOIN revisao_cenario o ON o.id=r.revisao_origem_id
            WHERE r.criado_em<c.criado_em OR (o.id IS NOT NULL AND
                (r.numero<=o.numero OR r.criado_em<o.criado_em))
            OR (c.tipo='BASE' AND r.fator<>1) OR (c.tipo='OTIMISTA' AND r.fator<=1)
            OR (c.tipo='CONSERVADOR' AND (r.fator<0 OR r.fator>=1))
            OR (SELECT COUNT(*) FROM linha_cenario l WHERE l.revisao_id=r.id)<>
               (SELECT COUNT(*) FROM linha_previsao l WHERE l.execucao_id=r.execucao_id)""",
        'Fator calculado em todas as células': """
            SELECT COUNT(*) FROM linha_cenario l JOIN revisao_cenario r ON r.id=l.revisao_id
            JOIN linha_previsao b ON b.execucao_id=l.execucao_id AND b.produto_id=l.produto_id AND b.periodo=l.periodo
            WHERE l.quantidade_calculada<>ROUND(b.quantidade_base*r.fator,4)""",
        'Eventos ordenados e posteriores à criação': """
            SELECT COUNT(*) FROM evento_revisao e JOIN revisao_cenario r ON r.id=e.revisao_id
            WHERE e.ocorrido_em<r.criado_em OR EXISTS(SELECT 1 FROM evento_revisao a
                WHERE a.revisao_id=e.revisao_id AND a.id<e.id AND a.ocorrido_em>e.ocorrido_em)""",
        'Estados refletem submissões e decisões': """
            SELECT COUNT(*) FROM revisao_cenario r WHERE
            (SELECT COUNT(*) FROM evento_revisao e WHERE e.revisao_id=r.id AND e.tipo='CRIACAO')<>1
            OR (r.estado='RASCUNHO' AND (r.submetido_em IS NOT NULL OR r.decidido_em IS NOT NULL))
            OR (r.estado<>'RASCUNHO' AND (r.submetido_em IS NULL OR r.submetido_em<r.criado_em
                OR NOT EXISTS(SELECT 1 FROM evento_revisao e WHERE e.revisao_id=r.id AND e.tipo='SUBMISSAO' AND e.ocorrido_em=r.submetido_em)))
            OR (r.estado='EM_REVISAO' AND r.decidido_em IS NOT NULL)
            OR (r.estado IN ('APROVADA','DEVOLVIDA') AND (r.decidido_em IS NULL OR r.decidido_em<r.submetido_em
                OR NOT EXISTS(SELECT 1 FROM evento_revisao e WHERE e.revisao_id=r.id AND e.ocorrido_em=r.decidido_em
                    AND e.tipo=IF(r.estado='APROVADA','APROVACAO','DEVOLUCAO'))))""",
        'Ajustes auditados antes da submissão': """
            SELECT COUNT(*) FROM ajuste a JOIN evento_revisao e ON e.id=a.evento_id
            JOIN revisao_cenario r ON r.id=a.revisao_id
            WHERE e.tipo<>'AJUSTE' OR e.motivo<>a.justificacao
            OR (r.submetido_em IS NOT NULL AND e.ocorrido_em>r.submetido_em)
            OR a.sequencia<>(SELECT COUNT(*) FROM ajuste b WHERE b.revisao_id=a.revisao_id
                AND b.produto_id=a.produto_id AND b.periodo=a.periodo AND b.sequencia<=a.sequencia)
            OR (a.sequencia>1 AND a.valor_anterior<>(SELECT b.valor_novo FROM ajuste b
                WHERE b.revisao_id=a.revisao_id AND b.produto_id=a.produto_id AND b.periodo=a.periodo AND b.sequencia=a.sequencia-1))""",
        'Último ajuste corresponde ao valor final': """
            SELECT COUNT(*) FROM ajuste a JOIN linha_cenario l ON l.revisao_id=a.revisao_id
                AND l.produto_id=a.produto_id AND l.periodo=a.periodo
            WHERE a.sequencia=(SELECT MAX(b.sequencia) FROM ajuste b WHERE b.revisao_id=a.revisao_id
                AND b.produto_id=a.produto_id AND b.periodo=a.periodo) AND a.valor_novo<>l.quantidade_final""",
        'Planos publicados após aprovação e antes do horizonte': """
            SELECT COUNT(*) FROM plano_publicado p JOIN revisao_cenario r ON r.id=p.revisao_id
            JOIN evento_revisao e ON e.id=p.evento_aprovacao_id
            WHERE r.estado<>'APROVADA' OR e.tipo<>'APROVACAO' OR e.ocorrido_em<>r.decidido_em
            OR p.publicado_em<e.ocorrido_em OR p.publicado_em>=(SELECT MIN(l.periodo) FROM linha_plano l WHERE l.plano_id=p.id)
            OR (SELECT COUNT(*) FROM linha_plano l WHERE l.plano_id=p.id)<>
               (SELECT COUNT(*) FROM linha_cenario l WHERE l.revisao_id=r.id)""",
        'Snapshot do plano corresponde à revisão e previsão': """
            SELECT COUNT(*) FROM linha_plano p JOIN linha_cenario c ON c.revisao_id=p.revisao_id
                AND c.produto_id=p.produto_id AND c.periodo=p.periodo
            JOIN linha_previsao b ON b.execucao_id=c.execucao_id AND b.produto_id=c.produto_id AND b.periodo=c.periodo
            JOIN produto pr ON pr.id=p.produto_id
            WHERE p.quantidade_aprovada<>c.quantidade_final OR p.quantidade_base<>b.quantidade_base
            OR p.codigo_produto<>pr.codigo OR p.unidade<>pr.unidade""",
        'Avaliações com proveniência conhecida no corte': """
            SELECT COUNT(*) FROM avaliacao a JOIN plano_publicado p ON p.id=a.plano_id
            JOIN lote l ON l.id=a.lote_realizados_id LEFT JOIN lote o ON o.id=a.lote_operacional_id
            WHERE p.publicado_em>a.corte_realizados OR a.criado_em<a.corte_realizados
            OR l.tipo<>'REALIZADOS' OR l.estado<>'VALIDADO' OR l.validado_em>a.corte_realizados
            OR l.disponivel_em>a.corte_realizados OR (o.id IS NOT NULL AND
               (o.tipo<>'OPERACIONAL' OR o.estado<>'VALIDADO' OR o.validado_em>a.corte_realizados OR o.disponivel_em>a.corte_realizados))""",
        'Elegibilidade e motivos temporais recalculados': """
            SELECT COUNT(*) FROM avaliacao_celula c JOIN avaliacao a ON a.id=c.avaliacao_id
            JOIN plano_publicado p ON p.id=c.plano_id LEFT JOIN observacao o ON o.id=c.observacao_id
            WHERE c.elegivel_temporal<>(p.publicado_em<c.periodo AND DATE_ADD(c.periodo,INTERVAL 1 MONTH)<=a.corte_realizados)
            OR NOT(c.motivo_exclusao <=> CASE WHEN p.publicado_em>=c.periodo THEN 'PLANO_PUBLICADO_TARDIAMENTE'
                WHEN DATE_ADD(c.periodo,INTERVAL 1 MONTH)>a.corte_realizados THEN 'PERIODO_NAO_FECHADO'
                WHEN o.id IS NULL THEN 'SEM_REALIZADO' WHEN o.disponivel_em>a.corte_realizados THEN 'REALIZADO_APOS_CORTE' ELSE NULL END)""",
        'Backtests completos, históricos e sem fuga de realizados': """
            SELECT COUNT(*) FROM backtest b JOIN lote l ON l.id=b.lote_id
            WHERE b.estado<>'CONCLUIDO' OR b.modo<>'SIMULACAO_RETROSPETIVA' OR b.lote_sha256<>l.sha256
            OR b.criado_em>b.concluido_em OR b.corte_realizados>b.criado_em
            OR DATE_ADD(b.ultimo_corte,INTERVAL 1 MONTH)>b.corte_realizados
            OR (SELECT COUNT(*) FROM backtest_celula c WHERE c.backtest_id=b.id)<>
              (TIMESTAMPDIFF(MONTH,b.primeiro_corte,b.ultimo_corte)+1)*b.horizonte_meses*
              (SELECT COUNT(DISTINCT o.produto_id) FROM observacao o WHERE o.lote_id=b.lote_id AND o.tipo=b.tipo_serie)""",
        'Células backtest respeitam cortes e horizontes': """
            SELECT COUNT(*) FROM backtest_celula c JOIN backtest b ON b.id=c.backtest_id
            LEFT JOIN observacao o ON o.id=c.observacao_id
            WHERE c.corte<b.primeiro_corte OR c.corte>b.ultimo_corte OR c.horizonte>b.horizonte_meses
            OR c.periodo<>DATE_ADD(c.corte,INTERVAL c.horizonte MONTH)
            OR (o.id IS NOT NULL AND (o.tipo<>b.tipo_serie OR o.disponivel_em>b.corte_realizados
                OR LAST_DAY(c.periodo)>DATE(b.corte_realizados)))""",
        'Autores existentes antes dos atos auditados': """
            SELECT COUNT(*) FROM (
              SELECT importado_por autor,importado_em instante FROM lote
              UNION ALL SELECT criado_por,criado_em FROM execucao_previsao
              UNION ALL SELECT criado_por,criado_em FROM cenario
              UNION ALL SELECT criado_por,criado_em FROM revisao_cenario
              UNION ALL SELECT autor_id,ocorrido_em FROM evento_revisao
              UNION ALL SELECT criado_por,criado_em FROM avaliacao
              UNION ALL SELECT criado_por,criado_em FROM backtest
              UNION ALL SELECT alterado_por,alterado_em FROM evento_estado_utilizador
            ) a JOIN utilizador u ON u.id=a.autor WHERE u.criado_em>a.instante""",
        'Produtos existentes antes do uso no planeamento': """
            SELECT COUNT(*) FROM execucao_produto p JOIN produto pr ON pr.id=p.produto_id
            JOIN execucao_previsao e ON e.id=p.execucao_id WHERE pr.criado_em>e.criado_em""",
        'Realizados coincidem com vendas históricas sobrepostas': """
            SELECT COUNT(*) FROM observacao a JOIN observacao h ON h.produto_id=a.produto_id
              AND h.periodo=a.periodo AND h.tipo='VENDAS'
            WHERE a.tipo='PROCURA_REALIZADA' AND a.quantidade<>h.quantidade""",
    }
    for label, statement in sql_checks.items():
        zero(label, statement)

    # A mesma fronteira de snapshot que a auditoria de ajustes herdados do serviço.
    zero('Decisores independentes dos criadores e de ajustes próprios/herdados', """
        WITH RECURSIVE cadeia AS (
          SELECT r.id raiz,r.id,r.revisao_origem_id,r.pressupostos,
            CAST(9223372036854775807 AS SIGNED) limite FROM revisao_cenario r
          UNION ALL
          SELECT c.raiz,r.id,r.revisao_origem_id,r.pressupostos,
            LEAST(c.limite,CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.ajusteOrigemAteId')),'0') AS UNSIGNED))
          FROM revisao_cenario r JOIN cadeia c ON r.id=c.revisao_origem_id
          WHERE JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.herdaAjustes'))='true'
        )
        SELECT COUNT(*) FROM evento_revisao e JOIN revisao_cenario r ON r.id=e.revisao_id
        WHERE e.tipo IN ('APROVACAO','DEVOLUCAO') AND (e.autor_id=r.criado_por
          OR NOT EXISTS(SELECT 1 FROM utilizador_papel p WHERE p.utilizador_id=e.autor_id AND p.papel='APROVADOR')
          OR EXISTS(SELECT 1 FROM cadeia c JOIN ajuste a ON a.revisao_id=c.id
            JOIN evento_revisao ae ON ae.id=a.evento_id WHERE c.raiz=r.id AND a.id<=c.limite AND ae.autor_id=e.autor_id))""")

    zero('Valores finais integralmente explicados por ajustes próprios ou herdados', """
        WITH RECURSIVE cadeia AS (
          SELECT r.id raiz,r.id,r.revisao_origem_id,r.pressupostos,0 nivel,
            CAST(9223372036854775807 AS SIGNED) limite FROM revisao_cenario r
          UNION ALL
          SELECT c.raiz,r.id,r.revisao_origem_id,r.pressupostos,c.nivel+1,
            LEAST(c.limite,CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.ajusteOrigemAteId')),'0') AS UNSIGNED))
          FROM revisao_cenario r JOIN cadeia c ON r.id=c.revisao_origem_id
          WHERE JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.herdaAjustes'))='true'
        ), ultimos AS (
          SELECT c.raiz,a.produto_id,a.periodo,a.valor_novo,
            ROW_NUMBER() OVER(PARTITION BY c.raiz,a.produto_id,a.periodo ORDER BY c.nivel,a.sequencia DESC) pos
          FROM cadeia c JOIN ajuste a ON a.revisao_id=c.id AND a.id<=c.limite
        )
        SELECT COUNT(*) FROM linha_cenario l LEFT JOIN ultimos a ON a.raiz=l.revisao_id
          AND a.produto_id=l.produto_id AND a.periodo=l.periodo AND a.pos=1
        WHERE l.quantidade_final<>COALESCE(a.valor_novo,l.quantidade_calculada)""")

    states = dict(rows('SELECT estado,COUNT(*) FROM revisao_cenario GROUP BY estado'))
    check(all(int(states.get(state, 0)) > 0 for state in
              ['RASCUNHO', 'EM_REVISAO', 'DEVOLVIDA', 'APROVADA']),
          'Demonstração dos quatro estados de revisão')

    # Recalcular a MM recursiva a partir das observações elegíveis, sem usar DTOs.
    forecast_rows = rows("""
        SELECT e.id,p.produto_id,e.ultimo_periodo_treino,e.horizonte_meses,
          p.metodo_aplicado,p.parametros_aplicados,l.periodo,l.quantidade_base
        FROM execucao_previsao e JOIN execucao_produto p ON p.execucao_id=e.id
        JOIN linha_previsao l ON l.execucao_id=e.id AND l.produto_id=p.produto_id
        ORDER BY e.id,p.produto_id,l.periodo""")
    series = defaultdict(list)
    for execution, product, last, horizon, method, params, period, amount in forecast_rows:
        series[(execution, product, last, horizon, method, params)].append(Decimal(amount))
    verified_forecasts = 0
    with localcontext() as ctx:
        ctx.prec = 40
        for (execution, product, last, horizon, method, parameters), actual in series.items():
            params = json.loads(parameters)
            if method == 'MEDIA_MOVEL':
                window = int(params['janela'])
                values = [Decimal(value[0]) for value in rows(f"""
                    SELECT o.quantidade FROM observacao o JOIN execucao_previsao e
                      ON e.lote_historico_id=o.lote_id AND e.tipo_serie=o.tipo
                    WHERE e.id={int(execution)} AND o.produto_id={int(product)}
                      AND o.periodo BETWEEN DATE_SUB(e.ultimo_periodo_treino,INTERVAL {window-1} MONTH)
                        AND e.ultimo_periodo_treino AND o.disponivel_em<=e.corte_temporal ORDER BY o.periodo""")]
                check(len(values) == window, f'Janela MM elegível execução {execution}/produto {product}')
                expected = []
                for _ in range(int(horizon)):
                    value = (sum(values[-window:]) / window).quantize(SCALE, rounding=ROUND_HALF_UP)
                    values.append(value)
                    expected.append(value)
            elif method == 'ETS(A,A,N)':
                start = str(params['inicioTreino'])
                check(bool(re.fullmatch(r'[0-9]{4}-[0-9]{2}', start)), 'Início de treino ETS válido')
                history = [float(value[0]) for value in rows(f"""
                    SELECT o.quantidade FROM observacao o JOIN execucao_previsao e
                      ON e.lote_historico_id=o.lote_id AND e.tipo_serie=o.tipo
                    WHERE e.id={int(execution)} AND o.produto_id={int(product)}
                      AND o.periodo BETWEEN '{start}-01' AND e.ultimo_periodo_treino
                      AND o.disponivel_em<=e.corte_temporal ORDER BY o.periodo""")]
                check(len(history) == params['numeroObservacoes'] and len(history) >= 6,
                      f'Histórico ETS elegível execução {execution}/produto {product}')
                alpha, beta = float(params['alpha']), float(params['beta'])
                check(0 < beta < alpha < 1, 'Coeficientes ETS admissíveis')
                level, trend, sse = history[0], history[1] - history[0], 0.0
                for observed in history[1:]:
                    fitted = level + trend
                    error = observed - fitted
                    sse += error * error
                    level = fitted + alpha * error
                    trend += beta * error
                check(abs(level-float(params['nivelFinal'])) <= 1e-7
                      and abs(trend-float(params['tendenciaFinal'])) <= 1e-7
                      and abs(sse-float(params['sseTreino'])) <= max(1e-6, abs(sse)*1e-10),
                      f'Estado ETS recalculado execução {execution}/produto {product}')
                expected = [Decimal(str(max(0, level + h * trend))).quantize(SCALE, rounding=ROUND_HALF_UP)
                            for h in range(1, int(horizon) + 1)]
            else:
                raise AssertionError('Método aplicado inesperado na simulação: ' + method)
            check(len(actual) == len(expected) and all(abs(a-b) <= (SCALE if method == 'ETS(A,A,N)' else 0)
                   for a, b in zip(actual, expected)), f'Fórmula {method} execução {execution}/produto {product}')
            verified_forecasts += len(actual)

    # Não inferir o estado ativo de passwords ou de convites: usar o estado real.
    active = dict(rows('SELECT email,ativo FROM utilizador'))
    logins, main_account = [], None
    for account in accounts:
        client = _ReadClient(base)
        is_active = active.get(account['email']) == '1'
        status = 200 if is_active else 401
        profile = client.request('POST', '/api/v1/auth/login',
                                 {'email': account['email'], 'password': account['password']}, expected=status)
        if is_active:
            try:
                current = client.request('GET', '/api/v1/auth/me')
                check(profile == current and current['email'] == account['email']
                      and set(current['papeis']) == set(account['papeis']) and current['ativo'],
                      'Login e perfis de ' + account['key'])
                client.request('GET', '/api/v1/produtos?size=1')
                client.request('GET', '/api/v1/planos?size=1')
                if set(account['papeis']) == {'CONSUMIDOR'}:
                    for path in ['/api/v1/cenarios', '/api/v1/execucoes-previsao', '/api/v1/lotes', '/api/v1/backtests']:
                        client.request('GET', path, expected=403)
                    checked.append('RBAC CONSUMIDOR: cenários, previsões, lotes e backtests recusados')
                if main_account is None and {'ADMIN', 'PLANEADOR'} & set(account['papeis']):
                    main_account = account
            finally:
                client.logout()
        else:
            checked.append('Conta inativa: 401 numa única tentativa de ' + account['key'])
        logins.append({'key': account['key'], 'ativo': is_active, 'http': status})
    check(main_account is not None, 'Sessão de leitura privilegiada disponível')
    main_client = _ReadClient(base)
    main_client.request('POST', '/api/v1/auth/login',
                        {'email': main_account['email'], 'password': main_account['password']})

    metrics_count = 0
    csv_count = 0
    try:
        plans = main_client.pages('/api/v1/planos')
        check(len(plans) == counts['plano_publicado'], 'Lista API inclui todos os planos SQL')
        for plan in plans:
            lines = main_client.pages('/api/v1/planos/' + plan['id'] + '/linhas')
            download = main_client.request('GET', '/api/v1/planos/' + plan['id'] + '/exportacao.csv', raw=True)
            exported = list(csv.DictReader(io.StringIO(download.decode('utf-8-sig')), delimiter=';'))
            check(len(lines) == len(exported) == plan['totalLinhas'], 'CSV completo do plano ' + plan['id'])
            expected = [(plan['id'], line['produto'], line['periodo'], line['unidade'],
                         line['quantidadeBase'], line['quantidade']) for line in lines]
            actual = [(line['plano_id'], line['produto'], line['periodo'], line['unidade'],
                       line['quantidade_base'], line['quantidade']) for line in exported]
            check(actual == expected, 'Valores e ordenação CSV do plano ' + plan['id'])
            csv_count += len(exported)

        for assessment in main_client.pages('/api/v1/avaliacoes'):
            path = '/api/v1/avaliacoes/' + assessment['id']
            cells = main_client.pages(path + '/celulas')
            check(len(cells) == assessment['totalCelulas'], 'Todas as células da avaliação ' + assessment['id'])
            grouped = defaultdict(list)
            for cell in cells:
                grouped[cell['produtoId']].append(cell)
            for precision in main_client.pages(path + '/precisao'):
                group = grouped[precision['produtoId']]
                _verify_precision(group, precision['metricas'], check, path)
                for horizon in precision['porHorizonte']:
                    subset = [c for c in group if c['horizonte'] == horizon['horizonte']]
                    _verify_precision(subset, horizon['metricas'], check, path)
                metrics_count += 1
            for operation in main_client.pages(path + '/operacao'):
                _verify_operation(grouped[operation['produtoId']], operation, check, path)
                metrics_count += 1

        for backtest in main_client.pages('/api/v1/backtests'):
            path = '/api/v1/backtests/' + backtest['id']
            cells = main_client.pages(path + '/celulas')
            check(len(cells) == backtest['totalCelulas'], 'Todas as células do backtest ' + backtest['id'])
            grouped = defaultdict(list)
            for cell in cells:
                grouped[(cell['produtoId'], cell['horizonte'])].append(tuple(
                    _decimal(cell[key]) for key in ['previsaoMediaMovel', 'previsaoEts', 'realizado']))
            for comparison in main_client.pages(path + '/comparacao'):
                expected = _metrics(grouped[(comparison['produtoId'], comparison['horizonte'])])
                check(expected == comparison['metricas'], 'Métricas recalculadas ' + path + '/' + comparison['produtoId'])
                metrics_count += 1
    finally:
        main_client.logout()
    return {'resultado': 'APROVADO', 'somenteLeitura': True, 'contagens': counts,
            'verificacoes': len(checked), 'checks': checked, 'logins': logins,
            'celulasPrevisaoRecalculadas': verified_forecasts,
            'linhasCsvVerificadas': csv_count, 'gruposMetricasRecalculados': metrics_count,
            'estadosRevisao': {key: int(value) for key, value in states.items()}}


def _verify_precision(cells, metrics, check, label):
    pairs = [tuple(_decimal(cell[key]) for key in
                   ['quantidadeBase', 'quantidadeAprovada', 'quantidadeRealizada'])
             for cell in cells if cell['elegivelPrecisao']]
    common = _metrics(pairs)
    expected = {
        'candidatos': len(cells), 'paresMae': common['paresComparaveis'],
        'paresMape': common['paresMape'], 'zerosExcluidosMape': common['zerosExcluidosMape'],
        'emFalta': sum(c['motivoExclusao'] == 'SEM_REALIZADO' for c in cells),
        'realizadosAposCorte': sum(c['motivoExclusao'] == 'REALIZADO_APOS_CORTE' for c in cells),
        'planosTardios': sum(c['motivoExclusao'] == 'PLANO_PUBLICADO_TARDIAMENTE' for c in cells),
        'periodosNaoFechados': sum(c['motivoExclusao'] == 'PERIODO_NAO_FECHADO' for c in cells),
        'coberturaPercentagem': _ratio(Decimal(len(pairs)), Decimal(len(cells))),
        'maeBase': common['maeMediaMovel'], 'maeAprovado': common['maeEts'],
        'mapeBase': common['mapeMediaMovel'], 'mapeAprovado': common['mapeEts'],
        'ganhoAjuste': None if common['maeMediaMovel'] is None else _rounded(
            Decimal(common['maeMediaMovel']) - Decimal(common['maeEts'])),
    }
    check(expected == metrics, 'Precisão e cobertura recalculadas ' + label)


def _verify_operation(cells, result, check, label):
    known = [cell['operacional'] for cell in cells if cell['operacional'] is not None]
    service = [op for op in known if op['unidadesSolicitadas'] is not None and op['unidadesSatisfeitasNoPrazo'] is not None]
    rupture = [op for op in known if op['diasObservadosComProcura'] is not None and op['diasComProcuraSemStock'] is not None]
    stock = [op for op in known if op['stockFecho'] is not None and op['limiteStock'] is not None]
    losses = [op for op in known if op['unidadesPerdidas'] is not None]
    total = lambda values, key: sum((_decimal(op[key]) for op in values), Decimal(0))
    expected = {
        'candidatos': len(cells), 'elegiveisTemporais': sum(c['elegivelTemporal'] for c in cells),
        'registosConhecidos': len(known), 'paresServico': len(service), 'paresRutura': len(rupture),
        'paresStock': len(stock), 'registosPerdas': len(losses),
        'nivelServicoPercentagem': _ratio(total(service, 'unidadesSatisfeitasNoPrazo'), total(service, 'unidadesSolicitadas')),
        'ruturaPercentagem': _ratio(total(rupture, 'diasComProcuraSemStock'), total(rupture, 'diasObservadosComProcura')),
        'excessoStockMedio': _mean([max(Decimal(0), _decimal(op['stockFecho']) - _decimal(op['limiteStock'])) for op in stock]),
        'unidadesPerdidasTotal': _rounded(total(losses, 'unidadesPerdidas')) if losses else None,
    }
    check(all(result[key] == value for key, value in expected.items()), 'Indicadores operacionais recalculados ' + label)
