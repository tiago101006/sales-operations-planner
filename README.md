# NexoPlan

Aplicação web de previsão de procura e planeamento de vendas e operações (S&OP), desenvolvida por Tiago Dias. Importa históricos CSV, compara Média Móvel e ETS, permite preparar cenários com ajustes auditados e publica planos após aprovação. A monitorização acompanha MAE, MAPE, nível de serviço, ruturas e excesso de stock.

O Spring Boot serve a interface e a API REST. O MySQL guarda os dados de planeamento e o Flyway gere as migrações.

## Iniciar localmente

Requer Docker com Docker Compose e Python 3. Na pasta do projeto, executa:

```bash
./nexoplan
```

O comando compila as alterações, inicia o MySQL e o backend, espera que estejam
prontos e abre o site no navegador. Podes repeti-lo mesmo com os serviços iniciados.
A primeira compilação pode demorar mais. Não precisas de abrir o VS Code para usar o site.

Para teres uma entrada no menu de aplicações do Linux, instala o atalho uma vez:

```bash
./nexoplan instalar-atalho
```

Depois, pesquisa **NexoPlan** no menu de aplicações. O atalho inicia os serviços e
abre o site. Se mudares a pasta do projeto, volta a instalar o atalho.

Outros comandos:

```bash
./nexoplan parar             # Para os serviços e conserva os dados
./nexoplan estado            # Mostra o estado dos serviços
./nexoplan logs              # Acompanha as mensagens de diagnóstico (Ctrl+C para sair)
./nexoplan --sem-navegador    # Inicia sem abrir o navegador
```

O endereço habitual é [http://127.0.0.1:8080](http://127.0.0.1:8080). O lançador
deteta a porta configurada e mostra o endereço correto no terminal.

### Primeira instalação

Numa máquina nova, prepara primeiro a configuração local:

```bash
cp -n .env.example .env
```

Define `MYSQL_PASSWORD` e `MYSQL_ROOT_PASSWORD` em `.env` e inicia:

```bash
./nexoplan
```

Numa instalação nova, cria a primeira conta de administrador:

```bash
bash scripts/criar_admin.sh
```

Entra no site com essa conta. As portas locais podem ser configuradas com
`BACKEND_PORT` e `MYSQL_PORT` em `.env`. Uma instalação com volumes ou credenciais
locais próprios pode usar `compose.override.yaml`, que o Compose carrega
automaticamente e que fica fora do Git. Conserva esse ficheiro juntamente com a
configuração privada da máquina. O arranque não cria contas nem importa dados.
Se existir `.nexoplan-local.json`, conserva-o com o override: o lançador usa-o para
confirmar que está a abrir os volumes corretos e recusa o arranque se a configuração
esperada desaparecer.

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

- [Revisão de segurança e preparação da instalação pública](docs/seguranca.md).

- [Manual de utilização](docs/manual-utilizacao.md), [painéis por perfil](docs/paineis-perfil.md) e [pesquisa e filtros](docs/pesquisa-filtros.md).
- [Arquitetura e modelo relacional](docs/arquitetura.md), [autenticação](docs/autenticacao.md) e [catálogo](docs/catalogo.md).
- [Importação CSV](docs/importacao-historicos.md), [Média Móvel](docs/previsao-media-movel.md), [ETS](docs/previsao-ets.md) e [comparação de modelos](docs/backtesting.md).
- [Cenários e ajustes](docs/cenarios-ajustes.md), [aprovação e publicação](docs/aprovacao-publicacao.md) e [monitorização](docs/monitorizacao.md).
- [Dados de demonstração](docs/dados-demonstracao.md), [simulação de uma empresa NexoPlan](docs/simulacao-empresa.md), [backup e restauro](docs/backup-restauro.md) e [plano de adoção](docs/plano-adocao.md).
- [Validação funcional](docs/validacao-piloto.md), [desempenho](docs/desempenho.md) e [protocolo de usabilidade](docs/usabilidade.md).
