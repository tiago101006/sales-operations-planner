# ETS(A,A,N): segundo método de previsão

## Âmbito e algoritmo

Esta implementação Java acrescenta ETS(A,A,N): erros aditivos, tendência aditiva,
sem sazonalidade. Não é seleção automática de toda a família ETS. A sazonalidade,
os intervalos e a avaliação por backtesting não estão implementados neste bloco.

Equações da forma de inovações, para cada observação depois da primeira:

```text
erro = observado - (nivelAnterior + tendenciaAnterior)
nivel = nivelAnterior + tendenciaAnterior + alpha * erro
tendencia = tendenciaAnterior + beta * erro
previsao(h) = nivelFinal + h * tendenciaFinal
```

Aqui beta é o coeficiente de inovações, não beta* da formulação de Holt. A grelha
fixa usa alpha=0.1,...,0.9 e beta*=0.1,...,0.9, com beta=alpha*beta*. São 81 pares,
todos com 0 < beta < alpha < 1. Seleciona-se o menor SSE de treino, em ordem crescente;
num empate exato conserva-se o primeiro par. Não existe aleatoriedade.

Inicialização: nível igual ao primeiro valor observado; tendência igual à diferença
entre o segundo e o primeiro. O processamento começa na segunda observação. Não há
otimização dos estados iniciais nem maximização contínua da verosimilhança. O SSE
inclui os erros da segunda à última observação e não é uma medida fora da amostra.
A avaliação dos métodos com períodos reservados será um bloco próprio.

O cálculo estatístico usa double; apenas a saída é convertida para BigDecimal com
quatro casas e HALF_UP. Estados internos não são arredondados a cada mês. Resultados
negativos são truncados em zero, com contagem e diagnóstico. Valores superiores
ao limite DECIMAL(18,4) produzem ERRO no produto, sem publicar as suas linhas;
não são silenciosamente reduzidos ao máximo da coluna.

Versão do motor: `ets-aan-grid-v1`. Alterar grelha, equações, inicialização ou políticas
exige outra versão. Média Móvel conserva `moving-average-recursive-v1` e o algoritmo
anterior. Não foi necessária nova migração nem uma dependência Java adicional.

## Preparação e histórico insuficiente

Continuam a aplicar-se lote HISTORICO/VALIDADO, tipo VENDAS ou CONSUMO, corte temporal,
disponibilidade das observações e regras de permissões do módulo de previsão.
O corte não pode anteceder a disponibilidade/validação do lote nem estar no futuro.

ETS lê até `historicoMeses` meses anteriores ao último mês de treino (por defeito 24,
entre 6 e 120). Começa na primeira observação elegível dentro desse intervalo: não
exige preencher os meses anteriores ao início da série. A partir desse início até
ao último mês de treino não pode haver lacunas, e são necessárias pelo menos seis
observações. Zeros explícitos são válidos; dados desconhecidos no corte não entram.
Um intervalo inicial mais curto pode ser usado se satisfizer esse mínimo. Lacunas
internas ou no fim impedem o ETS; não se escolhe silenciosamente outro sufixo.

Por defeito, falta de histórico/lacunas -> `INSUFICIENTE`,
sem linhas. Se o pedido escolher recurso MEDIA_MOVEL, tenta-se a janela final
consecutiva de recurso. Só quando essa janela está completa se publicam previsões
com estado RECURSO e método efetivo MEDIA_MOVEL. O motivo fica no diagnóstico e nos
parâmetros; não se apresentam valores de recurso como se fossem ETS. Uma previsão
ETS fora do limite numérico recebe ERRO, sem recurso automático por esse motivo.

## Pedido REST

Mantém-se `POST /api/v1/execucoes-previsao`, sem novos endpoints. Exemplo:

```json
{
  "loteHistoricoId":"1",
  "tipoSerie":"VENDAS",
  "corteTemporal":"2026-11-10T18:00:00Z",
  "ultimoPeriodoTreino":"2026-10",
  "horizonteMeses":3,
  "metodo":"ETS",
  "parametros":{"variante":"AAN","historicoMeses":24,"recurso":"SEM_RECURSO"}
}
```

Usar ID/corte adequados ao lote real, nunca copiar um corte futuro. O script calcula
o instante UTC automaticamente. `{}` nos parâmetros ETS assume AAN, 24 meses e
SEM_RECURSO. Janela/multiplosPassos são específicos da Média Móvel e não se misturam
com parâmetros ETS. Alpha e beta são aprendidos pelo motor, não fornecidos no pedido.
Uma variante não suportada ou configuração incompatível recebe 422.

Para autorizar explicitamente o recurso:

```json
{"variante":"AAN","historicoMeses":24,"recurso":"MEDIA_MOVEL","janelaRecurso":3}
```

`janelaRecurso` vai de 1 a 36, predefinida 3 quando o recurso está ativo. Não é aceite
se recurso=SEM_RECURSO. O horizonte mantém-se entre 1 e 12. Permissões, CSRF e
imutabilidade das execuções são iguais às da Média Móvel.

## Rastreabilidade e consultas

Os parâmetros normalizados do pedido ficam no cabeçalho. `/diagnosticos` passa
a incluir `parametrosAplicados`, com os dados efetivos de cada produto. Para ETS:
alpha, beta/definição, SSE, número de observações, início/fim de treino, estados
iniciais/finais, política e quantidade de previsões truncadas. Método efetivo
`ETS(A,A,N)`. Para recurso, a janela Média Móvel e o motivo são guardados sem inventar
coeficientes ETS. Para insuficientes, o método aplicado continua null.

Os cabeçalhos leem o JSON realmente guardado, incluindo em execuções antigas da
Média Móvel; não reconstroem parâmetros com os defaults atuais. Campos de contagem:

- produtosPrevistos: produtos que geraram linhas, incluindo os de recurso;
- produtosRecurso: subconjunto dos anteriores com estado RECURSO;
- produtosInsuficientes: sem histórico suficiente, sem linhas;
- produtosErro: falha numérica/limite, sem linhas.

CONCLUIDA significa processamento terminado, não que todos os produtos tenham
previsões ETS válidas. A soma de previstos, insuficientes e erro é a população;
recurso não deve ser somado novamente. Pedidos repetidos criam outro ID, mantendo
resultados iguais para os mesmos dados/configuração/versão. Falhas SQL revertem a
execução inteira; diagnósticos numéricos por produto deixam os restantes avançar.

## Referência independente

O Java foi comparado com statsmodels 0.15.0, ETSModel com error=add, trend=add,
seasonal=None e estados conhecidos. O estado pré-primeira-observação do statsmodels
é definido como `nivelInicial - tendenciaInicial`, de forma a alinhar com o estado
Java depois da primeira observação. Não se compara com defaults de inicialização
ou otimização diferentes.

As fixtures em `backend/src/test/resources/ets/reference-statsmodels.json` abrangem
oito séries, com coeficientes fixos e com a mesma grelha: constante, zero, tendência,
irregular, zeros intercalados, declínio e o exemplo SKU001. Os testes comparam as
previsões publicadas, coeficientes, SSE e estados finais. Os testes Maven usam o
JSON guardado; não precisam de Python nem statsmodels.

Para regenerar apenas a referência, opcionalmente:

```bash
python3 -m venv /tmp/sop-ets-reference
/tmp/sop-ets-reference/bin/pip install statsmodels==0.15.0
/tmp/sop-ets-reference/bin/python scripts/gerar_referencia_ets.py
```

Isto é ferramenta de validação, não uma dependência do backend ou dos scripts
interativos de utilização. Alterações às referências devem ser revistas no Git.

## Experimentar com a conta existente

```bash
python3 scripts/testar_ets.py
```

Usar ADMIN e aceitar os defaults com Enter: lote validado proposto, ETS, VENDAS,
último mês completo (outubro de 2026 nesta demonstração), até 24 meses, SEM_RECURSO
e horizonte 3. Para o exemplo SKU001 completo:

| Período | ETS(A,A,N) |
|---|---|
| 2026-11 | 23.4382 |
| 2026-12 | 23.8311 |
| 2027-01 | 24.2239 |

Esperado: CONCLUIDA, 1 previsto, 0 recursos, 0 insuficientes, 0 erros e 3 linhas.
O diagnóstico deve mostrar ETS(A,A,N), 24 observações, alpha=0.5 e beta=0.05.
Estes valores correspondem ao CSV de exemplo, não a qualquer histórico.
O script comum `testar_previsao.py` continua a propor MEDIA_MOVEL e agora permite
escolher o método. Ainda não existe um endpoint de comparação de métricas.

## Testes e Git

```bash
cd backend
./mvnw verify
```

Além dos testes anteriores, verificam-se as referências independentes, clipping,
coeficientes, validação, histórico limitado, lacunas/corte, recurso explícito e
insuficiente, overflow por produto, repetição, leitura dos parâmetros antigos e
rollback de toda a execução perante falha na segunda linha prevista.

Verificado em 10 de novembro de 2026: 46 testes aprovados, sem falhas, erros ou
testes ignorados. Incluem os 36 anteriores, 3 do motor ETS (com oito referências
statsmodels) e 7 testes HTTP/MySQL para ETS. A imagem foi reconstruída, ambos os
serviços ficaram saudáveis e os dados da execução de Média Móvel já existente foram
comparados antes/depois sem alterações. O endpoint sem sessão continua a devolver
401. Os scripts interativos foram verificados sintaticamente; o ensaio com a conta
real fica para o estudante. Não foram criadas execuções ETS na base do projeto.

Na raiz, para guardar este bloco após o commit da Média Móvel:

```bash
git add backend README.md docs/previsao-media-movel.md docs/previsao-ets.md scripts/testar_previsao.py scripts/testar_ets.py scripts/gerar_referencia_ets.py
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: acrescentar ETS com parametros e recurso explicito"
git push
```

## Fontes

- [Hyndman e Athanasopoulos: suavização exponencial e ETS](https://otexts.com/fpp3/exponential-smoothing.html)
- [statsmodels: ETSModel](https://www.statsmodels.org/stable/generated/statsmodels.tsa.exponential_smoothing.ets.ETSModel.html)
