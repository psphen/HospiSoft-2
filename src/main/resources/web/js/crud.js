/*
 * Shared helpers for the CRUD pages (medicos.js, pacientes.js): table rendering utilities,
 * pagination, modal form state and validation feedback. Pure view glue; rules live in Java.
 */
(function () {
  'use strict';

  var PAGE_SIZE = 10;
  var PAGE_WINDOW = 5;

  function escapeHtml(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function badge(activo) {
    return activo
      ? '<span class="badge-stock activo">Activo</span>'
      : '<span class="badge-stock inactivo">Inactivo</span>';
  }

  function actionButtons(id, modalId) {
    var safeId = escapeHtml(Number(id));
    return '<button class="btn-icon edit" data-action="edit" data-id="' + safeId + '" data-bs-toggle="modal" data-bs-target="#' + modalId + '" title="Editar"><i class="fas fa-pen"></i></button> '
      + '<button class="btn-icon delete" data-action="delete" data-id="' + safeId + '" data-bs-toggle="modal" data-bs-target="#modalEliminar" title="Eliminar"><i class="fas fa-trash"></i></button>';
  }

  function emptyRow(columns, text) {
    return '<tr><td colspan="' + columns + '" class="text-center text-muted py-4">' + escapeHtml(text) + '</td></tr>';
  }

  /* Renders "Mostrando X de N <noun>" and the prev / numbered / next controls. */
  function renderPagination(summaryEl, listEl, page, noun, onPage) {
    var shown = page.items.length;
    summaryEl.textContent = 'Mostrando ' + shown + ' de ' + page.total + ' ' + noun;

    var current = page.page;
    var totalPages = page.totalPages;
    var start = Math.max(1, current - Math.floor(PAGE_WINDOW / 2));
    var end = Math.min(totalPages, start + PAGE_WINDOW - 1);
    start = Math.max(1, end - PAGE_WINDOW + 1);

    var html = '';
    html += item(current - 1, '<i class="fas fa-chevron-left"></i>', current <= 1, false);
    for (var n = start; n <= end; n++) {
      html += item(n, String(n), false, n === current);
    }
    html += item(current + 1, '<i class="fas fa-chevron-right"></i>', current >= totalPages, false);
    listEl.innerHTML = html;

    Array.prototype.forEach.call(listEl.querySelectorAll('a.page-link[data-page]'), function (link) {
      link.addEventListener('click', function (event) {
        event.preventDefault();
        var li = link.parentNode;
        if (li.classList.contains('disabled') || li.classList.contains('active')) {
          return;
        }
        onPage(parseInt(link.getAttribute('data-page'), 10));
      });
    });

    function item(target, label, disabled, active) {
      var cls = 'page-item' + (disabled ? ' disabled' : '') + (active ? ' active' : '');
      return '<li class="' + cls + '"><a class="page-link" href="#" data-page="' + escapeHtml(Number(target)) + '">' + label + '</a></li>';
    }
  }

  /*
   * Request sequencing: a slow response must never overwrite a newer one. Callers take a token
   * before sending and check isCurrent(key, token) before touching the DOM.
   */
  var requestCounters = {};

  function nextRequest(key) {
    requestCounters[key] = (requestCounters[key] || 0) + 1;
    return requestCounters[key];
  }

  function isCurrent(key, token) {
    return requestCounters[key] === token;
  }

  /* Called when a list request fails: no stale counts or page links remain. */
  function clearPagination(summaryEl, listEl) {
    summaryEl.textContent = '';
    listEl.innerHTML = '';
  }

  /* Page to reload when the current page ran past the end (e.g. after a delete). */
  function clampedPage(page) {
    if (page.items.length === 0 && page.page > page.totalPages && page.totalPages >= 1) {
      return page.totalPages;
    }
    return null;
  }

  /* Validation feedback: marks fields with is-invalid and fills their invalid-feedback div. */
  function clearValidation(form) {
    Array.prototype.forEach.call(form.querySelectorAll('.is-invalid'), function (el) {
      el.classList.remove('is-invalid');
    });
    Array.prototype.forEach.call(form.querySelectorAll('.invalid-feedback'), function (el) {
      el.textContent = '';
    });
  }

  function showValidation(form, errors, fieldMap) {
    clearValidation(form);
    var firstInvalid = null;
    Object.keys(errors || {}).forEach(function (field) {
      var id = fieldMap[field];
      var input = id ? document.getElementById(id) : null;
      if (!input) {
        return;
      }
      input.classList.add('is-invalid');
      var feedback = input.parentNode.querySelector('.invalid-feedback');
      if (feedback) {
        feedback.textContent = errors[field];
      }
      if (!firstInvalid) {
        firstInvalid = input;
      }
    });
    if (firstInvalid) {
      firstInvalid.focus();
    }
  }

  function showModalAlert(alertEl, message) {
    alertEl.textContent = message;
    alertEl.classList.remove('d-none');
  }

  function hideModalAlert(alertEl) {
    alertEl.textContent = '';
    alertEl.classList.add('d-none');
  }

  function setBusy(button, busy, label) {
    button.disabled = busy;
    if (busy) {
      button.setAttribute('data-label', button.innerHTML);
      button.innerHTML = label;
    } else if (button.getAttribute('data-label')) {
      button.innerHTML = button.getAttribute('data-label');
      button.removeAttribute('data-label');
    }
  }

  function modal(id) {
    return window.bootstrap.Modal.getOrCreateInstance(document.getElementById(id));
  }

  /* Text to show after a delete, according to the Java outcome. */
  function deleteMessage(outcome) {
    return outcome === 'DEACTIVATED'
      ? 'Se dio de baja (tiene historial asociado)'
      : 'Eliminado';
  }

  window.HospiSoftCrud = {
    PAGE_SIZE: PAGE_SIZE,
    escapeHtml: escapeHtml,
    badge: badge,
    actionButtons: actionButtons,
    emptyRow: emptyRow,
    renderPagination: renderPagination,
    clearPagination: clearPagination,
    nextRequest: nextRequest,
    isCurrent: isCurrent,
    clampedPage: clampedPage,
    clearValidation: clearValidation,
    showValidation: showValidation,
    showModalAlert: showModalAlert,
    hideModalAlert: hideModalAlert,
    setBusy: setBusy,
    modal: modal,
    deleteMessage: deleteMessage
  };
})();
