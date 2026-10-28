package com.hospisoft.service;

import com.hospisoft.config.AppConfig;
import com.hospisoft.db.DatabaseAuthException;
import com.hospisoft.db.DatabaseUnavailableException;
import com.hospisoft.db.SchemaChecker;
import java.util.List;

/** Real startup check: configuration, then connectivity and credentials, then required tables. */
public final class DefaultStartupProbe implements StartupProbe {

    private final AppConfig config;
    private final SchemaChecker schemaChecker;

    /** @param schemaChecker may be null when the configuration is not usable */
    public DefaultStartupProbe(AppConfig config, SchemaChecker schemaChecker) {
        this.config = config;
        this.schemaChecker = schemaChecker;
    }

    @Override
    public StartupStatus probe() {
        String expected = config.expectedPath() == null ? AppConfig.FILE_NAME : config.expectedPath().toString();
        switch (config.state()) {
            case NOT_FOUND:
                return StartupStatus.configMissing(expected);
            case READ_ERROR:
                return StartupStatus.configReadError(expected);
            case KEYS_MISSING:
                return StartupStatus.keysMissing(expected, config.missingKeys());
            default:
                break;
        }
        if (schemaChecker == null) {
            return StartupStatus.dbUnavailable();
        }
        List<String> missing;
        try {
            missing = schemaChecker.findMissing();
        } catch (DatabaseAuthException ex) {
            return StartupStatus.dbAuthFailed();
        } catch (DatabaseUnavailableException ex) {
            return StartupStatus.dbUnavailable();
        }
        if (!missing.isEmpty()) {
            return StartupStatus.tablesMissing(missing);
        }
        return StartupStatus.ok();
    }
}
