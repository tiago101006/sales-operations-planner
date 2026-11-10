# Integração do Flyway

## O que muda

O Flyway passa a ser o único mecanismo de criação e evolução do esquema. A V1 foi
movida, sem alterar o SQL, para `backend/src/main/resources/db/migration/`.
Retirada a montagem de inicialização SQL do Compose. O Spring Boot aplica migrações
antes de disponibilizar a aplicação e o Hibernate mantém `ddl-auto=validate`.

Incluídos `spring-boot-starter-flyway` e o módulo MySQL. As versões são as geridas
pelo parent Spring Boot. Baseline automático está desativado; `clean` está bloqueado
na aplicação e no plugin Maven. Não foi acrescentada uma V2 sem alteração de negócio.

## Duas situações

**Base vazia:** o Flyway cria `flyway_schema_history` e executa V1, registada como
`SQL`, com checksum. Um segundo arranque não reaplica V1.

**Base existente:** o script de adoção compara os `SHOW CREATE TABLE` das tabelas com
uma instância temporária criada a partir da V1. Apenas o contador de AUTO_INCREMENT
é excluído da comparação. Diferenças de tabelas, colunas, índices, FKs ou CHECKs
impedem a adoção. Depois de parar o backend, volta a verificar a estrutura, cria
backup local com permissões 0600 e executa o comando explícito `flyway:baseline`.
A base é marcada com versão 1 e tipo `BASELINE`; os dados de negócio não são alterados.
O script compara também o hash do dump de dados antes e depois.

Um baseline não executa V1 e não guarda o checksum dessa migração como uma execução
SQL. A correspondência inicial é assegurada pela comparação de estrutura, não por
`validate` do Flyway. As migrações SQL seguintes terão o seu checksum normal.
Esta ferramenta destina-se ao ambiente local do Compose, sem outros escritores.

## Comandos

Na raiz do projeto, para uma instalação antiga ainda sem histórico:

```bash
python3 scripts/adotar_flyway.py
python3 scripts/adotar_flyway.py --baseline
docker compose up -d --build --wait
```

Sem `--baseline`, o script apenas compara a estrutura. Com histórico existente,
apresenta o histórico e não tenta adotar outra vez. Um erro exige analisar a causa;
não ativar `baseline-on-migrate` nem executar `repair` para o ocultar.
O backup fica em `tmp/backups/`, ignorado pelo Git. Em caso de falha depois de parar
o backend, este fica parado para permitir diagnóstico; os dados não são eliminados.

Consultar o histórico:

```bash
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT installed_rank,version,description,type,success FROM flyway_schema_history;"'
```

Para futuras alterações, criar, por exemplo,
`backend/src/main/resources/db/migration/V2__descricao_da_alteracao.sql`.
Não modificar V1 nem outras migrações já aplicadas. Depois de testar, reconstruir
o backend. Não é necessário apagar volumes ou executar SQL manualmente.

## Verificação

`cd backend && ./mvnw verify` verifica num MySQL temporário:

- Criação das 21 tabelas mais histórico por Flyway numa base vazia.
- Validação de checksum e recusa de alteração do valor registado.
- Recusa de base não vazia sem baseline explícito.
- Adoção preservando um produto existente e repetição sem reaplicar V1.
- Saúde da aplicação por HTTP.

Em 10 de novembro de 2026, `./mvnw verify` concluiu com 3 testes aprovados.
A estrutura das 21 tabelas do Compose foi comparada com a V1 sem divergências,
foi criado backup local privado e foi registado baseline de versão 1. O hash dos
dados de negócio antes/depois foi igual. A repetição do script reconheceu o histórico
existente sem criar outro baseline.
O Compose foi reconstruído e ambos os serviços ficaram saudáveis. Nos logs, o Flyway
reconheceu a versão 1 e não reaplicou V1. Os endpoints de estado e saúde responderam
com HTTP 200. A base mantém 21 tabelas de negócio, mais a tabela técnica do histórico.

## Git

Na raiz do projeto:

```bash
git add backend compose.yaml database scripts README.md docs
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: gerir esquema MySQL com Flyway e baseline verificado"
git push
```

## Referências

- [Spring Boot: inicialização de dados](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Flyway: baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)
- [Flyway: MySQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)
