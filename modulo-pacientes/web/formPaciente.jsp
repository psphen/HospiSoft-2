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
    <title><%= titulo %> - HospiSoft</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand mb-0 h1">HospiSoft</span>
        <a href="pacientes" class="btn btn-sm btn-light">← Volver a la lista</a>
    </div>
</nav>

<div class="container mt-4">
    <div class="card shadow-sm mx-auto" style="max-width: 720px;">
        <div class="card-header bg-primary text-white">
            <h4 class="mb-0"><%= titulo %></h4>
        </div>
        <div class="card-body">
            <%
                if (error != null && !error.trim().isEmpty()) {
            %>
            <div class="alert alert-danger" role="alert">
                <%= error %>
            </div>
            <%
                }
            %>

            <form method="POST" action="pacientes">
                <input type="hidden" name="accion" value="<%= accion %>">
                <% if (editando) { %>
                <input type="hidden" name="id" value="<%= p.getId() %>">
                <% } %>

                <div class="row">
                    <div class="col-md-6 mb-3">
                        <label for="nombres" class="form-label">Nombres *</label>
                        <input type="text" class="form-control" id="nombres" name="nombres"
                               value="<%= p != null && p.getNombres() != null ? p.getNombres() : "" %>" required>
                    </div>
                    <div class="col-md-6 mb-3">
                        <label for="apellidos" class="form-label">Apellidos *</label>
                        <input type="text" class="form-control" id="apellidos" name="apellidos"
                               value="<%= p != null && p.getApellidos() != null ? p.getApellidos() : "" %>" required>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-6 mb-3">
                        <label for="fecha_nacimiento" class="form-label">Fecha de nacimiento *</label>
                        <input type="date" class="form-control" id="fecha_nacimiento" name="fecha_nacimiento"
                               value="<%= p != null && p.getFechaNacimiento() != null ? p.getFechaNacimiento() : "" %>" required>
                    </div>
                    <div class="col-md-6 mb-3">
                        <label for="cedula" class="form-label">Cédula *</label>
                        <input type="text" class="form-control" id="cedula" name="cedula"
                               value="<%= p != null && p.getCedula() != null ? p.getCedula() : "" %>" required>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-6 mb-3">
                        <label for="telefono" class="form-label">Teléfono *</label>
                        <input type="text" class="form-control" id="telefono" name="telefono"
                               value="<%= p != null && p.getTelefono() != null ? p.getTelefono() : "" %>" required>
                    </div>
                    <div class="col-md-6 mb-3">
                        <label for="correo" class="form-label">Correo</label>
                        <input type="email" class="form-control" id="correo" name="correo"
                               value="<%= p != null && p.getCorreo() != null ? p.getCorreo() : "" %>">
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-6 mb-3">
                        <label for="sexo" class="form-label">Sexo *</label>
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
                    <div class="col-md-6 mb-3">
                        <label for="rh" class="form-label">RH *</label>
                        <input type="text" class="form-control" id="rh" name="rh"
                               value="<%= p != null && p.getRh() != null ? p.getRh() : "" %>" required>
                    </div>
                </div>

                <div class="mb-3">
                    <label for="eps" class="form-label">EPS *</label>
                    <input type="text" class="form-control" id="eps" name="eps"
                           value="<%= p != null && p.getEps() != null ? p.getEps() : "" %>" required>
                </div>

                <div class="d-flex justify-content-end gap-2">
                    <a href="pacientes" class="btn btn-secondary">Cancelar</a>
                    <button type="submit" class="btn btn-primary">Guardar</button>
                </div>
            </form>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
