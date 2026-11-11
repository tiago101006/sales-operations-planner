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
