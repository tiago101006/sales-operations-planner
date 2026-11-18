"""Povoa exclusivamente uma instalação vazia e isolada com a empresa fictícia NexoPlan.

O orquestrador prepara o bootstrap ADMIN e fornece passwords apenas em memória.
Todos os objetos de negócio são criados pela API. ``fixture_sql(sql)`` serve apenas
para datar os objetos fictícios que este processo acabou de criar; nunca altera
quantidades, decisões, credenciais ou regras dos serviços. Não executa resets.
"""
from __future__ import annotations

import calendar
import csv
import hashlib
import http.cookiejar
import io
import json
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid


ACCOUNTS = [
    {"key": "admin", "nome": "Leonor Matos", "email": "admin@nexoplan.pt", "papeis": ["ADMIN"]},
    {"key": "admin2", "nome": "Miguel Varela", "email": "miguel.varela@nexoplan.pt", "papeis": ["ADMIN"]},
    {"key": "planeador", "nome": "Inês Seabra", "email": "ines.seabra@nexoplan.pt", "papeis": ["PLANEADOR"]},
    {"key": "planeador2", "nome": "Rui Falcão", "email": "rui.falcao@nexoplan.pt", "papeis": ["PLANEADOR"]},
    {"key": "comercial", "nome": "Carolina Pires", "email": "carolina.pires@nexoplan.pt", "papeis": ["COMERCIAL"]},
    {"key": "aprovador", "nome": "Sofia Neves", "email": "sofia.neves@nexoplan.pt", "papeis": ["APROVADOR"]},
    {"key": "aprovador2", "nome": "Tomás Brito", "email": "tomas.brito@nexoplan.pt", "papeis": ["APROVADOR"]},
    {"key": "consumidor", "nome": "Duarte Esteves", "email": "duarte.esteves@nexoplan.pt", "papeis": ["CONSUMIDOR"]},
    {"key": "consumidor2", "nome": "Mariana Lobo", "email": "mariana.lobo@nexoplan.pt", "papeis": ["CONSUMIDOR"]},
    {"key": "antigo", "nome": "Pedro Teles", "email": "pedro.teles@nexoplan.pt", "papeis": ["CONSUMIDOR"]},
]


class APIClient:
    """Cliente urllib com sessão e CSRF próprios, incluindo convites e ativação."""

    def __init__(self, base):
        url = urllib.parse.urlsplit(base)
        if (url.scheme != "http" or url.hostname not in ("localhost", "127.0.0.1", "::1")
                or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment):
            raise ValueError("A fixture exige uma instalação HTTP local isolada.")
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.user = None

    def request(self, method, path, data=None, upload=None, raw=False):
        if not path.startswith("/api/v1/"):
            raise ValueError("Caminho API inválido.")
        headers, body = {}, None
        if method not in ("GET", "HEAD"):
            csrf = self.request("GET", "/api/v1/auth/csrf")
            headers[csrf["headerName"]] = csrf["token"]
        if upload is not None:
            kind, filename, contents = upload
            if "/" in filename or "\\" in filename or '"' in filename or "\r" in filename or "\n" in filename:
                raise ValueError("Nome de ficheiro inválido.")
            contents = contents.encode("utf-8") if isinstance(contents, str) else contents
            boundary = uuid.uuid4().hex
            fields = {"tipo": kind, "origem": "NexoPlan | SIMULAÇÃO FICTÍCIA ISOLADA", "versaoEsquema": "v1"}
            parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
                     for name, value in fields.items()]
            parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="ficheiro"; filename="{filename}"\r\nContent-Type: text/csv\r\n\r\n'.encode(),
                      contents, f"\r\n--{boundary}--\r\n".encode()]
            body = b"".join(parts)
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif data is not None:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, method=method, headers=headers, data=body)
        with self.opener.open(req, timeout=240) as response:
            value = response.read()
            return value if raw else json.loads(value) if value else None

    def login(self, email, password):
        self.user = self.request("POST", "/api/v1/auth/login", {"email": email, "password": password})
        return self.user

    def logout(self):
        if self.user is not None:
            try:
                self.request("POST", "/api/v1/auth/logout", {})
            finally:
                self.user = None

    def all(self, path):
        values, page = [], 0
        separator = "&" if "?" in path else "?"
        while True:
            result = self.request("GET", f"{path}{separator}size=200&page={page}")
            values.extend(result["items"])
            page += 1
            if page >= result["totalPages"]:
                return values


def _instant(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def _last_day(month, hour=18, minute=0):
    year, month = map(int, month.split("-"))
    return datetime(year, month, calendar.monthrange(year, month)[1], hour, minute, tzinfo=timezone.utc)


def _sql_text(value):
    # Hex literals avoid SQL escaping and sql_mode dependencies, including names with accents.
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


class _FixtureDates:
    """Whitelist de tabelas, IDs devolvidos pela API e colunas estritamente temporais."""
    COLUMNS = {
        "utilizador": {"criado_em"}, "produto": {"criado_em"},
        "lote": {"importado_em", "disponivel_em", "validado_em"},
        "execucao_previsao": {"criado_em", "concluido_em"},
        "cenario": {"criado_em"},
        "revisao_cenario": {"criado_em", "submetido_em", "decidido_em"},
        "evento_revisao": {"ocorrido_em"}, "plano_publicado": {"publicado_em"},
        "avaliacao": {"criado_em"}, "backtest": {"criado_em", "concluido_em"},
    }

    def __init__(self, callback):
        if not callable(callback):
            raise ValueError("É obrigatório fornecer a ligação SQL da fixture isolada.")
        self.callback = callback
        self.ids = {table: set() for table in self.COLUMNS}
        self.operations = 0

    def register(self, table, object_id):
        value = int(object_id)
        if value <= 0:
            raise ValueError("ID de fixture inválido.")
        self.ids[table].add(value)
        return value

    def set(self, table, object_id, **columns):
        object_id = int(object_id)
        if object_id not in self.ids[table] or not columns or not columns.keys() <= self.COLUMNS[table]:
            raise ValueError("Alteração fora dos objetos/colunas da fixture.")
        assignments = []
        for key, value in columns.items():
            if not isinstance(value, datetime) or value.tzinfo is None:
                raise ValueError("Os instantes da fixture devem incluir UTC.")
            assignments.append(key + "='" + value.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f") + "'")
        self.callback("UPDATE " + table + " SET " + ",".join(assignments) + " WHERE id=" + str(object_id) + ";")
        self.operations += 1

    def account(self, user_id, name, date, bootstrap=False):
        self.register("utilizador", user_id)
        self.set("utilizador", user_id, criado_em=date)
        if bootstrap:
            self.callback("UPDATE utilizador SET nome=" + _sql_text(name) + " WHERE id=" + str(int(user_id)) + ";")
        else:
            start = date.strftime("%Y-%m-%d %H:%M:%S")
            end = (date + timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S")
            used = (date + timedelta(minutes=5)).strftime("%Y-%m-%d %H:%M:%S")
            self.callback(f"UPDATE convite_utilizador SET criado_em='{start}',expira_em='{end}',usado_em='{used}' "
                          f"WHERE utilizador_id={int(user_id)} AND usado_em IS NOT NULL;")


def _load_dataset(folder):
    folder = Path(folder).resolve()
    manifest = json.loads((folder / "manifesto.json").read_text(encoding="utf-8"))
    if manifest.get("sintetico") is not True or manifest.get("versao") != 1:
        raise ValueError("É obrigatório um manifesto sintético v1.")
    for filename, expected in manifest["sha256"].items():
        path = (folder / filename).resolve()
        if not path.is_relative_to(folder) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Ficheiro não corresponde ao manifesto: " + filename)
    products = json.loads((folder / "produtos.json").read_text(encoding="utf-8"))
    if len(products) != 24:
        raise ValueError("A simulação NexoPlan exige exatamente 24 produtos.")
    return folder, manifest, products


def _historical_snapshots(folder):
    """Junção auditável por chave; nunca inventa quantidades nem muda disponibilidades."""
    rows, fields = {}, None
    for filename in ("historico_treino.csv", "historico_atual.csv"):
        reader = csv.DictReader(io.StringIO((folder / filename).read_text(encoding="utf-8-sig")), delimiter=";")
        fields = fields or reader.fieldnames
        if fields != reader.fieldnames:
            raise ValueError("Os históricos têm esquemas diferentes.")
        for row in reader:
            key = (row["produto"], row["periodo"], row["tipo"])
            if key in rows and row != rows[key]:
                raise ValueError("Históricos contraditórios: " + str(key))
            rows[key] = row
    snapshots = {}
    for last in ("2026-07", "2026-08"):
        year, month = map(int, last.split("-"))
        serial = year * 12 + month - 1

        def first_for(row):
            start = serial - (23 if row["tipo"] == "VENDAS" else 11)
            return f"{start // 12:04d}-{start % 12 + 1:02d}"

        selected = [row for key, row in sorted(rows.items()) if first_for(row) <= row["periodo"] <= last]
        output = io.StringIO(newline="")
        writer = csv.DictWriter(output, fieldnames=fields, delimiter=";", lineterminator="\n")
        writer.writeheader()
        writer.writerows(selected)
        snapshots["historico_" + last.replace("-", "_") + ".csv"] = output.getvalue().encode("utf-8")
    return snapshots


def _validate_accounts(accounts):
    expected = {a["key"]: a for a in ACCOUNTS}
    if len(accounts) != len(expected) or {a["key"] for a in accounts} != set(expected):
        raise ValueError("Fornece as dez contas fictícias de ACCOUNTS.")
    indexed = {a["key"]: a for a in accounts}
    for key, template in expected.items():
        account = indexed[key]
        if any(account.get(field) != template[field] for field in ("nome", "email", "papeis")):
            raise ValueError("Os metadados das contas devem corresponder a ACCOUNTS: " + key)
        password = account.get("password", "")
        if not isinstance(password, str) or len(password) < 12 or len(password.encode("utf-8")) > 72:
            raise ValueError("Password privada fora dos limites: " + key)
    return indexed


def populate_company(base, accounts, dataset, fixture_sql):
    """Povoa BD isolada vazia com apenas o bootstrap de ACCOUNTS[0].

    ``accounts`` contém as passwords privadas; o relatório devolvido nunca as inclui.
    ``fixture_sql`` deve apontar para a mesma BD isolada do backend ``base``. O
    orquestrador é responsável por isolar o Docker e validar o destino deste callback.
    """
    indexed = _validate_accounts(accounts)
    folder, manifest, products = _load_dataset(dataset)
    fixtures = _FixtureDates(fixture_sql)
    clients, people = {}, {}
    report = {"sintetico": True, "empresa": "NexoPlan", "corteAvaliacao": "2026-11-05T12:00:00Z",
              "contas": [], "produtos": [], "lotes": {}, "ciclos": [], "verificacoes": {}}
    try:
        admin = APIClient(base)
        admin.login(indexed["admin"]["email"], indexed["admin"]["password"])
        clients["admin"] = admin
        existing = admin.all("/api/v1/utilizadores")
        if (len(existing) != 1 or existing[0]["email"] != indexed["admin"]["email"]
                or set(existing[0]["papeis"]) != {"ADMIN"}):
            raise ValueError("A instalação isolada deve conter apenas o bootstrap ADMIN fictício.")
        for endpoint in ("produtos", "lotes", "execucoes-previsao", "cenarios", "planos", "avaliacoes", "backtests"):
            if admin.request("GET", f"/api/v1/{endpoint}?size=1")["totalElements"]:
                raise ValueError("Fixture recusada: a instalação já contém " + endpoint + ".")
        people["admin"] = existing[0]
        born = datetime(2024, 6, 20, 9, tzinfo=timezone.utc)
        fixtures.account(existing[0]["id"], indexed["admin"]["nome"], born, bootstrap=True)
        for offset, template in enumerate(ACCOUNTS[1:], start=1):
            key, account = template["key"], indexed[template["key"]]
            invite = admin.request("POST", "/api/v1/utilizadores", {k: account[k] for k in ("nome", "email", "papeis")})
            client = APIClient(base)
            client.request("POST", "/api/v1/auth/ativacao", {"token": invite["token"], "password": account["password"],
                                                           "confirmacao": account["password"]})
            invite = None
            people[key] = client.login(account["email"], account["password"])
            clients[key] = client
            fixtures.account(people[key]["id"], account["nome"], born + timedelta(hours=offset))

        planner, planner2 = clients["planeador"], clients["planeador2"]
        for product in products:
            created = planner.request("POST", "/api/v1/produtos", {k: product[k] for k in ("codigo", "nome", "unidade")})
            fixtures.register("produto", created["id"])
            fixtures.set("produto", created["id"], criado_em=born + timedelta(days=1))
            report["produtos"].append(created)

        generated = _historical_snapshots(folder)
        lots = report["lotes"]

        def upload(filename, kind, stamp, expected="VALIDADO", client=planner):
            content = generated.get(filename) if filename in generated else (folder / filename).read_bytes()
            try:
                lot = client.request("POST", "/api/v1/lotes", upload=(kind, Path(filename).name, content))
            except urllib.error.HTTPError as error:
                if error.code != 422 or expected != "REJEITADO":
                    raise
                problem = json.loads(error.read())
                if problem.get("code") != "LOTE_REJEITADO" or not str(problem.get("loteId", "")).isdigit():
                    raise ValueError("A API não devolveu o lote rejeitado esperado.") from None
                lot = client.request("GET", "/api/v1/lotes/" + str(problem["loteId"]))
            if lot["estado"] != expected:
                raise AssertionError(f"Lote {filename}: esperado {expected}, recebido {lot['estado']}.")
            fixtures.register("lote", lot["id"])
            columns = {"importado_em": stamp, "disponivel_em": stamp}
            if expected == "VALIDADO":
                columns["validado_em"] = stamp + timedelta(minutes=2)
            fixtures.set("lote", lot["id"], **columns)
            lot = client.request("GET", "/api/v1/lotes/" + lot["id"])
            lots[filename] = lot
            return lot

        cycle_sources = [("2026-06", "historico_treino.csv"), ("2026-07", "historico_2026_07.csv"),
                         ("2026-08", "historico_2026_08.csv"), ("2026-10", "historico_atual.csv")]
        for last, filename in cycle_sources:
            upload(filename, "HISTORICO", _last_day(last))
        outcome_date = datetime(2026, 11, 5, 9, tzinfo=timezone.utc)
        actual_lot = upload("realizados.csv", "REALIZADOS", outcome_date)
        op_lot = upload("operacionais.csv", "OPERACIONAL", outcome_date + timedelta(minutes=10), client=planner2)
        for offset, filename in enumerate(("invalidos/duplicados.csv", "invalidos/unidade_incompativel.csv")):
            if (folder / filename).exists():
                upload(filename, "HISTORICO", datetime(2026, 10, 30, 10, offset, tzinfo=timezone.utc), "REJEITADO")

        def forecast(last, filename, method, series="VENDAS"):
            cutoff = _last_day(last, 18, 30)
            params = ({"janela": 3, "multiplosPassos": "RECURSIVO"} if method == "MEDIA_MOVEL"
                      else {"variante": "AAN", "historicoMeses": 24, "recurso": "SEM_RECURSO"})
            execution = planner.request("POST", "/api/v1/execucoes-previsao", {
                "loteHistoricoId": lots[filename]["id"], "tipoSerie": series, "corteTemporal": _instant(cutoff),
                "ultimoPeriodoTreino": last, "horizonteMeses": 3, "metodo": method, "parametros": params})
            if execution["estado"] != "CONCLUIDA" or execution["totalLinhas"] != (72 if series == "VENDAS" else 24):
                raise AssertionError("Previsão não produziu as linhas esperadas: " + method + "/" + series)
            fixtures.register("execucao_previsao", execution["id"])
            start = cutoff + timedelta(minutes=10 if method == "MEDIA_MOVEL" else 15)
            fixtures.set("execucao_previsao", execution["id"], criado_em=start, concluido_em=start + timedelta(minutes=1))
            return planner.request("GET", "/api/v1/execucoes-previsao/" + execution["id"])

        for cycle_index, (last, filename) in enumerate(cycle_sources):
            cycle = {"treino": last, "execucoes": {}, "cenarios": {}}
            for method in ("MEDIA_MOVEL", "ETS"):
                cycle["execucoes"][method] = forecast(last, filename, method)
            source = cycle["execucoes"]["ETS" if cycle_index % 2 else "MEDIA_MOVEL"]
            tick = _last_day(last, 19)

            def clock():
                nonlocal tick
                stamp = tick
                tick += timedelta(seconds=30)
                return stamp

            def date_creation(case, author):
                stamp = clock()
                scenario, revision = case["cenario"], case["revisao"]
                fixtures.register("cenario", scenario["id"])
                fixtures.set("cenario", scenario["id"], criado_em=stamp)
                date_revision(revision, stamp, author)

            def date_revision(revision, stamp, author):
                fixtures.register("revisao_cenario", revision["id"])
                fixtures.set("revisao_cenario", revision["id"], criado_em=stamp)
                events = author.all("/api/v1/revisoes/" + revision["id"] + "/eventos")
                for event in events:
                    fixtures.register("evento_revisao", event["id"])
                    fixtures.set("evento_revisao", event["id"], ocorrido_em=stamp)

            def adjust(revision, editor, line_index, factor, reason):
                lines = editor.all("/api/v1/revisoes/" + revision["id"] + "/linhas")
                line = lines[line_index]
                if Decimal(line["quantidadeFinal"]) == 0:
                    line = next(candidate for candidate in lines if candidate["periodo"] == line["periodo"]
                                and Decimal(candidate["quantidadeFinal"]) > 0)
                amount = (Decimal(line["quantidadeFinal"]) * Decimal(factor)).quantize(Decimal(".0001"), rounding=ROUND_HALF_UP)
                result = editor.request("POST", "/api/v1/revisoes/" + revision["id"] + "/ajustes", {
                    "produtoId": line["produtoId"], "periodo": line["periodo"], "quantidade": str(amount),
                    "justificacao": reason, "versaoEsperada": revision["versao"]})
                event_id = result["ajuste"]["eventoId"]
                fixtures.register("evento_revisao", event_id)
                fixtures.set("evento_revisao", event_id, ocorrido_em=clock())
                return editor.request("GET", "/api/v1/revisoes/" + revision["id"])

            def transition(revision, action, actor, reason=None):
                payload = {"versaoEsperada": revision["versao"]}
                if reason:
                    payload["motivo"] = reason
                result = actor.request("POST", "/api/v1/revisoes/" + revision["id"] + "/" + action, payload)
                stamp = clock()
                kind = {"submissao": "SUBMISSAO", "devolucao": "DEVOLUCAO", "aprovacao": "APROVACAO"}[action]
                event = next(e for e in actor.all("/api/v1/revisoes/" + revision["id"] + "/eventos") if e["tipo"] == kind)
                fixtures.register("evento_revisao", event["id"])
                fixtures.set("evento_revisao", event["id"], ocorrido_em=stamp)
                column = "submetido_em" if action == "submissao" else "decidido_em"
                fixtures.set("revisao_cenario", revision["id"], **{column: stamp})
                return result if action == "aprovacao" else actor.request("GET", "/api/v1/revisoes/" + revision["id"])

            descriptions = {
                "BASE": ("1.00", "Continuidade das encomendas recorrentes; capacidade e preços contratados constantes. Ajustes documentam reservas comerciais e limites de armazenagem."),
                "OTIMISTA": ("1.10", "Hipótese fictícia de mais 10%: entrada gradual de dois distribuidores e campanha sazonal confirmada apenas neste cenário; capacidade adicional sujeita a validação."),
                "CONSERVADOR": ("0.90", "Hipótese fictícia de menos 10%: adiamento de encomendas de clientes industriais e reposição prudente para proteger tesouraria e reduzir excesso de stock."),
            }
            for kind, (factor, description) in descriptions.items():
                author = planner2 if kind == "CONSERVADOR" else planner
                case = author.request("POST", "/api/v1/cenarios", {"execucaoId": source["id"], "nome": f"NexoPlan {last} | {kind.title()}",
                    "tipo": kind, "fator": factor, "pressupostos": {"descricao": description}})
                date_creation(case, author)
                revision = case["revisao"]
                revision = adjust(revision, author, 0, "1.035" if kind != "CONSERVADOR" else "0.97",
                                  "Simulação fictícia: reserva de encomendas do distribuidor Norte ajusta a primeira referência; proposta validada pela equipa comercial.")
                revision = adjust(revision, planner2, 10, "0.98",
                                  "Simulação fictícia: reduzir reposição no segundo mês devido ao stock disponível e à capacidade limitada da zona de picking.")
                case["revisao"] = revision
                cycle["cenarios"][kind] = case

            base_case = cycle["cenarios"]["BASE"]
            base_revision = transition(base_case["revisao"], "submissao", planner)
            approver = clients["aprovador" if cycle_index % 2 == 0 else "aprovador2"]
            if cycle_index == 0:
                base_revision = transition(base_revision, "devolucao", approver,
                    "Simulação fictícia: confirmar a encomenda Norte e documentar a redução de stock antes de aprovar o trimestre julho-setembro.")
                base_case["revisaoDevolvida"] = base_revision
                base_revision = planner2.request("POST", "/api/v1/cenarios/" + base_case["cenario"]["id"] + "/revisoes", {
                    "revisaoOrigemId": base_revision["id"], "versaoOrigemEsperada": base_revision["versao"],
                    "pressupostos": {"descricao": descriptions["BASE"][1] + " Revisão corrigida: reserva Norte confirmada e cobertura de stock revista."}})
                date_revision(base_revision, clock(), planner2)
                base_revision = adjust(base_revision, planner2, 0, "1.01",
                    "Simulação fictícia: confirmação do cliente acrescenta 1% à reserva Norte; correção pedida na devolução, mantendo o histórico herdado.")
                base_revision = transition(base_revision, "submissao", planner2)
            approval = transition(base_revision, "aprovacao", approver)
            fixtures.register("plano_publicado", approval["planoId"])
            fixtures.set("plano_publicado", approval["planoId"], publicado_em=_last_day(last, 20))
            base_case["revisao"] = planner.request("GET", "/api/v1/revisoes/" + base_revision["id"])
            cycle["plano"] = planner.request("GET", "/api/v1/planos/" + approval["planoId"])
            # Fila real de trabalho: há rascunhos, revisões submetidas e uma devolução por corrigir.
            pending_kind = "CONSERVADOR" if cycle_index % 2 == 0 else "OTIMISTA"
            pending = cycle["cenarios"][pending_kind]
            pending["revisao"] = transition(pending["revisao"], "submissao", planner2 if pending_kind == "CONSERVADOR" else planner)
            if cycle_index == 2:
                pending["revisao"] = transition(pending["revisao"], "devolucao", clients["aprovador2"],
                    "Simulação fictícia: a redução global de 10% ignora a encomenda sazonal do Sul; rever pressupostos e stock antes de submeter nova revisão.")
            for case in cycle["cenarios"].values():
                case["cenario"] = planner.request("GET", "/api/v1/cenarios/" + case["cenario"]["id"])
            report["ciclos"].append(cycle)

        report["execucaoConsumo"] = forecast("2026-10", "historico_atual.csv", "MEDIA_MOVEL", "CONSUMO")
        assessment_cut = datetime(2026, 11, 5, 12, tzinfo=timezone.utc)
        for index, cycle in enumerate(report["ciclos"]):
            plan_lines = planner.all("/api/v1/planos/" + cycle["plano"]["id"] + "/linhas")
            months = sorted({line["periodo"] for line in plan_lines})
            assessment = planner.request("POST", "/api/v1/avaliacoes", {"planoId": cycle["plano"]["id"],
                "loteRealizadosId": actual_lot["id"], "loteOperacionalId": op_lot["id"],
                "periodoInicio": months[0], "periodoFim": months[-1], "corteRealizados": _instant(assessment_cut)})
            fixtures.register("avaliacao", assessment["id"])
            fixtures.set("avaliacao", assessment["id"], criado_em=assessment_cut + timedelta(minutes=5 + index))
            cycle["avaliacao"] = planner.request("GET", "/api/v1/avaliacoes/" + assessment["id"])
            cycle["precisao"] = planner.all("/api/v1/avaliacoes/" + assessment["id"] + "/precisao")
            cycle["operacao"] = planner.all("/api/v1/avaliacoes/" + assessment["id"] + "/operacao")
            cells = planner.all("/api/v1/avaliacoes/" + assessment["id"] + "/celulas")
            exclusions = {}
            for cell in cells:
                reason = cell["motivoExclusao"] or "ELEGIVEL_PRECISAO"
                exclusions[reason] = exclusions.get(reason, 0) + 1
            cycle["cobertura"] = {"candidatos": len(cells), "motivos": exclusions,
                "mesesComPrecisao": sorted({cell["periodo"] for cell in cells if cell["elegivelPrecisao"]}),
                "novembroNaoFechado": sum(cell["periodo"] == "2026-11" and
                    cell["motivoExclusao"] == "PERIODO_NAO_FECHADO" for cell in cells)}
            if last != "2026-10" and not any(cell["elegivelPrecisao"] for cell in cells):
                raise AssertionError("O plano histórico não contém pares MAE elegíveis.")
            exported = clients["consumidor"].request("GET", "/api/v1/planos/" + cycle["plano"]["id"] + "/exportacao.csv", raw=True)
            cycle["exportacao"] = {"sha256": hashlib.sha256(exported).hexdigest(),
                "linhas": len(list(csv.DictReader(io.StringIO(exported.decode("utf-8-sig")), delimiter=";")))}
            if cycle["exportacao"]["linhas"] != 72:
                raise AssertionError("Exportação não contém as 72 células do plano.")

        backtest = planner2.request("POST", "/api/v1/backtests", {"loteHistoricoId": lots["historico_atual.csv"]["id"],
            "tipoSerie": "VENDAS", "primeiroCorte": "2026-06", "ultimoCorte": "2026-07", "horizonteMeses": 3,
            "janelaMediaMovel": 3, "historicoEtsMeses": 24, "corteRealizados": _instant(assessment_cut)})
        if backtest["estado"] != "CONCLUIDO" or backtest["totalCelulas"] != 144:
            raise AssertionError("Backtest não contém os dois cortes e 144 células esperados.")
        fixtures.register("backtest", backtest["id"])
        fixtures.set("backtest", backtest["id"], criado_em=assessment_cut + timedelta(minutes=15),
                     concluido_em=assessment_cut + timedelta(minutes=16))
        report["backtest"] = planner.request("GET", "/api/v1/backtests/" + backtest["id"])
        report["comparacaoBacktest"] = planner.all("/api/v1/backtests/" + backtest["id"] + "/comparacao")

        # O ex-colaborador deixa um evento auditável e perde acesso; os restantes perfis ficam ativos.
        old = admin.request("GET", "/api/v1/utilizadores/" + people["antigo"]["id"])
        admin.request("PATCH", "/api/v1/utilizadores/" + old["id"] + "/estado", {"ativo": False, "versaoEsperada": old["versao"]})
        fixtures.callback("UPDATE evento_estado_utilizador SET alterado_em='2026-10-30 16:00:00' WHERE utilizador_id=" + str(int(old["id"])) + ";")
        for account in ACCOUNTS:
            user = admin.request("GET", "/api/v1/utilizadores/" + people[account["key"]]["id"])
            report["contas"].append({"key": account["key"], **user})
        report["verificacoes"] = {
            "contas": len(report["contas"]), "produtos": len(report["produtos"]), "lotes": len(lots),
            "execucoes": 9, "cenarios": 12, "planos": 4, "avaliacoes": 4, "backtests": 1,
            "sqlFixtureApenasDatasENomeBootstrap": True, "operacoesDatasFixture": fixtures.operations,
            "periodosRealizados": ["2026-07", "2026-08", "2026-09", "2026-10"],
            "novembroExcluido": sum(c["cobertura"]["novembroNaoFechado"] for c in report["ciclos"]) == 48,
            "credenciaisNoRelatorio": False,
        }
        if not report["verificacoes"]["novembroExcluido"]:
            raise AssertionError("Novembro deve estar excluído das métricas por período não fechado.")
        report["sha256Dataset"] = manifest["sha256"]
        report["sha256HistoricosDerivados"] = {name: hashlib.sha256(content).hexdigest() for name, content in generated.items()}
        return report
    finally:
        for client in clients.values():
            try:
                client.logout()
            except (urllib.error.URLError, TimeoutError):
                pass
