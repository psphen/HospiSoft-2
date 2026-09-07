package com.hospisoft;

import com.hospisoft.controller.PacienteController;
import com.hospisoft.model.Paciente;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static final Scanner scanner = new Scanner(System.in);
    private static final PacienteController controlador = new PacienteController();

    public static void main(String[] args) {
        int opcion;
        do {
            mostrarMenu();
            opcion = leerEntero("Seleccione una opción: ");
            switch (opcion) {
                case 1 -> registrarPaciente();
                case 2 -> listarPacientes();
                case 3 -> buscarPaciente();
                case 4 -> actualizarPaciente();
                case 5 -> eliminarPaciente();
                case 6 -> System.out.println("¡Hasta pronto!");
                default -> System.out.println("Opción no válida. Intente de nuevo.");
            }
            if (opcion != 6) {
                pausa();
            }
        } while (opcion != 6);
    }

    private static void mostrarMenu() {
        System.out.println("\n===== MÓDULO DE PACIENTES — HOSPISOFT =====");
        System.out.println("1. Registrar paciente");
        System.out.println("2. Listar pacientes");
        System.out.println("3. Buscar paciente por id");
        System.out.println("4. Actualizar paciente");
        System.out.println("5. Eliminar paciente");
        System.out.println("6. Salir");
    }

    private static void registrarPaciente() {
        System.out.println("\n--- Registrar paciente ---");
        Paciente p = pedirDatosPaciente(0);

        String mensaje = controlador.insertar(p);
        System.out.println(mensaje);
    }

    private static void listarPacientes() {
        System.out.println("\n--- Listado de pacientes ---");
        List<Paciente> pacientes = controlador.listarTodos();

        if (pacientes.isEmpty()) {
            return;
        }

        System.out.printf("%-4s %-18s %-18s %-12s %-12s %-12s %-24s %-6s %-14s %-5s%n",
                "ID", "NOMBRES", "APELLIDOS", "F. NACIMIENTO", "CÉDULA", "TELÉFONO", "CORREO", "SEXO", "EPS", "RH");
        System.out.println("-".repeat(125));

        for (Paciente p : pacientes) {
            System.out.printf("%-4d %-18s %-18s %-12s %-12s %-12s %-24s %-6s %-14s %-5s%n",
                    p.getId(),
                    ajustar(p.getNombres(), 18),
                    ajustar(p.getApellidos(), 18),
                    p.getFechaNacimiento(),
                    ajustar(p.getCedula(), 12),
                    ajustar(p.getTelefono(), 12),
                    ajustar(p.getCorreo(), 24),
                    p.getSexo(),
                    ajustar(p.getEps(), 14),
                    p.getRh());
        }
    }

    private static void buscarPaciente() {
        System.out.println("\n--- Buscar paciente por id ---");
        int id = leerEntero("Ingrese el id del paciente: ");
        Paciente p = controlador.buscarPorId(id);
        if (p != null) {
            System.out.println(p);
        }
    }

    private static void actualizarPaciente() {
        System.out.println("\n--- Actualizar paciente ---");
        int id = leerEntero("Ingrese el id del paciente a actualizar: ");

        Paciente existente = controlador.buscarPorId(id);
        if (existente == null) {
            return;
        }

        System.out.println("Datos actuales:");
        System.out.println(existente);

        Paciente p = pedirDatosPaciente(id);
        String mensaje = controlador.actualizar(p);
        System.out.println(mensaje);
    }

    private static void eliminarPaciente() {
        System.out.println("\n--- Eliminar paciente ---");
        int id = leerEntero("Ingrese el id del paciente a eliminar: ");

        Paciente p = controlador.buscarPorId(id);
        if (p == null) {
            return;
        }

        System.out.print("¿Está seguro de eliminar a " + p.getNombres() + " " + p.getApellidos() + "? (s/n): ");
        String confirmacion = scanner.nextLine().trim().toLowerCase();
        if (!confirmacion.equals("s")) {
            System.out.println("Eliminación cancelada.");
            return;
        }

        String mensaje = controlador.eliminar(id);
        System.out.println(mensaje);
    }

    private static Paciente pedirDatosPaciente(int id) {
        Paciente p = new Paciente();
        p.setId(id);

        System.out.print("Nombres: ");
        p.setNombres(scanner.nextLine());

        System.out.print("Apellidos: ");
        p.setApellidos(scanner.nextLine());

        p.setFechaNacimiento(leerFecha("Fecha de nacimiento (yyyy-MM-dd): "));

        System.out.print("Cédula: ");
        p.setCedula(scanner.nextLine());

        System.out.print("Teléfono: ");
        p.setTelefono(scanner.nextLine());

        System.out.print("Correo (opcional): ");
        p.setCorreo(scanner.nextLine());

        String sexo = leerSexo("Sexo (M/F/Otro): ");
        p.setSexo(controlador.normalizarSexo(sexo));

        System.out.print("EPS: ");
        p.setEps(scanner.nextLine());

        System.out.print("RH: ");
        p.setRh(scanner.nextLine());

        return p;
    }

    private static int leerEntero(String mensaje) {
        while (true) {
            System.out.print(mensaje);
            try {
                return Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Error: debe ingresar un número entero válido.");
            }
        }
    }

    private static LocalDate leerFecha(String mensaje) {
        while (true) {
            System.out.print(mensaje);
            String entrada = scanner.nextLine().trim();
            try {
                return LocalDate.parse(entrada);
            } catch (DateTimeParseException e) {
                System.out.println("Error: la fecha debe tener el formato yyyy-MM-dd (ej. 1990-05-15).");
            }
        }
    }

    private static String leerSexo(String mensaje) {
        while (true) {
            System.out.print(mensaje);
            String entrada = scanner.nextLine().trim();
            if (controlador.normalizarSexo(entrada) != null) {
                return entrada;
            }
            System.out.println("Error: el sexo debe ser M, F u Otro.");
        }
    }

    private static void pausa() {
        System.out.print("Presione Enter para continuar...");
        scanner.nextLine();
    }

    private static String ajustar(String texto, int ancho) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        if (texto.length() > ancho) {
            return texto.substring(0, ancho - 3) + "...";
        }
        return texto;
    }
}