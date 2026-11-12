# NexoPlan — Manual de utilização

Este manual acompanha a interface integrada. Para instalar e iniciar a aplicação,
seguir o [README](../README.md). As contas são atribuídas pelo administrador.
Não existe registo público.

## Entrar e encontrar a área de trabalho

Abrir o endereço da instalação no navegador e introduzir email e password.
Cada novo login abre a Visão geral do respetivo perfil. Atualizar uma página com
sessão válida mantém essa página. Terminar sessão antes de trocar de conta.

A navegação acompanha o trabalho: **Dados** (produtos/importações), **Previsão**
(previsões/comparação de modelos), **Decisão** (cenários/planos) e **Resultados**
(monitorização). Só aparecem as áreas permitidas ao perfil. **Equipa** é exclusiva
do administrador. No telemóvel, usar **Abrir navegação**.

| Perfil | Trabalho principal |
|---|---|
| Administrador | Gerir contas e catálogo; preparar dados, previsões, cenários e avaliações |
| Planeador | Preparar dados e alternativas, justificar ajustes e enviar para aprovação |
| Comercial | Consultar previsões, cenários, planos e resultados |
| Aprovador | Analisar revisões e aprovar ou devolver, cumprindo a independência |
| Consumidor | Consultar produtos, planos publicados e monitorização |

Uma conta pode ter vários papéis. Ser administrador, por si só, não permite aprovar.
O aprovador não pode decidir uma revisão criada por si nem uma que conserve ajustes
seus. O backend verifica estas condições mesmo que o endereço seja aberto diretamente.

## 1. Preparar os produtos

Em **Dados → Produtos**, procurar pelo código ou nome antes de criar um produto.
Usar **Novo produto**, indicar código único, nome e unidade, por exemplo `un`, `kg`
ou `L`. O código e a unidade devem coincidir com os ficheiros a importar.

Depois de haver dados associados, código e unidade ficam fixos. Editar permite
alterar nome/estado de acordo com as regras apresentadas. Desativar um produto
preserva o histórico; não é uma operação de eliminação.

## 2. Importar e validar dados

Em **Dados → Importações → Importar CSV**:

1. Escolher o tipo de dados e indicar a origem.
2. Selecionar o ficheiro e consultar o formato esperado, se necessário.
3. Deixar a correção de lote sem seleção numa primeira importação.
4. Importar uma vez e consultar o resultado.

| Tipo | Utilização |
|---|---|
| Histórico de vendas / consumo | Treinar previsões e comparar modelos |
| Procura realizada | Comparar o que aconteceu com o plano |
| Resultados operacionais | Medir serviço, ruturas, stock e perdas |

O CSV usa UTF-8, separador `;`, cabeçalho, meses `AAAA-MM` e ponto decimal.
A data de disponibilidade indica quando o registo ficou conhecido, em UTC (`Z`).
Os produtos devem existir no catálogo. Os limites e cabeçalhos estão em
[importação de dados](importacao-historicos.md) e na ajuda do formulário.

**Validado** permite utilizar o lote. **Rejeitado** conserva os diagnósticos:
consultar linha, campo e motivo; corrigir o ficheiro e importar um novo lote.
Um lote rejeitado não publica apenas a parte válida.

Um lote é um snapshot completo do seu tipo. Para corrigir um anterior, selecionar
esse lote no formulário e enviar o snapshot corrigido completo. O anterior permanece
conservado; não enviar apenas as linhas alteradas como se fossem uma atualização.

## 3. Gerar a previsão base

No detalhe de um histórico validado, usar **Gerar previsão** para levar esse lote
já escolhido. Também é possível abrir **Previsão → Previsões → Gerar previsão**:
pesquisar pelo ficheiro/origem ou escolher entre os históricos recentes. Confirmar
um resultado; escrever na pesquisa, por si só, não seleciona um lote.

Preencher série, horizonte, último mês de treino e dados conhecidos até. Escolher:

- **Média Móvel recursiva:** indicar a janela de meses. Os passos seguintes usam
  também previsões já calculadas nessa execução.
- **ETS com tendência aditiva:** indicar o máximo de meses de treino e a política
  para séries curtas. A variante implementada é ETS(A,A,N), sem sazonalidade.

Confirmar a execução concluída, as quantidades e a qualidade do cálculo por produto.
Um histórico insuficiente ou um método de recurso fica identificado. A previsão
base é conservada; criar cenários não a modifica.

**Dados conhecidos até** e **último mês de treino** são diferentes: o primeiro limita
quando a informação ficou disponível; o segundo limita os meses utilizados no treino.
Uma lacuna no histórico não equivale a procura zero.

## 4. Comparar os modelos

Em **Previsão → Comparar modelos**, criar um backtest e confirmar histórico,
horizonte e cortes sugeridos. Cada corte identifica o último mês de treino.
A comparação usa os mesmos pares elegíveis para Média Móvel e ETS.

Consultar MAE, MAPE e cobertura por produto/horizonte. Valores menores de erro
favorecem o modelo apenas no conjunto e período avaliados. O backtest é uma simulação
retrospetiva; não publica um plano nem garante o mesmo resultado no futuro.

## 5. Criar cenários e ajustar quantidades

Abrir uma previsão e usar **Explorar cenários**. Confirmar a **Previsão de origem**.
Criar um cenário com nome, tipo, fator e pressupostos. Por exemplo, fator `1,10`
representa mais 10% de procura; `0,90` representa menos 10%.

| Conceito | Significado |
|---|---|
| Previsão base | Resultado conservado do modelo |
| Cenário | Hipótese alternativa, como uma campanha ou procura conservadora |
| Revisão | Versão guardada de um cenário; tem ID único e ordem própria |
| Plano publicado | Cópia das quantidades finais aprovada por uma pessoa independente |

**Novo cenário** cria outra hipótese. **Criar nova revisão** prepara outra versão
da mesma hipótese e conserva a origem. O ID `Revisão #9`, por exemplo, é distinto
da indicação `3.ª versão deste cenário`.

Numa revisão **Rascunho**, usar **Ajustar**, indicar a nova quantidade final e uma
justificação de negócio. Guardar e confirmar o valor na tabela e na auditoria,
com autor, instante e anterior/novo. A base e os outros cenários permanecem intactos.

Na cópia sem recálculo, valores e auditoria relevante são conservados. Escolher
outro fator com recálculo volta à base e não herda os ajustes anteriores. Ler a
confirmação antes de criar a revisão.

Para comparar, selecionar de dois a cinco cenários da mesma previsão. A comparação
usa a última revisão de cada cenário selecionado, identificada no resultado.
O histórico permite consultar versões anteriores e regressar à revisão de origem.

## 6. Enviar, decidir e publicar

| Estado | Ação seguinte |
|---|---|
| Rascunho | Ajustar com justificação ou enviar para aprovação |
| A aguardar aprovação | Aprovador independente analisa e decide; quantidades congeladas |
| Devolvida | Consultar motivo e criar nova revisão para corrigir |
| Aprovada | Consultar o plano publicado; preparar outra revisão para alterações futuras |

O planeador usa **Enviar para aprovação**. O aprovador entra com a sua própria
conta, abre a fila na Visão geral e consulta pressupostos, valores e auditoria.
Pode **Devolver**, com motivo obrigatório, ou **Aprovar e publicar**.

Se houver conflito porque outra pessoa alterou a revisão, atualizar os dados e
voltar a analisar antes de decidir. A aplicação não repete automaticamente a escrita.
Se uma ligação cair durante uma operação, consultar o resultado antes de repetir.

## 7. Consultar e exportar o plano

Em **Decisão → Planos publicados**, abrir o plano para consultar origem, aprovação
e quantidades. **Descarregar CSV** inclui todas as linhas, mesmo quando a tabela
está paginada. CSV e API de leitura usam a mesma cópia publicada.

O plano publicado não é editado diretamente. Uma decisão posterior exige nova
preparação e aprovação; o plano anterior continua disponível para auditoria.

## 8. Monitorizar os resultados

Importar realizados e, para indicadores operacionais, um lote operacional.
Em **Resultados → Monitorização → Nova avaliação**, escolher plano, lotes,
janela mensal e corte dos resultados. Uma nova avaliação conserva as anteriores.
Selecionar o produto/unidade para interpretar os indicadores.

| Indicador | Leitura |
|---|---|
| MAE base / aprovado | Erro absoluto médio na unidade do produto; menor é melhor |
| MAPE base / aprovado | Erro percentual médio; realizados iguais a zero são excluídos |
| Cobertura | Parte das células candidatas que pôde ser avaliada |
| Ganho do ajuste | MAE base menos MAE aprovado; positivo indica menor erro após ajuste |
| Nível de serviço | Satisfação da procura no prazo, com dados elegíveis |
| Taxa de rutura | Dias com procura e sem stock face aos dias observados com procura |
| Excesso de stock / perdas | Quantidades calculadas a partir dos registos operacionais elegíveis |

**Indisponível** significa que faltam condições para calcular a métrica. Consultar
**Detalhe e cobertura**: publicação tardia, mês ainda não fechado, realizados em
falta ou informação conhecida após o corte são motivos distintos. Cobertura 0%
indica ausência de células elegíveis; não é uma medição do erro da previsão.

Exemplo: um plano publicado a 10 de novembro não permite avaliar novembro como
uma previsão publicada antes do mês. Dezembro e janeiro só podem ser avaliados
quando fechados e com dados elegíveis. Importar mais dados não modifica uma avaliação
já guardada: criar outra avaliação com os lotes/corte adequados. Novembro conserva
a exclusão por publicação tardia.

As fórmulas e condições completas estão em [monitorização](monitorizacao.md).
Não somar quantidades de produtos com unidades diferentes.

## Ler os gráficos e obter ajuda

Mover o rato dentro da área do gráfico seleciona o mês mais próximo na horizontal.
A linha vertical e a caixa apresentam as séries desse mês em conjunto, com os valores
exatos disponíveis. No teclado, focar um ponto; no ecrã tátil, tocar na área do gráfico.
Escape ou um toque fora permitem fechar a consulta. Valores ausentes continuam
indisponíveis e não originam pontos artificiais.

Os gráficos identificam o produto/unidade no contexto; alguns mostram apenas o
primeiro produto da página. Consultar a tabela para os restantes registos.
As ajudas **Sobre esta área**, os ícones de informação e as explicações expansíveis
podem ser abertas quando necessário. A tabela e a auditoria mantêm o detalhe.

## Encontrar registos nas tabelas

Usar a pesquisa da própria tabela para procurar em todas as páginas. Nos registos
de produtos, pesquisar pelo código ou nome e limitar os meses quando necessário.
Pesquisar reinicia a primeira página; Limpar filtros recupera os resultados.
Os filtros permanecem ao atualizar a página e não alteram os dados originais.
O CSV do plano continua a exportar todas as linhas. Ver [campos por secção](pesquisa-filtros.md).
Na Equipa, o ADMIN pode procurar membros pelo nome, email ou perfil.

## Problemas frequentes

| Situação | Como proceder |
|---|---|
| Sessão terminada | Entrar novamente com a própria conta |
| Acesso recusado | Confirmar o perfil com o administrador |
| Histórico não aparece | Confirmar tipo Histórico e estado Validado; pesquisar pelo ficheiro/origem |
| CSV rejeitado | Consultar diagnósticos e importar um snapshot corrigido |
| Não consigo ajustar | Confirmar que é Rascunho e que a conta tem permissão de preparação |
| Não consigo aprovar | Confirmar papel Aprovador e independência face à criação/ajustes herdados |
| Métrica indisponível | Consultar cobertura, motivos de exclusão, meses e corte |

Para manutenção, cópias e recuperação, seguir [backup e restauro](backup-restauro.md).

Na área Equipa, a pesquisa encontra membros na lista. O botão **Consultar** de
cada linha abre os detalhes da pessoa selecionada numa janela; não é necessário introduzir
o ID noutra pesquisa. As responsabilidades estão disponíveis em **Responsabilidades por perfil**.
