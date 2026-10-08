package com.nongxin.repository.jdbc;

import com.nongxin.repository.ApiUsageRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Both scope reservations commit independently before a demo API key is returned. */
@Repository
public class JdbcApiUsageRepository implements ApiUsageRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate quotaTransaction;

    public JdbcApiUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        var manager =
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        manager.setRollbackOnCommitFailure(true);
        quotaTransaction = new TransactionTemplate(manager);
        quotaTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Reservation reserve(String day, String ip, int globalLimit, int ipLimit) {
        return quotaTransaction.execute(
                transaction -> {
                    // 第一条语句直接执行条件写入：由 SQLite 串行化写者，不先读出过期余量。
                    // 两个作用域共用当前事务和连接，任何失败都会撤销已预留的全局额度。
                    if (!incrementWithinLimit(day, "global", "all", globalLimit)) {
                        transaction.setRollbackOnly();
                        return Reservation.GLOBAL_LIMIT;
                    }
                    if (!incrementWithinLimit(day, "ip", ip, ipLimit)) {
                        transaction.setRollbackOnly();
                        return Reservation.IP_LIMIT;
                    }
                    return Reservation.GRANTED;
                });
    }

    @Override
    public int count(String day, String scope, String scopeKey) {
        try {
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key"
                                    + " = ?",
                            Integer.class,
                            day,
                            scope,
                            scopeKey);
            return count == null ? 0 : count;
        } catch (EmptyResultDataAccessException e) {
            // 新日期/新客户端尚无记录时用量为 0；状态查询不创建计数行。
            // 其他数据库异常不能伪装成零用量，仍由调用方处理。
            return 0;
        }
    }

    private boolean incrementWithinLimit(String day, String scope, String scopeKey, int limit) {
        int changed =
                jdbc.update(
                        "INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?,?,?,1) ON"
                            + " CONFLICT(day, scope, scope_key) DO UPDATE SET count = count + 1,"
                            + " updated_at = datetime('now','localtime') WHERE api_usage.count < ?",
                        day,
                        scope,
                        scopeKey,
                        limit);
        if (changed != 0 && changed != 1)
            throw new IllegalStateException("Unexpected quota update count");
        return changed == 1;
    }
}
