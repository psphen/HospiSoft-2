# Módulo de Pacientes — Hospisoft

Módulo funcional CRUD (Crear, Leer, Actualizar, Eliminar) para la gestión de pacientes del sistema **Hospisoft**. Es una aplicación de consola en Java que se conecta a una base de datos **MySQL** mediante **JDBC** y opera sobre la tabla `paciente`.

Evidencia de aprendizaje **AA2-EV01** — SENA.

## Requisitos

- **Java 21** (JDK con `javac` disponible en el PATH).
- **MySQL** corriendo en `localhost:3306` con la base de datos `hospisoft` y la tabla `paciente`.
- Conector **MySQL Connector/J 8.0.33** ubicado en `lib\mysql-connector-j-8.0.33.jar` (ya incluido en el proyecto).

### Esquema de la tabla

```sql
paciente (
  id INT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  nombres VARCHAR(100) NOT NULL,
  apellidos VARCHAR(100) NOT NULL,
  fecha_nacimiento DATE NOT NULL,
  cedula VARCHAR(20) NOT NULL UNIQUE,
  telefono VARCHAR(20) NOT NULL,
  correo VARCHAR(100) NULL,
  sexo ENUM('M','F','Otro') NOT NULL,
  eps VARCHAR(100) NOT NULL,
  rh VARCHAR(5) NOT NULL
)
```

## Compilar

Desde la carpeta `modulo-pacientes`:

```bash
javac -encoding UTF-8 -sourcepath src -cp "lib/mysql-connector-j-8.0.33.jar" -d out src/com/hospisoft/Main.java
```

También se pueden compilar todos los fuentes de forma explícita:

```bash
javac -encoding UTF-8 -cp "lib/mysql-connector-j-8.0.33.jar" -d out src/com/hospisoft/model/Paciente.java src/com/hospisoft/util/ConexionDB.java src/com/hospisoft/dao/PacienteDAO.java src/com/hospisoft/controller/PacienteController.java src/com/hospisoft/Main.java
```

## Ejecutar

```bash
java -cp "out;lib/mysql-connector-j-8.0.33.jar" com.hospisoft.Main
```

## Probar la conexión (opcional)

```bash
java -cp "out;lib/mysql-connector-j-8.0.33.jar" com.hospisoft.util.ConexionDB
```

Debe imprimir `Conexión exitosa`.

## Nota sobre acentos en la consola de Windows

Si los caracteres con tilde se ven mal en la consola de Windows (cmd o PowerShell), ejecute antes:

```bash
chcp 65001
```

## Estructura del proyecto

```
modulo-pacientes/
├── lib/
│   └── mysql-connector-j-8.0.33.jar
├── src/
│   └── com/hospisoft/
│       ├── Main.java                 (menú de consola)
│       ├── controller/
│       │   └── PacienteController.java (validación y control)
│       ├── dao/
│       │   └── PacienteDAO.java      (CRUD con PreparedStatement)
│       ├── model/
│       │   └── Paciente.java         (POJO)
│       └── util/
│           └── ConexionDB.java       (conexión JDBC)
└── out/                              (clases compiladas, generada por javac)
```