"""Conjunto sintético reproduzível; não liga à aplicação nem à base de dados."""
import argparse
import calendar
import csv
from datetime import date
import hashlib
import io
import json
from pathlib import Path
import random

HISTORY = ['produto', 'periodo', 'tipo', 'quantidade', 'unidade', 'disponivel_em']
ACTUAL = ['produto', 'periodo', 'quantidade', 'unidade', 'disponivel_em']
OPS = ['produto', 'periodo', 'unidade', 'unidades_solicitadas', 'unidades_satisfeitas_no_prazo', 'dias_observados_com_procura', 'dias_com_procura_sem_stock', 'stock_fecho', 'limite_stock', 'unidades_perdidas', 'prazo_medio_dias', 'disponivel_em']
PATTERNS = ['ESTAVEL', 'TENDENCIA', 'SAZONAL', 'INTERMITENTE', 'CAMPANHA']
NAMES = ['Arroz', 'Massa', 'Farinha', 'Azeite', 'Leite', 'Café', 'Chá', 'Bolachas', 'Cereais', 'Água', 'Sumo', 'Atum', 'Feijão', 'Grão', 'Açúcar', 'Sal', 'Detergente', 'Sabão', 'Guardanapos', 'Papel de cozinha']


def period(serial):
    return f'{serial // 12:04d}-{serial % 12 + 1:02d}'


def csv_text(header, rows):
    output = io.StringIO(newline='')
    writer = csv.writer(output, delimiter=';', lineterminator='\n')
    writer.writerow(header)
    writer.writerows(rows)
    return output.getvalue()


def generate(folder, as_of=date(2026, 11, 11), seed=42):
    if as_of.year < 2005:
        raise ValueError('A data de referência deve ser de 2005 ou posterior.')
    folder = Path(folder)
    rng = random.Random(seed)
    last = as_of.year * 12 + as_of.month - 2
    months = list(range(last - 38, last + 1))
    products, history, actuals, operations, daily = [], [], [], [], []
    for index in range(100):
        code = f'DEMO{index + 1:03d}'
        pattern = PATTERNS[index % 5]
        products.append({'codigo': code, 'nome': f'{NAMES[index % 20]} — embalagem {index // 20 + 1} (sintético)', 'unidade': 'un', 'padrao': pattern})
        base = 70 + index * 4
        for step, serial in enumerate(months):
            month = period(serial)
            year, number = map(int, month.split('-'))
            quantity = base + rng.randint(-8, 8)
            if pattern == 'TENDENCIA': quantity += step * (2 + index % 3)
            if pattern == 'SAZONAL': quantity = quantity * [85, 80, 90, 95, 100, 110, 115, 110, 100, 105, 125, 145][number - 1] // 100
            if pattern == 'INTERMITENTE': quantity = 0 if step % 4 != 0 else quantity * 2
            if pattern == 'CAMPANHA' and number in (6, 11): quantity = quantity * 3 // 2
            quantity = max(0, quantity)
            days = calendar.monthrange(year, number)[1]
            quantities = [quantity // days] * days
            for d in rng.sample(range(days), quantity % days): quantities[d] += 1
            satisfied, observed, stockout, losses = 0, 0, 0, 0
            for day, demand in enumerate(quantities, 1):
                without = demand > 0 and rng.randrange(20) == 0
                fulfilled = 0 if without else max(0, demand - (1 if demand and rng.randrange(8) == 0 else 0))
                loss = int(demand > 0 and rng.randrange(30) == 0)
                satisfied += fulfilled; observed += int(demand > 0); stockout += int(without); losses += loss
                if serial > last - 3: daily.append([code, f'{month}-{day:02d}', demand, fulfilled, int(without), loss])
            available = period(serial + 1) + '-01T00:00:00Z'
            history.append([code, month, 'VENDAS', f'{quantity:.4f}', 'un', available])
            if serial > last - 3:
                # One documented missing actual; zero demand remains an explicit zero.
                if not (code == 'DEMO005' and serial == last):
                    actuals.append([code, month, f'{quantity:.4f}', 'un', available])
                stock = '' if code == 'DEMO006' and serial == last else str(10 + rng.randrange(70))
                operations.append([code, month, 'un', quantity, satisfied, observed, stockout, stock, 40, losses, '2.0000', available])
    catalog = [{k: p[k] for k in ('codigo', 'nome', 'unidade')} for p in products]
    files = {
        'produtos.json': json.dumps(catalog, ensure_ascii=False, indent=2) + '\n',
        'produtos.csv': csv_text(['codigo', 'nome', 'unidade', 'padrao'], [[p[k] for k in ('codigo', 'nome', 'unidade', 'padrao')] for p in products]),
        'historico_treino.csv': csv_text(HISTORY, [r for r in history if r[1] <= period(last - 3)]),
        'historico_atual.csv': csv_text(HISTORY, [r for r in history if r[1] >= period(last - 35)]),
        'realizados.csv': csv_text(ACTUAL, actuals),
        'operacionais.csv': csv_text(OPS, operations),
        'evidencia_diaria.csv': csv_text(['produto', 'data', 'solicitadas', 'satisfeitas_no_prazo', 'dia_sem_stock_com_procura', 'perdidas'], daily),
    }
    sample = history[0]
    for name, column, value in [('quantidade_negativa', 3, '-1'), ('produto_desconhecido', 0, 'INEXISTENTE_DEMO'), ('periodo_invalido', 1, '2026-13'), ('unidade_incompativel', 4, 'kg')]:
        row = sample.copy();row[column] = value
        files['invalidos/' + name + '.csv'] = csv_text(HISTORY, [row])
    files['invalidos/duplicados.csv'] = csv_text(HISTORY, [sample, sample])
    manifest = {'versao': 1, 'sintetico': True, 'semente': seed, 'referencia': as_of.isoformat(),
        'produtos': 100, 'padroes': {p: 20 for p in PATTERNS}, 'unidade': 'un',
        'treino': {'inicio': period(months[0]), 'fim': period(last - 3), 'meses': 36, 'linhas': 3600},
        'atual': {'inicio': period(last - 35), 'fim': period(last), 'meses': 36, 'linhas': 3600},
        'avaliacao_retrospetiva': {'inicio': period(last - 2), 'fim': period(last), 'realizados': len(actuals), 'operacionais': len(operations)},
        'faltas_intencionais': ['DEMO005: realizado do último mês ausente', 'DEMO006: stock de fecho do último mês ausente'],
        'nota': 'Campanhas e sazonalidade são padrões sintéticos, não importações de promoções/calendários. O catálogo usa a API de produtos. Dados diários são evidência, não CSV importável. Planos criados agora conservam a data real de publicação.',
        'sha256': {name: hashlib.sha256(content.encode()).hexdigest() for name, content in sorted(files.items())}}
    files['manifesto.json'] = json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'
    # Refuse to replace a different dataset accidentally; identical regeneration is safe.
    for name, content in files.items():
        target = folder / name
        if target.exists() and target.read_bytes() != content.encode():
            raise ValueError(f'Ficheiro diferente já existe: {target}. Usa outra pasta de saída.')
    for name, content in files.items():
        target = folder / name;target.parent.mkdir(parents=True, exist_ok=True);target.write_bytes(content.encode())
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--saida', type=Path, default=Path(__file__).resolve().parents[1] / 'data/sinteticos')
    parser.add_argument('--referencia', type=date.fromisoformat, default=date(2026, 11, 11))
    parser.add_argument('--semente', type=int, default=42)
    args = parser.parse_args()
    try:
        result = generate(args.saida, args.referencia, args.semente)
    except ValueError as error:
        parser.error(str(error))
    print(f"100 produtos; {result['treino']['linhas']} linhas de treino; 3600 linhas atuais; {result['avaliacao_retrospetiva']['realizados']} realizados. Saída: {args.saida}")
