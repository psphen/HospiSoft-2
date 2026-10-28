/*
 * Medicos page: list with filters and pagination, create/edit modal, delete modal.
 * Every data operation goes through HospiSoft.appCall; rules and permissions live in Java.
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var OTHER = '__otra__';
  var COLUMNS = 8;

  var FIELDS = {
    documento: 'medico-documento',
    nombres: 'medico-nombres',
    apellidos: 'medico-apellidos',
    especialidad: 'medico-especialidad',
    telefono: 'medico-telefono',
    correo: 'medico-correo'
  };

  var body = document.getElementById('medicos-body');
  var summary = document.getElementById('page-summary');
  var pagination = document.getElementById('pagination');
  var searchInput = document.getElementById('filter-search');
  var especialidadFilter = document.getElementById('filter-especialidad');
  var estadoFilter = document.getElementById('filter-estado');
  var form = document.getElementById('form-medico');
  var modalAlert = document.getElementById('modal-alert');
  var title = document.getElementById('modalMedicoTitle');
  var saveButton = document.getElementById('btn-save');
  var confirmDelete = document.getElementById('btn-confirm-delete');
  var especialidadSelect = document.getElementById('medico-especialidad');
  var otraGroup = document.getElementById('medico-especialidad-otra-group');
  var otraInput = document.getElementById('medico-especialidad-otra');
  var estadoGroup = document.getElementById('medico-estado-group');

  var currentPage = 1;
  var pendingDeleteId = null;
  var especialidades = [];

  function currentFilter() {
    return {
      texto: searchInput.value.trim() || null,
      especialidad: especialidadFilter.value || null,
      activo: estadoFilter.value === '' ? null : estadoFilter.value === 'true'
    };
  }

  function load(page) {
    currentPage = page;
    var token = crud.nextRequest('list');
    body.innerHTML = crud.emptyRow(COLUMNS, 'Cargando...');
    window.HospiSoft.appCall('listMedicos', { filter: currentFilter(), page: page, pageSize: crud.PAGE_SIZE })
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
      body.innerHTML = crud.emptyRow(COLUMNS, 'No hay médicos para mostrar');
    } else {
      body.innerHTML = page.items.map(row).join('');
    }
    crud.renderPagination(summary, pagination, page, 'médicos', load);
  }

  function row(m) {
    var e = crud.escapeHtml;
    return '<tr>'
      + '<td>' + e(m.id) + '</td>'
      + '<td>' + e(m.documento) + '</td>'
      + '<td><strong>' + e(m.nombreCompleto) + '</strong></td>'
      + '<td>' + e(m.especialidad) + '</td>'
      + '<td>' + e(m.telefono) + '</td>'
      + '<td>' + e(m.correo) + '</td>'
      + '<td>' + crud.badge(m.activo) + '</td>'
      + '<td class="text-center">' + crud.actionButtons(m.id, 'modalMedico') + '</td>'
      + '</tr>';
  }

  function loadEspecialidades() {
    window.HospiSoft.appCall('listEspecialidades').then(function (response) {
      if (!response.ok) {
        return;
      }
      especialidades = response.data;
      var selected = especialidadFilter.value;
      especialidadFilter.innerHTML = '<option value="">Todas las especialidades</option>';
      response.data.forEach(function (name) {
        var option = document.createElement('option');
        option.value = name;
        option.textContent = name;
        especialidadFilter.appendChild(option);
      });
      especialidadFilter.value = selected;
    }).catch(function () { /* the filter simply keeps its default option */ });
  }

  /* ---------------------------------------------------------------- modal form */

  function hasOption(select, value) {
    return Array.prototype.some.call(select.options, function (o) { return o.value === value && o.value !== OTHER; });
  }

  /* Rebuilds the modal select from the known specialties plus the fixed "Otra" option. */
  function buildEspecialidadOptions(current) {
    var names = especialidades.slice();
    if (current && names.indexOf(current) === -1) {
      names.push(current);
    }
    especialidadSelect.innerHTML = '<option value="">Seleccione...</option>';
    names.forEach(function (name) {
      var option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      especialidadSelect.appendChild(option);
    });
    var other = document.createElement('option');
    other.value = OTHER;
    other.textContent = 'Otra';
    especialidadSelect.appendChild(other);
  }

  function setEspecialidad(value) {
    buildEspecialidadOptions(value);
    if (value && hasOption(especialidadSelect, value)) {
      especialidadSelect.value = value;
      otraGroup.classList.add('d-none');
      otraInput.value = '';
    } else if (value) {
      especialidadSelect.value = OTHER;
      otraGroup.classList.remove('d-none');
      otraInput.value = value;
    } else {
      especialidadSelect.value = '';
      otraGroup.classList.add('d-none');
      otraInput.value = '';
    }
  }

  function readEspecialidad() {
    return especialidadSelect.value === OTHER ? otraInput.value.trim() : especialidadSelect.value;
  }

  function fillForm(m) {
    document.getElementById('medico-id').value = m ? m.id : '';
    document.getElementById('medico-documento').value = m ? m.documento : '';
    document.getElementById('medico-nombres').value = m ? m.nombres : '';
    document.getElementById('medico-apellidos').value = m ? m.apellidos : '';
    document.getElementById('medico-telefono').value = m && m.telefono ? m.telefono : '';
    document.getElementById('medico-correo').value = m && m.correo ? m.correo : '';
    document.getElementById('medico-estado').value = m ? String(m.activo) : 'true';
    setEspecialidad(m ? m.especialidad : '');
    title.textContent = m ? 'Editar Médico' : 'Nuevo Médico';
    estadoGroup.classList.toggle('d-none', !m);
    crud.clearValidation(form);
    crud.hideModalAlert(modalAlert);
  }

  function readForm() {
    var id = document.getElementById('medico-id').value;
    return {
      id: id ? parseInt(id, 10) : 0,
      documento: document.getElementById('medico-documento').value,
      nombres: document.getElementById('medico-nombres').value,
      apellidos: document.getElementById('medico-apellidos').value,
      especialidad: readEspecialidad(),
      telefono: document.getElementById('medico-telefono').value,
      correo: document.getElementById('medico-correo').value,
      activo: id ? document.getElementById('medico-estado').value === 'true' : true
    };
  }

  function abandonEdit(message) {
    crud.modal('modalMedico').hide();
    saveButton.disabled = false;
    window.HospiSoft.showToast('danger', message);
  }

  function openEdit(id) {
    fillForm(null);
    title.textContent = 'Editar Médico';
    saveButton.disabled = true; // never allow saving an "edit" form that has no id yet
    window.HospiSoft.appCall('getMedico', { id: id }).then(function (response) {
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
    var fieldMap = Object.assign({}, FIELDS);
    if (especialidadSelect.value === OTHER) {
      fieldMap.especialidad = 'medico-especialidad-otra';
    }
    crud.setBusy(saveButton, true, 'Guardando…');
    window.HospiSoft.appCall('saveMedico', payload)
      .then(function (response) {
        crud.setBusy(saveButton, false);
        if (response.ok) {
          crud.modal('modalMedico').hide();
          window.HospiSoft.showToast('success', payload.id ? 'Médico actualizado' : 'Médico creado');
          loadEspecialidades();
          load(payload.id ? currentPage : 1);
          return;
        }
        if (response.code === 'VALIDATION') {
          crud.showValidation(form, response.errors, fieldMap);
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
    window.HospiSoft.appCall('deleteMedico', { id: id })
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
    especialidadSelect.addEventListener('change', function () {
      var other = especialidadSelect.value === OTHER;
      otraGroup.classList.toggle('d-none', !other);
      if (other) {
        otraInput.focus();
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
    loadEspecialidades();
    load(1);
  });
})();
