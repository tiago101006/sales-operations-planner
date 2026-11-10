# Aplicação de Previsão de Procura e S&OP

Projeto de Tiago Dias: backend Java 17 / Spring Boot 4.1.2, MySQL 8.4,
Docker Compose, migrações Flyway e autenticação por sessão com perfis. Inclui catálogo, importação de históricos/realizados/operacionais, previsões por Média Móvel e ETS(A,A,N), backtesting, cenários/revisões/ajustes auditados, aprovação/publicação por API e CSV e monitorização com MAE/MAPE, cobertura e indicadores operacionais. Interface gráfica e avaliação final seguem nos próximos blocos.

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

## Importar históricos CSV

```bash
python3 scripts/testar_importacao.py
```

Usar a conta ADMIN existente. Enter no caminho seleciona o exemplo sintético para
SKU001 (24 meses); Enter no lote anterior cria um lote novo. O produto deve estar
ativo e ter unidade `un`. O script mostra lote e observações ou os diagnósticos de
rejeição. Nenhum histórico é importado automaticamente.

Os originais ficam num volume Docker próprio, `sop_imports`; as observações e os
metadados ficam no MySQL. Uma cópia completa inclui ambos. Ver
[contratos, limites e exemplos](docs/importacao-historicos.md).

## Gerar a primeira previsão

```bash
python3 scripts/testar_previsao.py
```

Autenticar com ADMIN ou PLANEADOR e escolher um lote histórico validado. O script
propõe VENDAS, último mês completo, janela 3 e horizonte 3. Guarda uma execução nova,
mostra as previsões/diagnósticos e faz logout. Não altera o histórico nem execuções
anteriores. Meses em falta na janela não são preenchidos com zero.

Ver [Média Móvel: algoritmo, contratos e exemplo SKU001](docs/previsao-media-movel.md).

## Previsão ETS

```bash
python3 scripts/testar_ets.py
```

O script propõe ETS(A,A,N), até 24 meses de treino e SEM_RECURSO. São necessárias
pelo menos 6 observações consecutivas. Coeficientes e estados aprendidos ficam
visíveis nos diagnósticos. O recurso a Média Móvel é opcional e explicitamente
identificado. A variante deste bloco não tem sazonalidade.

Ver [algoritmo, referência independente e contratos ETS](docs/previsao-ets.md).

## Comparar os modelos por backtesting

```bash
python3 scripts/testar_backtest.py
```

O script propõe cortes históricos para o lote escolhido, compara os mesmos pares
produto/período/horizonte e mostra MAE, MAPE e cobertura. Os resultados são simulações
retrospectivas separadas das execuções operacionais. A migração V3 acrescenta duas
tabelas próprias e preserva o catálogo, históricos e previsões existentes.

Ver [contratos e interpretação do backtesting](docs/backtesting.md).

## Cenários e ajustes manuais

```bash
python3 scripts/testar_cenarios.py
```

O script usa uma execução operacional concluída para criar cenários otimista (+10%)
e conservador (−10%). Permite ajustar uma célula com justificação, copia a revisão
e verifica a auditoria herdada e a comparação. Enter usa os valores propostos;
ficam guardados dois cenários de ensaio, uma revisão adicional e um ajuste.
A previsão base é preservada. Não altera o esquema existente.

Ver [contratos, concorrência e auditoria das revisões](docs/cenarios-ajustes.md).

## Aprovar e publicar planos

```bash
python3 scripts/testar_aprovacao.py
```

O ensaio pede a conta ADMIN existente e outra conta APROVADOR. Na primeira utilização,
permite criar essa segunda conta, com email/password escolhidos no terminal; nas
seguintes, escolher `n` para usar a conta já criada. ADMIN não aprova automaticamente.

Cria um cenário de ensaio, submete, devolve, corrige numa revisão nova e aprova com
a segunda conta. Verifica o congelamento, a recusa de aprovação repetida e a igualdade
entre todas as linhas da API e o CSV, guardado em `tmp/exports/plano-<id>.csv`.
Planos publicados ficam disponíveis aos cinco perfis, incluindo CONSUMIDOR.

Ver [estados, contratos e separação de funções](docs/aprovacao-publicacao.md).

## Monitorizar um plano

```bash
python3 scripts/testar_monitorizacao.py
```

Usar ADMIN/PLANEADOR e selecionar o plano publicado. O script importa dois CSV
sintéticos de meses fechados e cria uma avaliação com lotes/corte explícitos.
Mostra precisão, operação e motivos de exclusão. Se o plano ainda não tiver meses
fechados, cobertura 0 e métricas `null` são esperadas; não se fabricam realizados
futuros. CSV de ensaio ficam em `tmp/monitorizacao/`.

Ver [contratos, fórmulas e regras temporais](docs/monitorizacao.md).

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
Incluem ainda autenticação/perfis, importações, previsões, backtesting, cenários e
workflow de aprovação: concorrência, rollback integral, auditoria herdada e
equivalência entre API e CSV de planos publicados.
Na monitorização verificam o caso de aceitação MAE/MAPE, exclusões por corte e
publicação tardia, ponderação operacional, faltas/zeros e preservação de avaliações.

## Migrações

A única V1 está em `backend/src/main/resources/db/migration/V1__schema_inicial.sql`.
As próximas alterações criam V2, V3, etc. Não editar migrações já aplicadas.
A V3 acrescenta duas tabelas de backtesting às 21 tabelas iniciais; existe ainda
a tabela técnica `flyway_schema_history`.
As versões de Flyway e Connector/J são geridas pelo parent Spring Boot.

## Documentação

- [Arquitetura](docs/arquitetura.md)
- [Plano por fases](docs/plano-implementacao.md)
- [SQL inicial](backend/src/main/resources/db/migration/V1__schema_inicial.sql)
- [Integração Flyway](docs/flyway.md)
- [Autenticação por sessão](docs/autenticacao.md)
- [Catálogo de produtos](docs/catalogo.md)
- [Importação de históricos](docs/importacao-historicos.md)
- [Previsão por Média Móvel](docs/previsao-media-movel.md)
- [Previsão ETS(A,A,N)](docs/previsao-ets.md)
- [Backtesting e métricas](docs/backtesting.md)
- [Cenários, revisões e ajustes auditados](docs/cenarios-ajustes.md)
- [Aprovação e publicação por API/CSV](docs/aprovacao-publicacao.md)
- [Monitorização e importação de resultados](docs/monitorizacao.md)
- [Marco anterior do backend](docs/backend-primeiro-marco.md)
