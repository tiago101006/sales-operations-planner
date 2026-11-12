# Monitorização de planos publicados

O backend da fase 6 importa realizados/operacionais e cria avaliações reproduzíveis
de planos publicados. A interface e a avaliação final de usabilidade/desempenho
permanecem para os próximos blocos.

## Experimentar na conta local

Na raiz do repositório:

```bash
python3 scripts/testar_monitorizacao.py
```

Usar ADMIN/PLANEADOR; Enter aceita URL, email ADMIN e plano mais recente. O script
cria dois CSV SINTÉTICOS, importa dois lotes e guarda uma avaliação. Não altera o
plano nem usa estes dados como medições reais do negócio. Guarda os exemplos em
`tmp/monitorizacao/`, ignorado pelo Git. Repetir cria novos lotes/avaliação.

Quando o plano ainda não tem meses fechados, o script usa o último mês fechado
para testar as importações. Esses dados não correspondem aos meses futuros do plano:
a avaliação deve mostrar exclusões temporais, cobertura 0 e MAE/MAPE `null`.
No plano local publicado em 2026-11-10 para novembro–janeiro, com corte no próprio
dia: novembro → PLANO_PUBLICADO_TARDIAMENTE; dezembro/janeiro → PERIODO_NAO_FECHADO.
Isto não é um erro nem significa precisão perfeita. Não se inventam realizados futuros.

Se houver meses fechados, o ensaio usa até três meses do primeiro produto, inclui
um zero no segundo e deixa o terceiro sem realizado. Mesmo assim, meses cujo plano
foi publicado tardiamente continuam excluídos.

Mensagem final:

```text
Ensaio concluido: realizados, operacionais, avaliacao, cobertura e exclusoes verificados.
Logout concluido.
```

O teste automático com datas passadas controladas verifica o caso de aceitação:
realizados 100, 0 e falta; base 90, 10, 30; aprovado 110, 0, 20 → MAE 10/5,
MAPE 10%/10%, dois pares MAE, um par MAPE e cobertura 66,6667%. As datas controladas
existem exclusivamente no MySQL temporário dos testes; a API não permite retroceder
a data de publicação dos planos locais.

## Importação de resultados

Reutiliza `POST /api/v1/lotes`, multipart com `ficheiro`, `tipo`, `origem`,
`versaoEsquema=v1` e `substituiLoteId` opcional. ADMIN e PLANEADOR podem importar.
Mantém limites de 10 MiB/100000 registos, UTF-8, separador `;`, ponto decimal,
SHA-256, original no volume de importações e rejeição integral com diagnósticos.
Um erro produz 422 com `loteId`; não publica linhas parciais.

REALIZADOS:

```csv
produto;periodo;quantidade;unidade;disponivel_em
SKU001;2025-02;100.0000;un;2025-03-01T00:00:00Z
SKU001;2025-03;0.0000;un;2025-04-01T00:00:00Z
```

A observação recebe tipo PROCURA_REALIZADA no servidor. Não se acrescenta este
tipo ao contrato das previsões: os motores continuam a aceitar VENDAS/CONSUMO.
Quantidade não negativa, até 14 algarismos inteiros e quatro casas; vazio é inválido.

OPERACIONAL:

```csv
produto;periodo;unidade;unidades_solicitadas;unidades_satisfeitas_no_prazo;dias_observados_com_procura;dias_com_procura_sem_stock;stock_fecho;limite_stock;unidades_perdidas;prazo_medio_dias;disponivel_em
SKU001;2025-02;un;100;80;20;2;30;10;3;2;2025-03-01T00:00:00Z
```

Todos os cabeçalhos são obrigatórios. Valores operacionais vazios ficam NULL;
exigir pelo menos um valor por registo. Solicitadas/satisfeitas e dias observados/
sem stock são pares: ambos preenchidos ou ambos vazios; o numerador não excede
o denominador. Dias são inteiros entre zero e o comprimento do mês. Quantidades
não negativas até DECIMAL(18,4); prazo médio até DECIMAL(10,4). Stock e limite
podem faltar independentemente, tornando o excesso indisponível.

Ambos os tipos exigem produto existente, unidade compatível, mês válido, chave
produto/mês única e disponibilidade UTC com Z, sem futuro e a partir das 00:00 UTC
do primeiro dia do mês seguinte. São agregados mensais fechados. Podem referenciar
produtos entretanto inativados, preservando a monitorização de planos antigos.

Uma correção aponta para lote VALIDADO do mesmo tipo e cria um snapshot completo
novo; não junta linhas nem altera lotes/avaliações anteriores. HISTORICO conserva
o seu contrato e regras anteriores. CALENDARIO/PROMOCOES ainda não são suportados.

Consultas por lote (os quatro perfis internos, sem CONSUMIDOR):

- `GET /lotes/{id}/observacoes`: HISTORICO ou REALIZADOS validado.
- `GET /lotes/{id}/operacionais`: OPERACIONAL validado. Cada item inclui ID,
  produtoId/produto, mês/unidade/disponibilidade e `valores`, com nomes dos campos CSV.
- `GET /lotes/{id}/erros`: diagnósticos; lote/original rejeitado são conservados.

## Avaliações e API

Criar: ADMIN/PLANEADOR. Consultar: os cinco perfis, incluindo CONSUMIDOR. Escrita
exige sessão e CSRF. Os resultados são de planos publicados; não expõem rascunhos.

| Método e caminho, com prefixo `/api/v1` | Resultado |
|---|---|
| `POST /avaliacoes` | 201, cabeçalho e Location da avaliação |
| `GET /avaliacoes?planoId=1` | Lista, filtro opcional de plano |
| `GET /avaliacoes/{id}` | Plano, lotes, janela, corte, autor e versão do cálculo |
| `GET /avaliacoes/{id}/precisao` | Produtos, resumo e discriminação por horizonte |
| `GET /avaliacoes/{id}/operacao` | Indicadores operacionais por produto |
| `GET /avaliacoes/{id}/celulas` | Valores, proveniência e motivos de exclusão |

Listagens paginadas com `page=0&size=50`, máximo 200. Precisão/operação paginam
produtos, mantendo todos os seus meses. Células ordenadas por código/mês. IDs como
strings; quantidades/indicadores com quatro casas; indisponibilidade como `null`.

Pedido:

```json
{
  "planoId": "1", "loteRealizadosId": "3", "loteOperacionalId": "4",
  "periodoInicio": "2026-11", "periodoFim": "2027-01",
  "corteRealizados": "2026-11-10T21:00:00Z"
}
```

O exemplo é ilustrativo: o corte real deve ser ≤ agora. Lote operacional opcional;
realizados obrigatório. Janela entre 1 e 120 meses, com pelo menos uma célula do
plano. Plano publicado e lotes do tipo correto, VALIDADOS e disponíveis/validados
no corte; corte não anterior à publicação. Cada criação recebe novo ID.

A avaliação fixa lotes, janela, corte, versão `monitoring-common-pairs-v1` e todas
as células do plano na janela, mesmo sem realizado. A FK composta impede associar
observação de outro lote/produto/mês. Cabeçalho e células persistem atomicamente.
As tabelas já existem em V1; não há migração nova.

## Precisão e cobertura

A decisão tem de estar publicada estritamente antes das 00:00 UTC do primeiro dia
do mês previsto, e o mês tem de estar fechado no corte. Classificação por prioridade:

1. PLANO_PUBLICADO_TARDIAMENTE.
2. PERIODO_NAO_FECHADO.
3. SEM_REALIZADO.
4. REALIZADO_APOS_CORTE.
5. Elegível: `motivoExclusao=null` e `elegivelPrecisao=true`.

`elegivelTemporal` corresponde às duas primeiras condições; pode ser true mesmo
sem realizado ou com realizado ainda desconhecido no corte. Quantidade realizada
após o corte é ocultada no resultado, mantendo ID/disponibilidade para diagnóstico.

Base e aprovado usam os mesmos pares elegíveis. MAE inclui zeros; MAPE exclui os
realizados zero. Sem pares, devolver null. Não calcular percentagens com denominador
zero. Ganho do ajuste = MAE base − MAE aprovado; positivo indica menor erro.

`metricas` inclui candidatos, paresMae, paresMape, zerosExcluidosMape, emFalta,
realizadosAposCorte, planosTardios, periodosNaoFechados, coberturaPercentagem,
maeBase/maeAprovado, mapeBase/mapeAprovado e ganhoAjuste.

Cobertura = 100 × paresMae / candidatos. Os motivos são exclusivos e a soma dos
seus contadores com paresMae é igual aos candidatos. O resumo de cada produto
inclui `porHorizonte`, evitando esconder diferenças entre 1, 2 ou 3 meses.
Não se agregam erros de produtos/unidades diferentes.

## Indicadores operacionais

Usam células temporalmente elegíveis e registos conhecidos no corte, independentemente
de existir realizado para precisão. Sem lote/registo/conhecimento no corte, o detalhe
inclui SEM_LOTE_OPERACIONAL, SEM_REGISTO_OPERACIONAL ou OPERACIONAL_APOS_CORTE.
Um campo em falta não bloqueia os outros indicadores do mesmo registo.

- Serviço = 100 × satisfeitas no prazo / solicitadas. Agregado por somas, sem
  fazer média simples de percentagens mensais. Sem solicitações → null.
- Rutura = 100 × dias sem stock e com procura / dias observados com procura.
  Agregado por somas. Sem dias observados → null. Não inferir a partir do stock mensal.
- Excesso por célula = max(stock de fecho − limite, 0), ou null com entrada em falta.
  O resumo usa a média dos excessos conhecidos (`excessoStockMedio`), não a soma
  de snapshots de stock ao longo do tempo.
- Perdas: soma das unidades perdidas conhecidas do produto; null se todas faltam.
- Prazo médio: apenas no detalhe mensal, sem média entre meses sem pesos de amostra.

O resumo mostra candidatos, elegiveisTemporais, registosConhecidos, paresServico,
paresRutura, paresStock e registosPerdas, permitindo avaliar a cobertura de cada
indicador. Zeros explícitos distinguem-se de campos ausentes. Resultados operacionais
descrevem os períodos observados; não demonstram causalidade do plano.

## Interface a construir

Cabeçalho com plano, lotes, janela e corte. Separar precisão (MAE/MAPE base/aprovado,
ganho e cobertura, com seleção de produto/horizonte) de operação (serviço, rutura,
excesso, perdas). Uma tabela detalha valores e razões de exclusão. Mostrar
"Indisponível" para null. Este ecrã é de consulta; ajustes continuam no ecrã de
cenários, numa nova revisão. Acrescentar ligação ao plano e às justificações.

Referências: [avaliação de previsões, FPP3](https://otexts.com/fpp3/toolbox.html#evaluating-point-forecast-accuracy)
e [integridade referencial MySQL](https://dev.mysql.com/doc/refman/8.4/en/constraint-foreign-key.html).

## Validação desta entrega

Em 2026-11-10, `./mvnw -B verify` terminou com BUILD SUCCESS: 92 testes, zero falhas,
zero erros e zero ignorados. Inclui 11 testes novos (9 de integração HTTP/MySQL e
2 do parser): caso MAE/MAPE, zeros/faltas, ponderação de serviço/rutura, campos nulos,
datas/corte/publicação, correções preservadas, perfis/CSRF, rejeição integral,
proveniência por FK e rollback do cabeçalho quando falha a gravação de células.
O parser também foi verificado com UTF-8 inválido, CSV inválido e 100001 registos.

A verificação sintática Python e os contratos JSON/links locais passaram. Docker
reconstruído com backend/MySQL saudáveis; saúde/identificação 200; avaliações sem
sessão 401. O SHA-256 do dump de dados de todas as tabelas ficou igual antes/depois
do arranque. O ensaio interativo com credenciais locais fica para o utilizador.
