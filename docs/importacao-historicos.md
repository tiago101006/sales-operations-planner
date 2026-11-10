# Importação de históricos CSV

## Âmbito deste bloco

Importação síncrona de vendas/consumo mensais, com ficheiro original, SHA-256, autor,
origem, esquema, estado e diagnósticos por lote. Calendário, promoções, realizados
e dados operacionais terão contratos próprios; não são aceites neste endpoint ainda.
O esquema da V1 já contém as tabelas necessárias, sem nova migração nesta entrega.

ADMIN e PLANEADOR podem importar. ADMIN, PLANEADOR, COMERCIAL e APROVADOR podem
consultar lotes, diagnósticos e observações. CONSUMIDOR mantém consulta ao catálogo,
mas não recebe acesso aos históricos brutos. Escritas exigem sessão e CSRF.

## CSV v1

```csv
produto;periodo;tipo;quantidade;unidade;disponivel_em
SKU001;2025-01;VENDAS;15;un;2025-02-01T00:00:00Z
SKU001;2025-02;VENDAS;0;un;2025-03-01T00:00:00Z
```

- UTF-8, BOM opcional, cabeçalho obrigatório e separador `;`. Aspas são interpretadas
  pelo Apache Commons CSV, incluindo campos delimitados entre aspas. A ordem das seis
  colunas pode variar; os nomes devem corresponder exatamente, sem repetições/extras.
- Produto: código existente, ativo e com a mesma unidade. Código e unidade distinguem
  maiúsculas/minúsculas. Espaços nas extremidades dos valores são removidos.
- Período: `AAAA-MM`, ano entre 1000 e 9999; guardado como primeiro dia do mês.
- Tipo: `VENDAS` ou `CONSUMO`, sem somar/reconciliar as duas séries automaticamente.
- Quantidade: não negativa, ponto decimal, sem expoentes/separadores de milhares,
  até 14 algarismos inteiros e quatro casas decimais. Guardada com escala 4.
- Disponibilidade: ISO-8601 UTC com `Z`, a partir do último dia do mês observado e
  não posterior ao instante da importação. Precisão de microssegundos na base.
  É a data declarada pela fonte em que o agregado ficou disponível, não a data do upload.
- A chave `(produto,periodo,tipo)` não pode repetir-se no mesmo lote. Pode existir em
  lotes diferentes, que são versões separadas. Zero é válido; ausência não vira zero.
- Linhas vazias no meio do ficheiro são inválidas. CSV malformado é rejeitado.

Limites: ficheiro até 10 MiB, pedido multipart até 11 MiB, até 100000 registos.
São guardados até 1000 diagnósticos mais um aviso `DIAGNOSTICOS_LIMITADOS` se houver
outros. `totalErros` conta os diagnósticos guardados, incluindo esse aviso. O parser
continua a contar registos acima do limite, sem aceitar as linhas excedentes.
`numeroLinha` identifica o registo CSV, incluindo cabeçalho como 1; para um campo
entre aspas com quebras de linha, não corresponde necessariamente à linha física.

## Endpoints

| Método e rota | Contrato |
|---|---|
| `POST /api/v1/lotes` | Multipart; cria lote validado ou rejeitado |
| `GET /api/v1/lotes` | Histórico paginado, do lote mais recente para o mais antigo |
| `GET /api/v1/lotes/{id}` | Metadados e totais; 404 se não existir |
| `GET /api/v1/lotes/{id}/erros` | Diagnósticos paginados |
| `GET /api/v1/lotes/{id}/observacoes` | Dados de lote VALIDADO; 409 nos restantes estados |

Campos multipart: `ficheiro` obrigatório; `tipo=HISTORICO`; `versaoEsquema=v1`;
`origem` por defeito `upload-manual`, 1 a 160 caracteres; `substituiLoteId` opcional.
Cabeçalho CSRF e cookie devem acompanhar o pedido como nos outros POST.
Todas as listagens usam `page=0&size=50`, tamanho entre 1 e 200.

Sucesso: HTTP 201, `Location: /api/v1/lotes/{id}`, corpo:

```json
{
  "id":"1","tipo":"HISTORICO","estado":"VALIDADO",
  "origem":"historico-sintetico","nomeFicheiro":"historico.csv",
  "sha256":"hash-sha256-do-original","versaoEsquema":"v1",
  "substituiLoteId":null,"importadoPor":"1",
  "importadoEm":"2026-11-10T12:00:00Z",
  "disponivelEm":"2026-11-10T12:00:00Z",
  "validadoEm":"2026-11-10T12:00:01Z",
  "totalLinhas":24,"totalErros":0
}
```

Disponibilidade do lote é o instante de receção do snapshot. As observações conservam
as datas de disponibilidade da fonte. Avaliações retrospetivas terão de distinguir
dados efetivamente disponíveis nessa data de uma simulação histórica; este módulo
não permite retroceder o instante de receção do lote.

Rejeição de conteúdo: HTTP 422, com `code=LOTE_REJEITADO`, `loteId` e `totalErros`.
O lote e o original ficam consultáveis, sem observações publicadas. Consultar `/erros`:

```json
{
  "items":[{"numeroLinha":3,"campo":"produto,periodo,tipo","codigo":"CHAVE_DUPLICADA","mensagem":"Produto, período e tipo repetidos neste ficheiro."}],
  "page":0,"size":50,"totalElements":1,"totalPages":1
}
```

Outros erros: 401 sem sessão; 403 sem perfil/CSRF; 413 acima do limite; 415 para nome
sem extensão `.csv`; 422 para metadados não suportados; 409 se a correção referenciar
um lote que não seja histórico validado; 404 para ID inexistente. Erros anteriores
à aceitação do ficheiro/metadados podem não criar lote.

## Proveniência e transações

O original recebe nome UUID num diretório controlado pelo servidor, nunca um caminho
fornecido pelo cliente. O SHA-256 é calculado sobre os bytes originais. A API não
expõe a localização no disco, nem permite alterar/eliminar lotes e observações.

Validação do CSV ocorre antes da publicação. Na transação de gravação, os produtos
são carregados com bloqueio partilhado, em grupos de até 500, e são verificados estado
e unidade. Alterações do catálogo usam bloqueio de escrita correspondente. O mesmo
DataSource/gestor de transações coordena JPA e JdbcTemplate; inserções em batch têm
grupos de 500 mas um único commit por lote.

Com erro de conteúdo, a transação guarda apenas lote e diagnósticos (`REJEITADO`).
Sem erros, grava todas as observações e promove o lote para `VALIDADO` no mesmo commit.
Uma falha de persistência reverte lote e observações e tenta remover o original sem
registo. Ficheiro e SQL não constituem uma transação distribuída; uma interrupção
abrupta pode exigir reconciliação de ficheiros órfãos na manutenção.

Em Docker, os originais ficam no volume `sop_imports`, montado em `/app/imports`,
com permissões para o utilizador sem privilégios do backend. Em Maven, por defeito
ficam em `backend/tmp/imports`. `IMPORT_STORAGE_PATH` permite escolher outro local.
Uma cópia de segurança completa terá de incluir base MySQL e originais do volume.

## Correções e repetição

Uma correção envia outro CSV completo do âmbito selecionado, com `substituiLoteId`
do histórico validado anterior. O lote anterior e as suas observações não mudam.
Não existe junção automática de deltas nem eliminação dos valores antigos. Uma
execução de previsão escolherá um lote explícito, não todas as versões acumuladas.
O sistema não verifica que o CSV contém todas as chaves do anterior: o fornecedor
é responsável por enviar o snapshot completo do âmbito pretendido.

Não existe idempotência por hash: repetir o envio cria outro lote, mesmo com conteúdo
igual. Num timeout, consultar a lista antes de repetir o POST.

## Experimentar com SKU001

Os ficheiros sintéticos em `data/exemplos/` referenciam o monitor SKU001 já criado,
unidade `un`. O válido contém 24 meses de novembro de 2024 a outubro de 2026,
incluindo uma observação zero explícita. Os outros exemplos têm duplicados ou erros.

```bash
python3 scripts/testar_importacao.py
```

Usar a conta ADMIN existente. Enter no caminho seleciona o exemplo válido. Enter
no ID anterior cria lote novo. O script mostra o lote e as observações. Para ensaiar
rejeição, repetir indicando `data/exemplos/historico_sku001_duplicado.csv`: HTTP 422,
estado REJEITADO e diagnóstico CHAVE_DUPLICADA são resultados esperados.

Após uma importação válida, código/unidade de SKU001 ficam fixos. Nome e estado
continuam editáveis pelo catálogo.

## Testes e Git

```bash
cd backend
./mvnw verify
```

Testes com MySQL temporário e HTTP real cobrem validade, zero/lacunas, original/hash,
autor, duplicados, dados inválidos, UTF-8/aspas, permissões, CSRF, tamanho, correções
e rollback de lote/observações/ficheiro quando uma constraint recusa a segunda linha.
Testes do parser verificam limite de 100000 registos e de diagnósticos.

Verificação de 10 de novembro de 2026: 24 testes aprovados, incluindo 7 de importação
por HTTP e 2 do parser. Os restantes 15 verificam os marcos anteriores. A falha de
persistência foi simulada com uma constraint temporária apenas na base de testes.
A imagem Docker foi construída, ambos os serviços ficaram saudáveis e a escrita
no volume de originais foi confirmada com o utilizador sem privilégios do backend.
Não foram importados históricos de teste para a base do projeto. O script interativo
foi compilado sintaticamente; o ensaio com a conta real é executado pelo estudante.

```bash
git add backend compose.yaml scripts/testar_importacao.py data/exemplos README.md docs/importacao-historicos.md
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: importar historicos CSV com validacao atomica e proveniencia"
git push
```

## Referências

- [Spring MVC: multipart](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)
- [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.html)
