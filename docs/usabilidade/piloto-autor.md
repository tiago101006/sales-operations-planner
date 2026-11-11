# NexoPlan — Piloto guiado pelo próprio autor

Este roteiro serve para ensaiar o percurso e encontrar dificuldades antes das
sessões com colegas. É uma revisão guiada do autor, separada da avaliação com
participantes. Registar apenas o que for realmente executado e observado.

## Preparar a cópia

Na raiz do projeto, uma cópia do último backup pode ser aberta com:

```bash
python3 scripts/backup.py verificar --ultimo --manter
```

Usar a URL alternativa que o script imprime, num perfil/janela privada. Guardar
também os comandos de remoção dessa cópia. O script conserva os dados originais
separados do ensaio. Se a cópia já foi preparada nesta conversa, usar a URL fornecida
em vez de criar outra.

Criar uma ficha em `tmp/usabilidade/PILOTO-AUTOR.md`, fora do Git. Registar URL,
backup, versão, navegador, zoom e as observações; deixar tempos e opiniões em branco
até os observar. Marcar a sessão como **piloto guiado do autor**.

## 1. Entrar e preparar a conta de trabalho

1. Na cópia, entrar com a conta ADMIN existente no backup e a password habitual.
2. Em **Equipa → Criar utilizador**, criar nome `Piloto do autor`, email
   `piloto@exemplo.local` e apenas o papel PLANEADOR. Escolher uma password com
   pelo menos 12 caracteres e até 72 bytes; não a guardar na ficha nem enviar no chat.
3. Terminar sessão e entrar com a nova conta. Abrir **Visão geral**.
4. Confirmar o painel **Espaço de planeamento** e a ausência do menu Equipa.

Antes das tarefas, registar incómodo visual de 0 (nenhum) a 10 (muito elevado).
O objetivo é registar a opinião real, não obter uma classificação específica.

## 2. Importar o histórico

1. Abrir **Importações → Importar CSV**.
2. Escolher **Histórico de vendas / consumo** e origem `Piloto do autor`.
3. Selecionar [historico_sku001_valido.csv](../../data/exemplos/historico_sku001_valido.csv).
4. Deixar a correção de lote anterior sem seleção e importar uma vez.
5. Confirmar estado **Validado**, 24 registos e produto SKU001. Anotar o ID do lote.

Se SKU001 não existir ou estiver inativo, preparar o catálogo na cópia antes de
repetir. Não corrigir a base principal para resolver um problema deste ensaio.

Anotar: encontraste o botão e a confirmação facilmente? Alguma mensagem foi confusa?

## 3. Gerar uma previsão de referência

Usa **Gerar previsão** no detalhe do histórico acabado de importar:
nesse percurso, o lote já vai escolhido. Pela página de previsões, o campo começa
vazio e permite reconhecer os históricos pelo ficheiro, origem e data, sem decorar IDs.
Preencher:

| Campo | Valor |
|---|---|
| Histórico | O histórico importado nesta tarefa, já escolhido pelo atalho |
| Série | Vendas |
| Horizonte | 3 meses |
| Último mês de treino | 2026-10 |
| Dados conhecidos até | Conservar o instante atual sugerido |
| Método | Média Móvel recursiva |
| Janela | 3 |

Gerar e confirmar execução **Concluída**, um produto previsto e três linhas.
Anotar o ID da execução e a quantidade base do primeiro mês.
As datas correspondem a este CSV de referência; não se está a medir cumprimento de
um plano publicado no passado.

## 4. Criar e comparar duas alternativas

1. Na execução, selecionar **Explorar cenários**.
2. Criar `Piloto — Otimista`, tipo Otimista, fator `1,10` e pressupostos
   `Piloto do autor: hipótese de mais 10% de procura`.
3. Voltar à lista da mesma execução e criar `Piloto — Conservador`, tipo
   Conservador, fator `0,90` e pressupostos
   `Piloto do autor: hipótese de menos 10% de procura`.
4. Selecionar as duas revisões na lista e usar **Comparar selecionadas**.
5. Confirmar que a quantidade otimista está acima da base e a conservadora abaixo.
   Anotar os três valores do primeiro mês e os IDs das revisões.

Explicar por palavras próprias: o que é a previsão base, o que muda num cenário e
quando uma revisão se torna um plano publicado?

Anotar: a seleção foi evidente? Os rótulos, gráfico e tabela ajudaram a comparar?

## 5. Ajustar e consultar a auditoria

1. Abrir a revisão RASCUNHO de `Piloto — Otimista`.
   Se já a enviaste para aprovação, usar **Criar nova revisão** para preparar
   outra versão do mesmo cenário e anotar o novo ID. A versão enviada fica conservada.
2. Na primeira linha de SKU001, selecionar **Ajustar**.
3. Introduzir quantidade final `30,0000` e justificação
   `Campanha de ensaio confirmada`; guardar uma vez.
4. Confirmar a quantidade 30,0000 e o novo registo na auditoria.
5. Em **Auditoria dos ajustes**, confirmar autor, motivo e valores anterior/novo.
6. Voltar à comparação. Confirmar que a previsão base e o cenário conservador
   mantêm os valores anotados anteriormente.

Guardar o ID da revisão e registar se foi claro que o ajuste ficou persistido.
Se for repetido por engano, conservar e descrever o evento adicional, sem apagar
o histórico para fazer a ficha parecer perfeita.

## 6. Fechar o piloto

- Registar a duração real; se seguiste este guia, indicar essa ajuda.
- Registar incómodo visual no fim, pausas e alterações de zoom ou brilho.
- Listar o que foi difícil de encontrar, ler ou compreender, mesmo que funcionasse.
- Separar problemas da aplicação, dúvidas sobre S&OP e falhas de preparação do ensaio.
- Anotar as melhorias propostas e as evidências (mensagem, tarefa e ID relevantes).

Uma sessão curta pode revelar problemas, mas não comprova conforto durante várias
horas. Os resultados deste piloto não entram na contagem dos cinco participantes.
Ao terminar, podem remover-se apenas os recursos desta cópia usando os comandos
impressos pelo script de restauro. A ficha preenchida permanece em `tmp/usabilidade/`.
