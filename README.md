# NexoPlan

Aplicação web de previsão de procura e planeamento de vendas e operações (S&OP), desenvolvida por Tiago Dias. Importa históricos CSV, compara Média Móvel e ETS, permite preparar cenários com ajustes auditados e publica planos após aprovação. A monitorização acompanha MAE, MAPE, nível de serviço, ruturas e excesso de stock.

O Spring Boot serve a interface e a API REST. O MySQL guarda os dados de planeamento e o Flyway gere as migrações.

## Iniciar localmente

Na raiz do repositório, com Docker e Docker Compose disponíveis:

```bash
cp -n .env.example .env
```

Define `MYSQL_PASSWORD` e `MYSQL_ROOT_PASSWORD` em `.env` e inicia os serviços:

```bash
docker compose up -d --build --wait
curl --fail http://localhost:8080/actuator/health
```

Numa instalação nova, cria a primeira conta de administrador:

```bash
bash scripts/criar_admin.sh
```

Abre [http://localhost:8080](http://localhost:8080) e entra com essa conta. As portas locais podem ser configuradas com `BACKEND_PORT` e `MYSQL_PORT` em `.env`.

```bash
docker compose logs --tail=100 backend
docker compose stop
```

O comando `stop` conserva os dados nos volumes Docker.

## Desenvolver e testar

Os testes do backend requerem Java 17 e Docker. O Maven Wrapper constrói o JAR e executa os testes com MySQL temporário por Testcontainers.

```bash
(cd backend && ./mvnw verify)
```

Os testes da interface requerem Node.js 20 ou superior e Python 3. Para os testes no navegador, instala o Chromium do Playwright se não tiveres o Chrome local.

```bash
(cd frontend-tests && npm ci && npm test)
(cd frontend-tests && npx playwright install chromium)
python3 scripts/testar_interface.py
```

O ensaio Playwright usa uma instância isolada da aplicação e uma base de dados temporária. Consulta o [guia de testes da interface](docs/interface-web.md) para usar o Chrome local ou configurar outro executável.

## Estrutura

| Pasta | Conteúdo |
| --- | --- |
| [`backend/`](backend/) | Backend Spring Boot, interface web, migrações Flyway e testes Java |
| [`frontend-tests/`](frontend-tests/) | Testes unitários, fluxos Playwright e ensaios de desempenho |
| [`data/`](data/) | Dados sintéticos e exemplos CSV |
| [`scripts/`](scripts/) | Comandos para instalação, demonstração, teste, backup e restauro |
| [`docs/`](docs/) | Manual, arquitetura, contratos da API e evidência de validação |

As [migrações SQL](backend/src/main/resources/db/migration/) são a fonte do esquema. Para alterar o esquema, cria uma migração numerada. Consulta o [guia Flyway](docs/flyway.md) se precisares de adotar uma base anterior à integração.

## Documentação

- [Manual de utilização](docs/manual-utilizacao.md), [painéis por perfil](docs/paineis-perfil.md) e [pesquisa e filtros](docs/pesquisa-filtros.md).
- [Arquitetura e modelo relacional](docs/arquitetura.md), [autenticação](docs/autenticacao.md) e [catálogo](docs/catalogo.md).
- [Importação CSV](docs/importacao-historicos.md), [Média Móvel](docs/previsao-media-movel.md), [ETS](docs/previsao-ets.md) e [comparação de modelos](docs/backtesting.md).
- [Cenários e ajustes](docs/cenarios-ajustes.md), [aprovação e publicação](docs/aprovacao-publicacao.md) e [monitorização](docs/monitorizacao.md).
- [Dados de demonstração](docs/dados-demonstracao.md), [backup e restauro](docs/backup-restauro.md) e [plano de adoção](docs/plano-adocao.md).
- [Validação funcional](docs/validacao-piloto.md), [desempenho](docs/desempenho.md) e [protocolo de usabilidade](docs/usabilidade.md).
