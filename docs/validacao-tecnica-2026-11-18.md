# NexoPlan — validação técnica de 18 de novembro de 2026

Revisão do código local, incluindo alterações ainda não publicadas. Contexto
confirmado pelo autor: uso no próprio computador, dados sintéticos que simulam
uma empresa e possível publicação na Internet antes da apresentação final.

## Correções desta revisão

1. **Orçamento de operações pesadas:** criar cenários, copiar revisões,
   aprovar/publicar planos e criar contas passam a partilhar o limite existente
   de duas operações por instância e uma por conta. Pedidos excedentes recebem
   HTTP 429 com `Retry-After`. As posições são libertadas mesmo após falhas;
   leituras e ajustes individuais continuam disponíveis. Código:
   `backend/src/main/java/pt/tiagodias/sop/security/RequestLimitsFilter.java`.
2. **Contrato do ensaio sintético:** a verificação dos utilizadores ainda
   esperava quatro campos, embora a API pública já devolvesse oito. O teste foi
   reproduzido e corrigido para verificar os campos públicos exatos, os seus
   tipos e a equivalência entre listagem e consulta individual. Campos adicionais
   como hashes, tokens ou bytes de fotografias continuam a causar falha.
   Código: `scripts/testar_dados_sinteticos.py`.
3. **Integração contínua:** acrescentado `.github/workflows/ci.yml`, com Java 17,
   Node.js 24 e Python 3.12, permissões de leitura e actions fixadas por SHA.
   Executa Maven, testes JavaScript, backup, dados sintéticos e fluxos Chromium
   contra instalações temporárias. Não usa segredos da instalação local.
4. **Artefactos de navegador:** capturas e sessões em `output/playwright/`
   ficam excluídas do Git; o notebook de validação permanece versionável.

## Resultados observados

| Verificação | Resultado |
| --- | --- |
| Backend antes da correção do orçamento | `mvnw verify`: 121 testes, zero falhas, erros ou ignorados |
| Regressão após a correção | 50 testes de autenticação, cenários, aprovação e limites passaram; incluem três testes novos do orçamento |
| Interface JavaScript | 17 testes passaram |
| Percursos completos Chromium/API/MySQL | Seis testes passaram, com os cinco perfis, convites, importação, previsão, comparação, aprovação, exportação, monitorização, conta, fotos, pesquisa e arquivo/eliminação de produtos |
| Salvaguardas de backup/restauro | Quatro testes passaram |
| Dados sintéticos na API isolada | 100 produtos; dois históricos de 3600 linhas; 299 realizados; 300 operacionais; 300 previsões por método; cinco CSV inválidos rejeitados; repetição sem duplicados e agregação diária confirmadas |
| Notebook numérico | 276 verificações passaram; 111 resultados Java reais comparados com referências Python |
| Dependências | OSV consultado para 89 bibliotecas Java e três pacotes npm; zero pacotes com alertas devolvidos. `npm audit` também devolveu zero alertas |
| Instalação local, apenas leitura | Dez rotas de dados/configuração recusaram acesso anónimo; CSP, `nosniff`, `DENY`, Referrer-Policy e Permissions-Policy têm os valores esperados |
| Inspeção pela CLI Playwright | Login em 390 × 844 sem deslocação horizontal; email inválido recusado pelo formulário; abrir a rota de equipa sem sessão mantém o login, sem mostrar a aplicação |

Os 121 testes da primeira passagem e os 50 da regressão sobrepõem-se; não devem
ser somados como se fossem testes distintos. Existem três testes novos de
segurança nesta revisão.

Os testes de escrita usaram MySQL, aplicações e credenciais temporários. O
notebook leu apenas ficheiros sintéticos versionados e chamou as classes Java
compiladas. A base de trabalho não foi repovoada nem alterada pelos ensaios.

## Validação numérica

O [notebook](../output/jupyter-notebook/validacao-nexoplan.ipynb) verifica Média
Móvel, ETS, MAE, MAPE, cobertura, regras temporais e indicadores operacionais.
No corte de 24 produtos e três meses houve 72 pares MAE, 61 pares MAPE e 11
realizados zero excluídos apenas do MAPE. As comparações mantêm as unidades
separadas. O [guia de execução](../output/jupyter-notebook/README.md) explica as
tolerâncias, o corte escolhido e como repetir o ensaio.

As dez células de código foram executadas em sequência com Python e os outputs
ficaram guardados no notebook. Não havia kernel Jupyter nem `nbclient`; a
integração com um kernel não foi testada. As fixtures ETS existentes foram
comparadas; a biblioteca externa que as originou não foi novamente executada.

## Segurança e limites da conclusão

O [modelo de ameaças](security/sales-operations-planner-threat-model.md) documenta
fronteiras, controlos, evidências e riscos residuais. A lacuna de concorrência foi
corrigida e testada. A revisão não confirmou um bypass de autenticação,
autorização, convites ou aprovação independente nos caminhos analisados.

Não foi avaliada uma instalação pública, nem foi feito um teste de intrusão
independente, carga hostil ou scanner das camadas das imagens Docker. A consulta
de dependências reflete os alertas conhecidos devolvidos na data do ensaio.
HTTPS, cookies Secure, proxy, proteção de tráfego, credenciais e backups devem
ser verificados no alojamento escolhido antes de uma publicação na Internet.

## GitHub Actions

Antes desta revisão, a consulta pública ao repositório não encontrou workflows,
execuções Actions ou pull requests abertos. Não havia um job remoto com falha
para reparar. A CLI `gh` não está instalada; não foi feita inspeção autenticada
de PRs nem foi alterada a autenticação GitHub.

O YAML e os comandos da nova pipeline foram verificados localmente. A primeira
execução real no GitHub depende de publicar o workflow através de commit/push;
não é apresentada aqui como aprovada. Não foi feito commit ou push pelo agente.

## Repetir os testes

Na raiz do projeto, com Java 17, Docker, Python e Node.js disponíveis:

```bash
(cd backend && ./mvnw -B -Dspring.test.context.cache.maxSize=2 verify)
(cd frontend-tests && npm ci && npm test)
(cd frontend-tests && npx playwright install chromium)
python3 -m unittest discover -s scripts -p test_backup.py
python3 scripts/testar_dados_sinteticos.py
python3 scripts/testar_interface.py
```

Para repetir as comparações numéricas, segue o guia do notebook referido acima.
Os logs desta sessão estão em `tmp/audit-skills-*.log`, os resultados dos dados
em `tmp/demonstracao/validacao.json` e as capturas em `output/playwright/`.
Estes artefactos temporários não entram no Git.
