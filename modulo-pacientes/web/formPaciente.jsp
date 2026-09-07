<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.hospisoft.model.Paciente" %>
<%
    Paciente p = (Paciente) request.getAttribute("paciente");
    boolean editando = (p != null && p.getId() > 0);
    String accion = editando ? "actualizar" : "insertar";
    String error = (String) request.getAttribute("error");
    String titulo = editando ? "Editar Paciente" : "Nuevo Paciente";
%>
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>HospiSoft — <%= titulo %></title>
  <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css">
  <link rel="stylesheet" href="css/style.css">
</head>
<body>

  <div class="min-vh-100">
    <aside class="sidebar d-flex flex-column" id="sidebar">
      <div class="p-3 border-bottom" style="border-color: rgba(255,255,255,.15) !important;">
        <div class="d-flex align-items-center gap-2">
          <div>
            <div class="fw-bold fs-5 lh-1 text-white">HospiSoft</div>
            <small class="text-white-50">Sistema Hospitalario</small>
          </div>
        </div>
      </div>
      <ul class="list-unstyled mb-0 flex-grow-1 pt-2">
        <li><a href="index.html" class="nav-link"><i class="fas fa-home" style="width:1.25rem;text-align:center"></i> Dashboard</a></li>
        <li><a href="medicos.html" class="nav-link"><i class="fas fa-user-md" style="width:1.25rem;text-align:center"></i> Médicos</a></li>
        <li><a href="pacientes" class="nav-link active"><i class="fas fa-users" style="width:1.25rem;text-align:center"></i> Pacientes</a></li>
        <li><a href="citas.html" class="nav-link"><i class="fas fa-calendar-check" style="width:1.25rem;text-align:center"></i> Citas</a></li>
        <li><a href="medicamentos.html" class="nav-link"><i class="fas fa-capsules" style="width:1.25rem;text-align:center"></i> Medicamentos</a></li>
      </ul>
      <div class="p-3 border-top text-white-50 small" style="border-color: rgba(255,255,255,.15) !important;">
        <i class="fas fa-circle text-success" style="font-size:.5rem;vertical-align:middle"></i>
        <span class="ms-1">Sistema activo</span>
      </div>
    </aside>

    <main class="main-content d-flex flex-column">
      <header class="top-navbar sticky-top bg-white border-bottom shadow-sm d-flex align-items-center justify-content-between px-4" style="height:60px;">
        <div class="d-flex align-items-center gap-3">
          <button class="btn d-md-none p-0" type="button" onclick="document.getElementById('sidebar').classList.toggle('show')">
            <i class="fas fa-bars fs-5"></i>
          </button>
        </div>
        <div class="d-flex align-items-center gap-3">
          <i class="fas fa-bell text-muted"></i>
          <div class="d-flex align-items-center gap-2 text-muted small">
            <span>Dr. Admin</span>
            <span class="d-inline-flex align-items-center justify-content-center rounded-circle" style="width:36px;height:36px;background:var(--clr-light);color:var(--clr-text);font-weight:700;">A</span>
          </div>
        </div>
      </header>

      <div class="p-4 flex-grow-1">
        <div class="d-flex justify-content-between align-items-center flex-wrap gap-3 mb-4">
          <h4 class="mb-0 fw-semibold"><%= titulo %></h4>
          <a href="pacientes" class="btn btn-outline-secondary"><i class="fas fa-arrow-left me-1"></i> Volver</a>
        </div>

        <%
            if (error != null && !error.trim().isEmpty()) {
        %>
        <div class="alert alert-danger alert-dismissible fade show" role="alert">
          <%= error %>
          <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Cerrar"></button>
        </div>
        <%
            }
        %>

        <div class="bg-white rounded-3 shadow-sm overflow-hidden" style="max-width: 860px;">
          <div class="px-4 py-3" style="background:var(--clr-light);border-bottom:1px solid var(--clr-mid);">
            <h5 class="mb-0" style="color:var(--clr-text);"><i class="fas fa-user-plus me-2"></i><%= titulo %></h5>
          </div>
          <div class="p-4">
            <form method="POST" action="pacientes">
              <input type="hidden" name="accion" value="<%= accion %>">
              <% if (editando) { %>
              <input type="hidden" name="id" value="<%= p.getId() %>">
              <% } %>

              <div class="row g-3">
                <div class="col-md-6">
                  <label for="nombres" class="form-label fw-medium small">Nombres *</label>
                  <input type="text" class="form-control" id="nombres" name="nombres"
                         value="<%= p != null && p.getNombres() != null ? p.getNombres() : "" %>" required>
                </div>
                <div class="col-md-6">
                  <label for="apellidos" class="form-label fw-medium small">Apellidos *</label>
                  <input type="text" class="form-control" id="apellidos" name="apellidos"
                         value="<%= p != null && p.getApellidos() != null ? p.getApellidos() : "" %>" required>
                </div>
                <div class="col-md-6">
                  <label for="fecha_nacimiento" class="form-label fw-medium small">Fecha de nacimiento *</label>
                  <input type="date" class="form-control" id="fecha_nacimiento" name="fecha_nacimiento"
                         value="<%= p != null && p.getFechaNacimiento() != null ? p.getFechaNacimiento() : "" %>" required>
                </div>
                <div class="col-md-6">
                  <label for="cedula" class="form-label fw-medium small">Cédula *</label>
                  <input type="text" class="form-control" id="cedula" name="cedula"
                         value="<%= p != null && p.getCedula() != null ? p.getCedula() : "" %>" required>
                </div>
                <div class="col-md-6">
                  <label for="telefono" class="form-label fw-medium small">Teléfono *</label>
                  <input type="text" class="form-control" id="telefono" name="telefono"
                         value="<%= p != null && p.getTelefono() != null ? p.getTelefono() : "" %>" required>
                </div>
                <div class="col-md-6">
                  <label for="correo" class="form-label fw-medium small">Correo</label>
                  <input type="email" class="form-control" id="correo" name="correo"
                         value="<%= p != null && p.getCorreo() != null ? p.getCorreo() : "" %>">
                </div>
                <div class="col-md-4">
                  <label for="sexo" class="form-label fw-medium small">Sexo *</label>
                  <select class="form-select" id="sexo" name="sexo" required>
                    <option value="">Seleccione...</option>
                    <%
                        String sexo = p != null ? p.getSexo() : "";
                    %>
                    <option value="M" <%= "M".equals(sexo) ? "selected" : "" %>>Masculino</option>
                    <option value="F" <%= "F".equals(sexo) ? "selected" : "" %>>Femenino</option>
                    <option value="Otro" <%= "Otro".equals(sexo) ? "selected" : "" %>>Otro</option>
                  </select>
                </div>
                <div class="col-md-4">
                  <label for="eps" class="form-label fw-medium small">EPS *</label>
                  <input type="text" class="form-control" id="eps" name="eps"
                         value="<%= p != null && p.getEps() != null ? p.getEps() : "" %>" required>
                </div>
                <div class="col-md-4">
                  <label for="rh" class="form-label fw-medium small">RH *</label>
                  <input type="text" class="form-control" id="rh" name="rh"
                         value="<%= p != null && p.getRh() != null ? p.getRh() : "" %>" required>
                </div>
              </div>

              <div class="d-flex justify-content-end gap-2 mt-4">
                <a href="pacientes" class="btn btn-outline-secondary">Cancelar</a>
                <button type="submit" class="btn btn-primary"><i class="fas fa-save me-1"></i> Guardar</button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </main>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>