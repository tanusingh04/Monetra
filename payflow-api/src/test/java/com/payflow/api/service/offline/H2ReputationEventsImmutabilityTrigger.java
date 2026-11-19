package com.payflow.api.service.offline;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * H2 Trigger simulating PostgreSQL prevent_reputation_events_tampering() function.
 * Strictly prohibits UPDATE and DELETE operations on the append-only reputation_events audit log.
 */
public class H2ReputationEventsImmutabilityTrigger implements Trigger {

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("Reputation events are strictly immutable and append-only. UPDATE and DELETE operations are prohibited.");
    }
}
