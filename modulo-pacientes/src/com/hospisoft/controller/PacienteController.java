package com.hospisoft.controller;

import com.hospisoft.dao.PacienteDAO;
import com.hospisoft.model.Paciente;

import java.util.List;
import java.util.regex.Pattern;

public class PacienteController {

    private static final Pattern FORMATO_CORREO = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");

    private final PacienteDAO dao;

    public PacienteController() {
        this.dao = new PacienteDAO();
    }

    public String insertar(Paciente p) {
        String error = validarCampos(p);
        if (error != null) {
            return error;
        }
        try {
            if (dao.existeCedula(p.getCedula(), 0)) {
                return "Error: la cédula " + p.getCedula() + " ya está registrada.";
            }
            int id = dao.insertar(p);
            return id > 0
                    ? "Paciente registrado correctamente con el id " + id + "."
                    : "Error: no se pudo obtener el id generado para el paciente.";
        } catch (Exception e) {
            return "Error de base de datos: " + e.getMessage();
        }
    }

    public List<Paciente> listarTodos() {
        try {
            List<Paciente> pacientes = dao.consultarTodos();
            if (pacientes.isEmpty()) {
                System.out.println("No hay pacientes registrados.");
            }
            return pacientes;
        } catch (Exception e) {
            System.out.println("Error de base de datos: " + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public Paciente buscarPorId(int id) {
        try {
            Paciente p = dao.consultarPorId(id);
            if (p == null) {
                System.out.println("No existe un paciente con el id " + id + ".");
            }
            return p;
        } catch (Exception e) {
            System.out.println("Error de base de datos: " + e.getMessage());
            return null;
        }
    }

    public String actualizar(Paciente p) {
        String error = validarCampos(p);
        if (error != null) {
            return error;
        }
        try {
            if (dao.existeCedula(p.getCedula(), p.getId())) {
                return "Error: la cédula " + p.getCedula() + " ya está registrada por otro paciente.";
            }
            boolean ok = dao.actualizar(p);
            return ok
                    ? "Paciente con id " + p.getId() + " actualizado correctamente."
                    : "Error: no se encontró el paciente con id " + p.getId() + ".";
        } catch (Exception e) {
            return "Error de base de datos: " + e.getMessage();
        }
    }

    public String eliminar(int id) {
        try {
            boolean ok = dao.eliminar(id);
            return ok
                    ? "Paciente con id " + id + " eliminado correctamente."
                    : "Error: no se encontró el paciente con id " + id + ".";
        } catch (Exception e) {
            return "Error de base de datos: " + e.getMessage();
        }
    }

    public String normalizarSexo(String sexo) {
        if (sexo == null) {
            return null;
        }
        String valor = sexo.trim().toUpperCase();
        if (valor.equals("M")) {
            return "M";
        }
        if (valor.equals("F")) {
            return "F";
        }
        if (valor.equals("OTRO")) {
            return "Otro";
        }
        return null;
    }

    public boolean validarCorreo(String correo) {
        if (correo == null || correo.trim().isEmpty()) {
            return true;
        }
        return FORMATO_CORREO.matcher(correo.trim()).matches();
    }

    private String validarCampos(Paciente p) {
        if (esVacio(p.getNombres())) {
            return "Error: los nombres son obligatorios.";
        }
        if (esVacio(p.getApellidos())) {
            return "Error: los apellidos son obligatorios.";
        }
        if (p.getFechaNacimiento() == null) {
            return "Error: la fecha de nacimiento es obligatoria.";
        }
        if (esVacio(p.getCedula())) {
            return "Error: la cédula es obligatoria.";
        }
        if (esVacio(p.getTelefono())) {
            return "Error: el teléfono es obligatorio.";
        }
        if (!validarCorreo(p.getCorreo())) {
            return "Error: el correo ingresado no tiene un formato válido.";
        }
        if (esVacio(p.getSexo())) {
            return "Error: el sexo es obligatorio (M, F u Otro).";
        }
        if (esVacio(p.getEps())) {
            return "Error: la EPS es obligatoria.";
        }
        if (esVacio(p.getRh())) {
            return "Error: el RH es obligatorio.";
        }
        return null;
    }

    private boolean esVacio(String texto) {
        return texto == null || texto.trim().isEmpty();
    }
}