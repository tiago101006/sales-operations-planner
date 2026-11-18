"""Gera os CSV reproduzíveis da distribuidora fictícia NexoPlan, sem usar a API.

As quantidades de VENDAS representam a procura mensal observada da demonstração.
Os realizados repetem essa mesma série; o serviço operacional mede a parte
satisfeita no prazo. Não há uma segunda série de procura estimada ou não observada.
"""

import argparse
import calendar
from collections import Counter
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import random

from gerar_dados import ACTUAL, HISTORY, OPS, csv_text, period


REFERENCE = date(2026, 11, 12)
SEED = 20261112
START = 2024 * 12 + 6
TRAIN_END = 2026 * 12 + 5
CURRENT_START = 2024 * 12 + 10
END = 2026 * 12 + 9
CONSUMPTION_START = 2025 * 12 + 6
CURRENT_CONSUMPTION_START = 2025 * 12 + 10
EVALUATION_START = 2026 * 12 + 6
PATTERNS = ('ESTAVEL', 'TENDENCIA', 'SAZONAL', 'INTERMITENTE', 'CAMPANHA')
SEASON = (83, 84, 90, 97, 108, 124, 148, 142, 111, 97, 90, 103)

# A unidade é a do cadastro: uma embalagem é uma unidade, os produtos a granel
# usam kg ou L. Os valores base são mensais e pertencem à mesma unidade.
CATALOG = (
    ('Arroz agulha 1 kg', 'un', 950),
    ('Massa esparguete 500 g', 'un', 620),
    ('Água mineral 1,5 L', 'un', 1450),
    ('Erva-cidreira 20 saquetas', 'un', 40),
    ('Bolachas de aveia 200 g', 'un', 370),
    ('Leite meio-gordo 1 L', 'un', 1190),
    ('Atum ao natural 120 g', 'un', 430),
    ('Sumo de laranja 1 L', 'un', 660),
    ('Guardanapos festivos 50 folhas', 'un', 30),
    ('Detergente da loiça 750 ml', 'un', 310),
    ('Feijão encarnado 540 g', 'un', 510),
    ('Café moído 250 g', 'un', 290),
    ('Papel de cozinha 2 rolos', 'un', 720),
    ('Cobertura de chocolate a granel', 'kg', 48),
    ('Azeite virgem extra 750 ml', 'un', 250),
    ('Açúcar branco a granel', 'kg', 820),
    ('Farinha de trigo a granel', 'kg', 670),
    ('Detergente multiusos a granel', 'L', 480),
    ('Ambientador recarga 250 ml', 'un', 32),
    ('Cereais de pequeno-almoço 375 g', 'un', 280),
    ('Sabonete líquido recarga 500 ml', 'un', 340),
    ('Óleo alimentar a granel', 'L', 530),
    ('Água com gás 1 L', 'un', 430),
    ('Chá de menta 20 saquetas', 'un', 38),
)
CONSUMPTION_CODES = ('NX-001', 'NX-006', 'NX-010', 'NX-012',
                     'NX-013', 'NX-016', 'NX-018', 'NX-022')


def amount(value, unit):
    """Arredonda embalagens a unidades inteiras e granel a quartos de kg/L."""
    quantum = Decimal(1) if unit == 'un' else Decimal('0.25')
    return (Decimal(value) / quantum).quantize(Decimal(1), rounding=ROUND_HALF_UP) * quantum


def number(value):
    return f'{value:.4f}'


def history_available(serial):
    year, month = serial // 12, serial % 12 + 1
    day = calendar.monthrange(year, month)[1]
    return f'{year:04d}-{month:02d}-{day:02d}T17:00:00Z'


def outcome_available(serial):
    return period(serial + 1) + '-01T00:00:00Z'


def sales(index, serial, base, unit):
    """O ruído depende da chave, para manter meses sobrepostos idênticos."""
    rng = random.Random(f'nexoplan:{SEED}:{index}:{serial}')
    quantity = Decimal(base) * Decimal(100 + rng.randint(-6, 6)) / 100
    pattern = PATTERNS[index % len(PATTERNS)]
    step, month = serial - START, serial % 12 + 1
    if pattern == 'TENDENCIA':
        quantity *= Decimal('0.75') + Decimal('0.018') * step
    elif pattern == 'SAZONAL':
        quantity *= Decimal(SEASON[month - 1]) / 100
    elif pattern == 'INTERMITENTE':
        quantity = quantity * Decimal('1.5') if (step + index) % 4 == 0 else Decimal(0)
    elif pattern == 'CAMPANHA':
        quantity *= {3: Decimal('1.5'), 6: Decimal('1.8'),
                     9: Decimal('1.55'), 11: Decimal('1.9')}.get(month, Decimal(1))
    return amount(quantity, unit)


def operational(index, serial, product, quantity, base):
    unit = product['unidade']
    year, month = serial // 12, serial % 12 + 1
    days = calendar.monthrange(year, month)[1]
    observed = days - index % 5 if quantity else 0
    stockout = min(observed, (index + month) % 5) if quantity else 0
    ratio = Decimal(98 - (index + month) % 5 * 3) / 100
    satisfied = min(quantity, amount(quantity * ratio, unit))
    limit = amount(Decimal(base) * Decimal('0.40'), unit)
    stock = amount(limit * (Decimal('1.40') if (index + month) % 3 == 0 else Decimal('0.60')), unit)
    # Ausência opcional deliberada: exclui só a célula do indicador de excesso.
    stock_value = '' if product['codigo'] == 'NX-006' and serial == END else number(stock)
    losses = amount(quantity * Decimal((index + month) % 3) / 100, unit)
    lead_time = Decimal('1.25') + Decimal((index + month) % 7) / 4
    return [product['codigo'], period(serial), unit, number(quantity), number(satisfied),
            observed, stockout, stock_value, number(limit), number(losses),
            number(lead_time), outcome_available(serial)]


def generate(folder):
    """Escreve o conjunto fixo; repetições são idênticas e outros ficheiros ficam intactos.

    Recusa substituir ficheiros gerados com conteúdo diferente. O manifesto é
    devolvido para os scripts de preparação e verificação da empresa.
    """
    folder = Path(folder)
    products, train, current, actuals, operations = [], [], [], [], []
    for index, (name, unit, base) in enumerate(CATALOG):
        code = f'NX-{index + 1:03d}'
        product = {'codigo': code, 'nome': name, 'unidade': unit,
                   'padrao': PATTERNS[index % len(PATTERNS)]}
        products.append(product)
        for serial in range(START, END + 1):
            quantity = sales(index, serial, base, unit)
            row = [code, period(serial), 'VENDAS', number(quantity), unit, history_available(serial)]
            if serial <= TRAIN_END:
                train.append(row)
            if serial >= CURRENT_START:
                current.append(row)
            if code in CONSUMPTION_CODES and serial >= CONSUMPTION_START:
                # Utilização interna em cabazes, amostras e manutenção, sem
                # alterar a série VENDAS usada na avaliação da procura.
                consumed = amount(quantity * Decimal(8 + index % 5) / 100, unit)
                consumption = [code, period(serial), 'CONSUMO', number(consumed), unit,
                               history_available(serial)]
                if serial <= TRAIN_END:
                    train.append(consumption)
                if serial >= CURRENT_CONSUMPTION_START:
                    current.append(consumption)
            if serial >= EVALUATION_START:
                # Uma observação ausente não é substituída por um zero.
                if not (code == 'NX-005' and serial == END):
                    actuals.append([code, period(serial), number(quantity), unit,
                                    outcome_available(serial)])
                operations.append(operational(index, serial, product, quantity, base))

    assert len(products) == 24 and len(train) == len(current) == 672
    assert len(actuals) == 95 and len(operations) == 96
    files = {
        'produtos.json': json.dumps([{k: p[k] for k in ('codigo', 'nome', 'unidade')}
                                    for p in products], ensure_ascii=False, indent=2) + '\n',
        'produtos.csv': csv_text(['codigo', 'nome', 'unidade', 'padrao'],
                                 [[p[k] for k in ('codigo', 'nome', 'unidade', 'padrao')]
                                  for p in products]),
        'historico_treino.csv': csv_text(HISTORY, train),
        'historico_atual.csv': csv_text(HISTORY, current),
        'realizados.csv': csv_text(ACTUAL, actuals),
        'operacionais.csv': csv_text(OPS, operations),
    }
    sample = train[0]
    wrong_unit = sample.copy()
    wrong_unit[4] = 'kg'  # NX-001 usa un no cadastro.
    files['invalidos/duplicados.csv'] = csv_text(HISTORY, [sample, sample])
    files['invalidos/unidade_incompativel.csv'] = csv_text(HISTORY, [wrong_unit])
    manifest = {
        'versao': 1,
        'empresa': 'NexoPlan Distribuição Alimentar e Higiene',
        'sintetico': True,
        'semente': SEED,
        'referencia': REFERENCE.isoformat(),
        'produtos': len(products),
        'padroes': dict(Counter(p['padrao'] for p in products)),
        'unidades': dict(Counter(p['unidade'] for p in products)),
        'catalogo': products,
        'treino': {'inicio': period(START), 'fim': period(TRAIN_END), 'meses': 24,
                   'vendas': 576, 'consumo': 96, 'linhas': len(train),
                   'consumo_inicio': period(CONSUMPTION_START), 'consumo_meses': 12},
        'atual': {'inicio': period(CURRENT_START), 'fim': period(END), 'meses': 24,
                  'vendas': 576, 'consumo': 96, 'linhas': len(current),
                  'consumo_inicio': period(CURRENT_CONSUMPTION_START), 'consumo_meses': 12},
        'produtos_consumo': list(CONSUMPTION_CODES),
        'avaliacao_retrospetiva': {'inicio': period(EVALUATION_START), 'fim': period(END),
                                 'meses': 4, 'celulas_esperadas': 96,
                                 'realizados': len(actuals), 'operacionais': len(operations)},
        'disponibilidade': {
            'historico': 'Último dia do mês às 17:00:00 UTC, após fecho da atividade mensal fictícia.',
            'realizados_operacionais': 'Primeiro dia do mês seguinte às 00:00:00 UTC.',
            'nota': 'O fecho do histórico às 17h permite publicar uma previsão antes do mês seguinte. '
                    'Resultados mensais só ficam disponíveis após o fim do mês.',
        },
        'faltas_intencionais': [
            'NX-005: realizado de 2026-10 ausente; 95 observações para 96 células esperadas.',
            'NX-006: stock_fecho de 2026-10 vazio; restantes campos operacionais preenchidos.',
        ],
        'semantica': [
            'Empresa, produtos e quantidades inteiramente fictícios; não incluem dados pessoais nem credenciais.',
            'VENDAS é a procura observada nesta demonstração e coincide exatamente com REALIZADOS nos meses comuns.',
            'unidades_solicitadas coincide com essa procura; unidades_satisfeitas_no_prazo mede apenas o serviço no prazo.',
            'CONSUMO representa utilização interna de oito referências em cabazes, amostras e manutenção.',
            'Quantidades zero de referências intermitentes são observadas, não lacunas; o MAPE deve excluir denominadores zero.',
            'Campanhas, tendências e sazonalidade são padrões gerados, não calendários ou promoções reais.',
            'Os CSV usam ponto decimal, separador ponto e vírgula e UTF-8; produtos.json serve a API de produtos.',
            'As datas de publicação de planos só podem ser contextualizadas pelo procedimento explícito da simulação isolada.',
        ],
        'contagens': {name: len(rows) for name, rows in [
            ('produtos.csv', products), ('historico_treino.csv', train),
            ('historico_atual.csv', current), ('realizados.csv', actuals),
            ('operacionais.csv', operations)]},
        'sha256': {name: hashlib.sha256(content.encode('utf-8')).hexdigest()
                   for name, content in sorted(files.items())},
    }
    files['manifesto.json'] = json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'
    # Valida todos os destinos antes de escrever, para evitar uma troca parcial.
    for name, content in files.items():
        target = folder / name
        if target.exists() and target.read_bytes() != content.encode('utf-8'):
            raise ValueError(f'Ficheiro diferente já existe: {target}. Usa outra pasta de saída.')
    for name, content in files.items():
        target = folder / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content.encode('utf-8'))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--saida', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'data/empresa')
    args = parser.parse_args()
    try:
        manifest = generate(args.saida)
    except ValueError as error:
        parser.error(str(error))
    print(f"24 produtos fictícios; {manifest['treino']['linhas']} linhas de treino; "
          f"{manifest['atual']['linhas']} linhas atuais; 95 realizados; 96 operacionais. "
          f"Saída: {args.saida}")


if __name__ == '__main__':
    main()
