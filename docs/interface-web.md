# NexoPlan — Interface web integrada

A NexoPlan já pode ser usada no navegador em `http://localhost:8080/`.
Usa a conta ADMIN existente ou outra conta criada para a equipa, incluindo o
APROVADOR dos ensaios anteriores. Não há dados de demonstração nem contas criadas
automaticamente na base de desenvolvimento.

## Experimentar esta entrega

Com o Docker iniciado, abrir a aplicação e entrar com email/password existentes.

1. Visão geral: confirmar os totais e consultar um dos planos publicados.
2. Produtos/Importações/Previsões: consultar o que já foi criado pelos scripts.
3. Cenários: selecionar a execução, abrir o cenário e uma revisão. Comparar duas
   alternativas selecionando as últimas revisões na lista da mesma execução.
4. Numa revisão RASCUNHO, usar Ajustar, preencher quantidade e justificação e guardar.
   A quantidade final e a auditoria devem refletir o pedido. São aceites ponto ou
   vírgula decimal; a API recebe uma string decimal com ponto, sem conversão para float.
5. Submeter congela a revisão. Terminar sessão e entrar com a conta APROVADOR
   independente para aprovar ou devolver com motivo. A correção de uma devolução
   usa Criar nova revisão; aprovar abre o plano publicado.
6. Descarregar o CSV do plano e consultar/criar uma avaliação em Monitorização.
   No plano de novembro–janeiro publicado em novembro, os resultados sem pares
   elegíveis continuam a mostrar Indisponível e os respetivos motivos.
7. Terminar sessão. A conta CONSUMIDOR consulta catálogo, planos e monitorização,
   sem ferramentas de importação, preparação ou gestão de contas.

Guardar ajustes, importar, gerar previsões e publicar cria dados reais na base local.
Para um ensaio que não altere os teus dados, usar o teste isolado descrito abaixo.

## Ecrãs

| Área | Comportamento |
|---|---|
| Visão geral | ADMIN: painel global; APROVADOR: fila de decisões; PLANEADOR: rascunhos/devoluções; COMERCIAL: previsões/cenários; CONSUMIDOR: planos/resultados |
| Produtos | Pesquisa/paginação; criar/editar com versão; código/unidade sujeitos às regras do backend |
| Importações | HISTORICO/REALIZADOS/OPERACIONAL, origem, ficheiro, correção opcional; diagnósticos e dados do lote |
| Previsões | Média Móvel/ETS, histórico, corte, último treino e horizonte; linhas/gráfico/diagnósticos |
| Comparar modelos | Sugestões de cortes do backend, backtest, MAE/MAPE/cobertura e células |
| Cenários | Criação com pressupostos; revisões; comparação de 2 a 5 revisões da mesma execução |
| Revisão | Base/calculada/final, ajustes justificados, cópia/recálculo, auditoria herdada, eventos e workflow |
| Planos publicados | Metadados de aprovação, células paginadas, CSV completo e entrada para avaliação |
| Monitorização | Plano/lotes/corte, seleção de produto, precisão por horizonte, operação e motivos de exclusão |
| Equipa | ADMIN cria contas com papéis explícitos e consulta utilizadores por ID |

O painel ADMIN usa cartões de produtos, execuções, cenários e planos, sem variações
percentuais artificiais. O gráfico mostra até 12 meses do primeiro produto do
último plano publicado, comparando base e aprovado na mesma unidade. Sem planos,
usa uma execução concluída com linhas entre as cinco mais recentes; sem dados,
apresenta instruções para começar. O produto, a unidade e o plano/execução são
identificados. Os acessos rápidos, planos e importações recentes usam as rotas
existentes. Os restantes perfis têm painéis próprios, descritos em
[painéis por perfil](paineis-perfil.md), com a mesma linguagem visual em verde
floresta, sálvia, areia e terracota. Em ecrãs pequenos, os
cartões passam a duas colunas e o gráfico/tabelas permitem deslocação horizontal.

As comparações selecionam a última revisão de cada cenário listado. O histórico
permite consultar todas as revisões, incluindo as anteriores/devolvidas/aprovadas.
A grelha compara quantidades finais e desvios; o gráfico representa o primeiro
produto da página de comparação, com o produto identificado na legenda.

O painel de monitorização separa precisão e operação e mantém os contadores de
cobertura junto das métricas. O detalhe lista todos os produtos e motivos; o
gráfico e cartões correspondem ao produto selecionado. A seleção não mistura unidades.
Faltas no gráfico criam intervalos, não zeros. Valores conhecidos mas excluídos
pela data da publicação são identificados na tabela; não entram nos erros.

## Arquitetura da interface

HTML/CSS e módulos JavaScript nativos em
`backend/src/main/resources/static/`. São incluídos no JAR e na imagem Docker.
Não há servidor frontend separado, compilação Node nem dependências externas em
tempo de execução. Node/Playwright servem apenas para os testes da interface.
Os gráficos e ícones são SVG locais; a ilustração da página inicial é decorativa.

- `index.html`: ponto de entrada, contentor, diálogo e anúncios acessíveis.
- `assets/app.mjs`: sessão, navegação por hash, comandos, formulários e eventos.
- `assets/views.mjs`: ecrãs, consultas, tabelas, paginação e relatórios.
- `assets/api.mjs`: REST, CSRF, erros, todas as páginas dos seletores e download.
- `assets/ui.mjs`: formatação decimal, escape, componentes e gráficos.
- `assets/app.css` e `brand.svg`: apresentação responsiva e identidade visual.

Os caminhos `#/cenarios`, `#/revisao/5`, etc. ficam no navegador; a aplicação é
carregada em `/`. Spring serve apenas os ficheiros públicos da interface sem sessão;
as APIs conservam a autenticação/autorização. Não foram alteradas tabelas ou migrações.

## Sessão, formulários e conflitos

Fetch usa a mesma origem e o cookie HttpOnly existente. O cliente pede um token
CSRF novo antes de cada escrita, incluindo login/logout e upload. Não guarda
passwords, tokens, relatórios ou sessões em localStorage/sessionStorage.
Uma sessão expirada limpa a interface e pede novo login. Não existem tentativas
automáticas de escrita, mesmo após falha de rede ou conflito de versão.

Quantidades e IDs permanecem strings; as quantidades conservam todas as casas
na apresentação e no pedido. A conversão numérica fica restrita às coordenadas
aproximadas dos gráficos, nunca aos valores enviados para guardar.

Os comandos usam a versão carregada para o formulário. Um 409 apresenta a versão
atual e Atualizar os dados; esta ação fecha o formulário e volta a ler o recurso.
O utilizador toma uma nova decisão. A justificação não é reenviada automaticamente.
Depois de uma rejeição de CSV, o formulário liga aos diagnósticos do lote rejeitado.

Os cortes usam hora local no formulário e UTC no pedido. Ao conservar o valor
sugerido, usa-se o instante de envio, evitando um corte anterior a uma importação
acabada de validar. Ao editar a data, usa-se o instante escolhido explicitamente.

Os botões seguem papéis/estado e a independência do aprovador considera os ajustes
herdados. O backend volta sempre a validar estas regras. A resposta HTML usa CSP
com scripts/estilos da própria origem, sem scripts inline; textos vindos da API
são escapados antes de entrarem no HTML. Formulários têm rótulos associados,
foco visível, diálogo nativo, mensagens de erro e navegação por teclado.

## Testes

Java/HTTP, com Docker ativo:

```bash
cd backend
./mvnw -B -Dspring.test.context.cache.maxSize=2 verify
```

O limite de contextos reduz a memória ocupada por bases temporárias; não reduz
os testes nem modifica a aplicação. O teste HTTP novo verifica a página, recursos
e MIME JavaScript, CSP e proteção das APIs sem sessão.

Testes do cliente (Node 20+):

```bash
cd frontend-tests
npm ci
npm test
```

Teste no navegador, a partir da raiz e depois de construir o JAR:

```bash
python3 scripts/testar_interface.py
```

O script cria um MySQL exclusivo numa porta aleatória, aplica o Flyway, cria contas
de teste e lança outro Spring Boot numa porta aleatória em 127.0.0.1. Define
explicitamente a ligação à base `sop_ui_test`, desativa a leitura de `.env` e
recusa usar as portas de desenvolvimento. Usa dados sintéticos, testa o fluxo por
Playwright e remove os processos/contentor/ficheiros temporários no fim.

Usa o Chrome instalado em `/usr/bin/google-chrome`, se existir. Pode indicar-se
`SOP_UI_CHROME_PATH` para outro executável Chromium compatível. Sem Chrome local,
instalar o navegador de teste com `npx playwright install chromium` em
`frontend-tests/`. O teste não usa o perfil pessoal do navegador. Logs/resultados
ficam em `tmp/interface-tests/`, `tmp/ui-test-results/` e `tmp/ui-preview/`, ignorados
pelo Git. A execução direta do Playwright sem o contexto isolado é recusada.

Referências: [recursos web Spring Boot](https://docs.spring.io/spring-boot/reference/web/servlet.html),
[módulos JavaScript](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules)
e [cache de contextos nos testes](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html).

## Trabalho seguinte

A interface é funcional, mas isto ainda não constitui a avaliação académica final.
Falta medir as tarefas com utilizadores e a meta de desempenho, executar/verificar
restauro de backup, consolidar o conjunto de dados e concluir manual, resultados e
relatório. Essas evidências não são substituídas pelos testes automáticos.

## Validação desta entrega

Em 2026-11-10 passaram 93 testes Java (`BUILD SUCCESS`, zero falhas, erros ou
ignorados), 8 testes Node e 2 testes Playwright num Chrome real. A bateria Java foi
executada com cache máximo de dois contextos. Os testes de navegador verificaram
login válido/inválido, catálogo e escape de HTML, criação/consulta de conta,
CSV rejeitado/válido, ETS, backtesting, cenários, ajuste, auditoria herdada,
devolução/correção/aprovação independente, CSV, monitorização, comparação,
conflito de versão sem repetição, CONSUMIDOR, sessão expirada, teclado e largura
móvel de 390 px. Não houve erros JavaScript no percurso completo.

Os testes usaram apenas uma base temporária exclusiva. O Docker de desenvolvimento
foi reconstruído: página/módulo/saúde responderam 200, APIs privadas sem sessão 401
e backend/MySQL ficaram saudáveis. A comparação SHA-256 dos dados de todas as
tabelas antes/depois do arranque confirmou preservação dos dados locais.
A página de login publicada foi ainda verificada no navegador da aplicação.

O redesenho do painel ADMIN foi validado com os 8 testes Node e os 2 testes
Playwright, incluindo painel vazio, previsão sem plano, valores aprovados iguais
à API e largura móvel de 390 px. A imagem Docker foi reconstruída e os recursos
servidos comparados com os ficheiros locais. As tabelas da base de desenvolvimento
mantiveram a mesma impressão SHA-256 antes e depois do arranque.

Depois da revisão manual, na raiz do repositório:

```bash
git add backend README.md docs/interface-web.md docs/plano-implementacao.md
git add frontend-tests scripts/testar_interface.py
git diff --cached --check
git --no-pager diff --cached --stat
git commit -m "feat: criar interface web para planeamento e monitorizacao"
git push
```
