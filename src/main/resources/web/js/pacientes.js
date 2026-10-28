/*
 * Pacientes page: list with filters and pagination, create/edit modal, delete modal.
 * MEDICO sessions get a read-only view (cosmetic; the Java service enforces it).
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var COLUMNS = 9;
  var ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
  var SEXO_LABEL = { M: 'Masculino', F: 'Femenino', OTRO: 'Otro' };

  var FIELDS = {
    documento: 'paciente-documento',
    nombres: 'paciente-nombres',
    apellidos: 'paciente-apellidos',
    fechaNacimiento: 'paciente-fecha-nacimiento',
    sexo: 'paciente-sexo',
    telefono: 'paciente-telefono',
    correo: 'paciente-correo',
    direccion: 'paciente-direccion'
  };

  var body = document.getElementById('pacientes-body');
  var summary = document.getElementById('page-summary');
  var pagination = document.getElementById('pagination');
  var searchInput = document.getElementById('filter-search');
  var sexoFilter = document.getElementById('filter-sexo');
  var estadoFilter = document.getElementById('filter-estado');
  var form = document.getElementById('form-paciente');
  var modalAlert = document.getElementById('modal-alert');
  var title = document.getElementById('modalPacienteTitle');
  var saveButton = document.getElementById('btn-save');
  var confirmDelete = document.getElementById('btn-confirm-delete');
  var birthInput = document.getElementById('paciente-fecha-nacimiento');
  var estadoGroup = document.getElementById('paciente-estado-group');

  var currentPage = 1;
  var pendingDeleteId = null;
  var readOnly = false;
  /* Rows of the current page by id, for the historial header. */
  var loadedPacientes = {};

  function currentFilter() {
    return {
      texto: searchInput.value.trim() || null,
      sexo: sexoFilter.value || null,
      activo: estadoFilter.value === '' ? null : estadoFilter.value === 'true'
    };
  }

  function load(page) {
    currentPage = page;
    var token = crud.nextRequest('list');
    body.innerHTML = crud.emptyRow(COLUMNS, 'Cargando...');
    window.HospiSoft.appCall('listPacientes', { filter: currentFilter(), page: page, pageSize: crud.PAGE_SIZE })
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
    loadedPacientes = {};
    page.items.forEach(function (p) {
      loadedPacientes[String(p.id)] = p;
    });
    if (page.items.length === 0) {
      body.innerHTML = crud.emptyRow(COLUMNS, 'No hay pacientes para mostrar');
    } else {
      body.innerHTML = page.items.map(row).join('');
    }
    crud.renderPagination(summary, pagination, page, 'pacientes', load);
  }

  function row(p) {
    var e = crud.escapeHtml;
    return '<tr>'
      + '<td>' + e(p.id) + '</td>'
      + '<td>' + e(p.documento) + '</td>'
      + '<td><strong>' + e(p.nombreCompleto) + '</strong></td>'
      + '<td>' + e(p.edad === null || p.edad === undefined ? '–' : p.edad) + '</td>'
      + '<td>' + e(SEXO_LABEL[p.sexo] || p.sexo) + '</td>'
      + '<td>' + e(p.telefono) + '</td>'
      + '<td>' + e(p.correo) + '</td>'
      + '<td>' + crud.badge(p.activo) + '</td>'
      + '<td class="text-center">' + (readOnly ? '' : crud.actionButtons(p.id, 'modalPaciente'))
      + ' <button class="btn-icon edit" data-action="historial" data-id="' + crud.escapeHtml(Number(p.id))
      + '" title="Ver historial"><i class="fas fa-notes-medical"></i></button></td>'
      + '</tr>';
  }

  /* ---------------------------------------------------------------- modal form */

  function fillForm(p) {
    document.getElementById('paciente-id').value = p ? p.id : '';
    document.getElementById('paciente-documento').value = p ? p.documento : '';
    document.getElementById('paciente-nombres').value = p ? p.nombres : '';
    document.getElementById('paciente-apellidos').value = p ? p.apellidos : '';
    birthInput.value = p && p.fechaNacimiento ? p.fechaNacimiento : '';
    document.getElementById('paciente-sexo').value = p ? p.sexo : '';
    document.getElementById('paciente-telefono').value = p && p.telefono ? p.telefono : '';
    document.getElementById('paciente-correo').value = p && p.correo ? p.correo : '';
    document.getElementById('paciente-direccion').value = p && p.direccion ? p.direccion : '';
    document.getElementById('paciente-estado').value = p ? String(p.activo) : 'true';
    title.textContent = p ? 'Editar Paciente' : 'Nuevo Paciente';
    estadoGroup.classList.toggle('d-none', !p);
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
  }

  function readForm() {
    var id = document.getElementById('paciente-id').value;
    return {
      id: id ? parseInt(id, 10) : 0,
      documento: document.getElementById('paciente-documento').value,
      nombres: document.getElementById('paciente-nombres').value,
      apellidos: document.getElementById('paciente-apellidos').value,
      fechaNacimiento: birthInput.value.trim(),
      sexo: document.getElementById('paciente-sexo').value,
      telefono: document.getElementById('paciente-telefono').value,
      correo: document.getElementById('paciente-correo').value,
      direccion: document.getElementById('paciente-direccion').value,
      activo: id ? document.getElementById('paciente-estado').value === 'true' : true
    };
  }

  function abandonEdit(message) {
    crud.modal('modalPaciente').hide();
    saveButton.disabled = false;
    window.HospiSoft.showToast('danger', message);
  }

  function openEdit(id) {
    fillForm(null);
    title.textContent = 'Editar Paciente';
    saveButton.disabled = true; // never allow saving an "edit" form that has no id yet
    window.HospiSoft.appCall('getPaciente', { id: id }).then(function (response) {
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
    if (payload.fechaNacimiento && !ISO_DATE.test(payload.fechaNacimiento)) {
      crud.showValidation(form, { fechaNacimiento: 'Use el formato AAAA-MM-DD' }, FIELDS);
      return;
    }
    crud.setBusy(saveButton, true, 'Guardando…');
    window.HospiSoft.appCall('savePaciente', payload)
      .then(function (response) {
        crud.setBusy(saveButton, false);
        if (response.ok) {
          crud.modal('modalPaciente').hide();
          window.HospiSoft.showToast('success', payload.id ? 'Paciente actualizado' : 'Paciente creado');
          load(payload.id ? currentPage : 1);
          return;
        }
        if (response.code === 'VALIDATION') {
          crud.showValidation(form, response.errors, FIELDS);
          return;
        }
        crud.showModalAlert(modalAlert, response.message);
      })
      .catch(function (error) {
        crud.setBusy(saveButton, false);
        crud.showModalAlert(modalAlert, error.message);
      });
  }

  function remove() {
    if (pendingDeleteId === null) {
      return;
    }
    var id = pendingDeleteId;
    pendingDeleteId = null;
    crud.modal('modalEliminar').hide();
    window.HospiSoft.appCall('deletePaciente', { id: id })
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

  /* ---------------------------------------------------------------- historial (read-only) */

  var historialPaciente = null;
  var historialPage = 1;
  var historialBody = document.getElementById('historial-body');
  var historialSummary = document.getElementById('historial-summary');
  var historialPagination = document.getElementById('historial-pagination');
  var historialAlert = document.getElementById('historial-alert');
  var historialRows = {};

  function openHistorial(id) {
    historialPaciente = id;
    var row = loadedPacientes[String(id)];
    document.getElementById('historial-paciente').textContent =
      row ? row.nombreCompleto + ' (' + row.documento + ')' : '–';
    crud.hideModalAlert(historialAlert);
    crud.modal('modalHistorial').show();
    loadHistorial(1);
  }

  function loadHistorial(page) {
    historialPage = page;
    var token = crud.nextRequest('historial');
    historialBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-3">Cargando...</td></tr>';
    window.HospiSoft.appCall('historialPaciente',
      { idPaciente: historialPaciente, page: page, pageSize: crud.PAGE_SIZE })
      .then(function (response) {
        if (!crud.isCurrent('historial', token)) {
          return;
        }
        if (!response.ok) {
          historialBody.innerHTML = '';
          crud.clearPagination(historialSummary, historialPagination);
          crud.showModalAlert(historialAlert, response.message);
          return;
        }
        renderHistorial(response.data);
      })
      .catch(function (error) {
        if (!crud.isCurrent('historial', token)) {
          return;
        }
        historialBody.innerHTML = '';
        crud.showModalAlert(historialAlert, error.message);
      });
  }

  function renderHistorial(pageData) {
    historialRows = {};
    pageData.items.forEach(function (h) {
      historialRows[String(h.id)] = h;
    });
    if (pageData.items.length === 0) {
      historialBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-3">Sin historias clínicas</td></tr>';
    } else {
      historialBody.innerHTML = pageData.items.map(function (h) {
        var e = crud.escapeHtml;
        return '<tr>'
          + '<td>' + e(window.HospiSoftAtencion.formatDate(h.fecha)) + '</td>'
          + '<td>' + e(h.medicoNombre) + '<div class="small text-muted">' + e(h.especialidad) + '</div></td>'
          + '<td>' + e(h.diagnostico) + '</td>'
          + '<td class="text-center"><button class="btn-icon edit" data-action="ver-historia" data-id="'
          + e(Number(h.id)) + '" title="Ver"><i class="fas fa-file-medical"></i></button></td>'
          + '</tr>';
      }).join('');
    }
    crud.renderPagination(historialSummary, historialPagination, pageData, 'historias', loadHistorial);
  }

  /* JavaFX WebKit may render type="date" as a plain text box: keep the ISO placeholder usable. */
  function prepareDateInput() {
    var native = birthInput.type === 'date';
    console.log('date input native picker: ' + native);
    if (!native) {
      birthInput.setAttribute('placeholder', 'AAAA-MM-DD');
    }
  }

  function applyRole() {
    var session = window.HospiSoft.call('getSession');
    readOnly = !!(session.ok && session.data && session.data.rol === 'MEDICO');
    if (readOnly) {
      document.getElementById('btn-add').style.display = 'none';
    }
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
    historialBody.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-action="ver-historia"]');
      if (!button) {
        return;
      }
      var historia = historialRows[button.getAttribute('data-id')];
      if (historia) {
        /* the list rows carry no recetas: reload the full record by its cita */
        window.HospiSoftAtencion.openHistoriaDeCita(historia.idCita);
      }
    });
    body.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-action]');
      if (!button) {
        return;
      }
      var id = parseInt(button.getAttribute('data-id'), 10);
      var action = button.getAttribute('data-action');
      if (action === 'edit') {
        openEdit(id);
      } else if (action === 'historial') {
        openHistorial(id);
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
  prepareDateInput();
  window.HospiSoft.onReady(function () {
    applyRole();
    load(1);
  });
})();
