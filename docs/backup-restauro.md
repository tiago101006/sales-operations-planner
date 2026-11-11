# NexoPlan — Backup e restauro

O backup inclui a base MySQL completa (estrutura, dados e histórico Flyway), todos
os CSV originais do volume `sop_imports`, um inventário por tabela/ficheiro e SHA-256
dos conteúdos. Inclui planos publicados, quantidades, revisões, ajustes e autores.
Não é preciso conhecer as passwords das contas da aplicação para copiar os dados.
Requer Linux, Python 3.11+ e Docker Compose; usa apenas a biblioteca padrão Python.

## Criar

Com o Docker iniciado, na raiz do projeto:

```bash
python3 scripts/backup.py criar
```

O script suspende brevemente o backend para coordenar a cópia da base com os
originais. Não editar a base pelo DBeaver nem executar outros escritores durante
essa janela. O MySQL continua ligado; a cópia usa uma transação InnoDB. O backend
é iniciado novamente num bloco `finally`, incluindo em caso de falha. Se já estava
parado, permanece parado. Após uma interrupção forçada que impeça a limpeza:

```bash
docker compose start --wait backend
```

O resultado fica em `tmp/backups/nexoplan-<instante UTC>-<sufixo>/`:

| Ficheiro | Conteúdo |
|---|---|
| `database.sql.gz` | Estrutura e dados, incluindo os hashes das passwords das contas |
| `imports.tar.gz` | Originais CSV com nomes, permissões e conteúdo preservados |
| `manifest.json` | Imagens Docker usadas, esquema, contagens e inventários SHA-256 |

A pasta só fica com o nome final depois de a cópia terminar e os originais
referenciados pelos lotes serem validados. Não se publica uma cópia parcial.
Os ficheiros são privados (pasta 0700, ficheiros 0600) e ficam fora do Git.
O `.env` e as passwords do MySQL não são incluídos. Esta cópia contém dados de
autenticação da aplicação e deve ser guardada como informação privada.

## Verificar um restauro real

```bash
python3 scripts/backup.py verificar --ultimo
```

Para escolher uma cópia diferente, pode passar-se a pasta impressa no comando de
criação como argumento, entre aspas se contiver espaços.

A verificação confere os hashes dos arquivos, rejeita caminhos de extração
inválidos, links e entradas duplicadas e cria **outra base, outro volume e outra
rede**. Nunca importa SQL nem extrai originais sobre os volumes do projeto.

Depois de restaurar, compara:

1. Definição de todas as tabelas, incluindo FKs, índices e contadores.
2. Número de registos de cada tabela e SHA-256 do dump de todos os dados ordenados.
3. Conteúdo e tamanho de todos os originais, incluindo a ligação SHA-256 aos lotes.
4. Arranque do backend com a imagem registada, validação Flyway/JPA e saúde `UP`.
5. API de planos sem sessão com HTTP 401 e dados intactos após o arranque.

O backend restaurado usa uma porta nova ligada apenas a `127.0.0.1`. O MySQL
restaurado não publica porta no anfitrião. As credenciais de ligação desta cópia são
aleatórias, transmitidas aos contentores sem serem impressas ou guardadas no repo.
Os relatórios ficam em `tmp/verificacoes-backup/`. Por omissão, os recursos
temporários são removidos no fim, incluindo após uma falha.

## Abrir e conservar a cópia restaurada

```bash
python3 scripts/backup.py verificar --ultimo --manter
```

Depois de verificar, o script imprime uma URL alternativa. Nessa cópia, as contas
da aplicação mantêm as passwords que tinham no instante do backup. Os contentores
e o volume recuperados ficam disponíveis, separados do ambiente original. O script
imprime os comandos com os nomes exatos para remover apenas essa cópia.

Esta é também a opção para ensaiar uma recuperação e inspecionar os dados antes
de qualquer substituição do ambiente original. O script não oferece uma opção
que apague ou sobrescreva automaticamente a base em utilização.

## Âmbito e transporte

O backup cobre dados e originais. O código e as configurações versionadas ficam no
Git; as imagens exatas do backend/MySQL são identificadas no manifesto e precisam
de estar disponíveis no Docker para este ensaio reproduzir o ambiente. Se forem
transportados os backups para outra máquina, transportar também essas imagens com
`docker image save`/`docker image load` e o repositório. Não remover as imagens
referenciadas antes de guardar essa cópia. Uma pasta no mesmo disco não protege
contra perda do disco: guardar também uma cópia privada noutro suporte.

SHA-256 verifica integridade; não cifra os ficheiros nem autentica um manifesto
substituído. Só restaurar cópias de confiança. Não há política automática de
retenção nem agendamento nesta entrega. Alterações futuras do esquema exigem testar
novamente o restauro com a versão correspondente da aplicação.

## Testes

```bash
python3 -m unittest discover -s scripts -p test_backup.py
```

Cobrem arquivos válidos/inválidos, corrupção, reinício do backend após erro e
limpeza restrita aos recursos criados pelo restauro. A verificação Docker compara
os dados reais numa cópia isolada; não introduz produtos ou dados de demonstração.

Em 2026-11-10 passou o ensaio com as 24 tabelas e os 4 originais da base local:
esquema, contagens e dados coincidiram; os CSV e as referências dos lotes mantiveram
os hashes; Flyway/JPA e o backend restaurado arrancaram com saúde UP; a API sem
sessão respondeu 401. A base de origem manteve a mesma impressão de dados e os
recursos temporários foram removidos. Passaram também os quatro testes Python.
Os relatórios privados e o backup concreto ficaram em `tmp/`, fora do Git.

Referências: [mysqldump MySQL 8.4](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html),
[backup de volumes Docker](https://docs.docker.com/engine/storage/volumes/#back-up-restore-or-migrate-data-volumes)
e [exportação de imagens](https://docs.docker.com/reference/cli/docker/image/save/).
