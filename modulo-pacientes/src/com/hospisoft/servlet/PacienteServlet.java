package com.hospisoft.servlet;

import com.hospisoft.dao.PacienteDAO;
import com.hospisoft.model.Paciente;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Servlet encargado de gestionar el CRUD de pacientes.
 *
 * <p>La URL de mapeo es <code>/pacientes</code>. Usa el patrón PRG
 * (Post/Redirect/Get) para evitar el reenvío del formulario al recargar.</p>
 */
@WebServlet("/pacientes")
public class PacienteServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final PacienteDAO pacienteDAO = new PacienteDAO();

    /**
     * Procesa las peticiones GET.
     *
     * <p>Según el parámetro {@code accion}:</p>
     * <ul>
     *   <li>{@code form}: muestra el formulario, con datos previos si llega {@code id}.</li>
     *   <li>{@code eliminar}: elimina el paciente indicado por {@code id}.</li>
     *   <li>{@code listar} o por defecto: lista todos los pacientes.</li>
     * </ul>
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String accion = req.getParameter("accion");
        if (accion == null) {
            accion = "listar";
        }

        switch (accion) {
            case "form":
                mostrarFormulario(req, resp);
                break;
            case "eliminar":
                eliminar(req, resp);
                break;
            default:
                listar(req, resp);
                break;
        }
    }

    /**
     * Procesa las peticiones POST (insertar o actualizar un paciente).
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        String accion = req.getParameter("accion");

        if ("actualizar".equals(accion)) {
            actualizar(req, resp);
        } else {
            insertar(req, resp);
        }
    }

    /**
     * Muestra el formulario de alta/edición.
     *
     * <p>Si llega {@code id} carga el paciente y lo pone como atributo
     * {@code paciente} para precargar los campos.</p>
     */
    private void mostrarFormulario(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String idParam = req.getParameter("id");
        if (idParam != null && !idParam.trim().isEmpty()) {
            try {
                int id = Integer.parseInt(idParam);
                Paciente p = pacienteDAO.consultarPorId(id);
                if (p != null) {
                    req.setAttribute("paciente", p);
                }
            } catch (SQLException e) {
                req.setAttribute("error", "Error de base de datos: " + e.getMessage());
            } catch (NumberFormatException e) {
                req.setAttribute("error", "El identificador del paciente no es válido.");
            }
        }
        req.getRequestDispatcher("/formPaciente.jsp").forward(req, resp);
    }

    /**
     * Elimina un paciente y redirige a la lista con un mensaje.
     */
    private void eliminar(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String idParam = req.getParameter("id");
        try {
            int id = Integer.parseInt(idParam);
            boolean ok = pacienteDAO.eliminar(id);
            String mensaje = ok
                    ? "Paciente eliminado correctamente."
                    : "No se encontró el paciente a eliminar.";
            resp.sendRedirect(req.getContextPath() + "/pacientes?mensaje=" + mensaje);
        } catch (SQLException e) {
            resp.sendRedirect(req.getContextPath()
                    + "/pacientes?mensaje=Error de base de datos: " + e.getMessage());
        } catch (NumberFormatException e) {
            resp.sendRedirect(req.getContextPath()
                    + "/pacientes?mensaje=El identificador del paciente no es válido.");
        }
    }

    /**
     * Consulta todos los pacientes y los envía a la vista de listado.
     */
    private void listar(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            List<Paciente> pacientes = pacienteDAO.consultarTodos();
            req.setAttribute("pacientes", pacientes);
        } catch (SQLException e) {
            req.setAttribute("error", "Error de base de datos: " + e.getMessage());
        }
        req.getRequestDispatcher("/listarPacientes.jsp").forward(req, resp);
    }

    /**
     * Crea un paciente nuevo tras validar los datos del formulario.
     */
    private void insertar(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        Paciente p = leerPaciente(req);
        String error = validar(p);

        if (error != null) {
            volverAlFormulario(req, resp, p, error);
            return;
        }

        try {
            if (pacienteDAO.existeCedula(p.getCedula(), 0)) {
                volverAlFormulario(req, resp, p,
                        "Ya existe un paciente registrado con la cédula " + p.getCedula() + ".");
                return;
            }
            pacienteDAO.insertar(p);
            resp.sendRedirect(req.getContextPath()
                    + "/pacientes?mensaje=Paciente registrado correctamente.");
        } catch (SQLException e) {
            volverAlFormulario(req, resp, p, "Error de base de datos: " + e.getMessage());
        }
    }

    /**
     * Actualiza un paciente existente tras validar los datos del formulario.
     */
    private void actualizar(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        Paciente p = leerPaciente(req);
        try {
            p.setId(Integer.parseInt(req.getParameter("id")));
        } catch (NumberFormatException e) {
            volverAlFormulario(req, resp, p, "El identificador del paciente no es válido.");
            return;
        }

        String error = validar(p);
        if (error != null) {
            volverAlFormulario(req, resp, p, error);
            return;
        }

        try {
            if (pacienteDAO.existeCedula(p.getCedula(), p.getId())) {
                volverAlFormulario(req, resp, p,
                        "Ya existe otro paciente registrado con la cédula " + p.getCedula() + ".");
                return;
            }
            boolean ok = pacienteDAO.actualizar(p);
            String mensaje = ok
                    ? "Paciente actualizado correctamente."
                    : "No se encontró el paciente a actualizar.";
            resp.sendRedirect(req.getContextPath() + "/pacientes?mensaje=" + mensaje);
        } catch (SQLException e) {
            volverAlFormulario(req, resp, p, "Error de base de datos: " + e.getMessage());
        }
    }

    /**
     * Reenvía al formulario mostrando un error y conservando los datos ingresados.
     */
    private void volverAlFormulario(HttpServletRequest req, HttpServletResponse resp,
                                    Paciente p, String error)
            throws ServletException, IOException {
        req.setAttribute("error", error);
        req.setAttribute("paciente", p);
        req.getRequestDispatcher("/formPaciente.jsp").forward(req, resp);
    }

    /**
     * Lee todos los campos del formulario y construye un objeto Paciente.
     */
    private Paciente leerPaciente(HttpServletRequest req) {
        Paciente p = new Paciente();
        p.setNombres(req.getParameter("nombres"));
        p.setApellidos(req.getParameter("apellidos"));
        p.setCedula(req.getParameter("cedula"));
        p.setTelefono(req.getParameter("telefono"));
        p.setCorreo(req.getParameter("correo"));
        p.setSexo(req.getParameter("sexo"));
        p.setEps(req.getParameter("eps"));
        p.setRh(req.getParameter("rh"));

        String fecha = req.getParameter("fecha_nacimiento");
        if (fecha != null && !fecha.trim().isEmpty()) {
            try {
                p.setFechaNacimiento(LocalDate.parse(fecha));
            } catch (DateTimeParseException e) {
                p.setFechaNacimiento(null);
            }
        }
        return p;
    }

    /**
     * Valida los campos obligatorios y el valor de sexo.
     *
     * @return un mensaje de error, o {@code null} si los datos son válidos.
     */
    private String validar(Paciente p) {
        if (esVacio(p.getNombres())) {
            return "El campo Nombres es obligatorio.";
        }
        if (esVacio(p.getApellidos())) {
            return "El campo Apellidos es obligatorio.";
        }
        if (p.getFechaNacimiento() == null) {
            return "El campo Fecha de nacimiento es obligatorio y debe tener formato válido.";
        }
        if (esVacio(p.getCedula())) {
            return "El campo Cédula es obligatorio.";
        }
        if (esVacio(p.getTelefono())) {
            return "El campo Teléfono es obligatorio.";
        }
        if (esVacio(p.getSexo())) {
            return "El campo Sexo es obligatorio.";
        }
        if (!"M".equals(p.getSexo()) && !"F".equals(p.getSexo()) && !"Otro".equals(p.getSexo())) {
            return "El valor de Sexo debe ser M, F u Otro.";
        }
        if (esVacio(p.getEps())) {
            return "El campo EPS es obligatorio.";
        }
        if (esVacio(p.getRh())) {
            return "El campo RH es obligatorio.";
        }
        return null;
    }

    /**
     * Indica si una cadena es nula o está vacía (ignorando espacios).
     */
    private boolean esVacio(String valor) {
        return valor == null || valor.trim().isEmpty();
    }
}
