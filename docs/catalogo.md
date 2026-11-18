# Catálogo de produtos

## Objetivo e acesso

Criar e manter os produtos que serão referenciados pelos históricos de vendas/consumo,
previsões, cenários e dados operacionais. Entidades JPA ficam no pacote `catalog`;
controllers usam DTOs e o serviço aplica as regras transacionais.

| Operação | ADMIN | PLANEADOR | COMERCIAL / APROVADOR / CONSUMIDOR |
|---|---|---|---|
| Consultar e pesquisar | Sim | Sim | Sim |
| Criar e alterar | Sim | Sim | Não |
| Eliminar produto sem histórico | Sim | Não | Não |

As escritas exigem sessão e token CSRF, como na autenticação. Produtos com histórico
podem ser arquivados com `ativo=false`, conservando as relações históricas.

## Endpoints e contratos

| Método e rota | Resultado |
|---|---|
| `POST /api/v1/produtos` | Cria produto ativo, 201 e Location |
| `GET /api/v1/produtos` | Lista paginada, com pesquisa e filtro de estado |
| `GET /api/v1/produtos/{id}` | Consulta produto, incluindo inativo, ou 404 |
| `GET /api/v1/produtos/{id}/foto` | Foto JPEG, com a mesma permissão de leitura do catálogo, ou 404 |
| `PATCH /api/v1/produtos/{id}` | Atualização parcial com versão esperada |
| `DELETE /api/v1/produtos/{id}?versaoEsperada=0` | Elimina um produto sem histórico, apenas ADMIN, com resposta 204 |

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
{"id":"1","codigo":"SKU001","nome":"Arroz 1 kg","unidade":"un","ativo":true,"versao":0,"temFoto":false}
```

Listagem: `GET /api/v1/produtos?page=0&size=50&q=arroz&ativo=true`.
`page` começa em 0; `size` vai de 1 a 200, predefinido 50. `q` procura fragmentos
no código ou nome, ignorando maiúsculas/minúsculas e aceitando até 160 caracteres.
Os caracteres `%` e `_` são literais, não wildcards. `ativo` é opcional; sem filtro,
incluem-se ativos e inativos. A ordenação fixa é código e ID.

```json
{
  "items":[{"id":"1","codigo":"SKU001","nome":"Arroz 1 kg","unidade":"un","ativo":true,"versao":0,"temFoto":false}],
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

### Foto opcional

Para criar um produto com foto ou substituir a foto na edição, usa o mesmo
`POST` ou `PATCH` com `multipart/form-data`. Envia os dados existentes na
parte `produto` com tipo `application/json` e o ficheiro na parte `foto`.
São aceites JPEG e PNG até 2 MiB e 4096 píxeis por lado. O servidor gera um
JPEG de até 256 píxeis por lado sem os metadados do original.

O envio da foto e as alterações ao produto são guardados em conjunto. Para
remover a foto, envia `{"versaoEsperada":1,"removerFoto":true}` no `PATCH`
JSON, usando a versão atual. Se omitires a foto e `removerFoto` na edição, a
foto atual fica guardada. Substituir ou remover a imagem avança a versão do
produto como qualquer outra alteração.

O campo `temFoto` indica se existe uma imagem para consultar. A migração V7
acrescenta `foto_produto` e deixa todos os produtos existentes sem foto.
O GET da foto exige sessão e devolve `Cache-Control: no-store`.

### Eliminar ou arquivar

O administrador pode eliminar um produto que ainda não tenha observações,
promoções, execuções de previsão, resultados operacionais ou células de backtest.
A foto é eliminada na mesma transação. A versão esperada é obrigatória na query
string e corresponde à última versão consultada. Uma versão desatualizada recebe
409. Um produto inexistente, incluindo uma repetição após eliminação, recebe 404.

Produtos com dados associados recebem 409 e a indicação para arquivar. O arquivo
usa o `PATCH` existente com `ativo=false` e a versão atual, conservando as relações
dos históricos, planos e da auditoria.

## Imagens do catálogo de demonstração

`data/catalogo-imagens/` contém 20 imagens ilustrativas criadas com ImageGen para
os 100 produtos DEMO. As cinco variantes de embalagem de cada tipo partilham a
mesma imagem representativa. Os recortes PNG conservam a transparência e os JPEG
de até 256 × 256 píxeis usam o mesmo fundo uniforme da galeria. O manifesto guarda
os códigos, os prompts e o SHA-256 de cada JPEG.

Com a base local em execução, a importação pode ser verificada e aplicada com

```bash
python3 scripts/carregar_fotos_catalogo.py --verificar
python3 scripts/carregar_fotos_catalogo.py
```

O importador completa as fotografias em falta numa transação, avança as versões
dos produtos e guarda uma cópia anterior privada em `tmp/`. Pode ser repetido e
preserva as fotografias já existentes. Os códigos do manifesto devem existir na
base selecionada pelo Docker Compose.

Para atualizar a versão gerada de uma imagem, usa `--atualizar-geradas`. A
substituição exige que o SHA-256 da fotografia existente corresponda ao
`sha256_anterior` do manifesto. Fotografias diferentes e versões já atualizadas
são preservadas.

O catálogo abre nos produtos ativos. O filtro de estado permite consultar os
arquivados ou todos os produtos e é mantido ao alternar entre Lista e Galeria.

## Identidade e histórico

Código e unidade podem ser corrigidos enquanto não existirem dados associados.
Depois da primeira observação, promoção, participação em execução de previsão,
resultado operacional ou célula de backtest, uma tentativa de os alterar recebe 409. As linhas de previsão,
cenário e plano dependem dessas relações. Nome e estado continuam editáveis.

O serviço bloqueia a linha do produto durante a alteração e a eliminação, e verifica
os cinco pontos de referência direta. Os importadores usam o bloqueio partilhado
correspondente durante as inserções, coordenando alterações concorrentes ao catálogo.
Desativar preserva os dados antigos e o importador de históricos recusa novas
observações para produtos inativos.

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

## Referências

- [Spring Data JPA: bloqueios](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [Spring Data JPA: consultas](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
