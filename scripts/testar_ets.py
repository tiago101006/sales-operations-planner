"""Usa o fluxo comum de previsao, propondo ETS(A,A,N)."""
from testar_previsao import main

if __name__ == '__main__':
    print('ETS(A,A,N): erro e tendencia aditivos, sem sazonalidade nesta variante.')
    main('ETS')
