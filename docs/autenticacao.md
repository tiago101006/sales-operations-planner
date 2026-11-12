# Autenticação por sessão e perfis

## Funcionamento

Spring Security autentica contas guardadas em `utilizador` e carrega os papéis de
`utilizador_papel`. O mapeamento JPA é validado contra o esquema gerido pelo Flyway.

Passwords são guardadas como hashes BCrypt, custo 12. Novas passwords exigem pelo
menos 12 caracteres e no máximo 72 bytes UTF-8; não são cortadas silenciosamente.
Os DTOs nunca devolvem password ou hash. O email é normalizado para minúsculas.

A sessão usa cookie JSESSIONID HttpOnly e SameSite=Lax, expira após 30 minutos sem
atividade ou oito horas desde o login e muda de ID ao fazer login. Não se usa JWT nem HTTP Basic. Os pedidos de
escrita, incluindo login e logout, exigem CSRF. O token deve ser obtido outra vez
depois do login e logout. Cookies e token pertencem à mesma sessão.

O estado ativo e os papéis são novamente consultados em cada pedido autenticado:
uma conta desativada perde a sessão; papéis revogados deixam de autorizar o pedido
seguinte. As sessões estão em memória e terminam ao reiniciar o backend.

`SESSION_COOKIE_SECURE=false` destina-se ao HTTP local. Com HTTPS deve ser `true`.
O login admite cinco tentativas por conta e 60 por endereço numa janela de
15 minutos. Uma autenticação bem-sucedida limpa o contador da conta. Limites
atingidos devolvem 429 e Retry-After. A identificação da origem usa a ligação TCP.

Ainda estão por implementar recuperação de password, MFA e persistência das
sessões e contadores entre instâncias. A instalação atual usa HTTP local.
Consulta a [revisão de segurança](seguranca.md) antes de preparar acesso público.

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
| `POST /api/v1/utilizadores` | ADMIN + CSRF | HTTP 201, conta por ativar e convite |
| `POST /api/v1/utilizadores/{id}/convites` | ADMIN + CSRF | Novo convite para uma conta por ativar. Exige `versaoEsperada` |
| `POST /api/v1/auth/ativacao` | Convite + CSRF | HTTP 204, password definida |
| `GET /api/v1/utilizadores?busca=texto&page=0&size=50` | ADMIN | Lista paginada de ID, nome, email e perfis; sem credenciais |
| `GET /api/v1/utilizadores/{id}` | ADMIN | DTO com estado e versão da conta ou 404 |
| `PATCH /api/v1/utilizadores/{id}/estado` | ADMIN + CSRF | Desativa/reativa; exige `ativo` e `versaoEsperada` |

Os endpoints de estado e saúde são públicos. As rotas de gestão de utilizadores exigem ADMIN.

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
  "papeis":["PLANEADOR"]
}
```

A resposta inclui `utilizador`, `token` e `expiraEm`. O administrador copia o link e entrega-o
por um canal privado. O destinatário escolhe e confirma a password no formulário de ativação.

```json
{"token":"token-do-convite","password":"password-escolhida","confirmacao":"password-escolhida"}
```

Cada convite dura 24 horas. A base guarda o hash do token, o autor e as datas de
emissão, expiração e utilização. Gerar outro convite ou desativar a conta invalida
o link anterior. A ativação consome o convite e guarda a password na mesma
transação. Depois de ativar, o funcionário entra com o seu email e a password
que escolheu. A migração V5 marca as contas existentes como já ativadas.

O token segue no fragmento do endereço e é retirado do URL quando a página abre.
A rota de ativação exige CSRF e partilha o limite por endereço do login.
Consulte a [orientação da OWASP sobre tokens de acesso](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html#url-tokens).

Papéis disponíveis: ADMIN, PLANEADOR, COMERCIAL, APROVADOR e CONSUMIDOR.

Erros: 401 para ausência de sessão ou credenciais inválidas; 403 para papel inadequado
ou CSRF ausente/inválido; 422 para validação; 409 para email duplicado; 400 para JSON
malformado ou papel desconhecido. Credenciais erradas, conta desconhecida e conta
desativada ou por ativar produzem a mesma mensagem de login. Erros não incluem valores de passwords.

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

## Referências

- [Spring Security: persistência de autenticação](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security: CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

## Desativação e reativação de contas

Em **Equipa → Consultar**, o administrador pode desativar ou reativar outra conta,
com confirmação. A tabela identifica os estados Ativo/Inativo; a pesquisa também
aceita estes termos. Não existe eliminação de utilizadores. A própria conta não
pode ser desativada nesta sessão: é necessária a intervenção de outro administrador.

```json
{"ativo":false,"versaoEsperada":0}
```

O endpoint devolve o DTO atualizado com `ativo` e `versao`. Versões antigas recebem
409, sem gravar alterações. Repetir o estado atual com a versão atual é idempotente.
As alterações de estado são serializadas entre administradores e confirmam novamente
que o autor está ativo e tem o papel ADMIN dentro da transação.

A migração V4 acrescenta `utilizador.versao_acesso` e a tabela
`evento_estado_utilizador`, com FKs para a conta e para o administrador responsável,
estados anterior/novo e instante UTC. A versão incrementa a cada mudança de estado.
O login guarda a versão na sessão e cada pedido autenticado compara-a com a base.
Contas inativas não podem entrar e as sessões anteriores são recusadas no pedido
seguinte, mesmo que a conta tenha entretanto sido reativada. As ações já executadas
permanecem no histórico. A reativação conserva password e perfis e exige novo login.

Os testes de autenticação cobrem autorização, CSRF, concorrência por versão,
autodesativação, revogação de sessões, reativação e auditoria. O ensaio no navegador
cobre consulta, cancelamento, desativação e reativação na área da equipa.
