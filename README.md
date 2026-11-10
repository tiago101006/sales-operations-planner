# Aplicação de Previsão de Procura e S&OP

Proposta inicial de arquitetura e base de dados para o MVP de Tiago Dias.
Ainda não contém o backend nem a interface web.

- [Arquitetura e regras de integridade](docs/arquitetura.md)
- [Fases, endpoints, contratos e interface](docs/plano-implementacao.md)
- [Esquema SQL MySQL 8.4](database/migrations/V1__schema_inicial.sql)
- [Validação executada e respetivos limites](docs/validacao-inicial.md)

## Iniciar apenas a base de dados

```bash
cd '/home/tiago/Documents/ChatGPT/Projeto 1'
cp .env.example .env
nano .env
docker compose up -d --wait db
docker compose exec db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SHOW TABLES"'
```

Definir as duas passwords locais em `.env` antes de iniciar. O ficheiro está ignorado
pelo Git. A porta local predefinida é `3307`; a porta entre contentores é `3306`.
O SQL é aplicado automaticamente **apenas na inicialização de um volume vazio**.
Não o executar uma segunda vez na mesma base.

Quando existir o backend, a gestão das migrações passa para Flyway; retirar a montagem
em `/docker-entrypoint-initdb.d` e colocar a migração em
`backend/src/main/resources/db/migration/`. Uma base já inicializada por este Compose
precisará de um baseline Flyway revisto antes dessa transição. Não ativar um baseline
automático para ocultar diferenças de esquema. `spring.jpa.hibernate.ddl-auto=validate`.

Não foram criados utilizadores de aplicação nem dados de demonstração na base persistente.
`MYSQL_USER` é aqui uma conta de desenvolvimento criada pelo entrypoint; na fase de
segurança separar a conta de migrações da conta de execução com permissões mínimas.

## Guardar esta fase no GitHub

O repositório estava sem commits e sem remoto na preparação desta proposta. A identidade
Git configurada era Tiago Dias, com o endereço noreply do GitHub. Não foi alterada.

Criar primeiro um repositório vazio no GitHub e executar:

```bash
git config --get user.name
git config --get user.email
git add .gitignore .env.example compose.yaml README.md database docs
git diff --cached --check
git diff --cached --stat
git commit -m "docs: definir arquitetura e esquema relacional inicial de SOP"
git branch -M main
read -r -p 'URL SSH ou HTTPS do teu repositório GitHub: ' SOP_REMOTE_URL
git remote add origin "$SOP_REMOTE_URL"
git push -u origin main
```

Não foi executado `git add`, `git commit` ou `git push` pelo assistente.
