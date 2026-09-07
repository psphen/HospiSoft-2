# Módulo de Pacientes — Hospisoft

Módulo funcional CRUD (Crear, Leer, Actualizar, Eliminar) para la gestión de pacientes del sistema **Hospisoft**. Es un único proyecto Java que evoluciona con las evidencias del SENA:

- **AA2-EV01** — Aplicación de consola en Java con **JDBC** puro.
- **AA2-EV02** — Módulo web con **Servlets + JSP** (formularios HTML, métodos GET y POST).

Ambas capas usan el mismo modelo, DAO y conexión a la base de datos **MySQL** (`hospisoft`, tabla `paciente`).

## Requisitos

- **Java 21** (JDK con `javac` disponible en el PATH).
- **MySQL** corriendo en `localhost:3306` con la base de datos `hospisoft` y la tabla `paciente` (usuario `root`, sin contraseña).
- **Tomcat 8.5** en el puerto 8081 para la parte web (conector HTTP configurado en `C:\xampp\tomcat\conf\server.xml`).
- Conector **MySQL Connector/J 8.0.33** ubicado en `web\WEB-INF\lib\mysql-connector-j-8.0.33.jar` (ya incluido).

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

## Compilar y desplegar (todo en uno)

Desde la carpeta `modulo-pacientes`:

```bat
build.bat
```

El script compila **todas** las clases (consola + servlets), copia las clases a `web\WEB-INF\classes` y despliega la aplicación en Tomcat.

## Ejecutar

### Consola (AA2-EV01)

```bash
java -cp "build\classes;web\WEB-INF\lib\mysql-connector-j-8.0.33.jar" com.hospisoft.Main
```

### Web (AA2-EV02)

Con Tomcat corriendo, abrir en el navegador:

```
http://localhost:8081/modulo-pacientes-web/pacientes
```

## Probar la conexión (opcional)

```bash
java -cp "build\classes;web\WEB-INF\lib\mysql-connector-j-8.0.33.jar" com.hospisoft.util.ConexionDB
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
├── build.bat                          (compila y despliega todo)
├── lib/
│   └── mysql-connector-j-8.0.33.jar
├── src/
│   └── com/hospisoft/
│       ├── Main.java                  (menú de consola — AA2-EV01)
│       ├── controller/
│       │   └── PacienteController.java (validación y control)
│       ├── dao/
│       │   └── PacienteDAO.java       (CRUD con PreparedStatement)
│       ├── model/
│       │   └── Paciente.java          (POJO)
│       ├── servlet/
│       │   └── PacienteServlet.java   (servlet web — AA2-EV02)
│       └── util/
│           └── ConexionDB.java        (conexión JDBC)
└── web/                               (aplicación web — AA2-EV02)
    ├── index.jsp
    ├── listarPacientes.jsp
    ├── formPaciente.jsp
    └── WEB-INF/
        ├── web.xml
        ├── classes/                   (clases compiladas)
        └── lib/mysql-connector-j-8.0.33.jar
```