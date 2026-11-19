package com.payflow.api.service.offline;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public class H2FraudProofImmutabilityTrigger implements Trigger {

    private String[] columnNames;

    @Override
    public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type) throws SQLException {
        try (Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            columnNames = new String[meta.getColumnCount()];
            for (int i = 0; i < meta.getColumnCount(); i++) {
                columnNames[i] = meta.getColumnName(i + 1).toLowerCase();
            }
        }
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (newRow == null) {
            throw new SQLException("Offline fraud proofs are strictly immutable and cannot be deleted.");
        }

        if (columnNames == null) {
            try (Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT * FROM offline_fraud_proofs WHERE 1=0")) {
                ResultSetMetaData meta = rs.getMetaData();
                columnNames = new String[meta.getColumnCount()];
                for (int i = 0; i < meta.getColumnCount(); i++) {
                    columnNames[i] = meta.getColumnName(i + 1).toLowerCase();
                }
            }
        }

        for (int i = 0; i < columnNames.length && i < oldRow.length && i < newRow.length; i++) {
            String colName = columnNames[i];
            if (!colName.equals("status") && !colName.equals("reviewed_by") &&
                !colName.equals("reviewed_at") && !colName.equals("review_note") &&
                !colName.equals("second_receipt_settled") && !colName.equals("protected_amount") &&
                !colName.equals("uncovered_amount") && !colName.equals("settlement_payment_id")) {
                if (!Objects.equals(oldRow[i], newRow[i])) {
                    throw new SQLException("Cryptographic evidence (payloads, signatures, counter, IDs) in fraud proofs is strictly immutable.");
                }
            }
        }
    }
}
