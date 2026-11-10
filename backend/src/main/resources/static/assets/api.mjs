export class ApiError extends Error {
  constructor(status, problem = {}) {
    const details = (problem.fieldErrors || []).map(e => `${e.field}: ${e.message}`).join(' · ');
    super([problem.detail || problem.title || `Pedido recusado (${status}).`, details].filter(Boolean).join(' '));
    this.status = status;
    this.problem = problem;
  }
}

export class Api {
  constructor(fetcher = (...args) => fetch(...args)) { this.fetcher = fetcher; this.onUnauthorized = () => {}; }
  async request(path, options = {}) {
    let response;
    try { response = await this.fetcher('/api/v1' + path, { credentials: 'same-origin', cache: 'no-store', ...options }); }
    catch { throw new Error(options.method && options.method !== 'GET'
      ? 'A ligação foi interrompida. O pedido pode ter sido guardado; consulta os dados antes de repetir.'
      : 'Não foi possível ligar à aplicação. Verifica a ligação e tenta atualizar.'); }
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      if (response.status === 401 && path !== '/auth/login') this.onUnauthorized();
      throw new ApiError(response.status, problem);
    }
    return response;
  }
  async get(path) { return (await this.request(path)).json(); }
  async send(path, body, method = 'POST') {
    const csrf = await this.get('/auth/csrf');
    const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
    const headers = { [csrf.headerName]: csrf.token };
    if (!multipart) headers['Content-Type'] = 'application/json';
    const response = await this.request(path, { method, headers, body: multipart ? body : JSON.stringify(body) });
    return response.status === 204 ? null : response.json();
  }
  async all(path) {
    const items = [];
    for (let page = 0; ; page++) {
      const result = await this.get(`${path}${path.includes('?') ? '&' : '?'}size=200&page=${page}`);
      items.push(...result.items);
      if (page + 1 >= result.totalPages) return items;
    }
  }
  async download(id) {
    const response = await this.request(`/planos/${encodeURIComponent(id)}/exportacao.csv`);
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = `plano-${id}.csv`; document.body.append(anchor); anchor.click(); anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
