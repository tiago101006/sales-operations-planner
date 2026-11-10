# Primeiro marco do backend

## Objetivo

Arrancar o backend, ligá-lo ao MySQL e disponibilizar um primeiro endpoint REST.
Este bloco inicia a fase 1; autenticação e catálogo ainda não estão implementados.

Projeto gerado pelo Spring Initializr com Spring Boot 4.1.2 e Java 17, compatível com
o JDK instalado. Maven Wrapper fixa a versão de Maven. Dependências: Web MVC,
Validation, Data JPA, Connector/J, Actuator e ferramentas de teste com Testcontainers.

## Ficheiros e responsabilidades

| Ficheiro | Responsabilidade |
|---|---|
| `backend/pom.xml` | Dependências e construção Maven |
| `backend/mvnw` | Maven Wrapper |
| `backend/src/main/java/pt/tiagodias/sop/SalesOperationsPlannerApplication.java` | Entrada da aplicação |
| `backend/src/main/java/pt/tiagodias/sop/status/StatusController.java` | Primeiro controller REST e DTO Java record |
| `backend/src/main/resources/application.properties` | Ligação MySQL, UTC e saúde |
| `backend/src/test/java/pt/tiagodias/sop/` | Integração com MySQL temporário |
| `backend/Dockerfile` | Construção Maven e execução JRE sem privilégios |
| `compose.yaml` | Backend depende de MySQL saudável |

## Contratos

`GET /api/v1/status` responde HTTP 200:

```json
{"application":"sales-operations-planner"}
```

Identifica a aplicação e demonstra controller -> DTO -> JSON. Não é um teste de base
de dados. `GET /actuator/health` faz a verificação de saúde, incluindo ligação MySQL:

```json
{"status":"UP"}
```

Uma falha de saúde produz o estado e código HTTP definidos pelo Actuator, normalmente
503. Só está exposto o endpoint `health`, sem detalhes internos dos componentes.
Em Docker, a resposta pode também listar os grupos `liveness` e `readiness`.

O backend é publicado apenas em localhost. Autenticação e autorização serão
implementadas antes de expor operações de negócio.

## Esquema e configuração

Conservado o volume MySQL existente. O backend não altera o esquema; por agora mantém-se
a inicialização SQL do Compose. Flyway entra antes da primeira alteração ao esquema,
com baseline explícito e verificado; não ativar `baseline-on-migrate` permanentemente.

Não existem ainda entidades JPA. `ddl-auto=validate` fica preparado para validar os
futuros mapeamentos. O teste usa a mesma V1 de `database/migrations` como recurso,
sem uma segunda cópia. `@ServiceConnection` fornece credenciais do MySQL temporário.

Credenciais reais são lidas de `.env` ou variáveis de ambiente, não incluídas na imagem
nem nos contratos HTTP. A ligação JDBC sem TLS destina-se ao ambiente local.

## Verificar e executar

```bash
cd backend
./mvnw verify
cd ..
docker compose up -d --build --wait
curl --fail http://localhost:8080/api/v1/status
curl --fail http://localhost:8080/actuator/health
```

O teste verifica 21 tabelas e saúde HTTP com MySQL real temporário. O Compose exercita
a imagem construída e a base de desenvolvimento. Ainda não foram testadas funcionalidades
de negócio, autorização, previsões, aprovação concorrente ou indicadores.

Verificado em 10 de novembro de 2026: `./mvnw verify` com um teste aprovado,
construção e arranque da imagem pelo Compose, ambos os serviços `healthy` e
os dois endpoints HTTP com resposta 200. O `.env` e `backend/target/` permanecem
ignorados pelo Git. Não foram criados commits nem enviados ficheiros pelo assistente.

## Guardar no GitHub

Executar na raiz, depois de rever as alterações:

```bash
git add backend compose.yaml .env.example README.md docs/backend-primeiro-marco.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat: iniciar backend Spring Boot com MySQL e verificacao de saude"
git push
```

## Próximo bloco

Flyway com adoção verificada da base existente, autenticação e perfis, seguido do
catálogo de produtos. O atual utilizador MySQL é de desenvolvimento; a separação
entre contas de migração e execução faz parte dessa evolução.

## Fontes técnicas

- [Spring Initializr](https://start.spring.io/)
- [Spring Boot: requisitos](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot: Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
