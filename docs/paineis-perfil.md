# NexoPlan — Painéis por perfil

A rota `#/visao` adapta o conteúdo ao papel da conta. Mantém a navegação, cores e
componentes comuns. Numa conta com vários papéis, a prioridade visual é ADMIN,
APROVADOR, PLANEADOR, COMERCIAL e CONSUMIDOR. Essa prioridade apenas escolhe o
painel: as permissões continuam a resultar do conjunto de papéis atribuído.

| Perfil | Conteúdo inicial |
|---|---|
| ADMIN | Produtos, previsões, cenários, planos, gráfico previsão/plano e importações recentes |
| APROVADOR | Fila paginada de revisões EM_REVISAO, da submissão mais antiga para a mais recente, e planos publicados |
| PLANEADOR | Rascunhos, revisões devolvidas, contagens por estado e atalhos para dados/previsões |
| COMERCIAL | Previsões recentes, comparação de modelos/cenários e planos publicados, em consulta |
| CONSUMIDOR | Planos publicados, avaliações recentes, catálogo e monitorização |

Os totais são da equipa. Revisões devolvidas ficam no histórico mesmo depois de
uma correção noutra revisão; o total não significa correções ainda pendentes.
A fila inclui todas as revisões submetidas, e não apenas a última de cada cenário.
Abrir uma revisão permite consultar pressupostos, quantidades e auditoria herdada.
Aprovar/devolver continua a exigir APROVADOR independente da criação e dos ajustes
preservados, estado EM_REVISAO e versão atual. Não há aprovação direta no painel.

## Contrato de consulta

`GET /api/v1/revisoes?estado=EM_REVISAO&page=0&size=20`

200 com `items`, `page`, `size`, `totalElements` e `totalPages`. Cada item:

```json
{
  "revisao": {
    "id": "5",
    "cenarioId": "3",
    "numero": 2,
    "estado": "EM_REVISAO",
    "criadoPor": "1",
    "submetidoEm": "2026-11-10T20:00:00Z",
    "versao": 1,
    "totalLinhas": 3
  },
  "cenarioNome": "Campanha de inverno",
  "autorNome": "Planeador"
}
```

O objeto `revisao` também contém os restantes campos do DTO de revisão; o exemplo
mostra apenas os mais relevantes. Estados: RASCUNHO, EM_REVISAO, DEVOLVIDA,
APROVADA. Paginação 1–200; parâmetro inválido devolve 422. Sem sessão: 401;
CONSUMIDOR: 403. Os quatro perfis internos têm consulta de revisões.

## Experimentar

1. ADMIN ou PLANEADOR: abrir um cenário, criar uma revisão RASCUNHO e submetê-la.
2. APROVADOR: entrar, selecionar **Visão geral**, confirmar a fila e usar **Analisar**.
3. Consultar ajustes/pressupostos e devolver com motivo ou aprovar, se independente.
4. Atualizar a fila: a revisão decidida deixa de aparecer em EM_REVISAO.
5. PLANEADOR: consultar a devolução e criar uma nova revisão para corrigir.
6. COMERCIAL/CONSUMIDOR: confirmar os painéis de consulta e ausência de ações de preparação.

O login conserva a rota pedida, permitindo abrir ligações diretas. O painel próprio
fica sempre acessível em **Visão geral**. Uma fila vazia significa que não existem
revisões submetidas; os planos já publicados não voltam para essa fila.

## Verificação

Os testes Java verificam estado, paginação, autorização, ordem e inclusão de duas
revisões submetidas do mesmo cenário. Os testes de navegador percorrem os cinco
papéis, abrem a revisão a partir da fila e mantêm o fluxo de decisão independente.
Usam bases temporárias, sem criar dados de demonstração na base de desenvolvimento.

Em 2026-11-10 passaram 94 testes Java (zero falhas, erros ou ignorados), 8 testes
Node e 2 testes de navegador. O Docker foi atualizado e ficou saudável. A impressão
SHA-256 de todas as tabelas manteve-se igual antes/depois do arranque.

O conjunto de dados sintéticos mais amplo fica para a fase final, por decisão do
utilizador. Esta entrega não preenche nem limpa as tabelas existentes.
