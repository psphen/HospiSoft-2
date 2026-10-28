/*
 * View glue shared by every page.
 * Business logic lives in Java; this file only calls the bridge (window.app) and updates the DOM.
 *
 * Bridge contract: every method takes and returns JSON strings.
 *   success: {"ok":true,"data":...}
 *   failure: {"ok":false,"code":"...","message":"..."}
 *
 * Two calling styles:
 *   HospiSoft.call(method, ...args)      synchronous, for in-memory methods (getSession, logout, navigate,
 *                                        getAllowedPages, cached getStartupStatus)
 *   HospiSoft.appCall(method, payload)   asynchronous (Promise), for anything that touches the database
 *                                        (login, fresh getStartupStatus). Java answers later through
 *                                        window.__hospisoft.resolve(requestId, envelopeJson).
 */
(function () {
  'use strict';

  var DB_BANNER_ID = 'db-banner';
  var DB_UNAVAILABLE_TEXT = 'Sin conexión con la base de datos';
  var ASYNC_TIMEOUT_MS = 40000;

  var pending = {};
  var sequence = 0;

  function showDbBanner() {
    var main = document.querySelector('.main-content');
    if (!main || document.getElementById(DB_BANNER_ID)) {
      return;
    }
    var banner = document.createElement('div');
    banner.id = DB_BANNER_ID;
    banner.className = 'alert alert-danger rounded-0 mb-0';
    banner.setAttribute('role', 'alert');
    banner.innerHTML = '<i class="fas fa-plug me-2"></i>' + DB_UNAVAILABLE_TEXT;
    main.insertBefore(banner, main.firstChild);
  }

  /* Transient alert at the top of the content area (success / danger); hides itself after 4 s. */
  function showToast(kind, text) {
    var area = document.querySelector('.main-content .p-4') || document.querySelector('.main-content');
    if (!area) {
      return;
    }
    var toast = document.createElement('div');
    toast.className = 'alert alert-' + kind + ' shadow-sm';
    toast.setAttribute('role', 'status');
    toast.textContent = text;
    area.insertBefore(toast, area.firstChild);
    setTimeout(function () {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 4000);
  }

  function hideDbBanner() {
    var banner = document.getElementById(DB_BANNER_ID);
    if (banner) {
      banner.parentNode.removeChild(banner);
    }
  }

  function inspect(response) {
    if (!response.ok && response.code === 'DB_UNAVAILABLE') {
      showDbBanner();
    }
    return response;
  }

  /* Calls a bridge method synchronously and always returns an envelope object, never throws. */
  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!window.app) {
      return { ok: false, code: 'NO_BRIDGE', message: 'Puente de aplicación no disponible' };
    }
    var raw;
    try {
      raw = window.app[method].apply(window.app, args);
    } catch (e) {
      return { ok: false, code: 'INTERNAL_ERROR', message: String(e) };
    }
    var response;
    try {
      response = JSON.parse(raw);
    } catch (e) {
      return { ok: false, code: 'BAD_RESPONSE', message: 'Respuesta inválida del puente' };
    }
    return inspect(response);
  }

  /* Receives asynchronous results from Java. */
  window.__hospisoft = {
    resolve: function (requestId, envelopeJson) {
      var entry = pending[requestId];
      if (!entry) {
        return;
      }
      delete pending[requestId];
      clearTimeout(entry.timer);
      var response;
      try {
        response = JSON.parse(envelopeJson);
      } catch (e) {
        response = { ok: false, code: 'BAD_RESPONSE', message: 'Respuesta inválida del puente' };
      }
      entry.resolve(inspect(response));
    }
  };

  /*
   * Asynchronous bridge call. Resolves with the envelope; rejects with an Error (code TIMEOUT)
   * when Java does not answer within ASYNC_TIMEOUT_MS, or with code NO_BRIDGE / BAD_REQUEST
   * when the request could not even be queued.
   */
  function appCall(method, payload) {
    return new Promise(function (resolve, reject) {
      var requestId = 'r' + (++sequence) + '-' + Date.now();
      var body = payload == null ? '' : (typeof payload === 'string' ? payload : JSON.stringify(payload));
      var ack = call('call', requestId, method, body);
      if (!ack.ok) {
        var queueError = new Error(ack.message);
        queueError.code = ack.code;
        reject(queueError);
        return;
      }
      pending[requestId] = {
        resolve: resolve,
        timer: setTimeout(function () {
          delete pending[requestId];
          var timeout = new Error('Sin respuesta de la aplicación');
          timeout.code = 'TIMEOUT';
          reject(timeout);
        }, ASYNC_TIMEOUT_MS)
      };
    });
  }

  /* Runs fn once the bridge is injected (or immediately when it already is). */
  function onReady(fn) {
    if (window.app) {
      fn();
    } else {
      window.addEventListener('app-ready', fn, { once: true });
    }
  }

  /* Cosmetic only: hides sidebar links the role may not open. Java enforces the same rule. */
  function applyRoleNavigation() {
    var response = call('getAllowedPages');
    if (!response.ok || !response.data) {
      return;
    }
    var allowedPages = response.data;
    var links = document.querySelectorAll('.sidebar a.nav-link[href]');
    Array.prototype.forEach.call(links, function (link) {
      var href = link.getAttribute('href');
      if (href === '#' || allowedPages.indexOf(href) !== -1) {
        return;
      }
      var item = link.closest('li') || link;
      item.style.display = 'none';
    });
  }

  function applyTopbar(session) {
    var name = session.displayName || session.username || '';
    var nameEl = document.getElementById('topbar-user-name');
    var avatarEl = document.getElementById('topbar-user-avatar');
    if (nameEl) {
      nameEl.textContent = name;
    }
    if (avatarEl) {
      avatarEl.textContent = name ? name.charAt(0).toUpperCase() : '?';
    }
  }

  function bindLogout() {
    var logout = document.getElementById('nav-logout');
    if (!logout) {
      return;
    }
    logout.addEventListener('click', function (event) {
      event.preventDefault();
      call('logout');
      call('navigate', 'login.html');
    });
  }

  /* Diagnostic only: proves the vendored assets resolved (visible in the Java console). */
  function logAssetDiagnostics() {
    try {
      var root = getComputedStyle(document.documentElement);
      var bootstrapLoaded = root.getPropertyValue('--bs-blue').trim() !== '';
      var icon = document.querySelector('.fas');
      var faCss = icon ? getComputedStyle(icon).fontFamily : '(no icon)';
      var faFont = (typeof document.fonts !== 'undefined' && document.fonts.check)
        ? document.fonts.check('900 1em "Font Awesome 6 Free"') : 'n/a';
      console.log('assets: bootstrap=' + bootstrapLoaded + ' fontawesome-css=' + faCss + ' fontawesome-font=' + faFont);
    } catch (e) {
      console.log('assets: diagnostic failed ' + e);
    }
  }

  function initChrome() {
    console.log('bridge ready on ' + document.title);
    logAssetDiagnostics();
    bindLogout();
    var response = call('getSession');
    if (!response.ok || !response.data) {
      return;
    }
    applyTopbar(response.data);
    applyRoleNavigation();
  }

  window.HospiSoft = {
    call: call,
    appCall: appCall,
    onReady: onReady,
    showDbBanner: showDbBanner,
    hideDbBanner: hideDbBanner,
    showToast: showToast
  };

  onReady(initChrome);
})();
