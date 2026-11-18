# NexoPlan — Simulação de uma empresa

Este conjunto representa uma distribuidora fictícia de alimentação, bebidas e
higiene. As pessoas, vendas e decisões são inventadas para explorar os fluxos da
aplicação. Não são dados de uma empresa real nem resultados de uma avaliação com
utilizadores externos.

## Conteúdo e percurso

O catálogo contém 24 referências, com códigos `NX-001` a `NX-024`, nomes comerciais
e unidades próprias. As séries incluem procura estável, crescimento, sazonalidade,
campanhas e encomendas intermitentes. Os ficheiros e os seus SHA-256 constam de
[`data/empresa/manifesto.json`](../data/empresa/manifesto.json).

Os históricos são snapshots completos e independentes. A sobreposição tem os
mesmos valores; não devem ser concatenados. Os realizados de julho a outubro de
2026 e os registos operacionais permitem observar nível de serviço, dias de
rutura, stock e perdas. Uma falta de realizado e uma falta de stock são
intencionais: ausência não é zero.

O povoamento usa as APIs reais para validar CSV, calcular Média Móvel e ETS,
criar cenários, registar ajustes com justificação, devolver revisões, copiar
revisões e aprovar planos com uma pessoa independente. Inclui trabalho em
rascunho e à espera de aprovação, planos publicados e comparação de modelos.
Os indicadores são calculados pelo backend a partir das quantidades; não são
valores decorativos inseridos no painel.

Na aplicação, experimentar:

1. **Equipa:** pesquisar os nomes e alternar entre perfis. A conta de um antigo
   funcionário fica inativa, mantendo o histórico.
2. **Produtos e importações:** pesquisar `NX-`, abrir um lote validado e consultar
   também a importação rejeitada e os seus erros.
3. **Previsões:** abrir as execuções e comparar os métodos. Os históricos de
   consumo têm menos produtos que os de vendas.
4. **Cenários:** comparar alternativas, abrir o histórico e verificar a autoria
   dos ajustes. O planeador prepara; o aprovador decide.
5. **Planos e monitorização:** abrir um plano histórico avaliado para ver
   indicadores disponíveis. Os meses futuros do plano atual continuam sujeitos
   às regras de fecho e disponibilidade.

## Cronologia dos dados fictícios

O gerador usa a referência fixa **12 de novembro de 2026**. O fecho mensal da
empresa é simulado às 17:00 UTC do último dia do mês; os resultados finais ficam
disponíveis no primeiro dia do mês seguinte.

Para representar decisões anteriores a julho, agosto e setembro, o povoamento
ajusta os timestamps **na base isolada e vazia** que está a construir. Cada lote
histórico contém apenas os meses conhecidos nessa data. Os cálculos são feitos
com o respetivo corte e os planos históricos são publicados antes do início dos
meses previstos. Este ajuste é uma preparação explícita de fixtures sintéticas;
não é uma funcionalidade para retroagir decisões de uma empresa em utilização.

A aplicação continua a recusar cortes futuros e a excluir publicações tardias,
meses ainda não fechados e realizados indisponíveis no corte. Não se alteram os
serviços de previsão, autorização ou monitorização para preencher indicadores.

## Preparar e substituir uma instalação local

Requer a imagem do backend já construída, Docker e Python 3.11+. Na raiz do
projeto, gerar os CSV:

```bash
python3 scripts/gerar_empresa.py
```

Para uma instalação iniciada com o Compose deste repositório, preparar e verificar
num ambiente separado, conservando os dados existentes:

```bash
python3 scripts/preparar_empresa.py \
  --web-container "$(docker compose ps -q backend)" \
  --database-container "$(docker compose ps -q db)"
```

Para **substituir todos os dados de negócio** dessa instalação, acrescentar
`--substituir` ao mesmo comando. Numa cópia restaurada, passar os nomes exatos dos
dois contentores dessa cópia. O comando aceita apenas backend publicado em
`127.0.0.1` e confirma a ligação à base indicada.

A preparação e a verificação acontecem primeiro num MySQL e volume novos. Só
depois o backend de destino para brevemente. É guardado um backup completo da
base anterior e dos CSV originais, com inventários e hashes. A estrutura das
tabelas e o histórico Flyway são conservados; as tabelas técnicas e de
funcionalidades sem dados nesta simulação podem continuar vazias.

Os novos dados e os originais são conferidos após a substituição. Em falha, o
comando recupera o backup. Os recursos temporários são removidos. A base e a
porta da instalação existente são mantidas; as sessões anteriores terminam com
o reinício do backend.

## Acessos e relatórios privados

Cada preparação cria uma pasta privada em `tmp/empresa/nexoplan-empresa-.../`:

- `acessos.md` e `contas.json`: nomes, emails `@nexoplan.com`, perfis e
  palavras-passe de demonstração fáceis de decorar.
- `simulacao.json` e, após substituição, `resultado.json`: IDs dos recursos e
  verificações executadas, sem palavras-passe ou tokens de convite.
- `antes-da-substituicao/`: backup do estado anterior, quando houve substituição.

Estas pastas estão excluídas do Git. As palavras-passe são próprias desta
simulação local e a aplicação guarda os seus hashes BCrypt. O mecanismo normal
de convites continua disponível para criar outras contas.

Para conferir o backup ou abrir uma cópia recuperada, usar o caminho impresso:

```bash
python3 scripts/backup.py verificar "CAMINHO_DO_BACKUP" --manter
```

Consultar também o [guia de backup e restauro](backup-restauro.md).
