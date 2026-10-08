package com.nongxin.bootstrap;

import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** One Boot-recognized initializer coordinates fresh schema creation and backed-up migrations. */
public final class NongxinDatabaseInitializer extends SqlDataSourceScriptDatabaseInitializer {
    private final SchemaMigrationService migration;
    private boolean initialized;

    public NongxinDatabaseInitializer(DataSource source, String url, String backupDirectory) {
        // The auto-configured JdbcTemplate depends on this initializer. Its private template
        // must be constructed directly from the datasource to avoid a dependency cycle.
        this(source, new SchemaMigrationService(new JdbcTemplate(source), url, backupDirectory));
    }

    private NongxinDatabaseInitializer(DataSource source, SchemaMigrationService migration) {
        super(source, new DatabaseInitializationSettings());
        this.migration = migration;
    }

    public SchemaMigrationService migration() {
        return migration;
    }

    @Override
    public synchronized boolean initializeDatabase() {
        if (initialized) return false;
        migration.initializeApplicationDatabase();
        initialized = true;
        return true;
    }
}
