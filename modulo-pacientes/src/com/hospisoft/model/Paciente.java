package com.hospisoft.model;

import java.time.LocalDate;

public class Paciente {

    private int id;
    private String nombres;
    private String apellidos;
    private LocalDate fechaNacimiento;
    private String cedula;
    private String telefono;
    private String correo;
    private String sexo;
    private String eps;
    private String rh;

    public Paciente() {
    }

    public Paciente(String nombres, String apellidos, LocalDate fechaNacimiento,
                    String cedula, String telefono, String correo,
                    String sexo, String eps, String rh) {
        this.nombres = nombres;
        this.apellidos = apellidos;
        this.fechaNacimiento = fechaNacimiento;
        this.cedula = cedula;
        this.telefono = telefono;
        this.correo = correo;
        this.sexo = sexo;
        this.eps = eps;
        this.rh = rh;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getNombres() {
        return nombres;
    }

    public void setNombres(String nombres) {
        this.nombres = nombres;
    }

    public String getApellidos() {
        return apellidos;
    }

    public void setApellidos(String apellidos) {
        this.apellidos = apellidos;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public void setFechaNacimiento(LocalDate fechaNacimiento) {
        this.fechaNacimiento = fechaNacimiento;
    }

    public String getCedula() {
        return cedula;
    }

    public void setCedula(String cedula) {
        this.cedula = cedula;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getCorreo() {
        return correo;
    }

    public void setCorreo(String correo) {
        this.correo = correo;
    }

    public String getSexo() {
        return sexo;
    }

    public void setSexo(String sexo) {
        this.sexo = sexo;
    }

    public String getEps() {
        return eps;
    }

    public void setEps(String eps) {
        this.eps = eps;
    }

    public String getRh() {
        return rh;
    }

    public void setRh(String rh) {
        this.rh = rh;
    }

    @Override
    public String toString() {
        return "Paciente{id=" + id
                + ", nombres='" + nombres + '\''
                + ", apellidos='" + apellidos + '\''
                + ", fechaNacimiento=" + fechaNacimiento
                + ", cedula='" + cedula + '\''
                + ", telefono='" + telefono + '\''
                + ", correo='" + correo + '\''
                + ", sexo='" + sexo + '\''
                + ", eps='" + eps + '\''
                + ", rh='" + rh + '\''
                + '}';
    }
}