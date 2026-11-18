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

  var requestCounters = {};

  function nextRequest(key) {
    requestCounters[key] = (requestCounters[key] || 0) + 1;
    return requestCounters[key];
  }

  function isCurrent(key, token) {
    return requestCounters[key] === token;
  }

  function clearPagination(summaryEl, listEl) {
    summaryEl.textContent = '';
    listEl.innerHTML = '';
  }

  function clampedPage(page) {
    if (page.items.length === 0 && page.page > page.totalPages && page.totalPages >= 1) {
      return page.totalPages;
    }
    return null;
  }

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

  function deleteMessage(outcome) {
    return outcome === 'DEACTIVATED'
      ? 'Se dio de baja (tiene historial asociado)'
      : 'Eliminado';
  }

  var EXPORT_TIMEOUT_MS = 30 * 60 * 1000;

  function errorText(response) {
    if (response.code === 'VALIDATION' && response.errors) {
      var fields = Object.keys(response.errors);
      if (fields.length > 0) {
        return response.errors[fields[0]];
      }
    }
    return response.message;
  }

  function bindExport(options) {
    var root = document.getElementById(options.menuId || 'export-menu');
    if (!root) {
      return;
    }
    var toggle = root.querySelector('[data-bs-toggle="dropdown"]');
    var busy = false;
    root.classList.remove('d-none');

    function done() {
      busy = false;
      setBusy(toggle, false);
    }

    Array.prototype.forEach.call(root.querySelectorAll('[data-export-format]'), function (item) {
      item.addEventListener('click', function (event) {
        event.preventDefault();
        if (busy) {
          return;
        }
        var filter = options.filter();
        if (filter === null) {
          return;
        }
        busy = true;
        setBusy(toggle, true, '<span class="spinner-border spinner-border-sm me-1" role="status"></span> Exportando…');
        var payload = { filter: filter, format: item.getAttribute('data-export-format') };
        window.HospiSoft.appCall(options.method, payload, { timeoutMs: EXPORT_TIMEOUT_MS })
          .then(function (response) {
            done();
            if (!response.ok) {
              window.HospiSoft.showToast('danger', errorText(response));
              return;
            }
            if (response.data && response.data.saved) {
              window.HospiSoft.showToast('success', 'Archivo guardado: ' + response.data.fileName
                + ' (' + response.data.rows + ' registro(s))');
            }
          })
          .catch(function (error) {
            done();
            window.HospiSoft.showToast('danger', error.message);
          });
      });
    });
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
    deleteMessage: deleteMessage,
    bindExport: bindExport
  };
})();
