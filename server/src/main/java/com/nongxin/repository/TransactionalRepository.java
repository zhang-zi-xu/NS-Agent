package com.nongxin.repository;

import org.springframework.transaction.TransactionStatus;

import java.util.function.Function;

/** Connection-bound transactions, shared with attachment confirmation and file recovery hooks. */
public interface TransactionalRepository {
    boolean hasBoundConnection();

    <T> T inTransaction(Function<TransactionStatus, T> operation);
}
