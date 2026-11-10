# Aplicação de Previsão de Procura e S&OP

Proposta inicial de arquitetura e base de dados para o MVP de Tiago Dias.
Já contém o backend inicial Spring Boot. A interface web e as funcionalidades de negócio serão implementadas nos próximos blocos.

- [Arquitetura e regras de integridade](docs/arquitetura.md)
- [Fases, endpoints, contratos e interface](docs/plano-implementacao.md)
- [Esquema SQL MySQL 8.4](database/migrations/V1__schema_inicial.sql)
- [Validação executada e respetivos limites](docs/validacao-inicial.md)

## Iniciar apenas a base de dados

```bash
# Executar na raiz deste repositório
cp .env.example .env
nano .env
docker compose up -d --wait db
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SHOW TABLES"'
```

Definir as duas passwords locais em `.env` antes de iniciar. O ficheiro está ignorado
pelo Git. A porta local predefinida é `3307`; a porta entre contentores é `3306`.
O SQL é aplicado automaticamente **apenas na inicialização de um volume vazio**.
Não o executar uma segunda vez na mesma base.

O backend ainda conserva a inicialização SQL do Compose. Antes da primeira evolução
do esquema, a gestão das migrações passará para Flyway, com um baseline explícito e
verificado da base existente. Não executar dois mecanismos em paralelo.
`spring.jpa.hibernate.ddl-auto=validate` impede alterações automáticas do esquema;
ainda não existem entidades JPA de negócio nesta etapa.

Não foram criados utilizadores de aplicação nem dados de demonstração na base persistente.
`MYSQL_USER` é aqui uma conta de desenvolvimento criada pelo entrypoint; na fase de
segurança separar a conta de migrações da conta de execução com permissões mínimas.

## Iniciar o backend

O projeto usa Java 17 e Spring Boot 4.1.2. Com o `.env` já configurado, executar na raiz:

```bash
docker compose up -d --build --wait
docker compose ps
curl http://localhost:8080/api/v1/status
curl http://localhost:8080/actuator/health
```

O primeiro endpoint responde `{"application":"sales-operations-planner"}`; o segundo
inclui `"status":"UP"` e verifica também a ligação ao MySQL; pode apresentar os grupos
`liveness` e `readiness`. A primeira construção
descarrega imagens e dependências. O backend fica em `127.0.0.1:8080`, configurável por
`BACKEND_PORT`. Não existe autenticação nesta etapa; os endpoints são para uso local.

Para consultar os logs e parar conservando os dados:

```bash
docker compose logs --tail=100 backend
docker compose stop
```

Para desenvolver diretamente com Maven e usar o MySQL do Compose:

```bash
docker compose stop backend
docker compose up -d --wait db
cd backend
./mvnw spring-boot:run
```

O Maven Wrapper requer JDK 17 ou superior e descarrega a sua versão de Maven no primeiro
uso. A aplicação lê o `.env` da raiz como propriedades; variáveis de ambiente têm
precedência. Terminar com `Ctrl+C`. Para regressar ao Docker, executar
`docker compose up -d --build --wait` na raiz.

## Testes do backend

```bash
cd backend
./mvnw verify
```

O teste de integração usa Testcontainers para iniciar um MySQL 8.4 temporário, aplicar
o SQL inicial, confirmar as 21 tabelas e verificar o endpoint de saúde por HTTP.
Não altera o volume de desenvolvimento. Requer Docker ativo.

Ver [primeiro marco do backend](docs/backend-primeiro-marco.md) para os ficheiros,
limites deste bloco e comandos Git. Autenticação, catálogo e importação vêm a seguir.
