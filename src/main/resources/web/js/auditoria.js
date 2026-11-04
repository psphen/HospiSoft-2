/*
 * Auditoria page: read-only list of the audit log with filters and pagination (ADMIN only; the
 * Java guard and the service enforce it). Rows arrive display-ready (labels and local date) from Java.
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var COLUMNS = 7;
  var ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
  var BAD_DATE = 'Use el formato AAAA-MM-DD';

  /* Badge colour per action family (cosmetic only). */
  var ACCION_CLASS = {
    CREAR: 'bg-success',
    EDITAR: 'bg-primary',
    ELIMINAR: 'bg-danger',
    DESACTIVAR: 'bg-warning text-dark',
    REPROGRAMAR: 'bg-info text-dark',
    CANCELAR: 'bg-warning text-dark',
    INICIAR_ATENCION: 'bg-primary',
    FINALIZAR_ATENCION: 'bg-success',
    LOGIN: 'bg-secondary',
    LOGIN_FALLIDO: 'bg-danger',
    LOGOUT: 'bg-secondary',
    EXPORTAR: 'bg-info text-dark'
  };

  var body = document.getElementById('auditoria-body');
  var summary = document.getElementById('page-summary');
  var pagination = document.getElementById('pagination');
  var searchInput = document.getElementById('filter-search');
  var accionFilter = document.getElementById('filter-accion');
  var moduloFilter = document.getElementById('filter-modulo');
  var desdeInput = document.getElementById('filter-desde');
  var hastaInput = document.getElementById('filter-hasta');
  var filterAlert = document.getElementById('filter-alert');

  function currentFilter() {
    return {
      texto: searchInput.value.trim() || null,
      accion: accionFilter.value || null,
      modulo: moduloFilter.value || null,
      desde: desdeInput.value.trim() || null,
      hasta: hastaInput.value.trim() || null
    };
  }

  /* Returns an error text when a date box holds something that is not AAAA-MM-DD. */
  function dateProblem(filter) {
    if ((filter.desde && !ISO_DATE.test(filter.desde)) || (filter.hasta && !ISO_DATE.test(filter.hasta))) {
      return BAD_DATE;
    }
    return null;
  }

  function load(page) {
    crud.hideModalAlert(filterAlert);
    var filter = currentFilter();
    var problem = dateProblem(filter);
    if (problem) {
      crud.showModalAlert(filterAlert, problem);
      return;
    }
    var token = crud.nextRequest('list');
    body.innerHTML = crud.emptyRow(COLUMNS, 'Cargando...');
    window.HospiSoft.appCall('listAuditoria', { filter: filter, page: page, pageSize: crud.PAGE_SIZE })
      .then(function (response) {
        if (!crud.isCurrent('list', token)) {
          return; // a newer request already answered
        }
        if (!response.ok) {
          var message = response.code === 'VALIDATION' && response.errors && response.errors.hasta
            ? response.errors.hasta
            : response.message;
          body.innerHTML = crud.emptyRow(COLUMNS, message);
          crud.clearPagination(summary, pagination);
          return;
        }
        var clamped = crud.clampedPage(response.data);
        if (clamped !== null) {
          load(clamped);
          return;
        }
        render(response.data);
      })
      .catch(function (error) {
        if (!crud.isCurrent('list', token)) {
          return;
        }
        body.innerHTML = crud.emptyRow(COLUMNS, error.message);
        crud.clearPagination(summary, pagination);
      });
  }

  function render(page) {
    if (page.items.length === 0) {
      body.innerHTML = crud.emptyRow(COLUMNS, 'No hay registros de auditoría para mostrar');
    } else {
      body.innerHTML = page.items.map(row).join('');
    }
    crud.renderPagination(summary, pagination, page, 'registros', load);
  }

  function row(a) {
    var e = crud.escapeHtml;
    var badgeClass = ACCION_CLASS[a.accion] || 'bg-secondary';
    var usuario = a.username ? e(a.username) : '<span class="text-muted">–</span>';
    return '<tr>'
      + '<td class="text-nowrap small">' + e(a.fecha) + '</td>'
      + '<td>' + usuario + '</td>'
      + '<td class="small">' + e(a.rol || '–') + '</td>'
      + '<td><span class="badge ' + badgeClass + '">' + e(a.accionLabel) + '</span></td>'
      + '<td>' + e(a.moduloLabel) + '</td>'
      + '<td class="small">' + e(a.idRegistro === null || a.idRegistro === undefined ? '–' : '#' + a.idRegistro) + '</td>'
      + '<td class="small">' + e(a.detalle || '') + '</td>'
      + '</tr>';
  }

  /* JavaFX WebKit may render type="date" as a plain text box: keep the ISO placeholder usable. */
  function prepareDateInputs() {
    [desdeInput, hastaInput].forEach(function (input) {
      if (input.type !== 'date') {
        input.setAttribute('placeholder', 'AAAA-MM-DD');
      }
    });
  }

  function bind() {
    document.getElementById('btn-filter').addEventListener('click', function () { load(1); });
    [searchInput, desdeInput, hastaInput].forEach(function (input) {
      input.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
          event.preventDefault();
          load(1);
        }
      });
    });
    [accionFilter, moduloFilter].forEach(function (select) {
      select.addEventListener('change', function () { load(1); });
    });
  }

  bind();
  prepareDateInputs();
  /* Filter for an export: the same date check as the list, null (no export) when it fails. */
  function exportFilter() {
    crud.hideModalAlert(filterAlert);
    var filter = currentFilter();
    var problem = dateProblem(filter);
    if (problem) {
      crud.showModalAlert(filterAlert, problem);
      return null;
    }
    return filter;
  }

  window.HospiSoft.onReady(function () {
    crud.bindExport({ method: 'exportAuditoria', filter: exportFilter });
    load(1);
  });
})();
