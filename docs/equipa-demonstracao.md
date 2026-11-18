# Renovação da equipa fictícia

A demonstração usa dez pessoas fictícias com emails `nome.apelido@nexoplan.com`
e retratos empresariais gerados. Nove contas estão ativas; a conta de um antigo
colaborador permanece inativa para demonstrar a conservação do histórico.

## O que muda

`scripts/renovar_equipa_demo.py` substitui nome, email, hash da palavra-passe e
foto das sete contas conhecidas do protótipo e acrescenta três contas. Os sete
identificadores existentes e as datas de criação são preservados, mantendo as
ligações de autoria e a independência das decisões já guardadas.

Os convites anteriores ainda não utilizados são revogados. O avanço da versão
de acesso e os novos emails invalidam as sessões antigas. Os produtos, CSV,
previsões, cenários, ajustes, planos, avaliações, eventos de estado e migrações
mantêm os seus dados, conferidos por um hash antes e depois da operação.

O manifesto público em `data/equipa-demo/` contém os metadados e os prompts das
imagens. Cada palavra-passe de demonstração é diferente, tem pelo menos doze
caracteres e é guardada na base apenas como hash BCrypt de custo 12.

## Execução local protegida

Requer a instalação Docker local deste repositório, Python com Pillow e bcrypt
e os retratos que correspondem ao manifesto. O comando sem opções apenas valida
e mostra o plano:

```bash
python3 scripts/renovar_equipa_demo.py
```

Para aplicar a renovação autorizada à instalação local:

```bash
python3 scripts/renovar_equipa_demo.py --aplicar
```

O backend para brevemente para criar um backup completo e executar uma transação
com as chaves estrangeiras ativas. Alterações inesperadas às contas cancelam a
operação. Uma falha desencadeia a recuperação do backup; se a recuperação falhar,
o backend fica parado para evitar servir dados inconsistentes.

Uma equipa que já corresponde ao manifesto não é novamente alterada: repetir o
comando não redefine palavras-passe. Se houver diferenças posteriores de estado,
perfil ou fotografia, a ferramenta recusa sobrescrevê-las automaticamente.

## Acessos privados

Cada aplicação cria uma pasta `tmp/equipa-demo/nexoplan-equipa-.../` com:

- `acessos.md`: tabela de nomes, emails, perfis, estado e palavras-passe locais.
- `contas.json`: os mesmos acessos para verificação local automatizada.
- `resultado.json`: correspondências e hashes de validação, sem palavras-passe.
- `antes/`: backup completo anterior à renovação.

As pastas têm acesso privado e os ficheiros de credenciais têm permissões 0600.
`tmp/` está excluída do Git. Estes acessos são próprios da demonstração local;
antes de uma publicação na Internet, devem ser substituídos. A funcionalidade
normal de convites continua a permitir que novos funcionários escolham a sua
própria palavra-passe.

## Validação

Os testes específicos verificam o manifesto, recusa de alterações inesperadas,
ficheiros privados e hashes BCrypt. A transação foi também ensaiada num MySQL
temporário com as sete migrações reais, incluindo cancelamento antes da escrita,
rollback de uma falha antes do commit e preservação das tabelas de negócio.

A aplicação local de 18/11/2026 confirmou os nove logins ativos, a recusa da
conta inativa, os dez retratos pela API e a recusa dos emails antigos. A lista
da equipa e as fotografias de outros utilizadores continuam reservadas aos
administradores. As contas de consumidor não têm acesso aos cenários. O hash
das tabelas de negócio e auditoria ficou igual antes e depois da renovação.
No navegador foram confirmados os dez membros, as fotografias carregadas e a
consulta de um perfil da equipa.

```bash
python3 -m unittest discover -s scripts -p test_renovar_equipa_demo.py
```
