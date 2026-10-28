# HospiSoft

Aplicación de escritorio para la gestión de un consultorio médico: usuarios, médicos, pacientes, citas, atención, historias clínicas, recetas y medicamentos.

- **Interfaz**: JavaFX `WebView` que muestra las páginas HTML del proyecto (Bootstrap 5 y Font Awesome empaquetados dentro del jar). Sin servidor web y sin navegador externo.
- **Lógica de negocio**: Java 21. El JavaScript de las páginas solo llama al puente y actualiza el DOM.
- **Base de datos**: PostgreSQL en Supabase por JDBC, siempre con `PreparedStatement`.

---

## 1. Requisitos

| Requisito | Detalle |
| --- | --- |
| JDK 21 | Oracle JDK u OpenJDK 21 (`java`, `javac` y `jpackage` vienen incluidos). |
| Maven | **No hace falta instalarlo**: el proyecto trae el Maven Wrapper (`mvnw.cmd` / `mvnw`), que descarga Maven 3.9.9 la primera vez y verifica su checksum. |
| Supabase | Un proyecto con la base `postgres`. |

Si `JAVA_HOME` no está definido (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
```

---

## 2. Base de datos

1. Abra el proyecto en Supabase → **SQL Editor** → *New query*.
2. Pegue el contenido completo de [`db/schema.sql`](db/schema.sql) y ejecútelo. El script es idempotente: crea las 7 tablas, los índices, los triggers de inmutabilidad, habilita RLS y carga los datos de prueba.
3. La aplicación **nunca** crea el esquema. Al iniciar solo verifica que las tablas existan y, si faltan, lo informa en la pantalla de inicio de sesión.

**Cadena de conexión**: Supabase → *Connect* → **Session pooler** (puerto **5432**). Se ve así:

```
jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
```

- No use el **host directo** `db.<project-ref>.supabase.co`: solo responde por IPv6 y en la mayoría de redes domésticas no es alcanzable.
- No use el **transaction pooler** (puerto 6543): no admite *prepared statements*, que es justamente lo que usa toda la capa de datos.
- `sslmode=require` es obligatorio.

### Regenerar los datos de prueba

Las filas semilla llevan el marcador `[seed]` en `motivo`, así que volver a ejecutar el script no las duplica, pero tampoco refresca las fechas. Para regenerarlas hay que desactivar temporalmente los triggers de inmutabilidad (un `DELETE` directo sobre `historia_clinica` o `receta` siempre falla):

```sql
BEGIN;
ALTER TABLE receta           DISABLE TRIGGER trg_receta_immutable;
ALTER TABLE historia_clinica DISABLE TRIGGER trg_historia_clinica_immutable;

DELETE FROM receta WHERE id_historia IN (
    SELECT h.id FROM historia_clinica h JOIN cita c ON c.id = h.id_cita WHERE c.motivo LIKE '[seed]%');
DELETE FROM historia_clinica WHERE id_cita IN (SELECT id FROM cita WHERE motivo LIKE '[seed]%');
DELETE FROM cita WHERE motivo LIKE '[seed]%';

ALTER TABLE historia_clinica ENABLE TRIGGER trg_historia_clinica_immutable;
ALTER TABLE receta           ENABLE TRIGGER trg_receta_immutable;
COMMIT;
```

Después vuelva a ejecutar `db/schema.sql`. La alternativa limpia es recrear el esquema desde cero.

---

## 3. Configuración

Las credenciales viven en un archivo externo `config.properties` que **nunca** se versiona (está en `.gitignore`). Copie [`config.properties.example`](config.properties.example) y complete los valores:

```properties
db.url=jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres?sslmode=require
db.user=postgres.PROJECT_REF
db.password=CHANGE_ME
db.pool.size=3
```

El archivo se lee como UTF-8 y los valores se toman tal cual (los espacios finales cuentan); una contrabarra dentro de un valor debe escribirse doble (`pa\\ss`).

**Dónde ubicarlo según el modo de ejecución:**

| Modo | Ubicación |
| --- | --- |
| Jar ejecutable (`java -jar target\hospisoft-desktop.jar`) | Junto al jar (`target\`) o en el directorio actual. |
| Aplicación empaquetada (`HospiSoft.exe`) | Junto al ejecutable, en `target\dist\HospiSoft\`. |
| Desarrollo (`mvnw.cmd javafx:run`) | En la raíz del proyecto. |
| Cualquiera | Ruta explícita con `-Dhospisoft.config=C:\ruta\config.properties`. |

Si el archivo no existe o le faltan claves, la aplicación igual arranca y explica el problema en la pantalla de inicio de sesión (incluida la ruta exacta donde lo buscó).

---

## 4. Compilación

```powershell
.\mvnw.cmd clean package
```

Genera `target\hospisoft-desktop.jar` (jar ejecutable con todas las dependencias) y ejecuta las pruebas.

### Empaquetado nativo (opcional)

```powershell
.\mvnw.cmd clean package -Ppackage-win
```

Requiere un JDK 14 o superior, que es el que trae `jpackage`. Usa `jpackage` (incluido en el JDK) para producir `target\dist\HospiSoft\HospiSoft.exe` junto con su propio runtime de Java: el usuario final no necesita tener Java instalado. Es una *app-image*, no un instalador, así que no requiere WiX ni Inno Setup. El perfil usa `exec-maven-plugin`, que es un **plugin de compilación** (como shade, surefire o javafx-maven-plugin): las dependencias de ejecución siguen siendo únicamente JavaFX y el driver de PostgreSQL.

Dos detalles del perfil:

- El runtime se recorta con `--add-modules` (solo los módulos que la aplicación usa de verdad). Sin ese recorte la imagen pesa 197 MB; con él, 125 MB. El recorte es la parte frágil del empaquetado: si falta un módulo, la aplicación compila y arranca pero falla al usarlo (`NoClassDefFoundError`). Después de cambiar esa lista hay que abrir el ejecutable **e iniciar sesión contra la base real**, no solo verificar que abra.
- Antes de generar la imagen se borra `target\dist`, porque `jpackage` se niega a escribir sobre una carpeta de aplicación existente. Tenga en cuenta que `mvnw.cmd clean` borra todo `target`: si dejó un `config.properties` dentro de `target\dist\HospiSoft\`, también desaparece.

---

## 5. Ejecución

```powershell
# Jar ejecutable
java -jar target\hospisoft-desktop.jar

# Aplicación empaquetada
.\target\dist\HospiSoft\HospiSoft.exe

# Desde el código fuente
.\mvnw.cmd javafx:run
```

---

## 6. Credenciales de prueba

Las carga `db/schema.sql`. Son **solo para desarrollo**: cámbielas o elimínelas antes de cargar datos reales.

| Usuario | Contraseña | Rol | Vinculado a |
| --- | --- | --- | --- |
| `admin` | `Admin123*` | ADMIN | — |
| `dr.garcia` | `Medico123*` | MEDICO | Carlos Andrés García Pérez |
| `dra.lopez` | `Medico123*` | MEDICO | Laura María López Rojas |
| `dr.ruiz` | `Medico123*` | MEDICO | Jorge Enrique Ruiz Castro |
| `paciente1` | `Paciente123*` | PACIENTE | María Fernanda Torres Gómez |
| `paciente2` | `Paciente123*` | PACIENTE | Juan Pablo Martínez Díaz |

Las contraseñas se almacenan con PBKDF2WithHmacSHA256, 600 000 iteraciones, sal de 16 bytes por usuario y clave de 256 bits. Tras 5 intentos fallidos seguidos el usuario queda bloqueado 30 segundos.

---

## 7. Arquitectura

```
src/main/java/com/hospisoft/
├── ui/        Launcher (main sin JavaFX), MainApp (Stage + WebView), PageRouter, AuthGuard
├── bridge/    JsApi (lo único visible para JS), Bridge, Json, Payload, Dtos, *Methods
├── config/    AppConfig (lectura de config.properties)
├── db/        ConnectionPool (pool propio), SchemaChecker, SqlErrorClassifier, excepciones
├── security/  PasswordHasher
├── model/     Usuario, Medico, Paciente, Cita, Medicamento, HistoriaClinica, Receta, vistas y enums
├── dao/       Repositorios (interfaces) y DAOs JDBC: Usuario, Medico, Paciente, Medicamento,
│              Cita, Atencion, Dashboard
└── service/   AuthService, PageAccess, MedicoService, PacienteService, MedicamentoService,
               CitaService, AtencionService, DashboardService, AppZone, validación y excepciones
src/main/resources/web/   páginas HTML, css/, js/ y vendor/ (Bootstrap y Font Awesome)
db/schema.sql             esquema y datos de prueba para Supabase
```

Capas: `ui` → `bridge` → `service` → `dao` → `db`; `model` es compartido. Los DAOs usan tipos de excepción del paquete `service` para que un error de la base llegue a la interfaz ya traducido.

### Contrato del puente

`MainApp` inyecta en cada carga de página, como `window.app`, una instancia de `JsApi` cuya superficie pública son exactamente `call`, `login`, `logout`, `getSession`, `getAllowedPages`, `getStartupStatus`, `navigate` y `log`; después dispara el evento `app-ready`. Todos los métodos reciben y devuelven cadenas JSON y nunca lanzan excepciones hacia JS.

```json
{"ok":true,"data":...}
{"ok":false,"code":"...","message":"...","errors":{"campo":"mensaje"}}
```

Códigos: `BAD_REQUEST`, `VALIDATION` (con `errors`), `NOT_FOUND`, `CONFLICT`, `AUTH_FAILED`, `FORBIDDEN`, `DB_UNAVAILABLE`, `DB_AUTH_FAILED`, `INTERNAL_ERROR`.

Todo lo que toca la base de datos va por el camino asíncrono: `call(requestId, método, payload)` responde de inmediato, ejecuta el trabajo en un hilo propio y entrega el resultado con `window.__hospisoft.resolve(requestId, sobre)`. En JavaScript se usa `HospiSoft.appCall(método, payload)`, que devuelve una `Promise` (rechaza con código `TIMEOUT` a los 40 s). Los métodos síncronos solo sirven para datos en memoria.

| Método | Entrada | Salida (`data`) |
| --- | --- | --- |
| `login` | `{"username","password"}` | sesión `{userId, username, rol, idReferencia, displayName}` |
| `logout` / `getSession` | — | `true` / sesión o `null` |
| `getStartupStatus` | — | `{state, expectedPath, missingKeys, missingTables}` |
| `getAllowedPages` | — | páginas que el rol puede abrir |
| `navigate` | nombre de página | página cargada |
| `listMedicos` / `listPacientes` / `listMedicamentos` / `listCitas` | `{"filter":{…},"page","pageSize"}` | `{items, total, page, pageSize, totalPages}` |
| `getMedico` / `getPaciente` / `getMedicamento` / `getCita` | `{"id"}` | DTO de la entidad |
| `saveMedico` / `savePaciente` / `saveMedicamento` / `saveCita` | DTO (`id` ausente o 0 inserta) | DTO guardado |
| `deleteMedico` / `deletePaciente` / `deleteMedicamento` | `{"id"}` | `{"outcome":"DELETED"\|"DEACTIVATED"}` |
| `cancelarCita` | `{"id"}` | cita cancelada |
| `listEspecialidades` / `listMedicosSelect` / `listPacientesSelect` / `medicamentosParaRecetar` | — | listas para los selectores |
| `agendaMedico` | `{"idMedico"?,"fecha"?}` | `{idMedico, fecha, slots:[{hora, citas:[cita]}]}` |
| `iniciarAtencion` | `{"idCita"}` | cita en `EN_CURSO` |
| `finalizarAtencion` | `{"idCita","diagnostico","tratamiento","notas","observaciones","recetas":[…]}` | `{idHistoria, cita, recetas}` |
| `getHistoriaCita` / `historialPaciente` / `misRecetas` | `{"idCita"}` / `{"idPaciente","page","pageSize"}` / `{"page","pageSize"}` | historia / página de historias / página de recetas |
| `dashboard` | — | `{rol, kpis, proximasCitas, resumenHoy, atendidasPct, duracionPromedioMin}` |

---

## 8. Reglas de negocio implementadas

**Acceso y navegación**

- La guardia de navegación vive en Java: sin sesión toda página redirige a `login.html`; con sesión, `login.html` va a `index.html`; una página no permitida para el rol va a `index.html`; cualquier ubicación fuera de la carpeta `web/` del classpath se bloquea y el puente nunca se inyecta en ella *(fase 1)*.
- Permisos por página: ADMIN todas; MEDICO Dashboard, Pacientes, Citas y Medicamentos; PACIENTE Dashboard, Citas y Medicamentos. Ocultar enlaces es solo cosmético; el servicio decide *(fase 1)*.
- Un médico o paciente dado de baja no puede iniciar sesión aunque su cuenta siga activa *(fase 1)*.

**Médicos y pacientes**

- `documento` único por tabla, campos obligatorios y longitudes según el esquema; correo validado si viene *(fase 2)*.
- Borrado: si el registro tiene historial asociado se da de baja lógica (`DEACTIVATED`); si no, se elimina. Si la base rechaza el borrado por clave foránea, también se da de baja *(fase 2)*.
- Un MEDICO solo ve los pacientes que atendió, y fuera de ese alcance recibe `NOT_FOUND`, nunca `FORBIDDEN` *(fase 2)*.

**Medicamentos**

- Estado de stock derivado: `AGOTADO` si `stock = 0`, `STOCK_BAJO` si `stock <= stock_minimo`, si no `DISPONIBLE` *(fase 3)*.
- Edición con concurrencia optimista: el formulario envía el `stock` que cargó y un cambio intermedio responde `CONFLICT` *(fase 3)*.
- MEDICO y PACIENTE solo consultan; el PACIENTE ve en su lugar sus órdenes de medicamentos *(fases 3 y 5)*.

**Citas y agenda**

- Cada cita ocupa 30 minutos; la grilla de agenda va de 07:00 a 19:00 y la hora debe caer en múltiplos de 5 minutos *(fase 4)*.
- No puede haber dos citas no canceladas del mismo médico —ni del mismo paciente— que se crucen; los bloques adyacentes sí se permiten *(fase 4)*.
- La verificación, el bloqueo de las filas (médico y luego paciente, `FOR NO KEY UPDATE`) y la escritura ocurren en una sola transacción *(fase 4)*.
- Las citas nacen `PROGRAMADA`, no pueden quedar en el pasado ni a más de un año, y solo las `PROGRAMADA` se reprograman o cancelan. Nunca se eliminan físicamente *(fase 4)*.

**Atención, historia clínica y recetas**

- Solo el médico inicia y finaliza sus propias citas, y únicamente puede tener **una** atención en curso a la vez (regla de servicio reforzada por el índice único parcial `cita_un_en_curso_por_medico`) *(fase 5)*.
- El cierre es atómico: estado de la cita, historia clínica, recetas y descuento de stock se guardan o se deshacen juntos, bloqueando el médico primero y los medicamentos en orden ascendente de id *(fase 5)*.
- Una línea sin stock suficiente —o de un medicamento inactivo— queda pendiente de entrega y exige una observación *(fase 5)*.
- La historia clínica y sus recetas son inmutables: los triggers de la base rechazan `UPDATE`, `DELETE` y `TRUNCATE`, y hay una sola historia por cita *(fase 5)*.
- Un médico consulta únicamente las historias que él mismo escribió; el paciente ve su expediente completo; el administrador ve todo *(fase 5)*.

**Tablero**

- Cada cifra se calcula según el rol de la sesión, "hoy" es el día local de la máquina donde corre la aplicación (su zona horaria, no la del servidor de base de datos) y las métricas de cierre (porcentaje de atendidas y duración promedio) salen de `hora_inicio_real` y `hora_fin_real`; si no hay datos se muestra un guion *(fase 6)*.
- El porcentaje de atendidas se calcula sobre las citas que **no** fueron canceladas, y las casillas del "Resumen del Día" (atendidas, en curso, pendientes y canceladas) suman exactamente el total del día *(fase 6)*.
- El indicador de medicamentos del médico cuenta los que están **en o por debajo** de su stock mínimo, no solo los que ya lo cruzaron *(fase 6)*.

---

## 9. Pruebas

```powershell
.\mvnw.cmd clean package      # compila y ejecuta las 356 pruebas (JUnit 5)
```

9 de esas pruebas son de integración y se **omiten** salvo que existan las tres variables de entorno siguientes. Cada una corre dentro de una transacción que se revierte, y el caso que sí confirma datos los borra al terminar, así que no dejan rastro:

```powershell
$env:HOSPISOFT_TEST_DB_URL = "jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres?sslmode=require"
$env:HOSPISOFT_TEST_DB_USER = "postgres.PROJECT_REF"
$env:HOSPISOFT_TEST_DB_PASSWORD = "..."
.\mvnw.cmd test -Dtest=PostgresIntegrationTest
```

Cubren: existencia de las 7 tablas, los límites del predicado de solapamiento, los bloqueos `FOR NO KEY UPDATE`, los triggers de inmutabilidad de `historia_clinica` y `receta`, la guarda del descuento de stock, un cierre de atención completo contra el DAO real y el tablero (su SQL es el único que se arma en tiempo de ejecución, así que se ejecutan las tres variantes de alcance: global, médico y paciente).

La cobertura de integración es **parcial**: verifica que el SQL se ejecute y respete las reglas críticas, no que cada pantalla devuelva las cifras correctas con datos de producción. Eso se valida a mano contra una base real.

---

## 10. Deuda técnica conocida

- **Credenciales del propietario en el cliente**: el escritorio guarda en `config.properties` las credenciales del rol `postgres`, dueño de la base. Quien tenga ese archivo tiene control total sobre los datos. Por eso **nunca** se distribuye ni se comprime la carpeta `target\dist\HospiSoft\` con un `config.properties` adentro: cada equipo recibe la imagen vacía y pone su propio archivo al lado del ejecutable.
- **RLS eludida**: todas las tablas tienen Row Level Security habilitado y políticas de ejemplo documentadas, pero el rol `postgres` las omite por ser propietario (no se usa `FORCE ROW LEVEL SECURITY`). Los permisos se aplican en Java. Un rol de base de datos con privilegios mínimos queda pendiente.
- **Credenciales semilla**: las cuentas de `db/schema.sql` son de desarrollo; hay que cambiarlas antes de usar datos reales.
- **Sin auditoría de accesos**: no se registra quién consultó una historia clínica. La inmutabilidad protege contra modificaciones, no contra lecturas indebidas.
- **TLS**: `sslmode=require` cifra pero no valida el certificado del servidor; la opción endurecida es `sslmode=verify-full` con `sslrootcert`.
- **Natives de JavaFX solo para Windows**: el jar ejecutable incluye los binarios nativos de Windows; en otro sistema operativo hay que reconstruir. La *app-image* es igualmente específica de la plataforma donde se genera.

---

## 11. Fases del proyecto

| Fase | Contenido | Estado |
| --- | --- | --- |
| 1 | Proyecto Maven, WebView, puente JS, autenticación, esquema y datos de prueba | Completa |
| 2 | CRUD de médicos y pacientes (filtros, paginación, validación, baja lógica) | Completa |
| 3 | CRUD de medicamentos con estado de stock y edición concurrente segura | Completa |
| 4 | Citas y agenda (solapamiento, reprogramación, cancelación) | Completa |
| 5 | Atención: iniciar/finalizar, historia clínica inmutable y recetas con descuento de stock | Completa |
| 6 | Tablero con datos reales y empaquetado ejecutable (`jpackage`) | Completa |

---

## 12. Historial

La carpeta [`modulo-pacientes/`](modulo-pacientes/) conserva la evidencia anterior del SENA (AA2-EV01 y AA2-EV02): un módulo Java + JDBC + servlets/JSP sobre MySQL con CRUD de pacientes. Se mantiene sin cambios como referencia histórica; la aplicación de escritorio descrita arriba lo reemplaza.
