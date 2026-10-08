package com.nongxin.repository;

/** Daily demo usage. This repository handles no provider credentials. */
public interface ApiUsageRepository {
    enum Reservation {
        GRANTED,
        GLOBAL_LIMIT,
        IP_LIMIT
    }

    Reservation reserve(String day, String ip, int globalLimit, int ipLimit);

    int count(String day, String scope, String scopeKey);
}
