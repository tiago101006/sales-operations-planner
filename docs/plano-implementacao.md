# Plano de implementação do MVP

Cada fase termina com uma demonstração verificável. Duração indicativa: uma a duas
semanas por fase, a ajustar ao calendário académico. O backend já implementa as fases
0 a 5: infraestrutura, autenticação/catálogo, históricos, previsões/backtesting,
cenários com ajustes auditados e aprovação/publicação por API/CSV. Seguem-se
monitorização com realizados e interface gráfica.
Este documento conserva o desenho geral; os contratos efetivos e limites de cada
bloco estão nos documentos específicos ligados no README, incluindo
[cenários e revisões](cenarios-ajustes.md) e
[aprovação/publicação](aprovacao-publicacao.md). Importações de realizados/operacionais,
calendários e promoções descritas abaixo ainda não estão implementadas.

## Convenções comuns

- Prefixo `/api/v1`. Identificadores SQL BIGINT como strings nos DTOs, evitando perda
  de precisão no navegador. Quantidades como strings decimais de quatro casas.
- Meses `YYYY-MM`; instantes ISO-8601 UTC. UTF-8 e cabeçalho nos CSV; separador `;`,
  ponto decimal, sem separadores de milhares. Estas são decisões iniciais de contrato.
- `201 Created` para recursos criados, `200 OK` para consultas/comandos concluídos,
  `400` para JSON malformado, `401` sem autenticação, `403` sem permissão, `404` sem
  recurso, `409` para conflitos de versão/estado e `422` para violação de regras de
  conteúdo. Um ficheiro rejeitado mantém o ID do lote no erro.
- Erros seguem uma estrutura Problem Details, com `code`, `fieldErrors` e
  `currentVersion` quando necessário. Limites iniciais propostos: 10 MiB e 100 000
  linhas por CSV, configuráveis e sujeitos a medição.
- Listagens paginadas `page=0&size=50`, tamanho máximo 200. Comparação de cenários
  pagina produtos e mantém todos os meses selecionados de cada produto.
- Sessão autenticada com Spring Security; cookies HttpOnly e proteção CSRF. O cliente
  usa um endpoint de CSRF antes de comandos. API de publicação usa a mesma autorização;
  integração com tokens de máquina pode ser adicionada depois de um consumidor de teste.

## Fase 0 — Contratos, base de dados e infraestrutura

Entregas atuais: SQL, Compose, arquitetura e este plano. Confirmar as hipóteses de
produto/mês, horizonte e unidades com o orientador. Não existem endpoints nesta fase.
Na fase seguinte o backend passa a controlar o esquema por Flyway, sem dois mecanismos
concorrentes de migração.

Git desta fase: seguir os comandos do README. Nenhum comando de commit foi executado.

## Fase 1 — Backend, autenticação e catálogo

| Endpoint | Regra |
|---|---|
| `GET /auth/csrf` | Obter token CSRF para o cliente |
| `POST /auth/login` | Autenticar e criar sessão |
| `POST /auth/logout` | Invalidar sessão |
| `GET /auth/me` | Devolver identidade e papéis |
| `POST /utilizadores` | ADMIN cria conta; password armazenada apenas como hash |
| `PUT /utilizadores/{id}/papeis` | ADMIN gere os papéis de forma explícita |
| `GET /produtos` | Catálogo paginado |
| `POST /produtos` | Criar código único e unidade |
| `PATCH /produtos/{id}` | Nome/estado; unidade e código ficam fixos após uso |

Estruturas: entidades JPA, DTOs Java `record`, `Set<Papel>`, `BigDecimal` para
quantidades e conversão explícita `YearMonth`/`LocalDate`. Criar a primeira conta
ADMIN por bootstrap local controlado; não disponibilizar registo público.
ADMIN gere acesso, mas só aprova se receber esse papel e cumprir a separação de funções.

Saída: login funcional, pedidos sem perfil recusados, migração aplicada numa base
vazia e validação do esquema JPA. Testar autorização também por ID de recurso.

```bash
git add backend compose.yaml docs
git diff --cached --check
git commit -m "feat: criar backend autenticado e catalogo de produtos"
git push
```

## Fase 2 — Importação e qualidade dos dados

| Endpoint | Regra |
|---|---|
| `POST /lotes` | Multipart: ficheiro, tipo, origem e versão do esquema |
| `GET /lotes` | Listar estado e proveniência |
| `GET /lotes/{id}` | Consultar hash, totais e datas |
| `GET /lotes/{id}/erros` | Diagnóstico paginado: linha, campo e motivo |
| `GET /lotes/{id}/observacoes` | Consultar dados de um lote validado |

Validar cabeçalhos, produtos, unidade, meses, tipos e quantidades não negativas.
Detetar duplicados com `HashSet<ChaveSerie>` (`produtoId`, `YearMonth`, `tipo`).
Usar parser CSV que respeite aspas; não usar `split(";")`.
As linhas são validadas antes de se tornarem utilizáveis. Guardar ficheiro original
imutável e SHA-256 fora da transação SQL; a aplicação não serve caminhos de ficheiro
fornecidos pelo utilizador. Um erro bloqueante rejeita o lote inteiro, sem observações
publicadas. Uma correção cria novo lote com `substituiLoteId`.

Para o MVP cada lote é um snapshot completo, incluindo o histórico necessário; uma
atualização não acrescenta silenciosamente observações a lotes anteriores. Vendas e
consumo permanecem alternativas. Calendário, promoções e operacionais têm esquemas
próprios selecionados pelo tipo do lote. O realizado fica em observação com tipo
`PROCURA_REALIZADA`, em lote `REALIZADOS`.

Saída: CSV válido utilizável; CSV com repetição rejeitado sem linhas disponíveis;
zero preservado; ausência assinalada separadamente. Testar também tamanho excessivo.

```bash
git add backend docs
git diff --cached --check
git commit -m "feat: importar e validar lotes CSV com proveniencia e diagnosticos"
git push
```

## Fase 3 — Previsões e avaliação temporal dos modelos

| Endpoint | Regra |
|---|---|
| `POST /execucoes-previsao` | Criar execução com dados, corte e configuração explícitos |
| `GET /execucoes-previsao/{id}` | Estado e configuração preservados |
| `GET /execucoes-previsao/{id}/linhas` | Previsões por produto/mês |
| `GET /execucoes-previsao/{id}/diagnosticos` | Método efetivo e séries insuficientes |

Começar com execução síncrona e `201` após cálculo; medir antes de introduzir jobs.
Se forem necessários jobs, mudar o contrato para `202`, preservando o recurso de estado.
Estruturas: `Map<ProdutoId, NavigableMap<YearMonth, BigDecimal>>`,
`ForecastRequest`, `ForecastResult`, `DiagnosticoSerie` e interface `ForecastEngine`.

Política inicial de média móvel: janela configurável `k`; cada passo futuro usa os
últimos `k` valores da série estendida pelas previsões anteriores. Exigir meses
consecutivos observados; não saltar lacunas. Fixar este algoritmo no teste e na
configuração, pois manter a média constante seria uma variante diferente.

ETS começa por uma variante aditiva identificada. A implementação/biblioteca é uma
decisão desta fase, verificada com séries de referência. Registar a variante, períodos
sazonais, inicialização, coeficientes e versão do motor. Se uma previsão for negativa,
aplicar a política explícita de truncar em zero e emitir diagnóstico. Se não houver
histórico suficiente, registar `INSUFICIENTE` ou recurso explícito a média móvel;
nunca omitir a mudança de método. Sazonalidade mensal anual usa período 12 e exige
histórico suficiente; não prometer sazonalidade fiável com três ou seis observações.

Guardar parâmetros aprendidos por produto em `execucao_produto`; guardar valores
publicados com quatro casas. O treino usa somente períodos e informação disponíveis
no corte. Usar `double` internamente se a implementação estatística exigir, convertendo
para `BigDecimal` apenas na fronteira com política de arredondamento documentada.
Script de backtest executa cortes sucessivos com horizonte comum e sem embaralhar
amostras no tempo. A seleção de parâmetros usa dados anteriores ao teste final.

Saída: mesma entrada e versão -> mesmos resultados à escala publicada; MAE/MAPE
corretos para séries de referência; comportamento explícito para lacunas e séries curtas.

```bash
git add backend scripts docs
git diff --cached --check
git commit -m "feat: gerar previsoes reproduziveis com media movel e ETS"
git push
```

## Fase 4 — Cenários, revisões e ajustes auditados

| Endpoint | Regra |
|---|---|
| `POST /cenarios` | Criar cenário e primeira revisão a partir de execução concluída |
| `GET /cenarios?execucaoId={id}` | Consultar alternativas da mesma execução |
| `GET /cenarios/{id}/revisoes` | Histórico de revisões |
| `POST /cenarios/{id}/revisoes` | Copiar origem ou criar revisão com novo fator |
| `GET /revisoes/{id}` | Estado, fator, pressupostos e versão |
| `GET /revisoes/{id}/linhas` | Valores base, calculados e finais |
| `POST /revisoes/{id}/ajustes` | Substituir uma célula em rascunho com motivo |
| `GET /revisoes/{id}/ajustes` | Histórico e ligação à revisão de origem |
| `GET /revisoes/{id}/eventos` | Linha temporal de auditoria |
| `GET /comparacao-cenarios?revisaoIds=1,2` | Comparar revisões da mesma execução |

Estruturas: `ChaveCelula(produtoId, periodo)`, `Map<ChaveCelula, LinhaCenarioDTO>`,
DTOs de comparação e lista ordenada de ajustes por sequência. Materializar 100 produtos
× 3 meses = 300 linhas por revisão é simples e permite consultas e exportação coerentes.
Bloquear a revisão em todos os comandos e verificar `versaoEsperada`. Alteração da
célula, ajuste e evento têm commit SQL conjunto. A sequência não usa apenas o relógio;
é gerada sob o mesmo bloqueio. Não existem endpoints para editar o histórico.

Saída: base 100 -> otimista 110 -> override 120 com anterior 110; conservador 90 e
baseline preservados. Justificações vazias e versões ultrapassadas são recusadas.

```bash
git add backend frontend docs
git diff --cached --check
git commit -m "feat: comparar cenarios e registar ajustes justificados por revisao"
git push
```

## Fase 5 — Aprovação e publicação

| Endpoint | Regra |
|---|---|
| `POST /revisoes/{id}/submissao` | Congelar rascunho em revisão |
| `POST /revisoes/{id}/devolucao` | APROVADOR devolve com motivo obrigatório |
| `POST /revisoes/{id}/aprovacao` | APROVADOR aprova e publica numa transação |
| `GET /planos` | Listar apenas planos publicados acessíveis |
| `GET /planos/{id}` | Metadados do snapshot e informação de aprovação |
| `GET /planos/{id}/linhas` | Linhas do snapshot, paginadas |
| `GET /planos/{id}/exportacao.csv` | Mesmas linhas da API em CSV |

Usar `@Transactional`, bloqueio da revisão e `versaoEsperada`. Aprovação exige papel
adequado e separação de autor/aprovador. O snapshot inclui quantidades base e final,
código e unidade. Guardar um único evento de aprovação e plano por revisão. Repetição
com versão antiga -> `409`, sem efeito adicional. A devolução conserva a revisão;
a correção usa `POST /cenarios/{id}/revisoes` e um novo ID.

Saída: teste concorrente produz um plano e um evento; API/CSV iguais, inclusive na
ordenação por código e período; rascunhos nunca aparecem na API de publicação.

```bash
git add backend frontend docs
git diff --cached --check
git commit -m "feat: aprovar revisoes e publicar planos imutaveis por API e CSV"
git push
```

## Fase 6 — Monitorização, interface e avaliação final

| Endpoint | Regra |
|---|---|
| `POST /avaliacoes` | Criar avaliação com plano, lotes, janela e corte dos realizados |
| `GET /avaliacoes/{id}` | Configuração e versões usadas |
| `GET /avaliacoes/{id}/precisao` | MAE/MAPE base e ajustado, cobertura e ganho |
| `GET /avaliacoes/{id}/operacao` | Serviço, ruturas, excesso, perdas e falta de dados |
| `GET /avaliacoes/{id}/celulas` | Detalhe auditável e motivos de exclusão |

Importação de realizados e operacionais reutiliza `POST /lotes`. Uma avaliação nova
não sobrescreve avaliações antigas. A interface cria uma avaliação para os novos
lotes, permitindo comparar correções dos realizados. As métricas de precisão usam
o mesmo conjunto de células base/ajustado e discriminam horizonte de 1, 2 ou 3 meses.
Estruturas: `AcumuladorErro` com somas, contadores e população elegível; DTOs com
indicador anulável, cobertura, unidade e motivos de indisponibilidade.

Saída: reproduzir CA05 do relatório: realizados 100, 0 e falta; base 90, 10 e 30;
ajustado 110, 0 e 20 -> MAE 10/5, MAPE 10%/10%, dois pares MAE e um MAPE.
Medir meta de p95 de dois segundos para guardar/mostrar um ajuste em 300 células,
com 30 medições e ambiente documentado. Validar tarefas com utilizadores, restaurar
backup e concluir manual, resultados de backtest e plano de adoção.

```bash
git add backend frontend scripts docs data
git diff --cached --check
git commit -m "feat: monitorizar precisao e resultados operacionais com cobertura"
git push
```

Os caminhos dos comandos de cada fase pressupõem que essas pastas foram criadas nessa
fase. Executar os testes correspondentes e rever o diff antes de cada commit. Dividir
uma fase em commits menores quando houver alterações independentes e verificáveis.

## Exemplos de contrato JSON

Execução: `POST /api/v1/execucoes-previsao`:

```json
{
  "loteHistoricoId": "1",
  "tipoSerie": "VENDAS",
  "corteTemporal": "2026-10-31T23:59:59Z",
  "ultimoPeriodoTreino": "2026-10",
  "horizonteMeses": 3,
  "metodo": "MEDIA_MOVEL",
  "parametros": {"janela": 3, "multiplosPassos": "RECURSIVO"}
}
```

Criação de cenário: `POST /api/v1/cenarios`:

```json
{
  "execucaoId": "1",
  "nome": "Campanha de inverno",
  "tipo": "OTIMISTA",
  "fator": "1.100000",
  "pressupostos": {"descricao": "Aumento esperado na campanha confirmada"}
}
```

Ajuste: `POST /api/v1/revisoes/1/ajustes`:

```json
{
  "produtoId": "1",
  "periodo": "2026-11",
  "quantidade": "120.0000",
  "justificacao": "Campanha confirmada pelo departamento comercial",
  "versaoEsperada": 0
}
```

Resposta `201 Created`:

```json
{
  "ajuste": {
    "id": "1", "eventoId": "2", "revisaoId": "1", "produtoId": "1",
    "periodo": "2026-11", "sequencia": 1,
    "valorAnterior": "110.0000", "valorNovo": "120.0000",
    "justificacao": "Campanha confirmada pelo departamento comercial",
    "autorId": "1", "autorNome": "Administrador",
    "ocorridoEm": "2026-10-31T18:00:00Z", "herdado": false
  },
  "versaoAtual": 1
}
```

Submissão/aprovação: corpo `{"versaoEsperada": 1}` com a versão atual em cada etapa.
Devolução acrescenta `"motivo"`. Uma aprovação responde com `planoId`, `revisaoId`
e `versaoAtual`, sem permitir escolher as quantidades nessa operação.

Plano: `GET /api/v1/planos/1` (linhas em endpoint separado):

```json
{
  "id": "1",
  "revisaoId": "1",
  "cenarioId": "1",
  "execucaoId": "1",
  "versaoContrato": "v1",
  "aprovadoPor": "2",
  "aprovadoEm": "2026-10-31T19:00:00Z",
  "publicadoEm": "2026-10-31T19:00:00.001Z",
  "totalLinhas": 1
}
```

`GET /api/v1/planos/1/linhas` devolve `items`, `page`, `size`, `totalElements` e
`totalPages`. Cada célula inclui produtoId, produto, periodo, unidade, quantidadeBase
e quantidade aprovada no campo `quantidade`. CSV equivalente, sem paginação:

```csv
plano_id;produto;periodo;unidade;quantidade_base;quantidade
1;SKU01;2026-11;un;100.0000;120.0000
```

Erro de ajuste:

```json
{
  "type": "urn:sop:erro:validacao",
  "title": "Ajuste inválido",
  "status": 422,
  "code": "JUSTIFICACAO_OBRIGATORIA",
  "instance": "/api/v1/revisoes/1/ajustes",
  "fieldErrors": [{"field": "justificacao", "message": "Indica o motivo do ajuste."}]
}
```

Pedido de avaliação:

```json
{
  "planoId": "1",
  "loteRealizadosId": "2",
  "loteOperacionalId": "3",
  "periodoInicio": "2026-11",
  "periodoFim": "2027-01",
  "corteRealizados": "2027-02-02T12:00:00Z"
}
```

## Wireframe lógico

**Cenários:** cabeçalho com execução, corte, método, revisão e estado; filtros de
produtos e meses; tabela com base, otimista, conservador e desvio; gráfico da série
com histórico e futuros. Selecionar uma célula abre o formulário de quantidade e
motivo obrigatório, seguido do histórico anterior/novo/autor/instante. Mostrar
ajustes herdados e acesso à revisão de origem. Ações de guardar ajuste, criar revisão,
submeter, devolver e aprovar dependem do estado e papel. Mostrar conflitos de versão
com instrução para atualizar a revisão.

**Monitorização:** cabeçalho com plano publicado, lotes avaliados, data do cálculo,
janela, produto e horizonte; cartões MAE/MAPE/cobertura e ganho do ajuste; gráfico
base/ajustado/realizado e tabela dos erros por mês. Área operacional separada com
serviço, ruturas, excesso e perdas. Mostrar unidade e cobertura junto de cada
indicador, incluindo os casos sem dados. Ligar ao plano e às justificações.
Este ecrã é de consulta; edições ocorrem numa nova revisão no ecrã de cenários.

**Importação:** upload por tipo, identificação do ficheiro/lote, resumo de validação
e tabela de erros com linha/campo/motivo. A execução de previsão só fica disponível
quando o histórico está validado.

## Dados sintéticos e testes

Gerador futuro em `scripts/gerar_dados.py`, com semente fixa `42`, manifesto de
parâmetros e duas saídas separadas: dados válidos e fixtures inválidas. Proposta:
100 produtos, 36 meses de histórico, três meses reservados para avaliação, com séries
constantes, tendência, sazonais anuais, intermitentes e eventos promocionais conhecidos.
Gerar unidades comparáveis por grupo, sem misturar `un`, `kg` e `L` nos totais.

```text
data/sinteticos/
  produtos.csv
  historico.csv
  calendario.csv
  promocoes.csv
  realizados.csv
  operacionais.csv
  manifesto.json
data/invalidos/
  duplicados.csv
  quantidade_negativa.csv
  produto_desconhecido.csv
  periodo_invalido.csv
  unidade_incompativel.csv
```

Histórico: `produto;periodo;tipo;quantidade;unidade;disponivel_em`.
Promoções: `produto;inicio;fim;descricao;disponivel_em`.
Calendário: `periodo;dias_uteis;feriados;disponivel_em`.
Realizados: `produto;periodo;quantidade;unidade;disponivel_em`.
Operacionais: `produto;periodo;unidade;unidades_solicitadas;unidades_satisfeitas_no_prazo;dias_observados_com_procura;dias_com_procura_sem_stock;stock_fecho;limite_stock;unidades_perdidas;prazo_medio_dias;disponivel_em`.

Para cada backtest, o snapshot do histórico só inclui meses anteriores ao corte e
linhas já disponíveis. O gerador deve criar observações diárias subjacentes para os
contadores de rutura, em vez de inferi-los do stock mensal. Manter os dados diários
como evidência de teste mesmo que o MVP importe agregados mensais. Inserir zeros
explícitos e retirar algumas linhas para ensaiar lacunas; não gerar uma quantidade
vazia como se fosse zero. Guardar datas de conhecimento de promoções.

Casos essenciais de aceitação: duplicados rejeitados atomicamente; cenários isolados;
motivo obrigatório; aprovação concorrente única; API/CSV equivalentes; zeros e faltas
nas métricas; recuperação de plano e auditoria por backup. As metas académicas de
usabilidade e desempenho só se consideram cumpridas depois de medidas.
