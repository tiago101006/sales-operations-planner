# Validação inicial — 10 de novembro de 2026

Executada numa instância MySQL 8.4.13, obtida da imagem `mysql:8.4`, num contentor
temporário sem portas publicadas e com `/var/lib/mysql` em tmpfs. Não foi iniciada
uma base persistente do projeto. O contentor de verificação foi removido no final.

## Resultados

- Migração aplicada com sucesso numa base vazia: **21 tabelas e 40 FKs**.
- Percurso válido: baseline 100, cenário 110, override 120, valor anterior 110,
  autor identificável via evento, plano e realizado ligado à avaliação.
- Onze rejeições confirmadas pelo MySQL: chave repetida no lote; quantidade negativa;
  mês fora do primeiro dia; motivo só com espaços; baseline de outra execução;
  evento de outra revisão; aprovação de outra revisão; segunda publicação da mesma
  revisão; realizado de outro lote; serviço com numerador/denominador incompletos;
  contagem de dias superior à duração do mês.
- As fixtures foram revertidas no fim de cada transação; não ficaram utilizadores
  nem dados de teste persistidos.
- `docker compose --env-file .env.example config --quiet` concluído com sucesso.
- Script Python compilado com sucesso.

O script `database/verify_schema.py` repete o percurso e as rejeições. Recebe como
argumento um contentor temporário com nome iniciado por `sop-schema-check-`, uma base
`sop` vazia já migrada e acesso root sem password dentro do contentor. Essa convenção
destina-se exclusivamente ao ensaio isolado, sem publicação de portas.

```bash
python3 database/verify_schema.py sop-schema-check-NOME
```

## Limites desta verificação

Ainda não existe backend. Não foram testados autenticação, autorização, transações
Spring, concorrência de aprovação, algoritmos de previsão, métricas do serviço,
interface, recuperação de backups ou desempenho. A rejeição de publicação repetida
valida a constraint UNIQUE, não um teste com duas aprovações HTTP concorrentes.
As fixtures de SQL constroem estados diretamente para ensaiar as constraints;
não representam a execução do workflow de negócio.

O Compose foi validado estruturalmente; o ensaio do SQL usou um contentor isolado,
não a montagem de inicialização do Compose nem o seu healthcheck. Esses testes de
integração ficam para a criação do ambiente de backend.
