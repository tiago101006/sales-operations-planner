# NexoPlan — Plano de adoção e passagem a utilização

Este é um plano proposto para experimentar o MVP numa equipa. Não documenta uma
implantação real nem resultados organizacionais já obtidos.

## Âmbito inicial

Começar com uma equipa e um conjunto limitado de produtos, usando dados autorizados
e unidades verificadas. Definir periodicidade mensal, horizonte de planeamento e
quem responde pelos históricos, decisões e resultados. Calendários, promoções e
integrações automáticas não fazem parte das importações implementadas neste MVP.

## Responsabilidades

| Responsável | Preparação / acompanhamento |
|---|---|
| Administrador | Configurar ambiente, atribuir contas/papéis e assegurar cópias/restauro |
| Planeador | Validar fontes, gerar previsões, preparar cenários e justificar ajustes |
| Aprovador independente | Rever pressupostos/auditoria e decidir a publicação |
| Comercial | Consultar alternativas e comunicar pressupostos à equipa de planeamento |
| Consumidor | Consultar o plano aprovado e acompanhar resultados permitidos |
| Responsável pelos dados | Garantir códigos/unidades, snapshots completos e datas de disponibilidade |

A responsabilidade pelos dados é uma função organizacional, não um sexto papel
de autenticação. Evitar contas partilhadas para preservar a autoria.

## Etapas e critérios de passagem

| Etapa | Atividades | Critério para avançar |
|---|---|---|
| Preparação | Instalar, definir contas, verificar catálogo e efetuar backup/restauro isolado | Acesso por perfil e recuperação demonstrados |
| Ensaio de dados | Importar uma amostra completa e analisar rejeições | Produtos/unidades conferidos; lote utilizável e proveniência consultável |
| Formação | Percorrer o manual com planeador e aprovador | Cada pessoa reconhece cenário, revisão, publicação e respetivas ações |
| Operação acompanhada | Executar um ciclo mensal em paralelo com o processo atual | Previsão reproduzível, ajustes justificados, aprovação independente e CSV/API coerentes |
| Avaliação | Recolher dificuldades, cobertura e erros após fecho dos meses | Problemas classificados e decisão explícita de continuar, corrigir ou alargar |
| Alargamento gradual | Acrescentar produtos/utilizadores após revisão do ensaio | Desempenho e qualidade dos dados adequados ao volume real |

Os critérios são propostos; não marcar etapas como cumpridas sem evidência real.
O piloto guiado do autor ajuda a corrigir a interface, mas não substitui sessões
independentes nem a avaliação com utilizadores representativos.

## Formação e apoio

Usar o [manual de utilização](manual-utilizacao.md) para uma sessão prática:
importar, prever, comparar duas alternativas, justificar um ajuste e consultar a
auditoria. Depois, trocar para uma conta independente para devolver/corrigir/aprovar
um plano. Fechar com exportação e interpretação de cobertura/indisponibilidade.

Guardar dúvidas e problemas com tarefa, passos, resultado esperado/observado e
versão da aplicação. Não incluir passwords nem dados pessoais em tickets públicos.

## Acompanhamento

Acordar a frequência de revisão e o responsável por cada indicador antes do ensaio.
Recolher contagens reais de importações rejeitadas, tarefas concluídas com/sem ajuda,
conflitos e incidentes. Para precisão e operação, reportar sempre janela, unidade,
horizonte, cobertura e exclusões. Não assumir melhoria de negócio só porque a
aplicação calcula previsões ou porque o erro de um exemplo sintético diminuiu.

Comparar modelos nos mesmos cortes por backtesting. Acompanhar ajustes separadamente:
um ajuste justificado pode melhorar ou piorar o erro, e só se mede esse efeito quando
existem realizados elegíveis. Metas organizacionais e valores de referência ficam
por acordar com uma equipa real; não são resultados deste projeto.

## Continuidade e limites

Definir responsável e frequência das cópias MySQL/CSV, armazenamento autorizado e
ensaio periódico de restauro. Aplicar o [procedimento existente](backup-restauro.md).
Antes de atualizar a aplicação, conservar uma cópia verificável e identificar a
versão. Em incidente, suspender escritas quando apropriado e recuperar numa cópia
isolada para verificar a integridade antes de substituir o ambiente utilizado.

A configuração atual serve desenvolvimento local. Uma exposição em rede exige
preparação específica de HTTPS, cookies seguros, segredos, acesso à base e operação.
Essa implantação precisa de validação própria; este plano não a dá por concluída.

## Evidência para a entrega académica

Separar no relatório: funcionalidades implementadas e testadas; medições de
desempenho com condições explícitas; observações do piloto guiado do autor; futuras
sessões com participantes; e proposta de adoção ainda não executada. Os dados
sintéticos de demonstração devem ser identificados como tal. Não incluir fichas
privadas preenchidas ou cópias de dados no Git.
