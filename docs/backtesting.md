# Avaliação retrospetiva: Média Móvel e ETS

## Objetivo e separação do planeamento

Comparar as previsões com observações posteriores ao treino, por produto e horizonte,
em cortes mensais sucessivos. Os resultados são identificados como
SIMULACAO_RETROSPETIVA. A migração V3 acrescenta `backtest` e `backtest_celula`, sem
alterar as tabelas existentes. Não são criadas linhas em `execucao_previsao` nem
planos publicáveis. A avaliação de planos aprovados nas tabelas `avaliacao` e
`avaliacao_celula` continuará a ser um módulo diferente.

ADMIN e PLANEADOR criam backtests; ADMIN, PLANEADOR, COMERCIAL e APROVADOR consultam.
CONSUMIDOR não consulta estes dados brutos. POST exige sessão e CSRF. Não há PATCH
ou DELETE. Repetir um pedido cria outro ID, preservando os anteriores.

## Cortes temporais

Um corte `2025-06` significa treino até junho e emissão simulada em
`2025-07-01T00:00:00Z`. Só entram observações com período até junho e disponibilidade
até esse instante, inclusive. O horizonte 1 aponta para julho, 2 para agosto, etc.
Esta política é fixa, registada na configuração e apresentada em cada célula.
Agregados mensais que só se tornem disponíveis depois do início do mês seguinte
ficam fora desse corte; a consequente falta de previsão é mostrada na cobertura.

O lote pode ter sido importado mais tarde do que os cortes: esta é uma simulação
com o snapshot histórico escolhido e as datas de disponibilidade declaradas pela
fonte. Não é prova de que a organização possuía esse snapshot nessa data. Não se
retrocedem timestamps dos lotes nem se relaxam as regras das execuções operacionais.

Em cada corte, o ETS é novamente ajustado apenas com o treino elegível, usando a
mesma grelha, inicialização e versão do motor operacional. A Média Móvel usa a
mesma regra recursiva e arredondamento. Não há recurso de ETS para Média Móvel
nesta comparação: um ETS insuficiente permanece explicitamente indisponível.

O realizado é a observação do mesmo produto/período/tipo no mesmo lote, desde que
o período esteja fechado e a observação esteja disponível até `corteRealizados`.
Este corte assume o instante atual se for omitido. Em falta/desconhecido, conserva-se
a célula sem realizado. Em VENDAS, o realizado é venda observada, que pode ser
inferior à procura durante ruturas; não representa automaticamente procura perdida.

## Métricas e população comum

Para cada grupo produto/horizonte, os dois métodos usam a mesma interseção de pares:
previsão MM, previsão ETS e realizado têm de existir. Uma previsão disponível só de
um método não entra no MAE de nenhum dos dois nessa comparação.

- MAE: média de `abs(previsto-realizado)`, incluindo realizados zero.
- MAPE: 100 vezes a média de `abs(previsto-realizado)/abs(realizado)`, excluindo zero.
- Cobertura: 100 vezes pares comparáveis / candidatos.
- Sem pares elegíveis, a métrica é null, nunca um zero que sugira previsão perfeita.
- Mostrar candidatos, pares MAE/MAPE, zeros excluídos e ausências de realizado/MM/ETS.
  As contagens de ausências podem sobrepor-se e não devem ser somadas como partição.

As métricas são calculadas com BigDecimal, quocientes percentuais com 18 casas e
publicação com 4 casas/HALF_UP. A versão é `common-pairs-v1`. Não se misturam MAE de
produtos ou unidades diferentes, nem horizontes diferentes. Não há média global ou
escolha automática de um modelo vencedor. MAPE pode exceder 100% e é sensível a
realizados próximos de zero.

## API

| Método e rota | Resultado |
|---|---|
| `POST /api/v1/backtests` | Cria simulação e células atomicamente, 201 + Location |
| `GET /api/v1/backtests` | Listagem paginada |
| `GET /api/v1/backtests/sugestao?loteHistoricoId=1&tipoSerie=VENDAS&horizonteMeses=3` | Propõe cortes com treino/realizados disponíveis |
| `GET /api/v1/backtests/{id}` | Configuração, versões, hash do lote, autor e estado |
| `GET /api/v1/backtests/{id}/celulas` | Detalhe por produto/corte/período/horizonte |
| `GET /api/v1/backtests/{id}/comparacao` | MAE/MAPE/cobertura por produto/horizonte |

Pedido para o exemplo SKU001 de 24 meses:

```json
{
  "loteHistoricoId":"1",
  "tipoSerie":"VENDAS",
  "primeiroCorte":"2025-04",
  "ultimoCorte":"2026-07",
  "horizonteMeses":3,
  "janelaMediaMovel":3,
  "historicoEtsMeses":24
}
```

Pode acrescentar-se `corteRealizados` ISO-8601 UTC. IDs usam strings nos JSON de
saída; quantidades e métricas são strings decimais ou null. Listagens usam page=0,
size=50 e tamanho máximo 200. As métricas são paginadas por grupos, não por células.

Limites: 1 a 24 cortes mensais, horizonte 1 a 12, janela MM 1 a 36, histórico ETS
6 a 120, até 1000 produtos e 20000 células. Os cortes têm de ser ordenados e não
posteriores ao corte de realizados; datas suportadas entre anos 1000 e 9999.
É permitido incluir alvos ainda sem realizado, que reduzem a cobertura. Lotes
rejeitados ou de outro tipo recebem 409; limites/conteúdo incoerente 422; ID
inexistente 404; perfil/CSRF 403; ausência de sessão 401; JSON malformado 400.

A sugestão começa, quando possível, depois das primeiras seis observações mensais
do intervalo global e deixa meses para o horizonte de teste. Limita os cortes para
respeitar o tamanho máximo. Não garante séries completas de todos os produtos;
as lacunas reais continuam diagnosticadas. A configuração pode ser ajustada no pedido.

## Persistência e rastreabilidade

O cabeçalho guarda o lote/hash, tipo, cortes, parâmetros, versões dos motores e das
métricas, autor e instantes. Cada célula guarda as duas previsões, estado, diagnóstico
e parâmetros de treino, incluindo coeficientes ETS ajustados naquele corte.
O realizado referencia a observação exata. FKs compostas impedem ligar um realizado
de outro lote, produto ou período. O snapshot anterior não é atualizado por uma
correção importada depois: comparar uma correção exige outro backtest com o novo lote.

O detalhe identifica se a célula é comparável e elegível para MAPE. Os valores
negativos ETS truncados e as falhas numéricas seguem as políticas do motor e ficam
identificados. Uma falha SQL reverte cabeçalho e todas as células. A consulta de
métricas recusa versões de cálculo desconhecidas; não aplica outra fórmula em silêncio.

## Experimentar

```bash
python3 scripts/testar_backtest.py
```

Usar ADMIN e aceitar os defaults com Enter. Para o CSV SKU001 completo, o serviço
propõe abril de 2025 a julho de 2026: 16 cortes, horizonte 3 e 48 células. O script
mostra três grupos (horizontes 1, 2 e 3), com 16 pares comparáveis em cada um e
cobertura 100%. Os scores são calculados a partir dos valores do histórico escolhido.
No fim pode consultar as primeiras células e faz logout.

Um MAE menor indica menor erro nessa amostra e unidade. Não constitui, por si só,
garantia de desempenho futuro. Escolher parâmetros repetidamente pelos mesmos
scores exige reservar um período final independente antes da avaliação académica.

## Testes e Git

```bash
cd backend
./mvnw verify
```

Os testes verificam fórmulas de referência com zeros/faltas, média dos erros percentuais,
população comum, paridade com os motores operacionais, previsões de tendência linear,
reprodução, alterações de alvos futuros sem mudar previsões ou coeficientes anteriores,
disponibilidade de treino/realizados, métricas indefinidas, unidades separadas,
permissões, CSRF, limites, FKs e rollback perante falha na segunda célula.

Verificação de 10 de novembro de 2026: `./mvnw verify` terminou com 58 testes
aprovados, sem falhas, erros ou testes ignorados. A V3 foi aplicada ao ambiente do
projeto depois de criar um backup SQL local com permissões 0600. O hash do dump dos
dados anteriores (contas, papéis, produtos, lotes, observações e previsões) coincidiu
antes/depois. Os serviços Docker ficaram saudáveis; saúde respondeu UP e acesso a
backtests sem sessão respondeu 401. Não foram criados backtests na base do projeto.
O script interativo foi verificado sintaticamente; o ensaio com a conta real é feito
pelo estudante.

```bash
git add backend README.md docs/previsao-media-movel.md docs/previsao-ets.md docs/backtesting.md
git add scripts/testar_previsao.py scripts/testar_ets.py scripts/gerar_referencia_ets.py scripts/testar_backtest.py
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: avaliar media movel e ETS com backtesting temporal e cobertura"
git push
```

Os comandos também incluem ficheiros ETS que ainda estejam locais. Não incluir
backups de `tmp/` nem credenciais `.env` no Git.

## Referência

[Hyndman e Athanasopoulos: métricas e validação temporal](https://otexts.com/fpp3/toolbox.html).
