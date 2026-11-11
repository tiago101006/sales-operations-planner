# NexoPlan — Medição de desempenho

## Resultado observado

Na execução de **10 de novembro de 2026, às 23:15 UTC**, a meta local de p95 ≤ 2 000 ms
foi atingida para guardar e apresentar um ajuste numa revisão com 300 células.
O [registo completo](resultados/desempenho-2026-11-10.json) conserva todas as amostras,
configuração, versões e hashes dos recursos executados. A documentação foi concluída
na retoma de 11 de novembro, sem substituir essa execução por novos resultados.

| Medida | Resultado |
|---|---:|
| Amostras medidas | 30 |
| Aquecimento anterior, excluído por definição | 5 ajustes |
| Mínimo | 64,5 ms |
| Mediana | 76,25 ms |
| Média | 77,09 ms |
| p95 | **95,2 ms** |
| Máximo | 128,9 ms |
| Meta | ≤ 2 000 ms |
| Erros JavaScript observados | 0 |

O p95 usa *nearest rank*: ordenar as 30 durações e selecionar a posição
`ceil(0,95 × 30) = 29`, com posições a começar em 1. A mediana é a média das posições
15 e 16. Nenhuma amostra medida é removida, incluindo a mais lenta. Os testes do
resumo estatístico verificam também a conservação de valores extremos.

## O que foi medido

A contagem começa no evento de clique em **Guardar ajuste**, já com o formulário
preenchido. Termina depois de a quantidade e a versão esperadas aparecerem no DOM,
o diálogo fechar e decorrerem dois `requestAnimationFrame`. Este é um indicador de
atualização do ecrã, não uma medição da perceção humana nem do instante físico de
apresentação de cada píxel.

Inclui obtenção de CSRF, POST autenticado, transação SQL com auditoria, releituras
dos dados e atualização da interface. Exclui o tempo humano para abrir/preencher o
formulário, login, geração de previsões e arranque dos serviços.

**Dimensão e paginação:** 100 produtos × 3 meses = 300 células na revisão. A página
utilizada tem **20 linhas visíveis**; não se afirma que as 300 são renderizadas de
uma vez. O ensaio usa um único utilizador e 35 substituições sequenciais da mesma
célula (5 aquecimentos + 30 medições), com quantidades distintas e motivo obrigatório.
Ao terminar, confirma versão 35, 35 ajustes auditados, 300 células e previsão base
preservada. Não simula acessos concorrentes ou conflitos entre vários utilizadores.

## Dados e ambiente

O script cria um MySQL e um backend temporários, conta ADMIN efémera, 100 produtos
`PERF001`–`PERF100` e 24 meses fechados de histórico por produto (2 400 observações).
Todos os registos são criados pelas APIs. Uma Média Móvel recursiva de janela 3
gera o horizonte de três meses; o cenário otimista aplica fator 1,10.
Estas fixtures são exclusivas do ensaio: o conjunto de demonstração para a base
principal continua reservado para a fase final.

| Componente | Ambiente registado |
|---|---|
| CPU | AMD Ryzen 7 7730U; 16 processadores lógicos |
| Memória total do sistema | 15 692 708 KiB (aprox. 15 GiB) |
| Sistema | Linux 7.2.13-200.fc44.x86_64; glibc 2.43 |
| Backend | JAR Spring Boot executado localmente com OpenJDK 17.0.11 |
| Limite de heap JVM | 384 MiB |
| MySQL | 8.4.13 em Docker; buffer pool 128 MiB; Performance Schema ligado |
| Navegador | Chrome 156.0.8078.79, headless, 1440 × 1000 |
| Node | 24.14.1 |
| Rede | Loopback no mesmo computador; portas temporárias |
| Concorrência | 1 |

A aplicação de desenvolvimento pode continuar ligada durante o ensaio; não há
reserva exclusiva de CPU ou RAM. A carga média registada antes do navegador foi
aproximadamente 2,02 / 1,16 / 1,22. As durações podem variar entre execuções e
computadores. O resultado não constitui um SLA de produção, uma medição em rede
remota, um teste de carga ou validação de todas as operações da aplicação.

## Repetir

Requisitos: Linux, Docker, JDK 17, Python 3 e Node, dependências de `frontend-tests`
instaladas e um JAR compilado. Se necessário, preparar as dependências com `npm ci`
em `frontend-tests/` e compilar com `./mvnw verify` em `backend/`.

Na raiz do repositório:

```bash
python3 scripts/medir_desempenho.py
```

O script utiliza um MySQL novo, portas temporárias diferentes de 8080/3307,
diretório de originais temporário e configuração explícita da ligação. O JAR vem da
última compilação; os recursos web são servidos a partir dos ficheiros atuais do
checkout. Os hashes de ambos ficam registados. O SHA do commit é acompanhado da
indicação de alterações locais: o commit, sozinho, não identifica essas alterações.

Cada execução escreve uma pasta nova em `tmp/performance-tests/` com:

- `context.json`: dimensão, parâmetros, ambiente e hashes.
- `result.json`: amostras de aquecimento/medição, estatísticas e resultado da meta.
- `samples.csv`: durações brutas para análise posterior.
- Logs e artefactos do navegador para diagnóstico.

As passwords temporárias não entram no relatório. A base, os originais e o processo
Java temporários são removidos no `finally`. O comando termina com erro se o fluxo
falhar ou se o p95 exceder a meta. Só um relatório com `completed: true` corresponde
a uma medição completa; `passedTarget` identifica o cumprimento do limite.

Para verificar os cálculos e os outros auxiliares JavaScript:

```bash
cd frontend-tests
npm test
```

O percurso funcional normal continua separado e pode ser verificado com
`python3 scripts/testar_interface.py`, na raiz. O modo de desempenho não substitui
os testes de autorização, importação, publicação ou concorrência.

## Limites da evidência

Este ensaio documenta a meta de ajuste no cenário local descrito. Continuam por
medir as tarefas com utilizadores, conforto em utilização prolongada e aceitação.
Essas medições exigem participantes reais; os resultados automáticos não as substituem.

Referências do mecanismo de medição:
[Performance.now](https://developer.mozilla.org/en-US/docs/Web/API/Performance/now)
e [execução de JavaScript no navegador com Playwright](https://playwright.dev/docs/evaluating).
