import { esc, field, input, label, button, initials } from './ui.mjs';

export function accountAvatar(user, revision, large = false) {
  const photo = user.temFoto
    ? `<img src="/api/v1/auth/foto?v=${esc(revision)}" alt="${large ? 'Foto de perfil' : ''}" data-profile-photo>`
    : '';
  return `<span class="avatar${large ? ' profile-avatar' : ''}" data-account-avatar>
    <span aria-hidden="true">${esc(initials(user.nome))}</span>${photo}
  </span>`;
}

export function accountPage(user, revision) {
  const newPasswordAttrs = 'required minlength="12" maxlength="72" autocomplete="new-password"';
  return `<div class="page-heading"><h1>A minha conta</h1></div>
    <div class="account-layout">
      <section class="profile-identity" aria-labelledby="account-details-title">
        <div class="profile-person">
          ${accountAvatar(user, revision, true)}
          <div>
            <h2 id="account-details-title">Dados da conta</h2>
            <p class="profile-name">${esc(user.nome)}</p>
          </div>
        </div>
        <dl class="profile-details">
          <div><dt>Email</dt><dd>${esc(user.email)}</dd></div>
          <div>
            <dt>Papéis atribuídos</dt>
            <dd class="profile-roles">${user.papeis.map(role => `<span>${esc(label(role))}</span>`).join('')}</dd>
          </div>
        </dl>
      </section>
      <details class="profile-photo-editor">
        <summary>Alterar fotografia de perfil</summary>
        <form id="account-photo-form" class="profile-form">
          <p class="profile-help">A fotografia aparece no cabeçalho e na lista da Equipa, consultada por administradores.</p>
          <div class="profile-photo-fields">
            ${field('Fotografia', '<input name="foto" type="file" accept="image/jpeg,image/png" required>', 'JPEG ou PNG, até 2 MB e 4096 × 4096 px.')}
          </div>
          <div class="form-error" role="alert"></div>
          <div class="profile-actions">
            <button class="btn primary" type="submit">Guardar foto</button>
            ${button('Remover foto', 'remove-account-photo', user.temFoto ? '' : 'hidden', 'danger')}
          </div>
        </form>
      </details>
      <section class="panel profile-card" aria-labelledby="account-password-title">
        <div class="panel-head"><h2 id="account-password-title">Alterar palavra-passe</h2></div>
        <div class="profile-password-start">
          <p class="profile-help">Confirma a tua palavra-passe atual para definir uma nova.</p>
          <button class="btn" type="button" data-action="open-account-password">Alterar palavra-passe</button>
        </div>
        <div id="account-password-editor"></div>
        <template id="account-password-template">
        <form id="account-password-form" class="profile-form" autocomplete="off">
          <div class="profile-password-fields">
            ${field('Palavra-passe atual', input('passwordAtual', 'password', '', 'required maxlength="200" autocomplete="off"'))}
            ${field('Nova palavra-passe', input('novaPassword', 'password', '', newPasswordAttrs), 'Pelo menos 12 caracteres e até 72 bytes UTF-8.')}
            ${field('Confirmar nova palavra-passe', input('confirmacao', 'password', '', newPasswordAttrs))}
          </div>
          <p class="profile-help">Ao guardar, vais ter de voltar a entrar com a nova palavra-passe.</p>
          <div class="form-error" role="alert"></div>
          <div class="profile-actions"><button class="btn primary" type="submit">Guardar nova palavra-passe</button>${button('Cancelar', 'cancel-account-password')}</div>
        </form>
        </template>
      </section>
    </div>`;
}

export function closePasswordEditor(container = document) {
  const host = container.querySelector('#account-password-editor');
  host?.querySelector('form')?.reset();
  host?.replaceChildren();
  const start = container.querySelector('.profile-password-start');
  if (start) start.hidden = false;
}

export function mountAccountPage(container) {
  const open = container.querySelector('[data-action="open-account-password"]');
  const host = container.querySelector('#account-password-editor');
  const template = container.querySelector('#account-password-template');
  if (!open || !host || !template) return;
  open.addEventListener('click', () => {
    closePasswordEditor(container);
    host.append(template.content.cloneNode(true));
    container.querySelector('.profile-password-start').hidden = true;
    host.querySelector('form').reset();
    host.querySelector('[name="passwordAtual"]').focus();
  });
  host.addEventListener('click', event => {
    if (!event.target.closest('[data-action="cancel-account-password"]') || host.querySelector('form')?.dataset.busy) return;
    closePasswordEditor(container);
    open.focus();
  });
}
