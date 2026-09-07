package com.hospisoft.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Clase utilitaria que centraliza la creación de la conexión a la base de datos MySQL "hospisoft".
 */
public class ConexionDB {

    private static final String URL = "jdbc:mysql://localhost:3306/hospisoft?useSSL=false&serverTimezone=America/Bogota&useUnicode=true&characterEncoding=UTF-8";
    private static final String USUARIO = "root";
    private static final String CONTRASENA = "";

    /**
     * Obtiene una conexión nueva a la base de datos.
     *
     * @return una conexión JDBC activa.
     * @throws SQLException si no se puede conectar a la base de datos.
     */
    public static Connection getConnection() throws SQLException {
        try {
            // Registra el driver de MySQL (redundante desde JDBC 4, pero explícito para la evidencia).
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No se encontró el driver de MySQL en el classpath. Verifique el conector.", e);
        }
        return DriverManager.getConnection(URL, USUARIO, CONTRASENA);
    }

    /**
     * Prueba simple de conexión.
     */
    public static void main(String[] args) {
        try (Connection conexion = getConnection()) {
            System.out.println("Conexión exitosa");
        } catch (SQLException e) {
            System.out.println("Error de conexión: " + e.getMessage());
        }
    }
}