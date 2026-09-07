<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.List" %>
<%@ page import="com.hospisoft.model.Paciente" %>
<%
    List<Paciente> pacientes = (List<Paciente>) request.getAttribute("pacientes");
    String mensaje = request.getParameter("mensaje");
    String error = (String) request.getAttribute("error");
%>
<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>HospiSoft — Gestión de Pacientes</title>
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
          <h4 class="mb-0 fw-semibold">Gestión de Pacientes</h4>
          <a href="pacientes?accion=form" class="btn btn-primary"><i class="fas fa-plus me-1"></i> Agregar Paciente</a>
        </div>

        <%
            if (mensaje != null && !mensaje.trim().isEmpty()) {
        %>
        <div class="alert alert-success alert-dismissible fade show" role="alert">
          <%= mensaje %>
          <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Cerrar"></button>
        </div>
        <%
            }
            if (error != null && !error.trim().isEmpty()) {
        %>
        <div class="alert alert-danger alert-dismissible fade show" role="alert">
          <%= error %>
          <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Cerrar"></button>
        </div>
        <%
            }
        %>

        <div class="bg-white rounded-3 shadow-sm overflow-hidden">
          <div class="table-responsive">
            <table class="table align-middle mb-0">
              <thead class="table-primary">
                <tr>
                  <th class="text-uppercase small">ID</th>
                  <th class="text-uppercase small">Nombre Completo</th>
                  <th class="text-uppercase small">Fecha Nacimiento</th>
                  <th class="text-uppercase small">Cédula</th>
                  <th class="text-uppercase small">Teléfono</th>
                  <th class="text-uppercase small">Correo</th>
                  <th class="text-uppercase small">Sexo</th>
                  <th class="text-uppercase small">EPS</th>
                  <th class="text-uppercase small">RH</th>
                  <th class="text-center text-uppercase small">Acciones</th>
                </tr>
              </thead>
              <tbody>
                <%
                    if (pacientes == null || pacientes.isEmpty()) {
                %>
                <tr>
                  <td colspan="10">
                    <div class="alert alert-info text-center mb-0" role="alert">No hay pacientes registrados.</div>
                  </td>
                </tr>
                <%
                    } else {
                        for (Paciente p : pacientes) {
                            String sexoTexto = "Otro";
                            String sexoBadge = "badge-stock pendiente";
                            if ("M".equals(p.getSexo())) { sexoTexto = "Masculino"; sexoBadge = "badge-stock activo"; }
                            else if ("F".equals(p.getSexo())) { sexoTexto = "Femenino"; sexoBadge = "badge-stock completada"; }
                %>
                <tr>
                  <td><%= p.getId() %></td>
                  <td><strong><%= p.getNombres() %> <%= p.getApellidos() %></strong></td>
                  <td><%= p.getFechaNacimiento() %></td>
                  <td><%= p.getCedula() %></td>
                  <td><%= p.getTelefono() %></td>
                  <td><%= p.getCorreo() != null ? p.getCorreo() : "" %></td>
                  <td><span class="<%= sexoBadge %>"><%= sexoTexto %></span></td>
                  <td><%= p.getEps() %></td>
                  <td><%= p.getRh() %></td>
                  <td class="text-center">
                    <a href="pacientes?accion=form&id=<%= p.getId() %>" class="btn-icon edit" title="Editar"><i class="fas fa-pen"></i></a>
                    <a href="pacientes?accion=eliminar&id=<%= p.getId() %>" class="btn-icon delete" title="Eliminar" onclick="return confirm('¿Desea eliminar a este paciente?');"><i class="fas fa-trash"></i></a>
                  </td>
                </tr>
                <%
                        }
                    }
                %>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </main>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>