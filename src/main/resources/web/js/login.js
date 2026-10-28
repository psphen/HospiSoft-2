/*
 * Login page glue. Database work goes through HospiSoft.appCall (asynchronous, never blocks the UI).
 */
(function () {
  'use strict';

  var form = document.getElementById('login-form');
  var usernameInput = document.getElementById('login-username');
  var passwordInput = document.getElementById('login-password');
  var submitButton = document.getElementById('login-submit');
  var errorBox = document.getElementById('login-error');
  var statusBox = document.getElementById('login-status');
  var submitLabel = submitButton.innerHTML;
  var submitting = false;

  function showError(message) {
    errorBox.textContent = message;
    errorBox.classList.remove('d-none');
  }

  function hideError() {
    errorBox.textContent = '';
    errorBox.classList.add('d-none');
  }

  function showStatus(message, level) {
    statusBox.className = 'alert alert-' + level + ' small mb-3';
    statusBox.textContent = message;
    statusBox.classList.remove('d-none');
  }

  function hideStatus() {
    statusBox.classList.add('d-none');
  }

  function setBusy(busy) {
    submitting = busy;
    submitButton.disabled = busy;
    submitButton.innerHTML = busy ? 'Verificando…' : submitLabel;
  }

  function renderStatus(status) {
    console.log('startup status: ' + status.state);
    switch (status.state) {
      case 'OK':
        hideStatus();
        break;
      case 'PENDING':
        showStatus('Verificando conexión con la base de datos…', 'secondary');
        break;
      case 'CONFIG_MISSING':
        showStatus('No se encontró config.properties (ruta esperada: ' + status.expectedPath + ')', 'warning');
        break;
      case 'CONFIG_READ_ERROR':
        showStatus('No se pudo leer config.properties (' + status.expectedPath + ')', 'warning');
        break;
      case 'CONFIG_KEYS_MISSING':
        showStatus('Faltan claves en config.properties: ' + status.missingKeys.join(', '), 'warning');
        break;
      case 'DB_UNAVAILABLE':
        showStatus('Sin conexión con la base de datos', 'danger');
        break;
      case 'DB_AUTH_FAILED':
        showStatus('Credenciales de base de datos inválidas, revise config.properties', 'danger');
        break;
      case 'TABLES_MISSING':
        showStatus('Faltan tablas en la base de datos: ' + status.missingTables.join(', ') +
          '. Ejecute db/schema.sql en el SQL Editor de Supabase', 'danger');
        break;
      default:
        showStatus('Estado desconocido: ' + status.state, 'secondary');
    }
  }

  function renderResponse(response) {
    if (response.ok) {
      renderStatus(response.data);
    } else {
      showStatus(response.message, 'danger');
    }
  }

  /* Fresh check on a background thread; the page shows PENDING meanwhile. */
  function checkStartup() {
    renderStatus({ state: 'PENDING' });
    window.HospiSoft.appCall('getStartupStatus').then(renderResponse).catch(function (error) {
      showStatus(error.message, 'danger');
    });
  }

  /* Java pushes this event after a background refresh: read the cached value, no blocking. */
  function readCachedStatus() {
    renderResponse(window.HospiSoft.call('getStartupStatus'));
  }

  function submit(event) {
    event.preventDefault();
    if (submitting) {
      return;
    }
    hideError();
    var username = usernameInput.value.trim();
    var password = passwordInput.value;
    if (!username || !password) {
      showError('Ingrese usuario y contraseña');
      return;
    }
    setBusy(true);
    window.HospiSoft.appCall('login', { username: username, password: password })
      .then(function (response) {
        setBusy(false);
        if (response.ok) {
          window.HospiSoft.call('navigate', 'index.html');
          return;
        }
        passwordInput.value = '';
        showError(response.message);
      })
      .catch(function (error) {
        setBusy(false);
        showError(error.message);
      });
  }

  form.addEventListener('submit', submit);

  window.addEventListener('load-error', function (event) {
    showError(event.detail);
  });

  window.HospiSoft.onReady(function () {
    checkStartup();
    window.addEventListener('startup-status', readCachedStatus);
    usernameInput.focus();
  });
})();
