/*
 * Medicamentos page: list with filters and pagination, create/edit modal, delete modal.
 * MEDICO and PACIENTE get a read-only view (cosmetic; the Java service enforces it).
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var COLUMNS = 8;
  var STATE_BADGE = {
    DISPONIBLE: '<span class="badge-stock disponible">Disponible</span>',
    STOCK_BAJO: '<span class="badge-stock pendiente">Stock Bajo</span>',
    AGOTADO: '<span class="badge-stock agotado">Agotado</span>'
  };

  var FIELDS = {
    codigo: 'medicamento-codigo',
    nombre: 'medicamento-nombre',
    presentacion: 'medicamento-presentacion',
    stock: 'medicamento-stock',
    stockMinimo: 'medicamento-stock-minimo',
    descripcion: 'medicamento-descripcion'
  };

  var body = document.getElementById('medicamentos-body');
  var summary = document.getElementById('page-summary');
  var pagination = document.getElementById('pagination');
  var searchInput = document.getElementById('filter-search');
  var activoFilter = document.getElementById('filter-activo');
  var estadoFilter = document.getElementById('filter-estado');
  var form = document.getElementById('form-medicamento');
  var modalAlert = document.getElementById('modal-alert');
  var title = document.getElementById('modalMedicamentoTitle');
  var saveButton = document.getElementById('btn-save');
  var confirmDelete = document.getElementById('btn-confirm-delete');
  var activoGroup = document.getElementById('medicamento-activo-group');

  var currentPage = 1;
  var pendingDeleteId = null;
  var readOnly = false;
  var rol = null;
  /* Stock value loaded into the edit form; sent back as stockExpected (optimistic concurrency). */
  var stockExpected = null;

  function currentFilter() {
    return {
      texto: searchInput.value.trim() || null,
      activo: activoFilter.value === '' ? null : activoFilter.value === 'true',
      stockState: estadoFilter.value || null
    };
  }

  function load(page) {
    currentPage = page;
    var token = crud.nextRequest('list');
    body.innerHTML = crud.emptyRow(COLUMNS, 'Cargando...');
    window.HospiSoft.appCall('listMedicamentos', { filter: currentFilter(), page: page, pageSize: crud.PAGE_SIZE })
      .then(function (response) {
        if (!crud.isCurrent('list', token)) {
          return; // a newer request already answered
        }
        if (!response.ok) {
          body.innerHTML = crud.emptyRow(COLUMNS, response.message);
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
      body.innerHTML = crud.emptyRow(COLUMNS, 'No hay medicamentos para mostrar');
    } else {
      body.innerHTML = page.items.map(row).join('');
    }
    crud.renderPagination(summary, pagination, page, 'medicamentos', load);
  }

  function stockCell(m) {
    var e = crud.escapeHtml;
    if (m.stock <= m.stockMinimo) {
      return '<td class="text-danger fw-semibold">' + e(m.stock)
        + ' <i class="fas fa-exclamation-triangle ms-1" title="Stock en o por debajo del mínimo"></i></td>';
    }
    return '<td>' + e(m.stock) + '</td>';
  }

  function estadoCell(m) {
    var html = STATE_BADGE[m.stockState] || crud.escapeHtml(m.stockState);
    if (!m.activo) {
      html += ' <span class="badge-stock inactivo">Inactivo</span>';
    }
    return '<td>' + html + '</td>';
  }

  function row(m) {
    var e = crud.escapeHtml;
    return '<tr>'
      + '<td>' + e(m.id) + '</td>'
      + '<td>' + e(m.codigo) + '</td>'
      + '<td><strong>' + e(m.nombre) + '</strong></td>'
      + '<td>' + e(m.presentacion) + '</td>'
      + stockCell(m)
      + '<td>' + e(m.stockMinimo) + '</td>'
      + estadoCell(m)
      + '<td class="text-center">' + (readOnly ? '' : crud.actionButtons(m.id, 'modalMedicamento')) + '</td>'
      + '</tr>';
  }

  /* ---------------------------------------------------------------- modal form */

  function fillForm(m) {
    document.getElementById('medicamento-id').value = m ? m.id : '';
    document.getElementById('medicamento-codigo').value = m ? m.codigo : '';
    document.getElementById('medicamento-nombre').value = m ? m.nombre : '';
    document.getElementById('medicamento-presentacion').value = m ? m.presentacion : '';
    document.getElementById('medicamento-stock').value = m ? m.stock : '';
    document.getElementById('medicamento-stock-minimo').value = m ? m.stockMinimo : '';
    document.getElementById('medicamento-descripcion').value = m && m.descripcion ? m.descripcion : '';
    document.getElementById('medicamento-activo').value = m ? String(m.activo) : 'true';
    stockExpected = m ? m.stock : null;
    title.textContent = m ? 'Editar Medicamento' : 'Nuevo Medicamento';
    activoGroup.classList.toggle('d-none', !m);
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
  }

  /* Numbers are sent as typed: Java reports non-integral or negative values as field errors. */
  function numberOrRaw(value) {
    var text = value.trim();
    if (text === '') {
      return null;
    }
    var n = Number(text);
    return isNaN(n) ? text : n;
  }

  function readForm() {
    var id = document.getElementById('medicamento-id').value;
    var payload = {
      id: id ? parseInt(id, 10) : 0,
      codigo: document.getElementById('medicamento-codigo').value,
      nombre: document.getElementById('medicamento-nombre').value,
      presentacion: document.getElementById('medicamento-presentacion').value,
      stock: numberOrRaw(document.getElementById('medicamento-stock').value),
      stockMinimo: numberOrRaw(document.getElementById('medicamento-stock-minimo').value),
      descripcion: document.getElementById('medicamento-descripcion').value
    };
    if (id) {
      payload.activo = document.getElementById('medicamento-activo').value === 'true';
      payload.stockExpected = stockExpected;
    }
    return payload;
  }

  function abandonEdit(message) {
    crud.modal('modalMedicamento').hide();
    saveButton.disabled = false;
    window.HospiSoft.showToast('danger', message);
  }

  function openEdit(id) {
    fillForm(null);
    title.textContent = 'Editar Medicamento';
    saveButton.disabled = true; // never allow saving an "edit" form that has no id yet
    window.HospiSoft.appCall('getMedicamento', { id: id }).then(function (response) {
      if (!response.ok) {
        abandonEdit(response.message);
        return;
      }
      fillForm(response.data);
      saveButton.disabled = false;
    }).catch(function (error) {
      abandonEdit(error.message);
    });
  }

  function save() {
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
    var payload = readForm();
    crud.setBusy(saveButton, true, 'Guardando…');
    window.HospiSoft.appCall('saveMedicamento', payload)
      .then(function (response) {
        crud.setBusy(saveButton, false);
        if (response.ok) {
          crud.modal('modalMedicamento').hide();
          window.HospiSoft.showToast('success', payload.id ? 'Medicamento actualizado' : 'Medicamento creado');
          load(payload.id ? currentPage : 1);
          return;
        }
        if (response.code === 'VALIDATION') {
          crud.showValidation(form, response.errors, FIELDS);
          return;
        }
        if (response.code === 'CONFLICT') {
          reloadAfterConflict(payload.id, response.message);
          return;
        }
        crud.showModalAlert(modalAlert, response.message);
      })
      .catch(function (error) {
        crud.setBusy(saveButton, false);
        crud.showModalAlert(modalAlert, error.message);
      });
  }

  /* The row changed since the form was opened: refill it from the server (new stockExpected) and let the user retry. */
  function reloadAfterConflict(id, message) {
    saveButton.disabled = true;
    window.HospiSoft.appCall('getMedicamento', { id: id }).then(function (response) {
      if (!response.ok) {
        abandonEdit(response.message);
        return;
      }
      fillForm(response.data);
      crud.showModalAlert(modalAlert, message);
      saveButton.disabled = false;
    }).catch(function (error) {
      abandonEdit(error.message);
    });
  }

  function remove() {
    if (pendingDeleteId === null) {
      return;
    }
    var id = pendingDeleteId;
    pendingDeleteId = null;
    crud.modal('modalEliminar').hide();
    window.HospiSoft.appCall('deleteMedicamento', { id: id })
      .then(function (response) {
        if (response.ok) {
          window.HospiSoft.showToast('success', crud.deleteMessage(response.data.outcome));
          load(currentPage);
        } else {
          window.HospiSoft.showToast('danger', response.message);
        }
      })
      .catch(function (error) {
        window.HospiSoft.showToast('danger', error.message);
      });
  }

  function applyRole() {
    var session = window.HospiSoft.call('getSession');
    rol = session.ok && session.data ? session.data.rol : null;
    readOnly = rol === 'MEDICO' || rol === 'PACIENTE';
    if (readOnly) {
      document.getElementById('btn-add').style.display = 'none';
    }
    if (rol === 'PACIENTE') {
      /* patients see their own orders instead of the stock table */
      document.getElementById('page-title').textContent = 'Mis órdenes de medicamentos';
      document.getElementById('filters-bar').classList.add('d-none');
      document.getElementById('stock-card').classList.add('d-none');
      document.getElementById('ordenes-card').classList.remove('d-none');
    }
  }

  /* ---------------------------------------------------------------- patient orders */

  function loadOrdenes(page) {
    var body = document.getElementById('ordenes-body');
    var summary = document.getElementById('ordenes-summary');
    var pagination = document.getElementById('ordenes-pagination');
    var token = crud.nextRequest('ordenes');
    body.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">Cargando...</td></tr>';
    window.HospiSoft.appCall('misRecetas', { page: page, pageSize: crud.PAGE_SIZE })
      .then(function (response) {
        if (!crud.isCurrent('ordenes', token)) {
          return;
        }
        if (!response.ok) {
          body.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">'
            + crud.escapeHtml(response.message) + '</td></tr>';
          crud.clearPagination(summary, pagination);
          return;
        }
        var data = response.data;
        if (data.items.length === 0) {
          body.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">No tiene órdenes de medicamentos</td></tr>';
        } else {
          body.innerHTML = data.items.map(function (r) {
            var e = crud.escapeHtml;
            var observacion = r.entregado || !r.observacionNoDisponible ? ''
              : '<div class="small text-muted">' + e(r.observacionNoDisponible) + '</div>';
            return '<tr>'
              + '<td>' + e(window.HospiSoftAtencion.formatDate(r.fecha)) + '</td>'
              + '<td><strong>' + e(r.nombre) + '</strong><div class="small text-muted">' + e(r.codigo)
              + ' · ' + e(r.presentacion) + '</div></td>'
              + '<td>' + e(r.cantidad) + '</td>'
              + '<td>' + e(r.dosis) + '</td>'
              + '<td>' + e(r.duracion) + '</td>'
              + '<td>' + window.HospiSoftAtencion.entregadoBadge(r) + observacion + '</td>'
              + '</tr>';
          }).join('');
        }
        crud.renderPagination(summary, pagination, data, 'órdenes', loadOrdenes);
      })
      .catch(function (error) {
        if (!crud.isCurrent('ordenes', token)) {
          return;
        }
        body.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-4">'
          + crud.escapeHtml(error.message) + '</td></tr>';
      });
  }

  /* ---------------------------------------------------------------- events */

  function bind() {
    document.getElementById('btn-filter').addEventListener('click', function () { load(1); });
    searchInput.addEventListener('keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        load(1);
      }
    });
    document.getElementById('btn-add').addEventListener('click', function () { fillForm(null); });
    body.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-action]');
      if (!button) {
        return;
      }
      var id = parseInt(button.getAttribute('data-id'), 10);
      if (button.getAttribute('data-action') === 'edit') {
        openEdit(id);
      } else {
        pendingDeleteId = id;
      }
    });
    saveButton.addEventListener('click', save);
    /* Enter inside the form saves only while Guardar is enabled (the "disabled until loaded" guard holds). */
    form.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && event.target.tagName !== 'TEXTAREA') {
        event.preventDefault();
        if (!saveButton.disabled) {
          saveButton.click();
        }
      }
    });
    confirmDelete.addEventListener('click', remove);
    document.getElementById('modalEliminar').addEventListener('hidden.bs.modal', function () {
      pendingDeleteId = null;
    });
  }

  bind();
  window.HospiSoft.onReady(function () {
    applyRole();
    if (rol === 'PACIENTE') {
      loadOrdenes(1);
    } else {
      load(1);
    }
  });
})();
