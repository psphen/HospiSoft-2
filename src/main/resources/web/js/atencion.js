/*
 * Attention closing form (#modalAtencion) and read-only clinical record (#modalHistoria).
 * Shared by citas.html and pacientes.html; every rule (stock, states, scope) lives in Java.
 * Each page only needs the modals it uses: missing elements simply disable that part.
 */
(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var STOCK_WARNING = 'Stock insuficiente: la orden quedará pendiente de entrega';

  var FIELDS = {
    diagnostico: 'atencion-diagnostico',
    tratamiento: 'atencion-tratamiento',
    notas: 'atencion-notas',
    observaciones: 'atencion-observaciones'
  };

  var form = document.getElementById('form-atencion');
  var alertBox = document.getElementById('atencion-alert');
  var recetasBody = document.getElementById('recetas-body');
  var saveButton = document.getElementById('btn-finalizar');
  var historiaAlert = document.getElementById('historia-alert');
  var historiaBody = document.getElementById('historia-recetas');

  var medicamentos = [];
  var medicamentosLoaded = false;
  var rowSeq = 0;
  var onSaved = null;
  /* Guards against a reply from a modal the user already dismissed and reopened. */
  var openToken = 0;

  function escape(value) {
    return crud.escapeHtml(value === null || value === undefined ? '' : value);
  }

  function formatDate(iso) {
    if (!iso) {
      return '–';
    }
    var date = String(iso).substring(0, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      return escape(iso);
    }
    var time = String(iso).length >= 16 ? ' ' + String(iso).substring(11, 16) : '';
    return date.substring(8, 10) + '/' + date.substring(5, 7) + '/' + date.substring(0, 4) + time;
  }

  function entregadoBadge(receta) {
    return receta.entregado
      ? '<span class="badge-stock disponible">Entregado</span>'
      : '<span class="badge-stock pendiente">Pendiente</span>';
  }

  /* ---------------------------------------------------------------- closing form */

  function medicamentoLabel(m) {
    return m.codigo + ' — ' + m.nombre + ' (' + m.presentacion + ') · stock: ' + m.stock;
  }

  function stockOf(idMedicamento) {
    var found = medicamentos.filter(function (m) { return String(m.id) === String(idMedicamento); })[0];
    return found ? found.stock : null;
  }

  function options() {
    return medicamentos.map(function (m) {
      return '<option value="' + escape(Number(m.id)) + '">' + escape(medicamentoLabel(m)) + '</option>';
    }).join('');
  }

  function addRecetaRow() {
    if (!recetasBody) {
      return;
    }
    var id = 'r' + (++rowSeq);
    var html = '<tr data-row="' + id + '">'
      + '<td><select class="form-select form-select-sm" data-field="idMedicamento">'
      + '<option value="">Seleccione medicamento...</option>' + options() + '</select>'
      + '<div class="invalid-feedback"></div>'
      + '<div class="small text-danger mt-1 d-none" data-role="warning"><i class="fas fa-exclamation-triangle me-1"></i>' + escape(STOCK_WARNING) + '</div>'
      + '<textarea class="form-control form-control-sm mt-1 d-none" rows="2" data-field="observacionNoDisponible" placeholder="Motivo por el que no se entrega" maxlength="200"></textarea>'
      + '<div class="invalid-feedback"></div></td>'
      + '<td><input type="number" class="form-control form-control-sm" data-field="cantidad" min="1" max="9999" step="1" style="max-width:100px;">'
      + '<div class="invalid-feedback"></div></td>'
      + '<td><input type="text" class="form-control form-control-sm" data-field="dosis" placeholder="1 cada 8 horas" maxlength="100">'
      + '<div class="invalid-feedback"></div></td>'
      + '<td><input type="text" class="form-control form-control-sm" data-field="duracion" placeholder="3 días" maxlength="100">'
      + '<div class="invalid-feedback"></div></td>'
      + '<td class="text-center"><button type="button" class="btn-icon delete" data-action="remove-receta" title="Quitar"><i class="fas fa-trash"></i></button></td>'
      + '</tr>';
    recetasBody.insertAdjacentHTML('beforeend', html);
  }

  /*
   * Reveals the observation textarea on every line that cannot be delivered. Quantities of the
   * same medicamento accumulate across rows, exactly like the server rule, so two rows that fit
   * on their own but overflow together are both flagged before submitting.
   */
  function refreshRows() {
    var remaining = {};
    Array.prototype.forEach.call(recetasBody.querySelectorAll('tr[data-row]'), function (row) {
      var select = row.querySelector('[data-field="idMedicamento"]');
      var cantidad = row.querySelector('[data-field="cantidad"]');
      var warning = row.querySelector('[data-role="warning"]');
      var observacion = row.querySelector('[data-field="observacionNoDisponible"]');
      var requested = parseInt(cantidad.value, 10);
      var insufficient = false;
      if (select.value !== '' && !isNaN(requested) && requested > 0) {
        var key = String(select.value);
        if (!(key in remaining)) {
          var stock = stockOf(select.value);
          remaining[key] = stock === null ? 0 : stock;
        }
        if (remaining[key] >= requested) {
          remaining[key] -= requested;
        } else {
          insufficient = true;
        }
      }
      warning.classList.toggle('d-none', !insufficient);
      observacion.classList.toggle('d-none', !insufficient);
    });
  }

  function readRecetas() {
    if (!recetasBody) {
      return [];
    }
    return Array.prototype.map.call(recetasBody.querySelectorAll('tr[data-row]'), function (row) {
      var value = function (field) {
        var el = row.querySelector('[data-field="' + field + '"]');
        return el ? el.value.trim() : '';
      };
      var cantidad = value('cantidad');
      return {
        idMedicamento: value('idMedicamento') ? Number(value('idMedicamento')) : null,
        cantidad: cantidad === '' ? null : Number(cantidad),
        dosis: value('dosis'),
        duracion: value('duracion'),
        observacionNoDisponible: value('observacionNoDisponible') || null
      };
    });
  }

  /* VALIDATION errors arrive as "recetas[i].field": map them onto the right row. */
  function showRecetaErrors(errors) {
    Object.keys(errors || {}).forEach(function (key) {
      var match = /^recetas\[(\d+)\]\.(\w+)$/.exec(key);
      if (!match) {
        return;
      }
      var rows = recetasBody.querySelectorAll('tr[data-row]');
      var row = rows[parseInt(match[1], 10)];
      if (!row) {
        return;
      }
      var input = row.querySelector('[data-field="' + match[2] + '"]');
      if (!input) {
        return;
      }
      input.classList.remove('d-none');
      input.classList.add('is-invalid');
      /* every field is followed by its own feedback div (the select has two siblings before it) */
      var feedback = input.nextElementSibling;
      while (feedback && !feedback.classList.contains('invalid-feedback')) {
        feedback = feedback.nextElementSibling;
      }
      if (feedback) {
        feedback.textContent = errors[key];
      }
      if (match[2] === 'observacionNoDisponible') {
        var warning = row.querySelector('[data-role="warning"]');
        if (warning) {
          warning.classList.remove('d-none');
        }
      }
    });
  }

  function clearRecetaErrors() {
    if (!recetasBody) {
      return;
    }
    Array.prototype.forEach.call(recetasBody.querySelectorAll('.is-invalid'), function (el) {
      el.classList.remove('is-invalid');
    });
    Array.prototype.forEach.call(recetasBody.querySelectorAll('.invalid-feedback'), function (el) {
      el.textContent = '';
    });
  }

  function loadMedicamentos() {
    return window.HospiSoft.appCall('medicamentosParaRecetar').then(function (response) {
      medicamentos = response.ok ? response.data : [];
      medicamentosLoaded = response.ok;
      return medicamentos;
    }).catch(function () {
      medicamentos = [];
      medicamentosLoaded = false;
      return medicamentos;
    });
  }

  /* Re-renders the options of the rows already on screen once the catalogue arrives. */
  function refreshSelects() {
    Array.prototype.forEach.call(recetasBody.querySelectorAll('[data-field="idMedicamento"]'), function (select) {
      var selected = select.value;
      select.innerHTML = '<option value="">Seleccione medicamento...</option>' + options();
      select.value = selected;
    });
  }

  /** Opens the closing form for a cita (EN_CURSO). */
  function openFinalizar(cita, afterSaved) {
    if (!form) {
      return;
    }
    onSaved = afterSaved || null;
    var token = ++openToken;
    document.getElementById('atencion-id-cita').value = cita.id;
    document.getElementById('atencion-paciente').textContent =
      cita.pacienteNombre + ' (' + cita.pacienteDocumento + ')';
    document.getElementById('atencion-medico').textContent =
      cita.medicoNombre + ' · ' + formatDate(cita.fecha) + ' ' + cita.hora;
    document.getElementById('atencion-diagnostico').value = '';
    document.getElementById('atencion-tratamiento').value = '';
    document.getElementById('atencion-notas').value = '';
    document.getElementById('atencion-observaciones').value = '';
    recetasBody.innerHTML = '';
    crud.clearValidation(form);
    crud.hideModalAlert(alertBox);
    saveButton.disabled = true;
    addRecetaButton.disabled = true;
    loadMedicamentos().then(function () {
      if (token !== openToken) {
        return; // the modal was dismissed and reopened meanwhile
      }
      if (!medicamentosLoaded) {
        crud.showModalAlert(alertBox, 'No se pudieron cargar los medicamentos');
        return; // Guardar and Agregar stay disabled: the form cannot be filled correctly
      }
      refreshSelects();
      saveButton.disabled = false;
      addRecetaButton.disabled = false;
    });
    crud.modal('modalAtencion').show();
  }

  function finalizar() {
    crud.clearValidation(form);
    clearRecetaErrors();
    crud.hideModalAlert(alertBox);
    var payload = {
      idCita: Number(document.getElementById('atencion-id-cita').value),
      diagnostico: document.getElementById('atencion-diagnostico').value,
      tratamiento: document.getElementById('atencion-tratamiento').value,
      notas: document.getElementById('atencion-notas').value,
      observaciones: document.getElementById('atencion-observaciones').value,
      recetas: readRecetas()
    };
    var token = openToken;
    setPending(true);
    crud.setBusy(saveButton, true, 'Guardando…');
    window.HospiSoft.appCall('finalizarAtencion', payload)
      .then(function (response) {
        if (token !== openToken) {
          return; // reply of a modal the user already closed and reopened
        }
        setPending(false);
        crud.setBusy(saveButton, false);
        if (response.ok) {
          crud.modal('modalAtencion').hide();
          window.HospiSoft.showToast('success', 'Atención finalizada');
          if (onSaved) {
            onSaved();
          }
          return;
        }
        if (response.code === 'VALIDATION') {
          crud.showValidation(form, response.errors, FIELDS);
          showRecetaErrors(response.errors);
          return;
        }
        crud.showModalAlert(alertBox, response.message);
      })
      .catch(function (error) {
        if (token !== openToken) {
          return;
        }
        setPending(false);
        crud.setBusy(saveButton, false);
        crud.showModalAlert(alertBox, error.message);
      });
  }

  /* While a close is in flight the modal cannot be dismissed, so no stale reply hits a reopened form. */
  function setPending(pending) {
    var element = document.getElementById('modalAtencion');
    if (!element) {
      return;
    }
    if (pending) {
      element.setAttribute('data-bs-backdrop', 'static');
      element.setAttribute('data-bs-keyboard', 'false');
    } else {
      element.removeAttribute('data-bs-backdrop');
      element.removeAttribute('data-bs-keyboard');
    }
  }

  /* ---------------------------------------------------------------- historia (read-only) */

  function renderHistoria(historia) {
    document.getElementById('historia-paciente').textContent =
      historia.pacienteNombre + ' (' + historia.pacienteDocumento + ')';
    document.getElementById('historia-medico').textContent =
      historia.medicoNombre + ' — ' + historia.especialidad;
    document.getElementById('historia-fecha').textContent = formatDate(historia.fecha);
    document.getElementById('historia-diagnostico').textContent = historia.diagnostico || '–';
    document.getElementById('historia-tratamiento').textContent = historia.tratamiento || '–';
    document.getElementById('historia-notas').textContent = historia.notas || '–';
    var recetas = historia.recetas || [];
    if (recetas.length === 0) {
      historiaBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-3">Sin medicamentos recetados</td></tr>';
      return;
    }
    historiaBody.innerHTML = recetas.map(function (r) {
      var observacion = r.entregado || !r.observacionNoDisponible ? ''
        : '<div class="small text-muted">' + escape(r.observacionNoDisponible) + '</div>';
      return '<tr>'
        + '<td><strong>' + escape(r.nombre) + '</strong><div class="small text-muted">' + escape(r.codigo)
        + ' · ' + escape(r.presentacion) + '</div></td>'
        + '<td>' + escape(r.cantidad) + '</td>'
        + '<td>' + escape(r.dosis) + '</td>'
        + '<td>' + escape(r.duracion) + '</td>'
        + '<td>' + entregadoBadge(r) + observacion + '</td>'
        + '</tr>';
    }).join('');
  }

  /** Opens the read-only record of a cita. */
  function openHistoriaDeCita(idCita) {
    if (!historiaBody) {
      return;
    }
    crud.hideModalAlert(historiaAlert);
    historiaBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-3">Cargando...</td></tr>';
    crud.modal('modalHistoria').show();
    window.HospiSoft.appCall('getHistoriaCita', { idCita: idCita }).then(function (response) {
      if (!response.ok) {
        crud.showModalAlert(historiaAlert, response.message);
        historiaBody.innerHTML = '';
        return;
      }
      renderHistoria(response.data);
    }).catch(function (error) {
      crud.showModalAlert(historiaAlert, error.message);
      historiaBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-3">'
        + crud.escapeHtml(error.message) + '</td></tr>';
    });
  }

  /** Opens the read-only record from an already loaded historia (the historial list). */
  function openHistoria(historia) {
    if (!historiaBody) {
      return;
    }
    crud.hideModalAlert(historiaAlert);
    renderHistoria(historia);
    crud.modal('modalHistoria').show();
  }

  /* ---------------------------------------------------------------- events */

  var addRecetaButton = document.getElementById('btn-add-receta');

  if (recetasBody) {
    addRecetaButton.addEventListener('click', addRecetaRow);
    recetasBody.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-action="remove-receta"]');
      if (button) {
        button.closest('tr').remove();
        refreshRows();
      }
    });
    recetasBody.addEventListener('change', refreshRows);
    recetasBody.addEventListener('input', refreshRows);
    saveButton.addEventListener('click', finalizar);
    form.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && event.target.tagName !== 'TEXTAREA') {
        event.preventDefault();
        if (!saveButton.disabled) {
          saveButton.click();
        }
      }
    });
  }

  window.HospiSoftAtencion = {
    openFinalizar: openFinalizar,
    openHistoriaDeCita: openHistoriaDeCita,
    openHistoria: openHistoria,
    formatDate: formatDate,
    entregadoBadge: entregadoBadge
  };
})();
