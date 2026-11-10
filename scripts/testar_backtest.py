"""Compara Media Movel e ETS por cortes temporais, com dados sinteticos/historicos."""
import getpass
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request


def main():
    base=input('URL do backend [http://localhost:8080]: ').strip() or 'http://localhost:8080'
    email=input('Email: ').strip()
    password=getpass.getpass('Password: ')
    client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logged_in=False

    def request(method,path,data=None):
        headers={'Content-Type':'application/json'}
        if method not in ('GET','HEAD'):
            csrf=request('GET','/api/v1/auth/csrf');headers[csrf['headerName']]=csrf['token']
        req=urllib.request.Request(base.rstrip('/')+path,data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
        with client.open(req,timeout=180) as response:
            raw=response.read();return json.loads(raw) if raw else None

    def show(value):
        print(json.dumps(value,ensure_ascii=False,indent=2))

    def number(value,suffix=''):
        return 'indisponivel' if value is None else value+suffix

    try:
        user=request('POST','/api/v1/auth/login',{'email':email,'password':password})
        password=None;logged_in=True
        if not {'ADMIN','PLANEADOR'}.intersection(user['papeis']):
            raise SystemExit('Este perfil nao pode criar backtests.')
        lots=request('GET','/api/v1/lotes?size=200')['items']
        valid=[lot for lot in lots if lot['tipo']=='HISTORICO' and lot['estado']=='VALIDADO']
        print('Historicos validados (pagina mais recente):')
        for lot in valid[:10]:
            print(f"  ID {lot['id']} | {lot['nomeFicheiro']} | {lot['totalLinhas']} registos")
        default=valid[0]['id'] if valid else ''
        lot_id=input(f'ID numerico do lote [{default}]: ').strip() or default
        if not lot_id.isdigit() or int(lot_id)<=0:
            raise SystemExit('Indica um ID de lote valido, nao o codigo SKU.')
        series=input('Tipo de serie [VENDAS]: ').strip().upper() or 'VENDAS'
        horizon=input('Horizonte em meses [3]: ').strip() or '3'
        if series not in ('VENDAS','CONSUMO') or not horizon.isdigit() or not 1<=int(horizon)<=12:
            raise SystemExit('Usa VENDAS ou CONSUMO, e horizonte entre 1 e 12.')
        suggestion=request('GET','/api/v1/backtests/sugestao?'+urllib.parse.urlencode({'loteHistoricoId':lot_id,'tipoSerie':series,'horizonteMeses':horizon}))
        print('Simulacao retrospetiva: cada corte identifica o ultimo mes de treino.')
        first=input(f"Primeiro corte [{suggestion['primeiroCorte']}]: ").strip() or suggestion['primeiroCorte']
        last=input(f"Ultimo corte [{suggestion['ultimoCorte']}]: ").strip() or suggestion['ultimoCorte']
        for month in (first,last):
            if not re.fullmatch(r'[1-9][0-9]{3}-(0[1-9]|1[0-2])',month):
                raise SystemExit('Os cortes devem usar AAAA-MM.')
        window=input('Janela Media Movel [3]: ').strip() or '3'
        history=input('Maximo de meses ETS [24]: ').strip() or '24'
        if not window.isdigit() or not 1<=int(window)<=36 or not history.isdigit() or not 6<=int(history)<=120:
            raise SystemExit('Janela entre 1 e 36; historico ETS entre 6 e 120.')
        result=request('POST','/api/v1/backtests',{'loteHistoricoId':lot_id,'tipoSerie':series,'primeiroCorte':first,'ultimoCorte':last,
                       'horizonteMeses':int(horizon),'janelaMediaMovel':int(window),'historicoEtsMeses':int(history)})
        print('Backtest criado:');show(result)
        path='/api/v1/backtests/'+result['id']
        comparison=request('GET',path+'/comparacao?size=200')
        print('Comparacao por produto e horizonte (primeira pagina):')
        for group in comparison['items']:
            m=group['metricas']
            print(f"\n{group['produto']} ({group['unidade']}) | horizonte {group['horizonte']} | pares {m['paresComparaveis']}/{m['candidatos']} | cobertura {number(m['coberturaPercentagem'],'%')}")
            print('  MAE:  MM='+number(m['maeMediaMovel'])+' | ETS='+number(m['maeEts']))
            print('  MAPE: MM='+number(m['mapeMediaMovel'],'%')+' | ETS='+number(m['mapeEts'],'%')+f" | zeros excluidos={m['zerosExcluidosMape']}")
        if input('\nVer detalhe das primeiras celulas? [s/N]: ').strip().lower()=='s':
            show(request('GET',path+'/celulas'))
    except urllib.error.HTTPError as error:
        try:
            problem=json.loads(error.read())
        except (ValueError,UnicodeError):
            problem={}
        print(f'Pedido recusado (HTTP {error.code}).');show(problem)
        raise SystemExit(1) from None
    except (urllib.error.URLError,TimeoutError):
        raise SystemExit('Falha de ligacao. Consulta a lista de backtests antes de repetir.') from None
    finally:
        if logged_in:
            try:
                request('POST','/api/v1/auth/logout',{});print('Logout concluido.')
            except (urllib.error.URLError,TimeoutError):
                print('Nao foi possivel confirmar o logout.')


if __name__=='__main__':
    main()
