# HospiSoft

Sistema de gestión para consultorio médico: pacientes, médicos, especialidades, citas, recetas y medicamentos.

## Estructura

- `index.html`, `pacientes.html`, `medicos.html`, `citas.html`, `medicamentos.html` — interfaz web (HTML + CSS).
- `modulo-pacientes/` — módulo Java con CRUD de pacientes (JDBC) correspondiente a la evidencia AA2-EV01.

## Módulo Java (AA2-EV01)

CRUD de pacientes con Java + JDBC contra la base de datos MySQL `hospisoft`.

### Requisitos

- Java 21
- MySQL (localhost:3306, usuario `root`, sin contraseña) con la base `hospisoft`
- Conector MySQL incluido en `modulo-pacientes/lib/`

### Compilar y ejecutar

Desde `modulo-pacientes/`:

```powershell
chcp 65001
javac -encoding UTF-8 -sourcepath src -cp "lib/mysql-connector-j-8.0.33.jar" -d out src/com/hospisoft/Main.java
java -cp "out;lib/mysql-connector-j-8.0.33.jar" com.hospisoft.Main
```

Menú: 1) registrar paciente, 2) listar, 3) buscar por id, 4) actualizar, 5) eliminar, 6) salir.