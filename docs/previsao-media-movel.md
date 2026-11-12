# Primeira previsão: Média Móvel recursiva

Este documento regista o primeiro bloco de previsão. Foi posteriormente acrescentado
[ETS(A,A,N)](previsao-ets.md), preservando o cálculo e os parâmetros da Média Móvel.

## Âmbito

Execuções síncronas e reproduzíveis, a partir de um lote histórico validado, com
seleção de VENDAS ou CONSUMO, corte temporal, janela e horizonte explícitos.
Este bloco implementa apenas MEDIA_MOVEL; ETS e avaliação retrospetiva serão
acrescentados nos blocos seguintes. Não foi necessária nova migração: as tabelas
`execucao_previsao`, `execucao_produto` e `linha_previsao` já existem na V1.

ADMIN e PLANEADOR podem criar execuções. ADMIN, PLANEADOR, COMERCIAL e APROVADOR
podem consultar. CONSUMIDOR não consulta previsões ainda não publicadas. POST exige
sessão e CSRF. Não existem PATCH/DELETE de execuções ou linhas.

## Preparação das séries

A execução recebe um lote explícito, sem combinar versões de históricos. O lote
tem de ser HISTORICO/VALIDADO e já estar disponível e validado no corte. O corte
não pode estar no futuro nem anteceder o último dia do mês de treino.
O modo atual não permite retroceder o corte para uma data anterior à importação;
ensaios históricos simulados precisarão de um contrato próprio, identificado como
simulação. Não alterar os timestamps de um lote para contornar esta regra.

A população inclui todos os produtos presentes no lote para o tipo de série
selecionado, até 1000 produtos. Uma série inexistente ou população superior ao
limite recebe 422. O estado atual do catálogo não altera os valores do histórico:
produtos atualmente inativos continuam analisáveis no snapshot já importado.

Para cada produto são exigidos exatamente os últimos k meses consecutivos até ao
último mês de treino. Só entram observações disponíveis até ao corte. Meses fora
da janela não são usados. Um zero explícito entra como zero; um mês em falta ou
ainda não disponível recebe diagnóstico JANELA_INCOMPLETA, sem preencher valores.
Lacunas anteriores à janela exigida não impedem este método.

VENDAS e CONSUMO nunca são somados automaticamente. Um produto sem janela completa
fica `INSUFICIENTE` e não produz linhas. Outros produtos
elegíveis da execução continuam a gerar previsões.

## Algoritmo e precisão

Regra adotada no MVP: média dos últimos k valores; nas etapas seguintes, deslocar
a janela e incluir as previsões anteriores. Não é uma média constante nos vários
horizontes. Cada valor é calculado com BigDecimal e publicado com escala 4, HALF_UP;
é esse valor arredondado que volta à janela.

Exemplo com janela 3 e observações 10, 20 e 30:

| Horizonte | Janela usada | Previsão |
|---|---|---|
| 1 | 10; 20; 30 | 20.0000 |
| 2 | 20; 30; 20.0000 | 23.3333 |
| 3 | 30; 20.0000; 23.3333 | 24.4444 |

Esta variante é uma decisão explícita do projeto, a avaliar com outros métodos;
não se assume que é a melhor para qualquer série. Não existem intervalos de previsão
ou tratamento sazonal nesta primeira implementação.

O contrato interno `ForecastEngine` separa o cálculo da persistência. A preparação
usa mapas ordenados YearMonth -> BigDecimal. A versão do motor é
`moving-average-recursive-v1`; alterações ao algoritmo/arredondamento devem criar
outra versão. A versão de software vem do `project.version` Maven (atualmente
0.0.1-SNAPSHOT); o commit Git identifica o código guardado pelo estudante.

## Endpoints

| Método e rota | Resultado |
|---|---|
| `POST /api/v1/execucoes-previsao` | 201 com execução e Location |
| `GET /api/v1/execucoes-previsao` | Execuções paginadas, mais recentes primeiro |
| `GET /api/v1/execucoes-previsao/{id}` | Configuração, estado e totais |
| `GET /api/v1/execucoes-previsao/{id}/linhas` | Quantidades base por produto/mês/horizonte |
| `GET /api/v1/execucoes-previsao/{id}/diagnosticos` | Método efetivo e estado por produto |

Listagens usam `page=0&size=50`, com tamanho máximo 200. Linhas/diagnósticos ordenados
por código; as linhas também por período. Quantidades e IDs são strings no JSON.

Pedido (usar um ID e corte compatíveis com o lote real):

```json
{
  "loteHistoricoId":"1",
  "tipoSerie":"VENDAS",
  "corteTemporal":"2026-11-10T17:00:00Z",
  "ultimoPeriodoTreino":"2026-10",
  "horizonteMeses":3,
  "metodo":"MEDIA_MOVEL",
  "parametros":{"janela":3,"multiplosPassos":"RECURSIVO"}
}
```

Janela entre 1 e 36; horizonte entre 1 e 12 meses. `multiplosPassos` omitido assume
RECURSIVO. O primeiro mês previsto é o mês imediatamente depois do último mês de
treino. É permitido gerar previsão do mês em curso; a avaliação posterior terá de
discriminar quando a decisão foi tomada, sem a apresentar como anterior ao período.

O cabeçalho devolve lote, tipo de série, corte, mês de treino, horizonte, método,
configuração normalizada (janela, escala, arredondamento, política de lacunas),
versões de software/motor, autor autenticado, instantes e contagens de produtos
previstos/insuficientes e linhas. Não aceita autor enviado pelo cliente.

Exemplo de linha:

```json
{"produtoId":"1","produto":"SKU001","unidade":"un","periodo":"2026-11","horizonte":1,"quantidadeBase":"22.3333"}
```

Diagnóstico insuficiente:

```json
{"produtoId":"2","produto":"SKU002","estado":"INSUFICIENTE","metodoAplicado":null,"diagnostico":"JANELA_INCOMPLETA: meses em falta ou indisponíveis no corte: 2026-09"}
```

Para um produto elegível, o estado é PREVISTO, método MEDIA_MOVEL e diagnóstico null.
CONCLUIDA indica que o processamento terminou; pode haver zero produtos previstos.
Verificar sempre `produtosPrevistos`, `produtosInsuficientes` e os diagnósticos.

Erros: 401 sem sessão; 403 sem perfil/CSRF; 404 para lote/execução inexistente; 409
para lote não validado ou não disponível no corte; 422 para parâmetros incoerentes,
tipo de série inexistente, corte futuro ou método ainda não implementado (ETS).
Tipos JSON/formato que não possam ser convertidos recebem 400.

## Persistência e repetição

Cabeçalho, configuração por produto e linhas são guardados na mesma transação.
Uma falha técnica reverte a execução completa. Não fica uma execução parcialmente
publicada nem se reutiliza o ID. Os estados PENDENTE/FALHOU ficam reservados para
um futuro modo de execução com jobs; não são produzidos por este fluxo síncrono.

Repetir o pedido cria outra execução, com outro ID e instantes, mas as mesmas
quantidades se lote, configuração e versão do motor forem iguais. As observações
originais e execuções anteriores não são alteradas. Não existe idempotência por
chave neste bloco; consultar a lista antes de repetir depois de um timeout.

Código e unidade do produto já estão fixos pela existência de observações; as linhas
de previsão consultadas conservam, por essa regra, a identidade e unidade. Planos
publicados terão o seu snapshot próprio numa fase posterior.

## Experimentar

```bash
python3 scripts/testar_previsao.py
```

Usar a conta ADMIN existente. O script lista os históricos validados mais recentes
e propõe o ID do mais recente. Enter aceita VENDAS, o mês completo anterior ao atual,
janela 3 e horizonte 3. O corte é o instante UTC do pedido. Pode ser necessário
alterar o mês de treino se o histórico não chegar ao mês proposto.

Para o exemplo SKU001 de novembro de 2024 a outubro de 2026, com janela 3 e último
mês outubro de 2026, os valores finais são agosto=23, setembro=20, outubro=24:

| Período previsto | Quantidade |
|---|---|
| 2026-11 | 22.3333 |
| 2026-12 | 22.1111 |
| 2027-01 | 22.8148 |

Esperado: CONCLUIDA, 1 produto previsto, 0 insuficientes, 3 linhas e logout. O script
não importa dados nem cria produtos. Se só houver lotes rejeitados, importar primeiro
um histórico válido. Para outro dataset, esperar os valores resultantes desse dataset.

## Verificação

```bash
cd backend
./mvnw verify
```

Testes do motor usam resultados de referência, zeros, recursão, arredondamento,
entrada preservada e limite DECIMAL. Testes HTTP/MySQL verificam reprodução,
isolamento vendas/consumo, lacunas, séries curtas, disponibilidade, corte, permissões,
CSRF, zero previsões, imutabilidade e rollback perante falha de persistência.

Verificado em 10 de novembro de 2026: `./mvnw verify` com 36 testes aprovados,
incluindo 8 HTTP/MySQL desta fase e 4 do motor, além dos 24 anteriores. O ensaio
de rollback usa uma constraint temporária apenas na base de testes, limitada às
novas execuções. A imagem foi construída e os dois serviços Docker ficaram saudáveis;
saúde HTTP respondeu UP e o acesso a previsões sem sessão respondeu 401. Não foram
criadas previsões na base do projeto pelo assistente. O script interativo foi
verificado sintaticamente; o ensaio com credenciais reais é feito pelo estudante.

## Referência de avaliação

[Hyndman e Athanasopoulos: ferramentas e validação temporal](https://otexts.com/fpp3/toolbox.html).
O cálculo recursivo descrito acima é o contrato específico desta implementação;
a avaliação comparativa por períodos será implementada separadamente.
