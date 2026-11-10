# Cenários, revisões e ajustes auditados

Este bloco implementa a fase 4 do backend. Usa previsões operacionais concluídas
(Média Móvel ou ETS); resultados de backtest não são previsões para planeamento.
O fluxo de aprovação e a publicação de planos pertencem à fase seguinte.

## Experimentar

Na raiz do projeto, com o backend iniciado e uma previsão concluída:

```bash
python3 scripts/testar_cenarios.py
```

Usar ADMIN ou PLANEADOR. Enter na URL usa `http://localhost:8080`; introduzir email
e password, depois Enter seleciona a execução operacional mais recente elegível.
O script guarda dois cenários (+10% e −10%), permite ajustar a primeira célula
otimista com justificação, copia essa revisão e consulta auditoria e comparação.
Com Enter nos restantes campos, o ajuste acrescenta uma unidade. Os dados são
identificados como ensaio manual e ficam na base local. Repetir cria novos cenários.

O resultado esperado é `Ensaio concluido: criacao, ajuste, copia com auditoria e
comparacao verificados.`, seguido de logout. A cópia tem zero ajustes locais e
um ajuste herdado, com o mesmo autor, justificação e valores do ajuste original.
As quantidades dependem da execução escolhida; não são os resultados do backtest.

## Regras de cálculo e revisão

- Uma criação materializa todas as células da execução numa revisão RASCUNHO,
  número 1, versão 0. Não altera previsões base nem outros cenários.
- Quantidade calculada = base × fator, arredondada a quatro casas com HALF_UP.
  Quantidade final começa igual à calculada e pode receber substituições manuais.
- BASE exige fator 1; OTIMISTA exige >1; CONSERVADOR aceita 0 ≤ fator <1;
  PERSONALIZADO aceita qualquer fator não negativo dentro de DECIMAL(12,6).
  O fator representa variação de procura, sem calcular disponibilidade de stock.
- Quantidades usam DECIMAL(18,4), sem negativos. Excesso de precisão ou de intervalo
  é recusado; um fator que exceda a capacidade das quantidades recusa a criação inteira.
- Nome até 120 caracteres; descrição dos pressupostos obrigatória até 2000;
  justificação do ajuste obrigatória até 1000. Texto só com espaços é inválido.
- Cada ajuste é uma substituição absoluta da quantidade final, não um incremento.
  Regista o valor final anterior, novo valor, sequência por célula, motivo, autor
  autenticado, instante UTC e evento. O cliente não escolhe o autor nem o instante.
- Apenas RASCUNHO aceita ajustes. Neste bloco, as revisões criadas permanecem nesse
  estado; não há endpoint genérico para mudar estado ou editar/apagar a auditoria.

Para uma base de 100: otimista 1,10 → calculada/final 110; ajuste para 120 →
anterior 110, novo 120. A base continua 100 e o conservador 0,90 continua 90.

Uma revisão adicional exige a revisão de origem do mesmo cenário e a sua versão
atual. Omitir o fator, ou indicar o mesmo, copia os valores calculados e finais.
Omitir pressupostos conserva a descrição. Um fator diferente recalcula a partir
da previsão base e deixa de herdar os ajustes anteriores. O tipo do cenário continua
a impor os limites do fator. A origem é preservada em ambos os casos.

A nova revisão recebe número sequencial, RASCUNHO e versão 0, sem alterar a origem.
Pode partir de qualquer estado da origem, permitindo corrigir futuramente uma
revisão devolvida através de uma revisão nova.

## Auditoria e concorrência

A cópia guarda o ID e a versão da origem e, quando conserva os valores, o limite
de ajustes visíveis naquele instante. Uma alteração posterior na origem não muda
os valores nem a auditoria herdada da cópia. Essa regra mantém-se em cópias de cópias.
Mudar o fator interrompe a herança: os ajustes antigos continuam consultáveis na
origem, mas não explicam as novas quantidades recalculadas.

`totalAjustesLocais` conta apenas alterações feitas diretamente nessa revisão.
`GET .../ajustes?incluirHerdados=true` acrescenta as alterações das origens relevantes,
com `herdado=true` e o `revisaoId` original. Não cria ajustes fictícios em nome de quem
copiou. A sequência é local à célula/revisão; o ID identifica globalmente o ajuste.
Os eventos consultados numa revisão são locais; CRIACAO identifica a proveniência.

Cada ajuste bloqueia a revisão, verifica `versaoEsperada` e guarda evento, ajuste,
quantidade final e incremento de versão numa única transação. Uma falha reverte
tudo. Dois pedidos com a mesma versão produzem um sucesso e um `409`.
A criação de revisões também bloqueia o cenário, evitando números repetidos.
As transações de escrita usam READ_COMMITTED e leituras `FOR UPDATE`; as consultas
de comparação usam uma transação de leitura consistente.

As chaves estrangeiras já previstas em V1 ligam cenário à execução, revisão ao
cenário/execução e célula à previsão original. O ajuste referencia a célula e,
através de chave composta, um evento da mesma revisão; esse evento referencia o autor.
Este bloco não altera migrações aplicadas nem precisa de uma migração nova.

Referências técnicas: [transações Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
e [leituras com bloqueio MySQL](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html).

## Endpoints

Todos têm prefixo `/api/v1`. Escrita: ADMIN e PLANEADOR. Consulta: ADMIN, PLANEADOR,
COMERCIAL e APROVADOR. CONSUMIDOR terá acesso a planos publicados, numa fase posterior.
Escritas exigem sessão e CSRF, como nos outros módulos.

| Método e caminho | Resultado |
|---|---|
| `POST /cenarios` | 201: cenário e primeira revisão |
| `GET /cenarios?execucaoId=1` | Lista, com filtro opcional de execução |
| `GET /cenarios/{id}` | Cabeçalho, total de revisões e última revisão |
| `GET /cenarios/{id}/revisoes` | Revisões, da mais recente para a mais antiga |
| `POST /cenarios/{id}/revisoes` | 201: nova revisão a partir da origem |
| `GET /revisoes/{id}` | Estado, versão, fator, pressupostos e contagens |
| `GET /revisoes/{id}/linhas` | Base, calculada, final e desvios por célula |
| `POST /revisoes/{id}/ajustes` | 201: ajuste registado e versão atual |
| `GET /revisoes/{id}/ajustes?incluirHerdados=true` | Auditoria; por omissão só ajustes locais |
| `GET /revisoes/{id}/eventos` | Eventos locais por ordem de criação |
| `GET /comparacao-cenarios?revisaoIds=1,2` | Comparação de 2 a 5 revisões distintas |

Listas usam `page=0&size=50`, máximo 200. Linhas ordenadas por código e mês;
ajustes herdados aparecem antes dos locais, sem incluir alterações posteriores à cópia.
A comparação exige a mesma execução; pagina produtos e mantém todos os meses e
revisões selecionadas de cada produto. Mantém a ordem de `revisaoIds` nas colunas.

IDs são strings nas respostas. Quantidades têm quatro casas; fatores seis.
`desvioAbsoluto` = final − base (diferença com sinal); `desvioPercentual` =
100 × (final − base) / base. Com base zero, o percentual é `null`, mesmo que
a quantidade final seja zero. Não se somam produtos com unidades diferentes.

## Contratos JSON

Criação: `POST /api/v1/cenarios`:

```json
{
  "execucaoId": "1",
  "nome": "Campanha de inverno",
  "tipo": "OTIMISTA",
  "fator": "1.100000",
  "pressupostos": {"descricao": "Hipótese de aumento de procura em 10%."}
}
```

Resposta: `{"cenario": {...}, "revisao": {...}}`. Usar `revisao.id` para consultar
linhas e ajustar; os IDs de cenário e revisão são recursos diferentes.

Ajuste: `POST /api/v1/revisoes/1/ajustes`:

```json
{
  "produtoId": "1",
  "periodo": "2026-11",
  "quantidade": "120.0000",
  "justificacao": "Campanha confirmada pelo departamento comercial.",
  "versaoEsperada": 0
}
```

Resposta ilustrativa `201`:

```json
{
  "ajuste": {
    "id": "1", "eventoId": "2", "revisaoId": "1", "produtoId": "1",
    "periodo": "2026-11", "sequencia": 1,
    "valorAnterior": "110.0000", "valorNovo": "120.0000",
    "justificacao": "Campanha confirmada pelo departamento comercial.",
    "autorId": "1", "autorNome": "Administrador",
    "ocorridoEm": "2026-11-10T18:00:00Z", "herdado": false
  },
  "versaoAtual": 1
}
```

Copiar após esse ajuste: `POST /api/v1/cenarios/1/revisoes`:

```json
{"revisaoOrigemId": "1", "versaoOrigemEsperada": 1}
```

Para recalcular, acrescentar `"fator": "1.200000"` e, opcionalmente,
`"pressupostos": {"descricao": "Nova hipótese de aumento de procura em 20%."}`.

Um conflito de versão responde `409`, `code: "VERSION_CONFLICT"` e
`currentVersion`. O cliente deve atualizar a revisão e mostrar o valor atual antes
de permitir nova decisão; não repetir automaticamente uma substituição manual.
Recurso inexistente/célula fora da revisão: 404. Perfil/CSRF: 403. Fator, quantidade
ou motivo inválidos: 422. Execução não elegível ou mistura de execuções: 409.

## Interface a construir

O ecrã de cenários deve mostrar execução, método, corte, cenário, revisão, estado
e versão no cabeçalho. A grelha compara base/calculada/final por produto/mês e mostra
o desvio. Selecionar uma célula abre quantidade, justificação e histórico auditado.
Criar revisão apresenta a origem e distingue copiar valores de recalcular com fator.
Alterar o fator deve informar que os ajustes deixam de ser herdados.

A monitorização ficará num ecrã de consulta de planos publicados e realizados,
com MAE/MAPE/cobertura e indicadores operacionais; não edita estas células.
Ainda não há frontend neste bloco.

## Verificação

Os testes novos usam MySQL Testcontainers e HTTP real: isolamento de cenários,
motivos obrigatórios, limites/arredondamento, cópia e recálculo, auditoria herdada
após alterações na origem, perfis/CSRF, chaves estrangeiras, comparação com base zero,
ajustes concorrentes, numeração concorrente e rollback após falha de escrita.
Não escrevem no volume de desenvolvimento. O ensaio manual usa os teus dados locais.

Validação executada em 2026-11-10: `./mvnw -B verify` terminou com BUILD SUCCESS,
68 testes, zero falhas, zero erros e zero ignorados (inclui 10 testes deste bloco).
O script passou a verificação sintática Python. O Compose foi reconstruído e iniciou
backend e MySQL saudáveis; saúde/identificação responderam 200 e cenários sem sessão
responderam 401. A comparação SHA-256 do dump de dados de todas as tabelas antes e
depois do arranque confirmou preservação dos dados existentes. O ensaio interativo
com a conta local fica para o utilizador, sem guardar a sua password.

Depois do ensaio manual, na raiz do repositório:

```bash
git add backend README.md docs/cenarios-ajustes.md docs/plano-implementacao.md scripts/testar_cenarios.py
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: gerir cenarios e revisoes com ajustes manuais auditados"
git push
```
