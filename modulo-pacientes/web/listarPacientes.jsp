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
    <title>Gestión de Pacientes - HospiSoft</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand mb-0 h1">HospiSoft</span>
        <span class="navbar-text">Módulo de Pacientes</span>
    </div>
</nav>

<div class="container mt-4">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h2>Gestión de Pacientes</h2>
        <a href="pacientes?accion=form" class="btn btn-success">+ Agregar Paciente</a>
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

    <div class="card shadow-sm">
        <div class="card-body">
            <%
                if (pacientes == null || pacientes.isEmpty()) {
            %>
            <div class="alert alert-info" role="alert">No hay pacientes registrados.</div>
            <%
                } else {
            %>
            <div class="table-responsive">
                <table class="table table-striped table-hover align-middle">
                    <thead class="table-primary">
                    <tr>
                        <th>ID</th>
                        <th>Nombre completo</th>
                        <th>Fecha nacimiento</th>
                        <th>Cédula</th>
                        <th>Teléfono</th>
                        <th>Correo</th>
                        <th>Sexo</th>
                        <th>EPS</th>
                        <th>RH</th>
                        <th>Acciones</th>
                    </tr>
                    </thead>
                    <tbody>
                    <%
                        for (Paciente p : pacientes) {
                            String sexoTexto = "Otro";
                            if ("M".equals(p.getSexo())) sexoTexto = "Masculino";
                            else if ("F".equals(p.getSexo())) sexoTexto = "Femenino";
                    %>
                    <tr>
                        <td><%= p.getId() %></td>
                        <td><%= p.getNombres() %> <%= p.getApellidos() %></td>
                        <td><%= p.getFechaNacimiento() %></td>
                        <td><%= p.getCedula() %></td>
                        <td><%= p.getTelefono() %></td>
                        <td><%= p.getCorreo() != null ? p.getCorreo() : "" %></td>
                        <td><%= sexoTexto %></td>
                        <td><%= p.getEps() %></td>
                        <td><%= p.getRh() %></td>
                        <td>
                            <a href="pacientes?accion=form&id=<%= p.getId() %>"
                               class="btn btn-sm btn-outline-primary">Editar</a>
                            <a href="pacientes?accion=eliminar&id=<%= p.getId() %>"
                               class="btn btn-sm btn-outline-danger"
                               onclick="return confirm('¿Desea eliminar a este paciente?');">Eliminar</a>
                        </td>
                    </tr>
                    <%
                        }
                    %>
                    </tbody>
                </table>
            </div>
            <%
                }
            %>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
