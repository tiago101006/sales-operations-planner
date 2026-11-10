# Aplicação de Previsão de Procura e S&OP

Projeto de Tiago Dias: backend Java 17 / Spring Boot 4.1.2, MySQL 8.4,
Docker Compose, migrações Flyway e autenticação por sessão com perfis. Inclui catálogo de produtos protegido por perfis; importação e planeamento vêm nos próximos blocos.

## Iniciar

Executar na raiz deste repositório. Na primeira configuração:

```bash
cp -n .env.example .env
```

Editar as passwords em `.env` e iniciar:

```bash
docker compose up -d --build --wait
docker compose ps
curl http://localhost:8080/api/v1/status
curl http://localhost:8080/actuator/health
```

Uma base vazia é criada automaticamente pelo Flyway. Uma base criada antes desta
integração precisa de adoção explícita, descrita abaixo. O Hibernate apenas valida
os mapeamentos (`ddl-auto=validate`); não cria nem altera tabelas. O Compose já não
monta scripts em `/docker-entrypoint-initdb.d`.

O endpoint de identificação responde `{"application":"sales-operations-planner"}`.
O endpoint de saúde inclui `"status":"UP"` e verifica a ligação ao MySQL; pode listar
os grupos `liveness` e `readiness`. Backend publicado em `127.0.0.1:8080`, MySQL em
`127.0.0.1:3307`. Autenticação por sessão com CSRF, para desenvolvimento local.

```bash
docker compose logs --tail=100 backend
docker compose stop
```

`stop` conserva os dados. `BACKEND_PORT` e `MYSQL_PORT` permitem alterar as portas
publicadas; entre contentores mantêm-se as portas internas. `.env` não entra no Git.

## Criar a primeira conta e testar login

Depois de construir/iniciar o backend:

```bash
bash scripts/criar_admin.sh
python3 scripts/testar_login.py
```

A primeira conta recebe apenas ADMIN e só pode ser criada se não existir nenhum
utilizador. Email e password são introduzidos no terminal, sem passwords no código.
Ver [autenticação e contratos](docs/autenticacao.md) para os endpoints e perfis.

## Catálogo de produtos

```bash
python3 scripts/testar_catalogo.py
```

Autenticar com a conta criada. O script lista os produtos e permite criar um por
escolha explícita. ADMIN e PLANEADOR podem criar/alterar; os cinco perfis podem
consultar. Não há produtos de demonstração inseridos automaticamente.
Ver [contratos e regras do catálogo](docs/catalogo.md).

A migração V2 acrescenta o controlo de versão por produto. O Flyway aplica-a no
arranque, preservando contas e dados. Código e unidade ficam fixos após uso.

## Adotar a base anterior ao Flyway

Na instalação deste projeto, a base já foi verificada e adotada. Numa outra instalação
que ainda tenha as 21 tabelas antigas sem histórico Flyway:

```bash
docker compose up -d --wait db
python3 scripts/adotar_flyway.py
python3 scripts/adotar_flyway.py --baseline
docker compose up -d --build --wait
```

O script compara a estrutura com V1 num MySQL temporário, recusa divergências, para
o backend e guarda um backup privado em `tmp/backups/` antes de registar V1 como
baseline. Confirma que os dados de negócio não mudaram. Requer JDK 17, Python 3 e
Docker. Não cria outro baseline se a tabela de histórico já existir. Não usar este
procedimento num servidor com outras aplicações a escrever na mesma base.

## Executar pelo Maven

```bash
docker compose stop backend
docker compose up -d --wait db
cd backend
./mvnw spring-boot:run
```

A aplicação lê `.env` na raiz como propriedades; variáveis de ambiente têm precedência.
Requer JDK 17 ou superior; o Wrapper descarrega Maven no primeiro uso. Parar com Ctrl+C.

## Testes

```bash
cd backend
./mvnw verify
```

Os testes usam MySQL temporário por Testcontainers: criação de uma base vazia pelo
Flyway, saúde HTTP, deteção de checksum alterado, recusa de base não vazia sem baseline,
adoção explícita com preservação de dados e repetição sem reaplicar migrações.
Não alteram os dados do volume de desenvolvimento. Requerem Docker ativo.

## Migrações

A única V1 está em `backend/src/main/resources/db/migration/V1__schema_inicial.sql`.
As próximas alterações criam V2, V3, etc. Não editar migrações já aplicadas.
Existem 21 tabelas de negócio e uma tabela técnica `flyway_schema_history`.
As versões de Flyway e Connector/J são geridas pelo parent Spring Boot.

## Documentação

- [Arquitetura](docs/arquitetura.md)
- [Plano por fases](docs/plano-implementacao.md)
- [SQL inicial](backend/src/main/resources/db/migration/V1__schema_inicial.sql)
- [Integração Flyway](docs/flyway.md)
- [Autenticação por sessão](docs/autenticacao.md)
- [Catálogo de produtos](docs/catalogo.md)
- [Marco anterior do backend](docs/backend-primeiro-marco.md)
