package com.payflow.api.merkle;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * H2 Trigger simulating PostgreSQL prevent_merkle_tampering() function.
 * Prohibits UPDATE and DELETE operations on ledger_merkle_batches and ledger_merkle_leaves.
 */
public class H2MerkleImmutabilityTrigger implements Trigger {

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("Merkle ledger batches and leaves are cryptographically sealed and strictly immutable. UPDATE and DELETE operations are prohibited.");
    }
}
