(function () {
  'use strict';

  var crud = window.HospiSoftCrud;
  var ESTADO_BADGE = {
    PROGRAMADA: '<span class="badge-stock pendiente">Programada</span>',
    EN_CURSO: '<span class="badge-stock confirmada">En curso</span>',
    FINALIZADA: '<span class="badge-stock completada">Finalizada</span>',
    CANCELADA: '<span class="badge-stock cancelada">Cancelada</span>'
  };
  var SLOTS = ['medicos', 'pacientes', 'citas', 'medicamentos'];
  var DASH = '–';

  var proximas = document.getElementById('proximas-citas');

  function text(id, value) {
    var element = document.getElementById(id);
    if (element) {
      element.textContent = value;
    }
  }

  function formatDate(iso) {
    if (!iso || !/^\d{4}-\d{2}-\d{2}$/.test(iso)) {
      return String(iso || '');
    }
    return iso.substring(8, 10) + '/' + iso.substring(5, 7) + '/' + iso.substring(0, 4);
  }

  function todayIso(offsetDays) {
    var date = new Date();
    date.setDate(date.getDate() + (offsetDays || 0));
    var month = String(date.getMonth() + 1);
    var day = String(date.getDate());
    return date.getFullYear() + '-' + (month.length < 2 ? '0' + month : month)
      + '-' + (day.length < 2 ? '0' + day : day);
  }

  function dayLabel(iso) {
    if (iso === todayIso(0)) {
      return 'Hoy';
    }
    if (iso === todayIso(1)) {
      return 'Mañana';
    }
    return formatDate(iso);
  }

  function citaRow(cita) {
    var e = crud.escapeHtml;
    return '<li>'
      + '<div class="d-flex align-items-center justify-content-center rounded-3 flex-shrink-0" style="width:36px;height:36px;background:var(--clr-light);color:var(--clr-text);">'
      + '<i class="fas fa-user-md"></i>'
      + '</div>'
      + '<div class="flex-grow-1">'
      + '<div><strong>' + e(cita.medicoNombre) + '</strong> — Paciente: ' + e(cita.pacienteNombre) + '</div>'
      + '<div class="small text-muted"><i class="far fa-clock me-1"></i>' + e(dayLabel(cita.fecha))
      + ' — ' + e(cita.hora) + '</div>'
      + '</div>'
      + (ESTADO_BADGE[cita.estado] || '')
      + '</li>';
  }

  function emptyRow(message) {
    return '<li><div class="flex-grow-1 text-muted">' + crud.escapeHtml(message) + '</div></li>';
  }

  function renderKpis(kpis) {
    SLOTS.forEach(function (slot, index) {
      var kpi = kpis[index];
      if (!kpi) {
        return;
      }
      text('kpi-' + slot + '-valor', kpi.valor);
      text('kpi-' + slot + '-etiqueta', kpi.etiqueta);
      text('kpi-' + slot + '-detalle', kpi.detalle);
    });
  }

  function renderResumen(data) {
    var resumen = data.resumenHoy || {};
    text('resumen-total', resumen.total);
    text('resumen-atendidas', resumen.atendidas);
    text('resumen-encurso', resumen.enCurso);
    text('resumen-pendientes', resumen.pendientes);
    text('resumen-canceladas', resumen.canceladas);
    text('resumen-atendidas-pct',
      data.atendidasPct === null || data.atendidasPct === undefined ? DASH : Math.round(data.atendidasPct) + '%');
    text('resumen-duracion',
      data.duracionPromedioMin === null || data.duracionPromedioMin === undefined
        ? DASH : Math.round(data.duracionPromedioMin) + ' min');
  }

  function render(data) {
    renderKpis(data.kpis || []);
    renderResumen(data);
    var citas = data.proximasCitas || [];
    proximas.innerHTML = citas.length === 0
      ? emptyRow('No hay citas próximas')
      : citas.map(citaRow).join('');
  }

  function clearValues() {
    SLOTS.forEach(function (slot) {
      text('kpi-' + slot + '-valor', DASH);
      text('kpi-' + slot + '-detalle', DASH);
    });
    ['resumen-total', 'resumen-atendidas', 'resumen-encurso', 'resumen-pendientes', 'resumen-canceladas',
      'resumen-atendidas-pct', 'resumen-duracion'].forEach(function (id) {
      text(id, DASH);
    });
  }

  function showError(message) {
    clearValues();
    proximas.innerHTML = emptyRow(message);
  }

  function load() {
    var token = crud.nextRequest('dashboard');
    window.HospiSoft.appCall('dashboard')
      .then(function (response) {
        if (!crud.isCurrent('dashboard', token)) {
          return;
        }
        if (!response.ok) {
          showError(response.message);
          return;
        }
        render(response.data);
      })
      .catch(function () {
        if (crud.isCurrent('dashboard', token)) {
          showError('No se pudo cargar el panel');
        }
      });
  }

  window.HospiSoft.onReady(load);
})();
