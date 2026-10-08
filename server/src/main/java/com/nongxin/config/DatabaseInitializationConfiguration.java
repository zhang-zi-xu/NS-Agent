package com.nongxin.config;

import com.nongxin.bootstrap.NongxinDatabaseInitializer;
import com.nongxin.bootstrap.SchemaMigrationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * A single Boot-recognized initializer owns both fresh schema creation and backed-up legacy
 * migration.
 */
@Configuration(proxyBeanMethods = false)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class DatabaseInitializationConfiguration {
    @Bean
    public NongxinDatabaseInitializer dataSourceScriptDatabaseInitializer(
            DataSource source,
            @Value("${spring.datasource.url:}") String url,
            @Value("${nongxin.backup-dir:}") String backupDirectory) {
        return new NongxinDatabaseInitializer(source, url, backupDirectory);
    }

    @Bean
    public SchemaMigrationService schemaMigrationService(NongxinDatabaseInitializer initializer) {
        return initializer.migration();
    }
}
