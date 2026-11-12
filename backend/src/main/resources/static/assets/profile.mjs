import { esc, field, input, label, button } from './ui.mjs';

export function accountAvatar(user, revision, large = false) {
  const words = user.nome.trim().split(/\s+/);
  const first = [...words[0]][0] || '';
  const last = words.length > 1 ? [...words.at(-1)][0] : '';
  const initials = (first + last).toLocaleUpperCase('pt-PT');
  const photo = user.temFoto
    ? `<img src="/api/v1/auth/foto?v=${esc(revision)}" alt="${large ? 'Foto de perfil' : ''}" data-profile-photo>`
    : '';
  return `<span class="avatar${large ? ' profile-avatar' : ''}" data-account-avatar>
    <span aria-hidden="true">${esc(initials)}</span>${photo}
  </span>`;
}

export function accountPage(user, revision) {
  const newPasswordAttrs = 'required minlength="12" maxlength="72" autocomplete="new-password"';
  return `<div class="page-heading"><h1>A minha conta</h1></div>
    <div class="account-layout">
      <section class="panel profile-card" aria-labelledby="account-details-title">
        <div class="panel-head"><h2 id="account-details-title">Dados da conta</h2></div>
        <dl class="profile-details">
          <div><dt>Nome</dt><dd>${esc(user.nome)}</dd></div>
          <div><dt>Email</dt><dd>${esc(user.email)}</dd></div>
          <div><dt>Papéis atribuídos</dt><dd>${user.papeis.map(role => esc(label(role))).join(' · ')}</dd></div>
        </dl>
      </section>
      <section class="panel profile-card" aria-labelledby="account-photo-title">
        <div class="panel-head"><h2 id="account-photo-title">Foto de perfil</h2></div>
        <form id="account-photo-form" class="profile-form">
          <div class="profile-photo-fields">
            ${accountAvatar(user, revision, true)}
            ${field('Fotografia', '<input name="foto" type="file" accept="image/jpeg,image/png" required>', 'JPEG ou PNG, até 2 MB e 4096 × 4096 px.')}
          </div>
          <div class="form-error" role="alert"></div>
          <div class="profile-actions">
            <button class="btn primary" type="submit">Guardar foto</button>
            ${button('Remover foto', 'remove-account-photo', user.temFoto ? '' : 'hidden', 'danger')}
          </div>
        </form>
      </section>
      <section class="panel profile-card" aria-labelledby="account-password-title">
        <div class="panel-head"><h2 id="account-password-title">Alterar palavra-passe</h2></div>
        <form id="account-password-form" class="profile-form">
          <div class="profile-password-fields">
            ${field('Palavra-passe atual', input('passwordAtual', 'password', '', 'required maxlength="200" autocomplete="current-password"'))}
            ${field('Nova palavra-passe', input('novaPassword', 'password', '', newPasswordAttrs), 'Pelo menos 12 caracteres e até 72 bytes UTF-8.')}
            ${field('Confirmar nova palavra-passe', input('confirmacao', 'password', '', newPasswordAttrs))}
          </div>
          <p class="profile-help">Ao guardar, vais ter de voltar a entrar com a nova palavra-passe.</p>
          <div class="form-error" role="alert"></div>
          <div class="profile-actions"><button class="btn primary" type="submit">Alterar palavra-passe</button></div>
        </form>
      </section>
    </div>`;
}
