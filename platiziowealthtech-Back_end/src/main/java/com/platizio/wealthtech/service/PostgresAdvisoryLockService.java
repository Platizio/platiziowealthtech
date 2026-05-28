package com.platizio.wealthtech.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PostgresAdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAdvisoryLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean runWithLock(int namespace, int lockId, Runnable task) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            if (!tryLock(connection, namespace, lockId)) {
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                unlock(connection, namespace, lockId);
            }
        }));
    }

    private boolean tryLock(Connection connection, int namespace, int lockId) throws SQLException {
        return queryBoolean(connection, "select pg_try_advisory_lock(?, ?)", namespace, lockId);
    }

    private void unlock(Connection connection, int namespace, int lockId) throws SQLException {
        queryBoolean(connection, "select pg_advisory_unlock(?, ?)", namespace, lockId);
    }

    private boolean queryBoolean(Connection connection, String sql, int namespace, int lockId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, namespace);
            statement.setInt(2, lockId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }
}
