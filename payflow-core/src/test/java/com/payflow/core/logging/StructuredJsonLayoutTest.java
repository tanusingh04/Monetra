package com.payflow.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredJsonLayoutTest {

    private final StructuredJsonLayout layout = new StructuredJsonLayout();

    @Test
    @DisplayName("Should format log event as valid JSON and mask JWT and passwords")
    void shouldFormatJsonAndMaskSensitiveData() {
        layout.start();

        LoggingEvent event = new LoggingEvent();
        event.setTimeStamp(System.currentTimeMillis());
        event.setLevel(Level.INFO);
        event.setLoggerName("com.payflow.api.service.AuthService");
        event.setThreadName("main");
        event.setMessage("User login attempt with password: \"mySuperSecretPassword123\" and token Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        event.setMDCPropertyMap(Map.of(
                "correlationId", "corr-12345",
                "paymentId", "pay-67890",
                "auth_header", "Bearer secretToken123"
        ));

        String json = layout.doLayout(event);

        assertNotNull(json);
        assertTrue(json.contains("\"level\":\"INFO\""));
        assertTrue(json.contains("\"correlationId\":\"corr-12345\""));
        assertTrue(json.contains("\"paymentId\":\"pay-67890\""));

        // Verify password and JWT are masked
        assertFalse(json.contains("mySuperSecretPassword123"));
        assertTrue(json.contains("***REDACTED***"));
        assertFalse(json.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
        assertTrue(json.contains("***REDACTED_JWT***"));
    }
}
