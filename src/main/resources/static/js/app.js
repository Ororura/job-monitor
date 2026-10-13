/* Theme preference and transport errors are the only application-side scripting. */
const theme = document.getElementById('theme');
function applyTheme(value) {
  document.documentElement.dataset.theme = value;
  if (theme) theme.value = value;
}
try { applyTheme(localStorage.getItem('job-monitor-theme') || 'system'); } catch (_) { applyTheme('system'); }
theme?.addEventListener('change', () => {
  applyTheme(theme.value);
  try { localStorage.setItem('job-monitor-theme', theme.value); } catch (_) { /* Storage can be disabled. */ }
});
document.body.addEventListener('htmx:beforeSwap', event => {
  if (event.detail.xhr.status >= 400 && event.detail.xhr.status <= 599) {
    event.detail.shouldSwap = true;
    event.detail.isError = false;
  }
});
function transportError() {
  const notice = document.getElementById('notice');
  notice.replaceChildren();
  const message = document.createElement('div');
  message.className = 'notice notice-error';
  message.setAttribute('role', 'alert');
  message.textContent = 'Нет соединения с приложением. Проверьте, что Job Monitor запущен, и повторите действие.';
  notice.appendChild(message);
}
document.body.addEventListener('htmx:sendError', transportError);
document.body.addEventListener('htmx:timeout', transportError);
document.body.addEventListener('htmx:afterSwap', event => {
  event.detail.target.querySelector('[aria-invalid="true"]')?.focus();
});
