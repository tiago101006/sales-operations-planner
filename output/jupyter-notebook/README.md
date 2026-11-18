# Validação numérica da NexoPlan

O [notebook de validação](validacao-nexoplan.ipynb) verifica os ficheiros sintéticos de `data/empresa/` e compara referências Python com os motores e métricas Java reais. Não acede à base de dados nem altera dados da aplicação.

## Executar novamente

1. Na raiz do repositório, compila o backend:

   ```bash
   cd backend
   ./mvnw -q -DskipTests compile
   cd ..
   ```

2. Abre o notebook no Jupyter ou no VS Code com suporte a notebooks e executa **todas as células pela ordem apresentada**, com Python 3.10+ e Java 17+ disponíveis.

As células usam apenas a biblioteca padrão Python. Para uma verificação sem instalar Jupyter, executa este comando na raiz do repositório. Termina imediatamente se uma célula falhar:

```bash
python3 - <<'PY'
import json
from pathlib import Path

path = Path('output/jupyter-notebook/validacao-nexoplan.ipynb')
notebook = json.loads(path.read_text(encoding='utf-8'))
namespace = {'__name__': '__main__'}
for index, cell in enumerate(notebook['cells'], start=1):
    if cell['cell_type'] == 'code':
        exec(compile(''.join(cell['source']), f'cell-{index}', 'exec'), namespace)
PY
```

## Resultado registado

- **276 verificações passaram**; dez células Python executadas pela ordem apresentada.
- **111 resultados Java comparados:** 48 casos de Média Móvel, 32 de ETS, 29 de métricas e dois de indicadores operacionais.
- **24 produtos sintéticos**, com 72 candidatos de julho–setembro de 2026: 72 pares MAE, 61 pares MAPE e 11 realizados zero excluídos apenas do MAPE.
- Hashes, contagens, unidades, chaves únicas e as duas faltas intencionais do conjunto de dados confirmados.
- Previsões e métricas Java/Python coincidiram a quatro casas decimais; parâmetros e SSE do ETS coincidiram dentro das tolerâncias indicadas nas células.

As referências ETS guardadas no repositório declaram Statsmodels 0.15.0. Este ensaio compara a fixture existente; não volta a executar essa biblioteca.

## Limites

A execução registada usou Python em sequência, sem `nbclient` nem kernel Jupyter instalados. As células foram realmente executadas e os respetivos outputs estão guardados; a integração com um kernel Jupyter não foi testada.

O ensaio usa um único corte retrospectivo. Não certifica a qualidade preditiva em dados reais, nem verifica a base de dados em execução, as permissões REST, a publicação de planos, o desempenho ou a segurança. O MAE é apresentado por unidade, sem agregar `un`, `kg` e `L`.
