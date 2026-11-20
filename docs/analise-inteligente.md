# Análise Inteligente local

A Análise Inteligente apresenta um relatório detalhado de uma avaliação de um
plano publicado. A NexoPlan constrói os factos e as explicações dos indicadores;
um modelo executado no computador através do Ollama ajuda a ordenar recomendações
compatíveis com esses dados. Não modifica o plano, não aprova decisões e não
recalcula MAE, MAPE ou indicadores operacionais.

## Preparar e utilizar

Requer Docker Compose, espaço para a imagem de execução e aproximadamente
3,3 GB para `qwen3.5:4b-q4_K_M`. O serviço tem um limite de 5 GB de RAM, sem swap adicional, e três CPUs;
a aplicação e o MySQL precisam de recursos adicionais.

```bash
./nexoplan preparar-ia
```

O lançador descarrega o modelo uma vez, ativa `SOP_AI_ENABLED=true` no `.env`
local e reconstrói/inicia a aplicação. Os arranques seguintes usam `./nexoplan`.
`./nexoplan parar` também para a IA, conservando o modelo e os relatórios.
Para desativar a geração, define `SOP_AI_ENABLED=false` no `.env` e reinicia
a aplicação. Os relatórios já guardados continuam consultáveis.

Em **Monitorização**, abre uma avaliação e escolhe **Análise Inteligente**.
A geração é explícita e pode demorar vários minutos num portátil. Podes sair da
página; o trabalho continua no servidor. Ao regressar, consultas o estado ou
o relatório concluído. Atualizar ou reabrir a página não inicia outra geração.

## Conteúdo do relatório

- Contexto: plano, avaliação, janela temporal e corte dos resultados.
- Qualidade dos dados: cobertura, realizados em falta, zeros e exclusões.
- Precisão: base e aprovado sobre os mesmos pares elegíveis, separados por produto.
- Operação: serviço, ruturas, excesso e perdas com a cobertura de cada indicador.
- Interpretação, limitações e ações a investigar, sem afirmar causas desconhecidas.
- Evidência completa por produto e mês para conferir a interpretação.

O texto técnico é construído a partir dos resultados calculados, para manter
corretas as quantidades, as exclusões e as relações entre indicadores. A IA
recebe um catálogo de recomendações elegíveis e devolve a sua ordenação por
identificadores. A aplicação valida essa resposta e apresenta os textos das
ações correspondentes. Não se mostra prosa livre do modelo como se fosse uma
conclusão verificada.

A análise abrange a avaliação completa, independentemente da página ou dos
filtros da tabela. As métricas de produtos ou unidades diferentes não são
fundidas num MAE global. O MAPE exclui realizados zero; o MAE pode incluí-los.
Ausência de dados não é zero, nem indica desempenho perfeito. Uma avaliação
com cobertura nula continua a permitir explicar os motivos e os próximos passos,
mas não permite concluir que o plano melhorou ou piorou a precisão.

O corte e as exclusões da avaliação ficam fixos. Quando houver novos realizados
ou meses entretanto fechados, cria outra avaliação; o relatório antigo conserva
o contexto original.

## Funcionamento técnico

Os cinco perfis com acesso à monitorização podem consultar e pedir a análise.
A criação exige sessão válida e CSRF.

| Método, com prefixo `/api/v1` | Comportamento |
| --- | --- |
| `GET /avaliacoes/{id}/analise-inteligente` | Consulta estado e eventual relatório; nunca inicia inferência |
| `POST /avaliacoes/{id}/analise-inteligente` | Inicia trabalho em segundo plano ou devolve o relatório/estado existente |

O backend lê todas as células num snapshot, calcula os resumos com as funções
de monitorização existentes e liberta a transação antes de contactar o modelo.
O pedido contém contexto e indicadores compactos de todos os produtos. O detalhe
mensal completo fica associado ao relatório para consulta. As explicações
descrevem os resumos por produto e a cobertura por horizonte; o anexo permite
conferir cada célula. Não é enviada ao modelo uma série mensal completa para fundamentar
afirmações sobre tendências ou sazonalidade. Se a avaliação exceder
o limite suportado, o pedido é recusado explicitamente; não se analisa apenas
a primeira página nem se corta a população silenciosamente.

A geração tem concorrência limitada, tempo máximo e tamanho de resposta limitado.
Pedidos repetidos não criam relatórios duplicados; uma falha pode ser repetida
após o intervalo indicado. Guardam-se modelo, versão das instruções, impressão
digital dos dados, estado e data de geração. Reiniciar o backend interrompe um
trabalho ainda em curso; a interface permite voltar a tentar.

## Privacidade e limites

O Compose não publica a porta do Ollama. `OLLAMA_NO_CLOUD=1` mantém a execução
local; o download inicial requer Internet. Não há chave de API nem faturação
por pedido. Existem custos de armazenamento, eletricidade e recursos do computador.
Não se enviam nomes de funcionários, emails, fotografias, credenciais ou
justificações livres ao modelo. O texto gerado é tratado como texto sem permissões
de HTML, scripts, ferramentas ou alterações na base de dados.

A IA pode sugerir uma ordem de prioridades pouco útil. A lista de ações é
limitada às condições observadas, mas a decisão continua a ser humana. A
evidência calculada permite conferir o relatório; a análise não comprova
relações de causa e efeito. O modelo local pequeno privilegia a execução num
portátil; a velocidade depende do equipamento e do modelo.

Referências técnicas: [Ollama em Docker](https://docs.ollama.com/docker),
[API de geração](https://docs.ollama.com/api/generate),
[respostas estruturadas](https://docs.ollama.com/capabilities/structured-outputs)
e [modelo Qwen3.5 4B](https://ollama.com/library/qwen3.5:4b-q4_K_M).
