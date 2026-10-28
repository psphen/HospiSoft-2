-- ============================================================================
-- HospiSoft - PostgreSQL schema and seed data (Supabase)
-- ============================================================================
--
-- HOW TO RUN
--   Open the Supabase Dashboard -> SQL Editor -> New query, paste this whole file
--   and run it. The desktop application never creates tables: it only checks that
--   the 7 tables below exist in schema public and reports which ones are missing.
--
-- IDEMPOTENCY
--   Tables use CREATE TABLE IF NOT EXISTS, seeds use ON CONFLICT DO NOTHING on
--   natural keys (username, documento, codigo). Appointments and clinical records
--   are seeded only when the referenced rows are present and not already seeded.
--
-- TEST CREDENTIALS (plaintext, for the seed below)
--   username    password        rol        linked to
--   admin       Admin123*       ADMIN      -
--   dr.garcia   Medico123*      MEDICO     medico documento 1001001001
--   dra.lopez   Medico123*      MEDICO     medico documento 1001001002
--   dr.ruiz     Medico123*      MEDICO     medico documento 1001001003
--   paciente1   Paciente123*    PACIENTE   paciente documento 2002002001
--   paciente2   Paciente123*    PACIENTE   paciente documento 2002002002
--
--   Hashes were generated with com.hospisoft.security.PasswordHasher (SchemaSeedTest
--   verifies them against the implementation on every build)
--   (PBKDF2WithHmacSHA256, 600000 iterations, 16-byte salt, 256-bit key, Base64).
--
-- ROW LEVEL SECURITY - KNOWN TECHNICAL DEBT
--   RLS is enabled on every table and example policies are documented below.
--   The desktop application connects through the session pooler as the
--   "postgres" role, which OWNS these tables. Table owners bypass RLS unless
--   FORCE ROW LEVEL SECURITY is set, and we deliberately do NOT set it: the
--   application enforces permissions in Java (service layer) and a dedicated
--   least-privilege database role is left for a later phase. The policies below
--   therefore document the intended model; they are not enforced for the app.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS usuario (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    salt          VARCHAR(50)  NOT NULL,
    rol           VARCHAR(10)  NOT NULL CHECK (rol IN ('ADMIN', 'MEDICO', 'PACIENTE')),
    activo        BOOLEAN      NOT NULL DEFAULT TRUE,
    id_referencia BIGINT       NULL,     -- medico.id or paciente.id depending on rol; NULL for ADMIN
    CONSTRAINT usuario_referencia_por_rol CHECK ((rol = 'ADMIN') = (id_referencia IS NULL))
);

CREATE TABLE IF NOT EXISTS medico (
    id           BIGSERIAL PRIMARY KEY,
    documento    VARCHAR(20)  NOT NULL UNIQUE,
    nombres      VARCHAR(80)  NOT NULL,
    apellidos    VARCHAR(80)  NOT NULL,
    especialidad VARCHAR(80)  NOT NULL,
    telefono     VARCHAR(20),
    correo       VARCHAR(120),
    activo       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS paciente (
    id               BIGSERIAL PRIMARY KEY,
    documento        VARCHAR(20)  NOT NULL UNIQUE,
    nombres          VARCHAR(80)  NOT NULL,
    apellidos        VARCHAR(80)  NOT NULL,
    fecha_nacimiento DATE         NOT NULL,
    sexo             VARCHAR(5)   NOT NULL CHECK (sexo IN ('M', 'F', 'OTRO')),
    telefono         VARCHAR(20),
    correo           VARCHAR(120),
    direccion        VARCHAR(160),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS cita (
    id                    BIGSERIAL PRIMARY KEY,
    id_medico             BIGINT      NOT NULL REFERENCES medico (id)   ON DELETE RESTRICT,
    id_paciente           BIGINT      NOT NULL REFERENCES paciente (id) ON DELETE RESTRICT,
    fecha_hora_programada TIMESTAMPTZ NOT NULL,
    motivo                VARCHAR(200),
    estado                VARCHAR(12) NOT NULL DEFAULT 'PROGRAMADA'
                          CHECK (estado IN ('PROGRAMADA', 'EN_CURSO', 'FINALIZADA', 'CANCELADA')),
    hora_inicio_real      TIMESTAMPTZ NULL,
    hora_fin_real         TIMESTAMPTZ NULL,
    observaciones         TEXT,
    CONSTRAINT cita_horas_reales CHECK (hora_fin_real IS NULL OR hora_inicio_real IS NULL OR hora_fin_real >= hora_inicio_real)
);

CREATE TABLE IF NOT EXISTS medicamento (
    id           BIGSERIAL PRIMARY KEY,
    codigo       VARCHAR(20)  NOT NULL UNIQUE,
    nombre       VARCHAR(100) NOT NULL,
    descripcion  VARCHAR(200),
    presentacion VARCHAR(80),
    stock        INT          NOT NULL DEFAULT 0 CHECK (stock >= 0),
    stock_minimo INT          NOT NULL DEFAULT 0 CHECK (stock_minimo >= 0),
    activo       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS historia_clinica (
    id          BIGSERIAL PRIMARY KEY,
    id_cita     BIGINT      NOT NULL UNIQUE REFERENCES cita (id)     ON DELETE RESTRICT,
    id_paciente BIGINT      NOT NULL        REFERENCES paciente (id) ON DELETE RESTRICT,
    id_medico   BIGINT      NOT NULL        REFERENCES medico (id)   ON DELETE RESTRICT,
    fecha       TIMESTAMPTZ NOT NULL DEFAULT now(),
    diagnostico TEXT        NOT NULL,
    tratamiento TEXT,
    notas       TEXT
);

CREATE TABLE IF NOT EXISTS receta (
    id                        BIGSERIAL PRIMARY KEY,
    id_historia               BIGINT  NOT NULL REFERENCES historia_clinica (id) ON DELETE RESTRICT,
    id_medicamento            BIGINT  NOT NULL REFERENCES medicamento (id)      ON DELETE RESTRICT,
    cantidad                  INT     NOT NULL CHECK (cantidad > 0),
    dosis                     VARCHAR(100),
    duracion                  VARCHAR(100),
    entregado                 BOOLEAN NOT NULL DEFAULT FALSE,
    observacion_no_disponible VARCHAR(200)
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_cita_medico_fecha ON cita (id_medico, fecha_hora_programada);
CREATE INDEX IF NOT EXISTS idx_cita_paciente     ON cita (id_paciente);
CREATE INDEX IF NOT EXISTS idx_receta_historia   ON receta (id_historia);

-- Defense in depth for the service rule "one attention in progress per medico":
-- the partial unique index makes a second EN_CURSO cita impossible even under a race.
CREATE UNIQUE INDEX IF NOT EXISTS cita_un_en_curso_por_medico ON cita (id_medico) WHERE estado = 'EN_CURSO';

-- ----------------------------------------------------------------------------
-- Immutability of historia_clinica
-- A clinical record is a legal document: once written it can be neither changed
-- nor removed. The trigger below rejects any UPDATE or DELETE at the database
-- level, so no application bug or manual query can rewrite history.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION historia_clinica_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'historia_clinica is immutable: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_historia_clinica_immutable ON historia_clinica;
CREATE TRIGGER trg_historia_clinica_immutable
    BEFORE UPDATE OR DELETE ON historia_clinica
    FOR EACH ROW EXECUTE FUNCTION historia_clinica_immutable();

-- TRUNCATE does not fire row-level triggers: block it with a statement-level trigger.
DROP TRIGGER IF EXISTS trg_historia_clinica_no_truncate ON historia_clinica;
CREATE TRIGGER trg_historia_clinica_no_truncate
    BEFORE TRUNCATE ON historia_clinica
    FOR EACH STATEMENT EXECUTE FUNCTION historia_clinica_immutable();

-- ----------------------------------------------------------------------------
-- Immutability of receta
-- A prescription is part of the clinical record: once written it can be neither
-- changed nor removed, exactly like historia_clinica. Dispensing decisions are
-- taken when the attention is closed and stored with the row.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION receta_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'receta is immutable: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_receta_immutable ON receta;
CREATE TRIGGER trg_receta_immutable
    BEFORE UPDATE OR DELETE ON receta
    FOR EACH ROW EXECUTE FUNCTION receta_immutable();

-- TRUNCATE does not fire row-level triggers: block it with a statement-level trigger.
DROP TRIGGER IF EXISTS trg_receta_no_truncate ON receta;
CREATE TRIGGER trg_receta_no_truncate
    BEFORE TRUNCATE ON receta
    FOR EACH STATEMENT EXECUTE FUNCTION receta_immutable();

-- ----------------------------------------------------------------------------
-- Row Level Security
-- Enabled on every table. See the header: the app connects as the table owner
-- (postgres) and therefore bypasses these policies. They document the intended
-- model for a future least-privilege role. The example uses a session setting
-- (app.user_id / app.rol) that the application would set per connection with
-- SET LOCAL after authenticating the user, e.g.:
--     SET LOCAL app.user_id = '42'; SET LOCAL app.rol = 'MEDICO';
-- ----------------------------------------------------------------------------

ALTER TABLE usuario          ENABLE ROW LEVEL SECURITY;
ALTER TABLE medico           ENABLE ROW LEVEL SECURITY;
ALTER TABLE paciente         ENABLE ROW LEVEL SECURITY;
ALTER TABLE cita             ENABLE ROW LEVEL SECURITY;
ALTER TABLE medicamento      ENABLE ROW LEVEL SECURITY;
ALTER TABLE historia_clinica ENABLE ROW LEVEL SECURITY;
ALTER TABLE receta           ENABLE ROW LEVEL SECURITY;

-- The policies below are granted TO authenticated, the Supabase role used by PostgREST/GoTrue
-- sessions; they require that role to exist (it does in every Supabase project).
-- medico, paciente, medicamento and receta have RLS enabled but no policy: for any
-- non-owner role they are default-deny by design until a least-privilege role is defined.
--
-- Example policies (documentation of the intended model, not enforced for postgres):

-- A user can read only its own usuario row.
DROP POLICY IF EXISTS usuario_self_read ON usuario;
CREATE POLICY usuario_self_read ON usuario
    FOR SELECT TO authenticated
    USING (id = NULLIF(current_setting('app.user_id', true), '')::BIGINT);

-- Medicos and admins read every appointment; a paciente reads only its own.
DROP POLICY IF EXISTS cita_read_by_role ON cita;
CREATE POLICY cita_read_by_role ON cita
    FOR SELECT TO authenticated
    USING (
        current_setting('app.rol', true) IN ('ADMIN', 'MEDICO')
        OR id_paciente = (
            SELECT u.id_referencia FROM usuario u
            WHERE u.id = NULLIF(current_setting('app.user_id', true), '')::BIGINT
        )
    );

-- Only the attending medico can insert a clinical record for its own appointment.
DROP POLICY IF EXISTS historia_insert_by_medico ON historia_clinica;
CREATE POLICY historia_insert_by_medico ON historia_clinica
    FOR INSERT TO authenticated
    WITH CHECK (
        current_setting('app.rol', true) = 'MEDICO'
        AND id_medico = (
            SELECT u.id_referencia FROM usuario u
            WHERE u.id = NULLIF(current_setting('app.user_id', true), '')::BIGINT
        )
    );

-- ----------------------------------------------------------------------------
-- Seed: medicos
-- ----------------------------------------------------------------------------

INSERT INTO medico (documento, nombres, apellidos, especialidad, telefono, correo, activo) VALUES
    ('1001001001', 'Carlos Andres', 'Garcia Perez',  'Medicina General', '3101234567', 'carlos.garcia@hospisoft.co', TRUE),
    ('1001001002', 'Laura Maria',   'Lopez Rojas',   'Pediatría',        '3112345678', 'laura.lopez@hospisoft.co',   TRUE),
    ('1001001003', 'Jorge Enrique', 'Ruiz Castro',   'Cardiología',      '3123456789', 'jorge.ruiz@hospisoft.co',    TRUE)
ON CONFLICT (documento) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Seed: pacientes
-- ----------------------------------------------------------------------------

INSERT INTO paciente (documento, nombres, apellidos, fecha_nacimiento, sexo, telefono, correo, direccion, activo) VALUES
    ('2002002001', 'Maria Fernanda', 'Torres Gomez',    DATE '1990-05-14', 'F',    '3201112233', 'maria.torres@correo.co',   'Calle 45 # 12-30, Bogota',     TRUE),
    ('2002002002', 'Juan Pablo',     'Martinez Diaz',   DATE '1985-11-02', 'M',    '3212223344', 'juan.martinez@correo.co',  'Carrera 7 # 80-15, Bogota',    TRUE),
    ('2002002003', 'Ana Lucia',      'Ramirez Ortiz',   DATE '2015-03-21', 'F',    '3223334455', 'ana.ramirez@correo.co',    'Calle 100 # 20-10, Medellin',  TRUE),
    ('2002002004', 'Pedro Antonio',  'Sanchez Mora',    DATE '1958-08-09', 'M',    '3234445566', 'pedro.sanchez@correo.co',  'Avenida 68 # 30-50, Cali',     TRUE),
    ('2002002005', 'Camila Andrea',  'Herrera Vargas',  DATE '2001-12-30', 'OTRO', '3245556677', 'camila.herrera@correo.co', 'Calle 10 # 5-20, Barranquilla', FALSE)
ON CONFLICT (documento) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Seed: usuarios (id_referencia resolved by documento so ids stay consistent)
-- ----------------------------------------------------------------------------

INSERT INTO usuario (username, password_hash, salt, rol, activo, id_referencia) VALUES
    ('admin',     '1kzTsxIYr9IaS4SsDTNMFSl7zbmC5IHIy51jvmzQUXU=', 'iuG3+zLPEcWHPhpM2+ih4Q==', 'ADMIN',    TRUE, NULL),
    ('dr.garcia', 'kr2zp3VPz72CsKlSjPsDheauyGeuwJjcP/s8ohZDMEg=', 'U8S9TioGYtSLuH2HFTm6jw==', 'MEDICO',   TRUE, (SELECT id FROM medico   WHERE documento = '1001001001')),
    ('dra.lopez', '5DoI1BagvNHH8Nkvo13b+c6i5JrmEKgs7ccFjvRPcs4=', 'ScGmn2e9aIDK53+uwIoouw==', 'MEDICO',   TRUE, (SELECT id FROM medico   WHERE documento = '1001001002')),
    ('dr.ruiz',   'gNiChS1AAl5Nyp3CKV6oaN+x0xM0fJJf7ErU7GqkVdE=', '0c5UyOdKVWBkf3/NGitstg==', 'MEDICO',   TRUE, (SELECT id FROM medico   WHERE documento = '1001001003')),
    ('paciente1', 'CzkZ2cMu019AvSTFYQi7U6NvGrprGpZqwKQStBMXrTQ=', 'sqWRJGe0OXo/q1cCYs070A==', 'PACIENTE', TRUE, (SELECT id FROM paciente WHERE documento = '2002002001')),
    ('paciente2', 'h99UZ+THpE84bA9c8fEynu8q5Zs8JJbHNF7glG32iOA=', 'mqlquBwJ4IdH/isT44bwZw==', 'PACIENTE', TRUE, (SELECT id FROM paciente WHERE documento = '2002002002'))
ON CONFLICT (username) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Seed: medicamentos (several at or below minimum stock, one out of stock)
-- ----------------------------------------------------------------------------

INSERT INTO medicamento (codigo, nombre, descripcion, presentacion, stock, stock_minimo, activo) VALUES
    ('MED-001', 'Acetaminofen 500 mg',   'Analgesico y antipiretico',          'Caja x 20 tabletas',   150, 30, TRUE),
    ('MED-002', 'Ibuprofeno 400 mg',     'Antiinflamatorio no esteroideo',     'Caja x 10 tabletas',    25, 30, TRUE),  -- below minimum
    ('MED-003', 'Amoxicilina 500 mg',    'Antibiotico de amplio espectro',     'Caja x 21 capsulas',    80, 20, TRUE),
    ('MED-004', 'Omeprazol 20 mg',       'Inhibidor de la bomba de protones',  'Caja x 14 capsulas',    20, 20, TRUE),  -- equal to minimum
    ('MED-005', 'Loratadina 10 mg',      'Antihistaminico',                    'Caja x 10 tabletas',     0, 15, TRUE),  -- out of stock
    ('MED-006', 'Metformina 850 mg',     'Antidiabetico oral',                 'Caja x 30 tabletas',    60, 25, TRUE),
    ('MED-007', 'Losartan 50 mg',        'Antihipertensivo',                   'Caja x 30 tabletas',    45, 25, TRUE),
    ('MED-008', 'Salbutamol inhalador',  'Broncodilatador',                    'Inhalador 100 mcg',     10, 12, TRUE),  -- below minimum
    ('MED-009', 'Suero oral',            'Sales de rehidratacion oral',        'Sobre x 20 g',         200, 50, TRUE),
    ('MED-010', 'Diclofenaco gel 1%',    'Antiinflamatorio topico',            'Tubo x 60 g',           35, 10, FALSE)
ON CONFLICT (codigo) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Seed: citas (offsets relative to the current hour so the sample stays meaningful)
-- Base h = date_trunc('hour', now()); every cita sits on the 30-minute grid and no two
-- non-cancelled citas of the same medico overlap (SchemaSeedTest checks this).
-- Guarded by a marker motivo so re-running the script does not duplicate them.
-- ----------------------------------------------------------------------------

INSERT INTO cita (id_medico, id_paciente, fecha_hora_programada, motivo, estado, hora_inicio_real, hora_fin_real, observaciones)
SELECT m.id, p.id, b.h + s.offs, s.motivo, s.estado, b.h + s.inicio, b.h + s.fin, s.observaciones
FROM (SELECT date_trunc('hour', now()) AS h) AS b
CROSS JOIN (VALUES
    -- documento medico, documento paciente, offset, motivo, estado, inicio offset, fin offset, observaciones
    ('1001001001', '2002002001', interval '2 hours',          '[seed] Control general',          'PROGRAMADA', NULL::interval,                NULL::interval,                NULL),
    ('1001001001', '2002002001', interval '-180 minutes',     '[seed] Gripe y malestar general', 'FINALIZADA', interval '-180 minutes',       interval '-155 minutes', 'Paciente atendido sin novedad'),
    ('1001001001', '2002002002', interval '-30 minutes',      '[seed] Dolor de cabeza',          'EN_CURSO',   interval '-20 minutes',        NULL,                          NULL),
    ('1001001003', '2002002004', interval '-10080 minutes',   '[seed] Hipertension',             'FINALIZADA', interval '-10090 minutes',     interval '-10050 minutes', 'Se ajusta dosis'),
    ('1001001002', '2002002003', interval '2 days',           '[seed] Control de crecimiento',   'PROGRAMADA', NULL,                          NULL,                          NULL),
    ('1001001003', '2002002004', interval '3 days 2 hours',   '[seed] Chequeo cardiologico',     'PROGRAMADA', NULL,                          NULL,                          NULL),
    ('1001001002', '2002002003', interval '-1440 minutes',    '[seed] Vacunacion',               'CANCELADA',  NULL,                          NULL,                          'Cancelada por el acudiente'),
    ('1001001001', '2002002002', interval '1 day',            '[seed] Primera consulta',         'CANCELADA',  NULL,                          NULL,                          'Cancelada por el paciente')
) AS s (doc_medico, doc_paciente, offs, motivo, estado, inicio, fin, observaciones)
JOIN medico   m ON m.documento = s.doc_medico
JOIN paciente p ON p.documento = s.doc_paciente
WHERE NOT EXISTS (SELECT 1 FROM cita WHERE motivo LIKE '[seed]%');

-- ----------------------------------------------------------------------------
-- Seed: historias clinicas for the two FINALIZADA citas
-- ----------------------------------------------------------------------------

INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, fecha, diagnostico, tratamiento, notas)
SELECT c.id, c.id_paciente, c.id_medico, c.hora_fin_real,
       'Infeccion respiratoria aguda leve',
       'Reposo, hidratacion y acetaminofen cada 8 horas por 3 dias',
       'Sin signos de alarma. Control si persiste la fiebre.'
FROM cita c
WHERE c.motivo = '[seed] Gripe y malestar general' AND c.estado = 'FINALIZADA'
  AND NOT EXISTS (SELECT 1 FROM historia_clinica h WHERE h.id_cita = c.id);

INSERT INTO historia_clinica (id_cita, id_paciente, id_medico, fecha, diagnostico, tratamiento, notas)
SELECT c.id, c.id_paciente, c.id_medico, c.hora_fin_real,
       'Hipertension arterial estadio 1',
       'Losartan 50 mg una vez al dia, dieta baja en sodio',
       'Se solicita control en 30 dias con toma de presion diaria.'
FROM cita c
WHERE c.motivo = '[seed] Hipertension' AND c.estado = 'FINALIZADA'
  AND NOT EXISTS (SELECT 1 FROM historia_clinica h WHERE h.id_cita = c.id);

-- ----------------------------------------------------------------------------
-- Seed: recetas (one not delivered because the medicamento is out of stock)
-- ----------------------------------------------------------------------------

INSERT INTO receta (id_historia, id_medicamento, cantidad, dosis, duracion, entregado, observacion_no_disponible)
SELECT h.id, md.id, 9, '1 tableta cada 8 horas', '3 dias', TRUE, NULL
FROM historia_clinica h
JOIN cita c ON c.id = h.id_cita AND c.motivo = '[seed] Gripe y malestar general'
JOIN medicamento md ON md.codigo = 'MED-001'
WHERE NOT EXISTS (SELECT 1 FROM receta r WHERE r.id_historia = h.id AND r.id_medicamento = md.id);

INSERT INTO receta (id_historia, id_medicamento, cantidad, dosis, duracion, entregado, observacion_no_disponible)
SELECT h.id, md.id, 10, '1 tableta cada 24 horas', '10 dias', FALSE, 'Sin existencias en farmacia; comprar en drogueria externa'
FROM historia_clinica h
JOIN cita c ON c.id = h.id_cita AND c.motivo = '[seed] Gripe y malestar general'
JOIN medicamento md ON md.codigo = 'MED-005'
WHERE NOT EXISTS (SELECT 1 FROM receta r WHERE r.id_historia = h.id AND r.id_medicamento = md.id);

INSERT INTO receta (id_historia, id_medicamento, cantidad, dosis, duracion, entregado, observacion_no_disponible)
SELECT h.id, md.id, 30, '1 tableta cada 24 horas', '30 dias', TRUE, NULL
FROM historia_clinica h
JOIN cita c ON c.id = h.id_cita AND c.motivo = '[seed] Hipertension'
JOIN medicamento md ON md.codigo = 'MED-007'
WHERE NOT EXISTS (SELECT 1 FROM receta r WHERE r.id_historia = h.id AND r.id_medicamento = md.id);
