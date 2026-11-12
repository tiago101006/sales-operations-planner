# Pesquisa e filtros das listagens

As tabelas paginadas dispõem de pesquisa aplicada no servidor a todo o conjunto
acessível dessa consulta. Só a página de resultados é enviada ao navegador.
Pesquisar/limpar reinicia a paginação; filtros de secções diferentes são independentes
e ficam no endereço da página, permitindo atualizar sem perder a pesquisa.

## Utilização

Na tabela pretendida, preencher **Pesquisar nesta tabela** e, quando disponíveis,
**Mês inicial/final**; usar **Pesquisar**. Os limites mensais são inclusivos.
**Limpar filtros** recupera o conjunto sem pesquisa. Sem correspondências, aparece
uma indicação para alterar/limpar os filtros. A contagem/paginação refere-se aos
resultados filtrados. Os dados e totais originais do lote/plano permanecem conservados.

| Secção | Pesquisa textual | Intervalo mensal |
|---|---|---|
| Produtos | Código/nome, pesquisa já existente | — |
| Importações | ID, ficheiro, origem, tipo, estado | Receção do lote |
| Registos validados | Código/nome atual do produto; tipo da série nos históricos | Mês do registo |
| Erros de importação | Linha, campo, código, mensagem | — |
| Execuções de previsão | ID, lote, método, série, estado | Criação |
| Quantidades previstas | Código/nome do produto | Mês previsto |
| Qualidade do cálculo | Produto, estado, método, diagnóstico | — |
| Lista de backtests | ID, lote, série, estado | Criação |
| Comparação de modelos | Produto | —; métricas conservam os cortes do backtest |
| Células da simulação | Produto, estado dos motores | Mês previsto, não o corte de treino |
| Cenários | ID, nome, tipo | Criação |
| Histórico/filas de revisões | ID/versão/estado; nome do cenário/autor nas filas | Criação |
| Quantidades do cenário | Código/nome do produto | Mês previsto |
| Comparação de cenários | Código/nome do produto | Mês previsto |
| Auditoria | Produto, autor, justificação | Mês da célula ajustada |
| Linha temporal | Tipo de evento, autor, motivo | Ocorrência do evento |
| Planos publicados | IDs do plano, revisão, cenário e aprovador | Publicação |
| Células publicadas | Código preservado no plano/nome atual do produto | Mês previsto |
| Avaliações | IDs da avaliação, plano e lote de realizados | Criação |
| Detalhe/cobertura da avaliação | Produto, motivo de exclusão | Mês avaliado |
| Equipa | ID, nome, email, perfil | — |

Exemplo com os dados sintéticos: no lote operacional, `DEMO100` e intervalo
`2026-10` a `2026-10` encontram uma linha, mesmo que o produto estivesse noutra
página. Só o produto, sem meses, encontra os seus três registos.

Filtrar o detalhe de uma avaliação não recalcula os indicadores globais dessa
avaliação. O download CSV do plano continua completo: os filtros alteram a consulta,
não o snapshot ou o contrato de exportação. Uma decisão de aprovação continua a
verificar a auditoria completa, mesmo quando a tabela visível está filtrada.

## Equipa e acesso

A listagem de membros é exclusiva do ADMIN. Apresenta o ID, nome, email e perfis.
O botão **Consultar** abre o perfil do membro selecionado. A resposta não seleciona nem
serializa passwords/hashes. As outras contas continuam impedidas pela autorização
da API, mesmo conhecendo o endereço. O catálogo de membros não é público.

## Contrato técnico

Os endpoints de leitura existentes aceitam `busca` (até 160 caracteres), `desde` e
`ate` (`AAAA-MM`) nas consultas descritas acima. O catálogo de produtos conserva o
seu parâmetro `q`. Paginação continua `page`/`size`, máximo 200. IDs e decimais
mantêm os tipos/formato anteriores. Intervalos inválidos devolvem 422.

As condições SQL usam parâmetros associados, aplicados a consultas previamente
limitadas ao lote/execução/revisão/plano. `%`, `_` e `!` no texto são procurados
literalmente; não são operadores de pesquisa. As contagens usam os mesmos filtros
que as linhas. Não se introduziram migrações nem alterações dos dados de negócio.

## Números, códigos e recuperação

Nas listas com identificador, `#1` procura exatamente o registo 1; não corresponde
à pesquisa de produto. Nos registos de um lote ou plano, usar o código de catálogo
(por exemplo `SKU001` ou `DEMO100`) ou o nome do produto. Os exemplos do campo
adaptam-se à tabela.

Meses incompletos são recusados antes de aplicar a pesquisa. Se um endereço contiver
filtros inválidos e a API recusar a consulta, **Limpar filtros e voltar à lista**
remove os filtros sem perder a página de origem.
