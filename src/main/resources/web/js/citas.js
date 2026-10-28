/*
 * Citas page: list with filters and pagination, create/reschedule modal, cancel modal and the
 * medico agenda. Every rule (overlaps, past dates, states, scope) lives in Java; this is view glue.
 * Times are local wall time: Java converts with the application zone.
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var COLUMNS = 8;
  var ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
  var TIME = /^\d{2}:\d{2}$/;
  var ESTADO_BADGE = {
    PROGRAMADA: '<span class="badge-stock pendiente">Programada</span>',
    EN_CURSO: '<span class="badge-stock confirmada">En curso</span>',
    FINALIZADA: '<span class="badge-stock completada">Finalizada</span>',
    CANCELADA: '<span class="badge-stock cancelada">Cancelada</span>'
  };

  var FIELDS = {
    idPaciente: 'cita-paciente',
    idMedico: 'cita-medico',
    fecha: 'cita-fecha',
    hora: 'cita-hora',
    motivo: 'cita-motivo',
    estado: 'cita-fecha'
  };

  var body = document.getElementById('citas-body');
  var summary = document.getElementById('page-summary');
  var pagination = document.getElementById('pagination');
  var searchInput = document.getElementById('filter-search');
  var fechaFilter = document.getElementById('filter-fecha');
  var estadoFilter = document.getElementById('filter-estado');
  var medicoFilter = document.getElementById('filter-medico');
  var rangoFilter = document.getElementById('filter-rango');
  var form = document.getElementById('form-cita');
  var modalAlert = document.getElementById('modal-alert');
  var title = document.getElementById('modalCitaTitle');
  var saveButton = document.getElementById('btn-save');
  var confirmDelete = document.getElementById('btn-confirm-delete');
  var pacienteSelect = document.getElementById('cita-paciente');
  var medicoSelect = document.getElementById('cita-medico');
  var fechaInput = document.getElementById('cita-fecha');
  var horaInput = document.getElementById('cita-hora');
  var agendaCard = document.getElementById('agenda-card');
  var agendaMedico = document.getElementById('agenda-medico');
  var agendaFecha = document.getElementById('agenda-fecha');
  var agendaBody = document.getElementById('agenda-body');

  var currentPage = 1;
  var pendingCancelId = null;
  /* Rows of the current page by id, so the closing form can show the cita header. */
  var loaded = {};
  var rol = null;
  var medicos = [];
  var pacientes = [];

  /* ---------------------------------------------------------------- formatting */

  function formatDate(iso) {
    if (!iso || !ISO_DATE.test(iso)) {
      return crud.escapeHtml(iso);
    }
    return iso.substring(8, 10) + '/' + iso.substring(5, 7) + '/' + iso.substring(0, 4);
  }

  function todayIso() {
    var d = new Date();
    var m = String(d.getMonth() + 1);
    var day = String(d.getDate());
    return d.getFullYear() + '-' + (m.length < 2 ? '0' + m : m) + '-' + (day.length < 2 ? '0' + day : day);
  }

  function estadoBadge(estado) {
    return ESTADO_BADGE[estado] || crud.escapeHtml(estado);
  }

  /* ---------------------------------------------------------------- list */

  function currentFilter() {
    return {
      texto: searchInput.value.trim() || null,
      fecha: fechaFilter.value.trim() || null,
      estado: estadoFilter.value || null,
      idMedico: medicoFilter.value ? Number(medicoFilter.value) : null,
      rango: rangoFilter.value || null
    };
  }

  function load(page) {
    currentPage = page;
    var token = crud.nextRequest('list');
    body.innerHTML = crud.emptyRow(COLUMNS, 'Cargando...');
    window.HospiSoft.appCall('listCitas', { filter: currentFilter(), page: page, pageSize: crud.PAGE_SIZE })
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
    loaded = {};
    page.items.forEach(function (c) {
      loaded[String(c.id)] = c;
    });
    if (page.items.length === 0) {
      body.innerHTML = crud.emptyRow(COLUMNS, 'Sin resultados para los filtros seleccionados');
    } else {
      body.innerHTML = page.items.map(row).join('');
    }
    crud.renderPagination(summary, pagination, page, 'citas', load);
  }

  function actions(c) {
    var id = crud.escapeHtml(Number(c.id));
    var html = '';
    if (rol === 'ADMIN' && c.estado === 'PROGRAMADA') {
      html += '<button class="btn-icon edit" data-action="edit" data-id="' + id + '" data-bs-toggle="modal" data-bs-target="#modalCita" title="Reprogramar"><i class="fas fa-pen"></i></button> '
        + '<button class="btn-icon delete" data-action="cancel" data-id="' + id + '" data-bs-toggle="modal" data-bs-target="#modalEliminar" title="Cancelar cita"><i class="fas fa-ban"></i></button> ';
    }
    if (rol === 'MEDICO' && c.estado === 'PROGRAMADA') {
      html += '<button class="btn-icon edit" data-action="iniciar" data-id="' + id + '" title="Iniciar atención"><i class="fas fa-play"></i></button> ';
    }
    if (rol === 'MEDICO' && c.estado === 'EN_CURSO') {
      html += '<button class="btn-icon edit" data-action="finalizar" data-id="' + id + '" title="Finalizar atención"><i class="fas fa-stop"></i></button> ';
    }
    if (c.estado === 'FINALIZADA') {
      html += '<button class="btn-icon edit" data-action="historia" data-id="' + id + '" title="Ver historia"><i class="fas fa-file-medical"></i></button>';
    }
    return html;
  }

  /* Starts the attention of one of the medico's own citas. */
  function iniciar(id) {
    window.HospiSoft.appCall('iniciarAtencion', { idCita: id })
      .then(function (response) {
        if (response.ok) {
          window.HospiSoft.showToast('success', 'Atención iniciada');
          load(currentPage);
          refreshAgendaIfVisible();
        } else if (response.code === 'VALIDATION' && response.errors && response.errors.estado) {
          window.HospiSoft.showToast('danger', response.errors.estado);
        } else {
          window.HospiSoft.showToast('danger', response.message);
        }
      })
      .catch(function (error) {
        window.HospiSoft.showToast('danger', error.message);
      });
  }

  function row(c) {
    var e = crud.escapeHtml;
    return '<tr>'
      + '<td>' + e(c.id) + '</td>'
      + '<td><strong>' + e(c.pacienteNombre) + '</strong><div class="small text-muted">' + e(c.pacienteDocumento) + '</div></td>'
      + '<td>' + e(c.medicoNombre) + '</td>'
      + '<td>' + e(c.especialidad) + '</td>'
      + '<td>' + formatDate(c.fecha) + '</td>'
      + '<td>' + e(c.hora) + '</td>'
      + '<td>' + estadoBadge(c.estado) + '</td>'
      + '<td class="text-center">' + actions(c) + '</td>'
      + '</tr>';
  }

  /* ---------------------------------------------------------------- selects */

  function fillSelect(select, firstLabel, items, labelOf) {
    var selected = select.value;
    select.innerHTML = '<option value="">' + crud.escapeHtml(firstLabel) + '</option>';
    items.forEach(function (item) {
      var option = document.createElement('option');
      option.value = item.id;
      option.textContent = labelOf(item);
      select.appendChild(option);
    });
    select.value = selected;
  }

  function medicoLabel(m) {
    return m.apellidos + ' ' + m.nombres + ' — ' + m.especialidad;
  }

  function pacienteLabel(p) {
    return p.documento + ' — ' + p.apellidos + ' ' + p.nombres;
  }

  var selectsLoaded = false;

  function loadSelects() {
    return Promise.all([
      window.HospiSoft.appCall('listMedicosSelect'),
      window.HospiSoft.appCall('listPacientesSelect')
    ]).then(function (responses) {
      if (responses[0].ok) {
        medicos = responses[0].data;
        fillSelect(medicoFilter, 'Todos los médicos', medicos, medicoLabel);
        fillSelect(agendaMedico, 'Seleccione médico...', medicos, medicoLabel);
        fillSelect(medicoSelect, 'Seleccione médico...', medicos, medicoLabel);
      }
      if (responses[1].ok) {
        pacientes = responses[1].data;
        fillSelect(pacienteSelect, 'Seleccione paciente...', pacientes, pacienteLabel);
      }
      selectsLoaded = responses[0].ok && responses[1].ok;
    }).catch(function () {
      selectsLoaded = false;
    });
  }

  /* Without the lists the form cannot be filled correctly: block Guardar and say so. */
  function requireSelects() {
    if (selectsLoaded) {
      return true;
    }
    crud.showModalAlert(modalAlert, 'No se pudieron cargar los listados');
    saveButton.disabled = true;
    return false;
  }

  /* ---------------------------------------------------------------- modal form */

  /* When the stored medico/paciente is no longer active it is missing from the list: show it anyway (disabled). */
  function selectOrPlaceholder(select, id, label) {
    select.value = id === null || id === undefined ? '' : String(id);
    if (id !== null && id !== undefined && select.value !== String(id)) {
      var option = document.createElement('option');
      option.value = String(id);
      option.textContent = label + ' (inactivo)';
      option.disabled = true;
      option.selected = true;
      select.appendChild(option);
    }
  }

  function fillForm(c) {
    document.getElementById('cita-id').value = c ? c.id : '';
    fillSelect(medicoSelect, 'Seleccione médico...', medicos, medicoLabel);
    fillSelect(pacienteSelect, 'Seleccione paciente...', pacientes, pacienteLabel);
    selectOrPlaceholder(medicoSelect, c ? c.idMedico : null, c ? c.medicoNombre : '');
    selectOrPlaceholder(pacienteSelect, c ? c.idPaciente : null, c ? c.pacienteNombre + ' (' + c.pacienteDocumento + ')' : '');
    fechaInput.value = c ? c.fecha : '';
    horaInput.value = c ? c.hora : '';
    document.getElementById('cita-motivo').value = c ? c.motivo : '';
    title.textContent = c ? 'Reprogramar Cita' : 'Nueva Cita';
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
  }

  function readForm() {
    var id = document.getElementById('cita-id').value;
    return {
      id: id ? parseInt(id, 10) : 0,
      idMedico: medicoSelect.value ? Number(medicoSelect.value) : null,
      idPaciente: pacienteSelect.value ? Number(pacienteSelect.value) : null,
      fecha: fechaInput.value.trim(),
      hora: horaInput.value.trim(),
      motivo: document.getElementById('cita-motivo').value
    };
  }

  function abandonEdit(message) {
    crud.modal('modalCita').hide();
    saveButton.disabled = false;
    window.HospiSoft.showToast('danger', message);
  }

  function openEdit(id) {
    fillForm(null);
    title.textContent = 'Reprogramar Cita';
    saveButton.disabled = true;
    window.HospiSoft.appCall('getCita', { id: id }).then(function (response) {
      if (!response.ok) {
        abandonEdit(response.message);
        return;
      }
      fillForm(response.data);
      saveButton.disabled = false;
      requireSelects();
    }).catch(function (error) {
      abandonEdit(error.message);
    });
  }

  function save() {
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
    var payload = readForm();
    var localErrors = {};
    if (payload.fecha && !ISO_DATE.test(payload.fecha)) {
      localErrors.fecha = 'Use el formato AAAA-MM-DD';
    }
    if (payload.hora && !TIME.test(payload.hora)) {
      localErrors.hora = 'Use el formato HH:MM';
    }
    if (Object.keys(localErrors).length > 0) {
      crud.showValidation(form, localErrors, FIELDS);
      return;
    }
    crud.setBusy(saveButton, true, 'Guardando…');
    window.HospiSoft.appCall('saveCita', payload)
      .then(function (response) {
        crud.setBusy(saveButton, false);
        if (response.ok) {
          crud.modal('modalCita').hide();
          window.HospiSoft.showToast('success', payload.id ? 'Cita reprogramada' : 'Cita creada');
          load(payload.id ? currentPage : 1);
          refreshAgendaIfVisible();
          return;
        }
        if (response.code === 'VALIDATION') {
          crud.showValidation(form, response.errors, FIELDS);
          if (response.errors.estado) {
            crud.showModalAlert(modalAlert, response.errors.estado);
          }
          return;
        }
        crud.showModalAlert(modalAlert, response.message);
      })
      .catch(function (error) {
        crud.setBusy(saveButton, false);
        crud.showModalAlert(modalAlert, error.message);
      });
  }

  function cancelCita() {
    if (pendingCancelId === null) {
      return;
    }
    var id = pendingCancelId;
    pendingCancelId = null;
    crud.modal('modalEliminar').hide();
    window.HospiSoft.appCall('cancelarCita', { id: id })
      .then(function (response) {
        if (response.ok) {
          window.HospiSoft.showToast('success', 'Cita cancelada');
          load(currentPage);
          refreshAgendaIfVisible();
        } else if (response.code === 'VALIDATION' && response.errors && response.errors.estado) {
          window.HospiSoft.showToast('danger', response.errors.estado);
        } else {
          window.HospiSoft.showToast('danger', response.message);
        }
      })
      .catch(function (error) {
        window.HospiSoft.showToast('danger', error.message);
      });
  }

  /* ---------------------------------------------------------------- agenda */

  function agendaRow(slot) {
    var e = crud.escapeHtml;
    if (!slot.citas || slot.citas.length === 0) {
      return '<tr><td>' + e(slot.hora) + '</td><td class="text-muted">Libre</td><td></td><td></td></tr>';
    }
    /* one line per cita: same-start or off-grid citas sharing the slot are never hidden */
    function lines(render) {
      return slot.citas.map(render).join('<br>');
    }
    return '<tr><td>' + e(slot.hora) + '</td>'
      + '<td>' + lines(function (c) { return '<strong>' + e(c.pacienteNombre) + '</strong> <span class="small text-muted">' + e(c.pacienteDocumento) + ' 00b7 ' + e(c.hora) + '</span>'; }) + '</td>'
      + '<td>' + lines(function (c) { return e(c.motivo); }) + '</td>'
      + '<td>' + lines(function (c) { return estadoBadge(c.estado); }) + '</td></tr>';
  }

  function loadAgenda() {
    var payload = { fecha: agendaFecha.value.trim() || null };
    if (rol === 'ADMIN') {
      if (!agendaMedico.value) {
        agendaBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">Seleccione un médico</td></tr>';
        return;
      }
      payload.idMedico = Number(agendaMedico.value);
    }
    if (payload.fecha && !ISO_DATE.test(payload.fecha)) {
      agendaBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">Use el formato AAAA-MM-DD</td></tr>';
      return;
    }
    agendaBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">Cargando...</td></tr>';
    window.HospiSoft.appCall('agendaMedico', payload).then(function (response) {
      if (!response.ok) {
        agendaBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">' + crud.escapeHtml(response.message) + '</td></tr>';
        return;
      }
      agendaBody.innerHTML = response.data.slots.map(agendaRow).join('');
    }).catch(function (error) {
      agendaBody.innerHTML = '<tr><td colspan="4" class="text-center text-muted py-4">' + crud.escapeHtml(error.message) + '</td></tr>';
    });
  }

  function refreshAgendaIfVisible() {
    if (rol === 'MEDICO' || (rol === 'ADMIN' && agendaMedico.value)) {
      loadAgenda();
    }
  }

  /* ---------------------------------------------------------------- role chrome (cosmetic) */

  function applyRole() {
    var session = window.HospiSoft.call('getSession');
    rol = session.ok && session.data ? session.data.rol : null;
    rangoFilter.value = rol === 'PACIENTE' ? 'TODAS' : 'HOY_Y_FUTURAS';
    if (rol !== 'ADMIN') {
      document.getElementById('btn-add').style.display = 'none';
      medicoFilter.style.display = 'none';
      agendaMedico.style.display = 'none';
    }
    if (rol === 'PACIENTE') {
      agendaCard.style.display = 'none';
    }
  }

  /* JavaFX WebKit may render date/time inputs as plain text boxes: keep the placeholders usable. */
  function prepareInputs() {
    console.log('date input native picker: ' + (fechaInput.type === 'date') + ', time input native picker: ' + (horaInput.type === 'time'));
    agendaFecha.value = todayIso();
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
    document.getElementById('btn-add').addEventListener('click', function () {
      fillForm(null);
      saveButton.disabled = false;
      requireSelects();
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
      } else if (action === 'cancel') {
        pendingCancelId = id;
      } else if (action === 'iniciar') {
        iniciar(id);
      } else if (action === 'finalizar') {
        window.HospiSoftAtencion.openFinalizar(loaded[String(id)], function () {
          load(currentPage);
          refreshAgendaIfVisible();
        });
      } else if (action === 'historia') {
        window.HospiSoftAtencion.openHistoriaDeCita(id);
      }
    });
    saveButton.addEventListener('click', save);
    form.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && event.target.tagName !== 'TEXTAREA') {
        event.preventDefault();
        if (!saveButton.disabled) {
          saveButton.click();
        }
      }
    });
    confirmDelete.addEventListener('click', cancelCita);
    document.getElementById('modalEliminar').addEventListener('hidden.bs.modal', function () {
      pendingCancelId = null;
    });
    document.getElementById('btn-agenda').addEventListener('click', loadAgenda);
  }

  bind();
  prepareInputs();
  window.HospiSoft.onReady(function () {
    applyRole();
    if (rol === 'ADMIN') {
      loadSelects();
    }
    load(1);
    if (rol === 'MEDICO') {
      loadAgenda();
    }
  });
})();
