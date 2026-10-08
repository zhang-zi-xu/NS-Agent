package com.nongxin.repository.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Function;

/** One datasource allows a save to pin images and write messages on the same connection. */
final class JdbcTransactionSupport {
    private final Object resource;
    private final TransactionTemplate transaction;

    JdbcTransactionSupport(JdbcTemplate jdbc) {
        var source = Objects.requireNonNull(jdbc.getDataSource());
        resource = source;
        var manager = new DataSourceTransactionManager(source);
        manager.setRollbackOnCommitFailure(true);
        transaction = new TransactionTemplate(manager);
    }

    boolean hasBoundConnection() {
        return TransactionSynchronizationManager.hasResource(resource);
    }

    <T> T execute(Function<TransactionStatus, T> operation) {
        return transaction.execute(operation::apply);
    }
}
