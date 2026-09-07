package com.hospisoft.dao;

import com.hospisoft.model.Paciente;
import com.hospisoft.util.ConexionDB;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class PacienteDAO {

    public int insertar(Paciente p) throws SQLException {
        String sql = "INSERT INTO paciente (nombres, apellidos, fecha_nacimiento, cedula, telefono, correo, sexo, eps, rh) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, p.getNombres());
            ps.setString(2, p.getApellidos());
            ps.setDate(3, Date.valueOf(p.getFechaNacimiento()));
            ps.setString(4, p.getCedula());
            ps.setString(5, p.getTelefono());
            ps.setString(6, p.getCorreo());
            ps.setString(7, p.getSexo());
            ps.setString(8, p.getEps());
            ps.setString(9, p.getRh());

            ps.executeUpdate();

            try (ResultSet claves = ps.getGeneratedKeys()) {
                if (claves.next()) {
                    return claves.getInt(1);
                }
            }
            return 0;
        }
    }

    public List<Paciente> consultarTodos() throws SQLException {
        String sql = "SELECT id, nombres, apellidos, fecha_nacimiento, cedula, telefono, correo, sexo, eps, rh "
                + "FROM paciente ORDER BY apellidos, nombres";
        List<Paciente> pacientes = new ArrayList<>();

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                pacientes.add(mapearPaciente(rs));
            }
        }
        return pacientes;
    }

    public Paciente consultarPorId(int id) throws SQLException {
        String sql = "SELECT id, nombres, apellidos, fecha_nacimiento, cedula, telefono, correo, sexo, eps, rh "
                + "FROM paciente WHERE id = ?";

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql)) {

            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapearPaciente(rs);
                }
                return null;
            }
        }
    }

    public boolean actualizar(Paciente p) throws SQLException {
        String sql = "UPDATE paciente SET nombres = ?, apellidos = ?, fecha_nacimiento = ?, cedula = ?, "
                + "telefono = ?, correo = ?, sexo = ?, eps = ?, rh = ? WHERE id = ?";

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql)) {

            ps.setString(1, p.getNombres());
            ps.setString(2, p.getApellidos());
            ps.setDate(3, Date.valueOf(p.getFechaNacimiento()));
            ps.setString(4, p.getCedula());
            ps.setString(5, p.getTelefono());
            ps.setString(6, p.getCorreo());
            ps.setString(7, p.getSexo());
            ps.setString(8, p.getEps());
            ps.setString(9, p.getRh());
            ps.setInt(10, p.getId());

            return ps.executeUpdate() == 1;
        }
    }

    public boolean eliminar(int id) throws SQLException {
        String sql = "DELETE FROM paciente WHERE id = ?";

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql)) {

            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean existeCedula(String cedula, int idExcluir) {
        String sql = "SELECT COUNT(*) FROM paciente WHERE cedula = ? AND id <> ?";

        try (Connection conexion = ConexionDB.getConnection();
             PreparedStatement ps = conexion.prepareStatement(sql)) {

            ps.setString(1, cedula);
            ps.setInt(2, idExcluir);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error de base de datos al validar la cédula: " + e.getMessage());
        }
        return false;
    }

    private Paciente mapearPaciente(ResultSet rs) throws SQLException {
        Paciente p = new Paciente();
        p.setId(rs.getInt("id"));
        p.setNombres(rs.getString("nombres"));
        p.setApellidos(rs.getString("apellidos"));
        Date fecha = rs.getDate("fecha_nacimiento");
        p.setFechaNacimiento(fecha != null ? fecha.toLocalDate() : null);
        p.setCedula(rs.getString("cedula"));
        p.setTelefono(rs.getString("telefono"));
        p.setCorreo(rs.getString("correo"));
        p.setSexo(rs.getString("sexo"));
        p.setEps(rs.getString("eps"));
        p.setRh(rs.getString("rh"));
        return p;
    }
}