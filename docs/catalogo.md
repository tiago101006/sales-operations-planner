# Catálogo de produtos

## Objetivo e acesso

Criar e manter os produtos que serão referenciados pelos históricos de vendas/consumo,
previsões, cenários e dados operacionais. Entidades JPA ficam no pacote `catalog`;
controllers usam DTOs e o serviço aplica as regras transacionais.

| Operação | ADMIN | PLANEADOR | COMERCIAL / APROVADOR / CONSUMIDOR |
|---|---|---|---|
| Consultar e pesquisar | Sim | Sim | Sim |
| Criar e alterar | Sim | Sim | Não |

As escritas exigem sessão e token CSRF, como na autenticação. Não existe eliminação
de produtos pela API: usar `ativo=false` e conservar as relações históricas.

## Endpoints e contratos

| Método e rota | Resultado |
|---|---|
| `POST /api/v1/produtos` | Cria produto ativo, 201 e Location |
| `GET /api/v1/produtos` | Lista paginada, com pesquisa e filtro de estado |
| `GET /api/v1/produtos/{id}` | Consulta produto, incluindo inativo, ou 404 |
| `PATCH /api/v1/produtos/{id}` | Atualização parcial com versão esperada |

Criação:

```json
{"codigo":"SKU001","nome":"Arroz 1 kg","unidade":"un"}
```

O código identifica o SKU; a unidade é a unidade de planeamento. Por exemplo, um saco
de arroz de 1 kg pode ser contado em `un`. Não converter automaticamente entre kg e un.
O código aceita letras ASCII, algarismos, ponto, hífen e underscore, até 64 caracteres.
É único e distingue maiúsculas de minúsculas, conforme a collation da V1. O nome aceita
até 160 caracteres; unidade até 20. Nome/unidade são obrigatórios e guardados sem
espaços nas extremidades. Um produto novo começa ativo, com versão 0.

Resposta:

```json
{"id":"1","codigo":"SKU001","nome":"Arroz 1 kg","unidade":"un","ativo":true,"versao":0}
```

Listagem: `GET /api/v1/produtos?page=0&size=50&q=arroz&ativo=true`.
`page` começa em 0; `size` vai de 1 a 200, predefinido 50. `q` procura fragmentos
no código ou nome, ignorando maiúsculas/minúsculas e aceitando até 160 caracteres.
Os caracteres `%` e `_` são literais, não wildcards. `ativo` é opcional; sem filtro,
incluem-se ativos e inativos. A ordenação fixa é código e ID.

```json
{
  "items":[{"id":"1","codigo":"SKU001","nome":"Arroz 1 kg","unidade":"un","ativo":true,"versao":0}],
  "page":0,
  "size":50,
  "totalElements":1,
  "totalPages":1
}
```

Alteração parcial:

```json
{"nome":"Arroz agulha 1 kg","ativo":false,"versaoEsperada":0}
```

`versaoEsperada` é obrigatória e corresponde à última `versao` consultada. Campos
omitidos ou null ficam inalterados; strings vazias não substituem nome/unidade/código.
Uma alteração efetiva incrementa a versão. Um pedido sem alterações efetivas pode
conservar a versão. Duas escritas com a mesma versão não podem sobrepor alterações:
o pedido desatualizado recebe 409, devendo voltar a consultar o produto.

## Identidade e histórico

Código e unidade podem ser corrigidos enquanto não existirem dados associados.
Depois da primeira observação, promoção, participação em execução de previsão ou
resultado operacional, uma tentativa de os alterar recebe 409. As linhas de previsão,
cenário e plano dependem dessas relações. Nome e estado continuam editáveis.

O serviço bloqueia a linha do produto durante a alteração e verifica os quatro
pontos de referência direta da V1. A futura importação deve validar produto/unidade
sob o bloqueio partilhado correspondente, na mesma transação das inserções, para
coordenar alterações concorrentes ao catálogo. Não existe ainda pipeline de importação.
Desativar preserva os dados antigos; a recusa de novas observações para produtos
inativos será aplicada pelo futuro importador.

## Migração V2

`V2__adicionar_versao_produto.sql` acrescenta `produto.versao_lock`, com valor inicial
0. É mapeado com `@Version`. A V1 não foi alterada. A migração funciona tanto em bases
novas como na base adotada por baseline e preserva os produtos existentes.

Erros: 401 sem sessão; 403 sem perfil/CSRF ou método não permitido pela política;
404 para ID inexistente; 409 para código duplicado, versão antiga ou identidade já
usada; 422 para dados/limites inválidos. Parâmetros que não possam ser convertidos
para o tipo esperado, como `page=abc`, recebem 400.

## Experimentar

```bash
python3 scripts/testar_catalogo.py
```

O script autentica, mostra a primeira página e pergunta se pretendes criar um produto.
Só cria se responderes `s`, com os dados introduzidos por ti. No fim faz logout.
Não existem produtos de demonstração inseridos automaticamente na base do projeto.

## Testar e guardar

```bash
cd backend
./mvnw verify
cd ..
docker compose up -d --build --wait
```

Os testes de catálogo usam MySQL temporário e pedidos HTTP reais: permissões dos
cinco perfis, CSRF, criação, duplicados, validação, paginação, pesquisa literal,
desativação/reativação, versões desatualizadas, bloqueio por cada tipo de uso e
duas atualizações concorrentes. O teste de adoção Flyway também verifica a V2 sobre
um produto anterior ao Flyway, com os seus dados preservados e versão inicial 0.

Verificado em 10 de novembro de 2026: 15 testes aprovados (6 de catálogo e 9 dos
marcos anteriores), imagem reconstruída e ambos os serviços Docker saudáveis. A V2
foi aplicada à base do projeto depois de criar um backup privado em `tmp/backups/`;
os totais de contas e produtos foram preservados. Saúde HTTP respondeu UP e o
catálogo sem autenticação respondeu 401. Não foram inseridos produtos de teste na
base do projeto. O script interativo foi verificado sintaticamente; o ensaio com
as credenciais do estudante é feito pelo próprio no terminal.

```bash
git add backend README.md docs/catalogo.md scripts/testar_catalogo.py
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: gerir catalogo de produtos com perfis e controlo de versao"
git push
```

## Referências

- [Spring Data JPA: bloqueios](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Spring Data JPA: consultas](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
