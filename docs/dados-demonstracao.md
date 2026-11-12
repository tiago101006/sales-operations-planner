# NexoPlan — Dados sintéticos para demonstração

Todos os dados desta entrega são fictícios. Não representam vendas, resultados
operacionais ou desempenho de uma empresa real. Semente 42, referência fixa
11/11/2026, códigos DEMO001 a DEMO100. Os produtos usam a unidade `un`.

## Conteúdo

| Ficheiro | Conteúdo e finalidade |
|---|---|
| produtos.json | 100 produtos no contrato de criação da API |
| produtos.csv | Catálogo legível e classificação dos padrões; não é um tipo de importação de lotes |
| historico_treino.csv | 3 600 observações: 36 meses, agosto/2023 a julho/2026 |
| historico_atual.csv | 3 600 observações: 36 meses, novembro/2023 a outubro/2026 |
| realizados.csv | 299 realizados de agosto a outubro/2026; um registo omitido intencionalmente |
| operacionais.csv | 300 registos mensais; um campo de stock ausente intencionalmente |
| evidencia_diaria.csv | Procura, satisfação no prazo, dias sem stock e perdas para conferir os agregados operacionais |
| invalidos/*.csv | Cinco ficheiros: duplicados, quantidade negativa, produto desconhecido, mês inválido e unidade incompatível |
| manifesto.json | Versão, referência, semente, períodos, quantidades, faltas intencionais e SHA-256 dos ficheiros |

Há 20 produtos por padrão: estável, tendência, sazonal, intermitente e campanha.
A sazonalidade usa um perfil mensal explícito no gerador. Campanhas são picos
sintéticos em junho/novembro; não são dados de promoções importados. Não se criaram
endpoints de calendário/promoções. ETS(A,A,N) continua sem componente sazonal.

Procura intermitente contém zeros explícitos. O realizado de DEMO005 em outubro
está ausente; o stock de fecho de DEMO006 nesse mês também. Estas faltas são diferentes
de zero. A evidência diária inclui os três meses de resultados e não é um CSV
aceite pelo importador de lotes. Os denominadores/numeradores operacionais resultam
da agregação diária, não de inferências a partir do stock de fecho.

Os dois históricos sobrepõem-se parcialmente, mas são snapshots independentes.
O de treino permite estudos retrospetivos; o atual prepara previsões para novembro
em diante. Não juntar as linhas dos dois ficheiros no mesmo lote.

## Reproduzir sem ligar à base

Na raiz do projeto:

```bash
python3 scripts/gerar_dados.py
```

A geração não importa dados. Repetir com os mesmos parâmetros produz os mesmos
bytes; não substitui ficheiros existentes diferentes. Para outro conjunto:

```bash
python3 scripts/gerar_dados.py --referencia 2026-11-11 --semente 42 --saida tmp/dados-experiencia
```

A referência fixa permite reproduzir a entrega. Ao preparar outra data, conferir
sempre os meses e a disponibilidade no manifesto; não importar resultados futuros.

## Importar na cópia de demonstração

Usar a cópia do piloto já preparada, sem criar um novo restauro que apague o seu
progresso. O importador pede endereço e credenciais localmente:

```bash
python3 scripts/importar_demonstracao.py
```

1. Confirmar o endereço da cópia; nesta sessão é `http://127.0.0.1:32770`.
2. Entrar com ADMIN ou PLANEADOR. A password não é mostrada nem guardada no relatório.
3. Esperar pelo resumo com os IDs dos quatro lotes.
4. Atualizar a aplicação e consultar Produtos/Importações.

O script usa apenas a API local autenticada com CSRF. Confere os hashes antes de
escrever. Cria produtos em falta e reutiliza lotes validados com o mesmo tipo/hash.
Recusa códigos existentes incompatíveis sem os alterar. Não apaga nem atualiza os
teus produtos/lotes anteriores. Repetição sequencial sem alterações reutiliza os
recursos; não executar duas importações simultâneas. Em falha de ligação, consultar
os resultados antes de repetir. O relatório fica em `tmp/demonstracao/importacao.json`.

A importação cria catálogo e quatro lotes; não cria contas, previsões, cenários ou
aprovações. Os ficheiros inválidos são apenas fixtures e não são importados por este
comando. Todos os produtos acrescentados estão identificados como sintéticos.

## Demonstrar no navegador

1. **Produtos:** pesquisar DEMO e consultar as várias páginas do catálogo.
2. **Previsões:** escolher o lote `historico_atual.csv`, Vendas, treino até 2026-10,
   horizonte 3. Gerar Média Móvel com janela 3 e/ou ETS com 24 meses, sem recurso.
   Esperar 100 produtos previstos e 300 quantidades por execução.
3. **Cenários:** para a mesma execução, criar Otimista 1,10 e Conservador 0,90,
   justificar um ajuste e comparar. Os produtos/unidades continuam identificados;
   os gráficos podem representar apenas o primeiro produto da página.
4. **Aprovação:** usar uma conta APROVADOR independente para decidir uma revisão
   enviada. Não alterar datas de publicação para obter indicadores.
5. **Backtesting:** usar um histórico validado e confirmar os cortes sugeridos.
   Este percurso permite observar MAE/MAPE com meses passados, sem publicar um
   plano retroativo. Conferir cobertura e zeros excluídos do MAPE.

## O que esperar da monitorização

Os resultados de agosto a outubro são dados fechados, úteis para importação,
validação e estudos retrospetivos. Um plano criado agora não passa a ser elegível
para esses meses. O plano atual de novembro–janeiro continua sujeito à publicação
tardia de novembro e aos meses ainda não fechados no corte.

Por isso, não prometer que importar estes ficheiros preenche as métricas da
avaliação #1. Essa avaliação é preservada. Criar avaliações futuras exige planos
publicados a tempo, meses fechados e realizados elegíveis. As métricas de backtesting
ficam numa área diferente da monitorização de planos.

## Verificar sem alterar a cópia utilizada

```bash
python3 scripts/testar_dados_sinteticos.py
```

O teste utiliza Spring/MySQL temporários, fora das portas da aplicação principal.
Confere reprodução dos ficheiros, agregação diária, importação dos quatro lotes,
repetição sem duplicados, previsões de 100 produtos por ambos os métodos e recusa
dos cinco ficheiros inválidos. Remove os recursos temporários ao terminar.
Requer o JAR já construído e as dependências do ambiente de testes descritas no README.
Os resultados privados ficam em `tmp/demonstracao/validacao.json`.

## Ensaio guiado de comparação com 100 produtos

Na área **Comparar modelos**, clicar em **Novo backtest**,
escolher `historico_atual.csv` e configurar:

| Campo | Valor |
|---|---|
| Série | Vendas |
| Horizonte | 3 meses |
| Primeiro corte de treino | 2026-05 |
| Último corte de treino | 2026-07 |
| Janela da Média Móvel | 3 |
| Máximo de meses ETS | 24 |

Os cortes são maio, junho e julho. Cada um prevê os três meses seguintes;
existem realizados no histórico até outubro de 2026. São esperadas **900 células**:
100 produtos × 3 cortes × 3 horizontes. Confirmar no resultado os estados dos motores,
a cobertura, as exclusões de zeros no MAPE e os indicadores por produto/horizonte.
Não assumir antecipadamente que ETS vence a Média Móvel em todos os produtos.

Pesquisar `DEMO100` na tabela de células deve encontrar **9 linhas** através de
todas as páginas. Pesquisar um código inexistente deve apresentar zero resultados;
limpar recupera as 900 células. Estas contagens pressupõem o snapshot gerado sem
alterações. Este ensaio funcional não é uma medição formal de desempenho nem uma
sessão independente de usabilidade.

## Ensaio guiado de previsão e cenários com 100 produtos

A simulação retrospetiva compara os métodos. Para obter uma previsão que possa
servir de base a cenários, abrir **Previsões → Gerar previsão** e escolher
`historico_atual.csv`, série Vendas, horizonte de 3 meses e último mês de treino
`2026-10`. Manter o corte de disponibilidade no momento do envio. Escolher ETS
com tendência aditiva, máximo de 24 meses de treino e sem método de recurso.
Esperam-se **100 produtos previstos e 300 quantidades base** de novembro de
2026 a janeiro de 2027.

A partir dessa execução, criar dois cenários de demonstração, **Otimista** com
fator `1,10` e **Conservador** com fator `0,90`. Comparar os dois e pesquisar
`DEMO100` nos resultados. Devem aparecer os três meses desse produto, com as
quantidades base e as duas alternativas lado a lado. As quantidades calculadas
devem corresponder aos fatores aplicados à base, com arredondamento a quatro
casas decimais. Nesta etapa basta conservar os cenários em rascunho.
