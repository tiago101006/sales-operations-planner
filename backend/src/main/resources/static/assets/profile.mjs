import { esc, field, input, label, button, initials, badge } from './ui.mjs';

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
  const status = !user.ativo ? 'INATIVO' : user.passwordDefinida ? 'ATIVO' : 'POR_ATIVAR';
  return `<div class="page-heading account-heading"><h1>A minha conta</h1></div>
    <div class="account-sheet">
      <section class="profile-portrait-panel" aria-labelledby="account-person-title">
        <div class="profile-person">
          ${accountAvatar(user, revision, true)}
          <h2 id="account-person-title" class="profile-name">${esc(user.nome)}</h2>
          <div class="profile-roles" aria-label="Papéis atribuídos">${user.papeis.map(p => `<span>${esc(label(p))}</span>`).join('')}</div>
        </div>
        <details class="profile-photo-editor">
          <summary>Alterar fotografia</summary>
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
      </section>
      <div class="profile-content">
        <section aria-labelledby="account-details-title">
          <h2 id="account-details-title">Dados da conta</h2>
          <dl class="profile-details">
            <div class="profile-email"><dt>Email</dt><dd>${esc(user.email)}</dd></div>
            <div><dt>Identificador</dt><dd>#${esc(user.id)}</dd></div>
            <div><dt>Estado da conta</dt><dd>${badge(status)}</dd></div>
          </dl>
        </section>
        <section class="profile-security" aria-labelledby="account-password-title">
          <h2 id="account-password-title">Segurança</h2>
          <div class="profile-password-start">
            <div><h3>Palavra-passe</h3><p class="profile-help">Confirma a atual para definir uma nova.</p></div>
            <button class="btn" type="button" data-action="open-account-password">Alterar palavra-passe</button>
          </div>
          <div id="account-password-editor"></div>
          <template id="account-password-template">
            <form id="account-password-form" class="profile-form" autocomplete="off">
              <div class="profile-password-fields">
                ${field('Palavra-passe atual', input('passwordAtual', 'password', '', 'required maxlength="200" autocomplete="off" readonly data-manual-password placeholder="Escreve a palavra-passe atual"'))}
                ${field('Nova palavra-passe', input('novaPassword', 'password', '', newPasswordAttrs), 'Pelo menos 12 caracteres e até 72 bytes UTF-8.')}
                ${field('Confirmar nova palavra-passe', input('confirmacao', 'password', '', newPasswordAttrs))}
              </div>
              <p class="profile-help">Ao guardar, vais ter de voltar a entrar com a nova palavra-passe.</p>
              <div class="form-error" role="alert"></div>
              <div class="profile-actions"><button class="btn primary" type="submit">Guardar nova palavra-passe</button>${button('Cancelar', 'cancel-account-password')}</div>
            </form>
          </template>
        </section>
      </div>
    </div>`;
}

export function closePasswordEditor(container = document) {
  const host = container.querySelector('#account-password-editor');
  host?.querySelector('form')?.reset();
  host?.replaceChildren();
  const start = container.querySelector('.profile-password-start');
  if (start) start.hidden = false;
}

export function requireCurrentPassword(form) {
  const current = form.querySelector('[data-manual-password]');
  if (!current || current.readOnly || current.dataset.manualPasswordReady !== 'true' || !current.value) {
    throw new Error('Introduz a tua palavra-passe atual para continuar.');
  }
  return current.value;
}

function prepareCurrentPassword(form) {
  const current = form.querySelector('[data-manual-password]');
  let typedValue = '';
  const blockedInsertion = type => /^insert(?:FromPaste.*|FromDrop|ReplacementText)$/.test(type);
  const unlock = (editing = false) => {
    if (current.dataset.manualPasswordReady !== 'true') current.value = '';
    current.readOnly = false;
    if (editing) current.dataset.manualPasswordReady = 'true';
  };
  // Direct touch/click enables the keyboard without accepting a saved login.
  for (const name of ['pointerdown', 'click']) current.addEventListener(name, event => {
    if (event.isTrusted && !event.defaultPrevented) unlock();
  });
  current.addEventListener('keydown', event => {
    if (!event.isTrusted || event.defaultPrevented) return;
    const clipboardShortcut = ((event.ctrlKey || event.metaKey) && !event.altKey && ['v', 'c', 'x'].includes(event.key.toLowerCase())) || (event.shiftKey && event.key === 'Insert');
    if (clipboardShortcut) { event.preventDefault(); return; }
    const plain = (!event.ctrlKey && !event.metaKey && !event.altKey) || event.getModifierState('AltGraph');
    const character = plain && ([...event.key].length === 1 || ['Dead', 'Process', 'Unidentified'].includes(event.key) || event.isComposing);
    if (character || ['Backspace', 'Delete'].includes(event.key)) unlock(true);
  });
  for (const name of ['paste', 'drop', 'copy', 'cut']) current.addEventListener(name, event => {
    event.preventDefault();
  });
  current.addEventListener('beforeinput', event => {
    if (blockedInsertion(event.inputType)) { event.preventDefault(); return; }
    if (event.isTrusted && /^insert(?:Text|CompositionText)$/.test(event.inputType)) unlock(true);
  });
  for (const name of ['input', 'change']) current.addEventListener(name, event => {
    // Keep saved-login replacement and clipboard insertion out of this field.
    if (current.readOnly || current.dataset.manualPasswordReady !== 'true') {
      current.value = '';
    } else if (name === 'input' && event.isTrusted && /^(?:insertText|insertCompositionText|delete\w+|historyUndo|historyRedo)$/.test(event.inputType)) {
      typedValue = current.value;
    } else {
      current.value = typedValue;
    }
  });
}

export function mountAccountPage(container, { change = (element, mutate) => mutate() } = {}) {
  const open = container.querySelector('[data-action="open-account-password"]');
  const host = container.querySelector('#account-password-editor');
  const template = container.querySelector('#account-password-template');
  if (!open || !host || !template) return;
  open.addEventListener('click', () => {
    change(container.querySelector('.profile-security'), () => {
      closePasswordEditor(container);
      host.append(template.content.cloneNode(true));
      container.querySelector('.profile-password-start').hidden = true;
      host.querySelector('form').reset();
      prepareCurrentPassword(host.querySelector('form'));
    });
    host.querySelector('[name="passwordAtual"]').focus({ preventScroll: true });
  });
  host.addEventListener('click', event => {
    if (!event.target.closest('[data-action="cancel-account-password"]') || host.querySelector('form')?.dataset.busy) return;
    change(container.querySelector('.profile-security'), () => closePasswordEditor(container));
    open.focus({ preventScroll: true });
  });
}
