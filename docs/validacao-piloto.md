# Validação funcional do NexoPlan

O piloto guiado de novembro de 2026 verificou o percurso desde o histórico
importado até à consulta e exportação de um plano publicado. O ensaio usou
dados sintéticos e contas com papéis distintos. O autor executou as tarefas
com orientação ao longo do desenvolvimento.

| Etapa | Resultado observado |
| --- | --- |
| Importação | O histórico de demonstração do lote #7 ficou validado com 3 600 registos de vendas mensais de 100 produtos. |
| Comparação dos modelos | O backtest #2 usou três cortes, de maio a julho de 2026, e um horizonte de três meses. Das 900 células, 900 tinham previsão de ambos os métodos e realizado disponível. |
| Previsão | A execução #4 gerou 300 quantidades para 100 produtos, de novembro de 2026 a janeiro de 2027. |
| Cenários e auditoria | A comparação entre as alternativas Otimista e Conservadora mostrou os três meses de DEMO100. Num ensaio anterior, a revisão #9 registou o ajuste de SKU001 de 24,5666 para 30,0000, com justificação e autoria. |
| Aprovação e exportação | O plano #3 foi publicado a partir da revisão #10. A base contém 300 linhas para 100 produtos. O autor confirmou a descarga do CSV com 300 linhas de dados. |
| Acesso do Consumidor | O autor confirmou a descarga do plano com esse perfil. A abertura direta de Cenários apresentou uma mensagem de acesso restrito. |
| Monitorização | A avaliação #2 do plano #2 teve cobertura de 0/3. Novembro foi excluído por publicação após o início do mês. Dezembro e janeiro ainda não estavam fechados. MAE e MAPE ficaram indisponíveis. |

### Como interpretar os resultados

No backtest #2, o MAE de DEMO100 a um mês foi de 127,0000 unidades para a
Média Móvel e de 126,5134 para ETS, com três pares comparáveis. O resultado
descreve este produto, horizonte e conjunto de cortes.

O [ensaio de desempenho](desempenho.md) mediu um p95 de 95,2 ms para guardar
e apresentar um ajuste numa revisão com 300 células. Foram 30 amostras na
instalação de teste de 10 de novembro de 2026. A medição refere-se a essa
operação, configuração e versão.

### Testes automatizados da monitorização

Em 12 de novembro de 2026, os nove testes de integração de monitorização
passaram na versão `14b7414`, numa base MySQL temporária. Num caso com dados
elegíveis, o MAE foi de 10 unidades para a previsão base e 5 para o plano
aprovado. O MAPE foi de 10% em ambos. A cobertura do MAE foi de 2 em 3
células, e um realizado igual a zero ficou excluído do MAPE. Outro caso
verificou nível de serviço, ruturas, excesso médio de stock e unidades
perdidas. Estes valores pertencem aos dados de teste.

Reprodução na raiz do repositório, com Docker ativo:

```bash
./backend/mvnw -f backend/pom.xml -B -Dtest=MonitoringTests verify
```

### Avaliação de usabilidade

O piloto foi conduzido pelo próprio autor. As sessões autónomas com
participantes e a avaliação da meta de usabilidade permanecem pendentes.
O [protocolo de usabilidade](usabilidade.md) define as tarefas e a recolha
de resultados. O [guião de demonstração](dados-demonstracao.md) descreve
como reproduzir o percurso com os dados sintéticos.
