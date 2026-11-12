# Segurança do NexoPlan

Revisão técnica realizada em 12 de novembro de 2026 sobre o backend, interface,
configuração Docker e dependências do projeto. A revisão inclui alterações e
testes de regressão. A avaliação de uma futura instalação pública depende também
da configuração do servidor, do domínio, do HTTPS e da rede.

## Resultado da revisão

| Área | Constatação e tratamento |
| --- | --- |
| Login | As tentativas eram ilimitadas. Foram acrescentados limites por conta e por endereço, partilhados entre sessões da mesma instância. |
| CSV publicado | Código e unidade podiam iniciar fórmulas em folhas de cálculo. A exportação passa a proteger essas células, incluindo dados antigos. |
| Recursos do servidor | Pedidos JSON e operações pesadas receberam limites adicionais de tamanho e concorrência. |
| Sessões | Foi acrescentado um prazo absoluto de oito horas e desativado o transporte do identificador de sessão no URL. |
| Rotas | As rotas autorizadas estão explícitas. A configuração recusa as restantes. |
| Cabeçalhos | Foram acrescentados Referrer-Policy e Permissions-Policy à CSP e às proteções já existentes. |
| Docker | O backend passa a executar com raiz de leitura, capacidades removidas e limites de recursos. |
| Ficheiros privados | Os novos originais CSV são criados com permissões 0600 em sistemas POSIX. O ficheiro local de configuração recebeu também permissões 0600. |
| Dependências | OSV consultado para 89 bibliotecas Java e três pacotes npm, sem alertas devolvidos. O npm audit também terminou sem alertas. |

## Autenticação e sessões

O login aceita até cinco tentativas por conta numa janela de 15 minutos.
Uma entrada bem-sucedida limpa o contador dessa conta. Tentativas paralelas
reservam capacidade antes do cálculo BCrypt. Uma conta desconhecida segue os
mesmos limites e mensagens.

A identidade guardada na base é usada para o limite da conta. Assim, diferenças
de maiúsculas e acentos que a collation MySQL considera equivalentes partilham
o mesmo contador.

O endereço de origem admite até 60 pedidos POST de login por janela de 15 minutos,
incluindo pedidos com CSRF inválido. O endereço vem da ligação TCP. Cabeçalhos
Forwarded e X-Forwarded-For enviados pelo cliente são ignorados.

Os limites devolvem HTTP 429 e Retry-After. O armazenamento tem um máximo de
10 000 chaves ativas e elimina as expiradas. Ao atingir essa capacidade,
novas chaves recebem uma recusa temporária.

Estes contadores residem na memória de uma instância e reiniciam com o processo.
Uma instalação com várias réplicas precisa de um limite partilhado e de proteção
no proxy de entrada. Um proxy também faz com que o backend veja o endereço desse
proxy. A configuração de origens confiáveis exige uma avaliação específica da rede.

As passwords continuam protegidas por BCrypt com custo 12. O estado da conta é
verificado depois do cálculo da password para reduzir diferenças de tempo na
resposta a contas desativadas. As respostas de erro mantêm uma mensagem genérica.

O identificador de sessão e o token CSRF rodam no login. Logout invalida a sessão.
A conta ativa e os papéis são reavaliados em cada pedido. A sessão termina após
30 minutos sem atividade ou após oito horas desde a autenticação.

No HTTP local, SESSION_COOKIE_SECURE permanece false. Uma instalação HTTPS precisa
de cookies Secure e de validação do transporte. A configuração atual ignora
cabeçalhos de encaminhamento. Estes ajustes devem ser revistos juntamente com o
proxy escolhido para a instalação.

## Pedidos, importações e cálculos

Os pedidos JSON aceitam até 64 KiB, incluindo transferência sem Content-Length e
tipos application/*+json. O limite é aplicado antes da desserialização.
Erros de JSON e de parâmetros devolvem mensagens controladas.

Importações, previsões, backtests e avaliações admitem duas operações em simultâneo
por instância e uma por conta. Pedidos adicionais recebem HTTP 429 com Retry-After.
As posições são libertadas mesmo quando uma operação falha. O cliente deve
consultar os resultados antes de repetir pedidos de escrita.

Os limites existentes de ficheiros continuam em vigor, incluindo 10 MiB por CSV,
11 MiB por pedido multipart, validação UTF-8, cabeçalho e valores, até 100 000
registos e diagnósticos limitados. Os originais usam nomes UUID e ficam num volume
separado da interface pública. A permissão 0600 aplica-se aos novos ficheiros.

O Tomcat tem limites de ligações, threads, duração de ligações e partes multipart.
A configuração admite 200 ligações, 50 threads e dez partes por pedido.
Uma proteção contra tráfego volumétrico e clientes muito lentos também precisa
de limites no servidor de entrada e no alojamento.

## Interface, dados e exportação

A CSP permite recursos da própria origem, recusa objetos e enquadramento por outras
páginas. Estão ativos X-Frame-Options, X-Content-Type-Options, Cache-Control,
Referrer-Policy e restrições de acesso à câmara, microfone, localização e pagamentos.

A interface escapa texto e atributos ao apresentar dados. Os testes de navegador
incluem conteúdo HTML introduzido nos dados. As consultas revistas usam parâmetros
para os valores recebidos. Os nomes de colunas e critérios de ordenação dinâmicos
são escolhidos pelo código.

A autorização de perfis é aplicada no servidor. Os testes cobrem a recusa de
acesso do CONSUMIDOR aos cenários, a exclusividade da gestão de contas para ADMIN
e a independência do aprovador, incluindo ajustes herdados.

Na exportação CSV, campos textuais que podem iniciar fórmulas recebem um apóstrofo.
A proteção inclui sinais de fórmula, variantes Unicode, espaços iniciais e
caracteres de controlo. A biblioteca CSV trata separadores, aspas e quebras de linha.
Os valores numéricos e os registos guardados mantêm-se. Os consumidores de dados
devem contar com esse prefixo nas células textuais protegidas.

O comportamento de uma folha de cálculo depois de editar, guardar e reabrir um
ficheiro pode variar. A política de abertura de documentos e a atualização das
aplicações de escritório continuam relevantes.

## Execução Docker

O backend usa o utilizador 10001, raiz de leitura, cap_drop ALL,
no-new-privileges, limite de 256 processos, 1 GiB de memória e duas CPUs.
O diretório temporário usa tmpfs com 128 MiB e opções noexec e nosuid.
Os originais CSV usam o volume de importações com escrita.

Backend e MySQL mantêm publicação apenas na interface 127.0.0.1. A base e os
volumes do piloto foram preservados durante a atualização. Uma instalação
temporária com as mesmas restrições confirmou login, criação de produto,
importação CSV e bloqueio de escrita na raiz do contentor.

## Verificações realizadas

- 111 testes Java aprovados, sem falhas, erros ou testes ignorados.
- 12 testes JavaScript aprovados.
- Dois testes Playwright aprovados com autenticação, perfis, importação, previsão,
  cenários, aprovação, exportação e monitorização.
- Testes de limites por conta, endereço, concorrência e expiração.
- Testes de JSON excessivo, transferência sem tamanho declarado e tipos JSON derivados.
- Testes de fórmulas em CSV, incluindo um snapshot antigo com dados perigosos.
- Verificação dos cabeçalhos e da recusa de acesso a configuração, backups e Actuator.
- Verificação do arranque e das operações de importação com as restrições Docker.
- Consulta OSV das coordenadas das bibliotecas efetivamente incluídas no JAR.
- npm audit dos pacotes de teste da interface.
- Verificação de ausência do ficheiro .env no histórico Git e pesquisa de
  assinaturas de tokens e chaves privadas nos ficheiros versionados.

Os relatórios locais detalhados estão em tmp/security, excluído do Git.
Os testes de ataque utilizaram instalações temporárias ou pedidos de leitura.
Os testes não enviaram credenciais a serviços externos. A consulta OSV enviou
nomes e versões de dependências públicas.

## Antes de disponibilizar à Internet

1. Configurar domínio, certificado HTTPS, redirecionamento de HTTP e cookies Secure.
   Verificar HSTS no endereço público e testar o proxy escolhido.
2. Substituir as passwords que ficaram visíveis nas capturas de ecrã.
   A rotação exige atualizar o MySQL e os serviços que usam essas credenciais.
   A edição de .env, por si só, não altera passwords de um volume MySQL existente.
3. Definir um canal privado para entregar os convites de ativação e preparar MFA
   para contas com privilégios.
4. Definir o acesso de rede, o limite de tráfego no proxy, registos de segurança,
   alertas e resposta a incidentes.
5. Executar uma análise de vulnerabilidades das imagens de sistema operativo,
   JRE e servidor MySQL, manter atualizações e repetir as verificações de dependências.
6. Guardar backups fora do servidor, controlar o acesso e testar o restauro.
7. Fazer testes de intrusão sobre a instalação final com autorização e âmbito definidos.

A revisão não incluiu uma infraestrutura pública, um ensaio de carga hostil,
uma auditoria completa do sistema operativo do anfitrião, um scanner de CVE das
camadas das imagens nem um teste de intrusão independente. As consultas de
dependências representam os alertas conhecidos na data da revisão.

## Referências

- [OWASP — autenticação e limitação de tentativas](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [OWASP — ficheiros recebidos](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
- [OWASP — CSV Injection](https://community.owasp.org/attacks/CSV_Injection)
- [Spring Security — cabeçalhos de segurança](https://docs.spring.io/spring-security/reference/features/exploits/headers.html)
- [Docker Compose — restrições dos serviços](https://docs.docker.com/reference/compose-file/services/)
- [OSV — consultas de vulnerabilidades conhecidas](https://google.github.io/osv.dev/api/)
