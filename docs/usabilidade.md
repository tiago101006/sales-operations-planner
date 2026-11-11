# NexoPlan — Avaliação exploratória de usabilidade e conforto

**Estado:** protocolo preparado; piloto guiado do autor em curso; recolha com colegas pendente.

## Objetivo e amostra

Observar se as pessoas encontram as funções, compreendem os estados e conseguem
importar um histórico, comparar cenários e justificar um ajuste. Observar também
legibilidade, densidade e conforto durante uma sessão de trabalho.

O RNF01 do relatório preliminar propõe que pelo menos quatro de cinco participantes
representativos do perfil de planeador concluam esse guião sem intervenção do
moderador. O V06 prevê registos por tarefa e interpretação da base, cenário e plano.

O estudante indicou que os participantes serão sobretudo **colegas de curso**.
Nesta etapa, usar uma amostra de conveniência e apresentar os resultados como
**exploratórios**. Registar experiência em planeamento e familiaridade com a
aplicação. Mesmo que quatro de cinco colegas terminem autonomamente, isso não
comprova, por si só, o RNF01 no público profissional pretendido. Essa validação e a
adequação da amostra à avaliação académica continuam por confirmar com a tutoria.

Preparar um piloto para testar o guião e, depois, procurar cinco participantes
distintos, identificados como P01–P05. O piloto não entra na contagem. Não contar a
mesma pessoa duas vezes como participantes independentes nem substituir resultados
fracos por novas tentativas. Com menos de cinco participantes, reportar o número
real e deixar a comparação com a meta de cinco por avaliar.

## Material

- [Piloto guiado para o próprio autor](usabilidade/piloto-autor.md).
- [Cartões de tarefa para o participante](usabilidade/guiao-participante.md).
- [Ficha individual de sessão](usabilidade/ficha-sessao.md).
- [Síntese dos resultados e problemas](usabilidade/sintese-resultados.md).
- CSV existente `data/exemplos/historico_sku001_valido.csv`.
- Cronómetro e navegador; uma cópia restaurada por sessão.

Copiar as fichas para `tmp/usabilidade/`, já ignorado pelo Git. Os modelos em `docs/`
devem continuar vazios. Guardar apenas códigos de participante, sem nomes reais,
contactos ou passwords. Não gravar áudio/vídeo por omissão. Uma eventual gravação
exige autorização específica e deve seguir as orientações da instituição.

## Preparação do moderador

Fazer este trabalho **antes** de receber cada participante. As instruções de
preparação e os critérios de observação não devem ser mostrados durante as tarefas.

1. Registar a versão: `git rev-parse HEAD` e `git status --short`. Registar também o
   navegador, resolução, zoom, duração planeada e a referência do backup.
2. Criar uma cópia de referência uma vez, com `python3 scripts/backup.py criar`.
   Guardar a pasta impressa. Esta operação suspende brevemente o backend, conforme
   o [procedimento de backup](backup-restauro.md).
3. Para cada sessão, restaurar **essa mesma pasta**, não um backup mais recente:

   ```bash
   read -r -p 'Pasta do backup de referência: ' SOP_USABILITY_BACKUP
   python3 scripts/backup.py verificar "$SOP_USABILITY_BACKUP" --manter
   ```

4. Usar a URL alternativa impressa pelo script, nunca a aplicação em 8080 para
   guardar os ensaios. Abrir a cópia num perfil/janela privada separado. Guardar os
   comandos de remoção exatos que o script imprime para essa cópia.
5. Entrar como ADMIN nessa cópia, com a conta existente no backup. Em **Equipa**,
   criar uma conta de sessão com nome P01 (depois P02, etc.), apenas PLANEADOR.
   Entregar as credenciais localmente, sem as escrever na ficha.
6. Confirmar SKU001 ativo e com unidade `un`. Se faltar, criar esse produto apenas
   na cópia. Disponibilizar o CSV ao participante numa localização conhecida.
7. Importar esse CSV na cópia, com origem `Preparação UT`. Gerar uma previsão de
   VENDAS, Média Móvel, janela 3, treino até **2026-10**, horizonte 3, corte atual.
   Registar o ID da execução. O CSV contém 24 meses de 2024-11 a 2026-10.
8. Criar nessa execução `UT-Otimista`, fator 1,10, e `UT-Conservador`, fator 0,90,
   com pressupostos de ensaio. Deixar ambos em RASCUNHO, sem ajustes. Registar IDs
   das revisões e quantidades iniciais do primeiro mês de SKU001.
9. Terminar sessão e deixar o participante entrar com a conta PLANEADOR. Entregar
   os cartões com os dados de entrada preenchidos: caminho do CSV, execução e nomes
   dos cenários. Esses dados são fornecidos a todos; não são pistas de navegação.

Os cenários são preparados antecipadamente para U02/U03 poderem ser realizados
mesmo se U01 falhar. Assim, uma falha de importação não determina as tarefas
seguintes. Não limpar resultados nem corrigir silenciosamente ações do participante.

Usar uma cópia nova para cada pessoa, repetir a preparação e remover a cópia no fim
com os comandos registados. Não usar `docker compose down -v` no projeto principal.
Este conjunto mínimo de sessão não é o dataset alargado reservado para a fase final.

Se forem atribuídos os cartões complementares, criar na cópia contas com apenas
APROVADOR ou CONSUMIDOR. Para O01, o moderador prepara e submete outra revisão,
com pressupostos e um ajuste justificado que o participante não tenha preparado.
Não usar a revisão U03 da própria pessoa e trocar-lhe a conta para contornar a
independência. Para O02, indicar os IDs de um plano publicado e de uma avaliação
existentes na cópia; se faltarem, preparar o fluxo completo ou não atribuir a tarefa.
Conservar datas reais e os motivos de indisponibilidade das métricas.

## Condução da sessão

Duração planeada do núcleo: 35–45 minutos, ajustada no piloto antes da recolha.
Dar cinco minutos iniciais para explicar o contexto de S&OP e a conta de ensaio.
Usar a mesma explicação com todos: base é a previsão calculada; cenários representam
alternativas; um plano publicado é uma decisão aprovada e preservada. Não demonstrar
os cliques das tarefas. Registar essa familiarização no método do relatório.

Dizer: “Estamos a avaliar a aplicação, não a tua capacidade. Podes parar ou fazer
uma pausa quando quiseres. Tenta resolver as tarefas como farias num trabalho real.”
Confirmar participação voluntária antes de começar. Pedir comentários espontâneos,
sem exigir que a pessoa fale continuamente enquanto se mede o tempo.

Para cada tarefa, iniciar o cronómetro ao entregar o cartão e terminar quando o
resultado for atingido, a pessoa desistir ou decorrerem dez minutos. Esse limite é
uma regra operacional deste protocolo, não um requisito de rapidez do projeto.
Registar interrupções/pausas separadamente; não apagar o tempo total observado.

O moderador pode repetir literalmente o cartão e esclarecer dados de entrada.
Indicar menus, botões, caminhos ou respostas conta como ajuda. Se for necessária,
registar o instante, o conteúdo e marcar a tarefa **COM_AJUDA**. Não transformar um
resultado assistido em autónomo porque a pessoa depois repetiu a ação sozinha.

Estados: AUTONOMA, COM_AJUDA, NAO_CONCLUIDA, NAO_EXECUTADA. Anotar sempre a razão,
incluindo problemas técnicos externos. Uma sessão incompleta não deve ser registada
como aprovação ou reprovação automática do produto; permanece identificada na síntese.

## Critérios do moderador

| Tarefa central | Evidência de conclusão autónoma |
|---|---|
| U01 Importação | Novo lote HISTORICO VALIDADO, 24 observações; participante encontra o estado e confirma que os dados estão disponíveis |
| U02 Comparação | Seleciona os dois cenários da execução indicada; identifica o otimista acima da base e o conservador abaixo; explica a diferença entre base, cenário e plano publicado |
| U03 Ajuste | No primeiro mês de SKU001 em UT-Otimista, guarda 30,0000 com justificação; encontra o histórico, autor e valor anterior/novo; base e outro cenário mantêm as quantidades iniciais |

Concluir o guião autonomamente exige **U01, U02 e U03 todas AUTONOMA** na primeira
sessão medida. A resposta conceptual em U02 deve distinguir cálculo base,
alternativas de planeamento e versão aprovada; não exigir terminologia decorada.
Se alguém concluir os cliques mas não compreender os valores, registar a dificuldade.

Os cartões O01/O02 são complementares e podem ficar para outras sessões. Não entram
na contagem principal nem tornam alguém um segundo participante. Contas de
APROVADOR devem ser independentes dos autores da revisão/ajustes. Se faltarem os
dados necessários, preparar o cenário ou marcar NAO_EXECUTADA por falta de preparação.

## Conforto e questionário

Registar desconforto visual percebido de 0 (nenhum) a 10 (muito elevado) no início,
aproximadamente aos 20 minutos e no fim, com o instante real. É uma opinião
subjetiva, não uma escala clínica. Registar alterações de zoom, brilho, pausas,
aproximação ao ecrã e locais onde a pessoa precisa de reler ou perde a posição.

No final, recolher as cinco perguntas de 1–5 e as respostas abertas da ficha.
Este questionário é próprio do projeto: **não é SUS ou outra escala validada**.
Apresentar respostas por pergunta, sem inventar uma pontuação padronizada global.

Uma sessão de 40 minutos não comprova conforto após várias horas. Se for possível,
recolher depois um diário de utilização em sessões habituais mais longas, com
pausas escolhidas pelo participante. Registar duração, tarefa e opinião; analisar
esses relatos separadamente do ensaio principal. Não exigir permanência desconfortável.

## Análise e decisões

Completar a matriz com todos os participantes, incluindo desistências, ajuda e
interrupções. Calcular por tarefa: conclusões autónomas / participantes a quem a
tarefa foi atribuída. Para o guião completo, contar quem terminou as três tarefas
autonomamente. Explicitar numerador, denominador e dados ausentes; não contar
NAO_EXECUTADA como sucesso. Sem cinco registos completos, deixar a apreciação da
meta de cinco como inconclusiva. Resultados com colegas permanecem exploratórios,
mesmo se forem 5/5. Não inferir significância estatística com esta pequena amostra.

Apresentar tempos individuais e mediana por tarefa, indicando se incluem ajuda.
Comparar início/fim do desconforto apenas nas sessões com ambos os valores, junto
das condições observadas. Não atribuir automaticamente qualquer mudança à interface.

Classificar problemas observados como bloqueantes, altos, médios ou baixos, com
tarefa, participantes afetados e evidência. Priorizar obstáculos à conclusão,
interpretações erradas e ilegibilidade antes de preferências decorativas. Manter
as decisões coerentes com a direção visual aprovada: fundos neutros, cor pontual,
contraste e espaçamento consistente. Guardar proposta, alteração, commit e resultado
de nova verificação. Repetições após correções devem ser reportadas à parte, pois
os participantes já conhecem o fluxo.

## Estado a apresentar no relatório

Até recolher as fichas: “Protocolo preparado; avaliação com participantes pendente.”
Depois: descrever recrutamento, experiência dos colegas, familiarização, tarefas,
resultados reais, problemas e limitações. Separar observações de utilizadores dos
[testes automáticos](interface-web.md) e da [medição de desempenho](desempenho.md).

Referência do projeto: Tiago Dias, *Relatório preliminar de engenharia de requisitos*,
9 de novembro de 2026, Tabela 5 / RNF01 (p. 12) e Tabela 9 / V06 (p. 25), PDF
fornecido pelo estudante. A meta é proposta e o perfil dos participantes é condição
a explicitar; este documento não altera silenciosamente esses critérios.
