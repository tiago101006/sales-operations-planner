# Autenticação por sessão e perfis

## Funcionamento

Spring Security autentica contas guardadas em `utilizador` e carrega os papéis de
`utilizador_papel`. O mapeamento JPA é validado contra o esquema existente. Não houve
alteração à V1 nem foi necessária uma V2.

Passwords são guardadas como hashes BCrypt, custo 12. Novas passwords exigem pelo
menos 12 caracteres e no máximo 72 bytes UTF-8; não são cortadas silenciosamente.
Os DTOs nunca devolvem password ou hash. O email é normalizado para minúsculas.

A sessão usa cookie JSESSIONID HttpOnly e SameSite=Lax, expira após 30 minutos sem
atividade e muda de ID ao fazer login. Não se usa JWT nem HTTP Basic. Os pedidos de
escrita, incluindo login e logout, exigem CSRF. O token deve ser obtido outra vez
depois do login e logout. Cookies e token pertencem à mesma sessão.

O estado ativo e os papéis são novamente consultados em cada pedido autenticado:
uma conta desativada perde a sessão; papéis revogados deixam de autorizar o pedido
seguinte. As sessões estão em memória e terminam ao reiniciar o backend.

`SESSION_COOKIE_SECURE=false` destina-se ao HTTP local. Com HTTPS deve ser `true`.
Ainda não existem frontend, recuperação de password, MFA, limitação de tentativas
ou persistência de sessões entre instâncias; esta entrega destina-se ao ambiente local.

## Primeira conta ADMIN

Primeiro construir/iniciar o ambiente:

```bash
docker compose up -d --build --wait
bash scripts/criar_admin.sh
```

Introduzir o email e uma password própria no terminal. A password é pedida sem eco,
confirmada e transmitida ao processo por variáveis de ambiente, sem ficar no histórico
de comandos nem em ficheiros do repositório. Não partilhar a password no chat.
O perfil `bootstrap-admin` executa apenas em modo sem servidor web e termina depois
da operação. Se já existir qualquer utilizador, recusa criar outra primeira conta.
A verificação e criação decorrem numa transação SERIALIZABLE.

O bootstrap atribui apenas ADMIN. Esse papel permite criar e consultar contas;
não concede automaticamente APROVADOR, PLANEADOR nem os restantes papéis.
Não existe registo público de contas. As restantes contas são criadas por ADMIN.

## Contratos

| Método e rota | Acesso | Resultado |
|---|---|---|
| `GET /api/v1/auth/csrf` | Público | Header e token CSRF da sessão |
| `POST /api/v1/auth/login` | Público + CSRF | Autentica e guarda a sessão |
| `GET /api/v1/auth/me` | Autenticado | ID, nome, email e papéis |
| `POST /api/v1/auth/logout` | CSRF | HTTP 204, invalida sessão |
| `POST /api/v1/utilizadores` | ADMIN + CSRF | HTTP 201, nova conta |
| `GET /api/v1/utilizadores/{id}` | ADMIN | DTO da conta ou 404 |

Os endpoints de estado e saúde continuam públicos. Os restantes pedidos exigem
autenticação; as rotas de utilizadores exigem ADMIN. A autorização das futuras
operações de planeamento será acrescentada ao implementar cada módulo.

CSRF devolve, por exemplo:

```json
{"headerName":"X-CSRF-TOKEN","token":"token-da-sessao"}
```

Enviar `Content-Type: application/json`, o cookie de sessão e o header indicado nas
operações de escrita. Login:

```json
{"email":"tiago@example.test","password":"password-definida-localmente"}
```

Resposta de login e `/me`:

```json
{"id":"1","nome":"Administrador","email":"tiago@example.test","papeis":["ADMIN"]}
```

Criação de uma conta por ADMIN:

```json
{
  "nome":"Planeador de procura",
  "email":"planeador@example.test",
  "password":"password-propria-com-12-ou-mais-caracteres",
  "papeis":["PLANEADOR"]
}
```

Papéis disponíveis: ADMIN, PLANEADOR, COMERCIAL, APROVADOR e CONSUMIDOR.
Sem endpoints para alterar contas ou papéis neste bloco. A reavaliação por pedido
já impede que uma futura alteração conserve privilégios numa sessão antiga.

Erros: 401 para ausência de sessão ou credenciais inválidas; 403 para papel inadequado
ou CSRF ausente/inválido; 422 para validação; 409 para email duplicado; 400 para JSON
malformado ou papel desconhecido. Credenciais erradas, conta desconhecida e conta
desativada produzem a mesma mensagem de login. Erros não incluem valores de passwords.

## Experimentar sem gerir cookies manualmente

```bash
python3 scripts/testar_login.py
```

O script pede o URL, email e password; obtém CSRF, faz login, consulta `/me` e faz
logout. Cookies e token ficam apenas em memória. O URL local por defeito é
`http://localhost:8080`.

## Verificação

```bash
cd backend
./mvnw verify
```

Testes HTTP com MySQL temporário verificam CSRF ausente/inválido, login com email
normalizado, rotação da sessão e token, persistência da sessão, logout, credenciais
inválidas, conta desativada, autorização ADMIN/PLANEADOR, consulta por ID, criação
de contas, email duplicado, validação, hash BCrypt, recusa de segundo bootstrap
e efeito de revogação de papéis. Não usam contas da base de desenvolvimento.

Verificado em 10 de novembro de 2026: `./mvnw verify` com 9 testes aprovados (6 de
autenticação e 3 dos marcos anteriores), imagem Docker construída e comando CLI
de bootstrap ensaiado num MySQL temporário. O CLI criou exatamente uma conta ADMIN,
terminou com sucesso e recusou uma segunda execução. A password de teste não apareceu
nos logs. A primeira conta real é criada pelo estudante no terminal, com as suas
credenciais, usando o script acima.

## Git

```bash
git add backend compose.yaml .env.example scripts/criar_admin.sh scripts/testar_login.py README.md docs/autenticacao.md
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: autenticar utilizadores por sessao com CSRF e perfis"
git push
```

## Referências

- [Spring Security: persistência de autenticação](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security: CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
