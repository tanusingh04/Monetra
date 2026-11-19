package com.payflow.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Production-grade Structured JSON Layout for Logback.
 * Formats all log records into single-line JSON with correlation IDs, payment IDs,
 * and automatic redaction of sensitive credentials (JWTs, passwords, financial tokens).
 */
public class StructuredJsonLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Sensitive data patterns for PCI-DSS & security compliance
    private static final Pattern JWT_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.?[A-Za-z0-9-_.+/=]*|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9-_]{10,}");
    private static final Pattern PASSWORD_JSON_PATTERN = Pattern.compile("(?i)(\"?(?:password|client_secret|secret|token|pin|cvv|private_key)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)(\"?)");
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile("(?i).*(password|secret|token|jwt|auth|pin|cvv).*");

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> logMap = new LinkedHashMap<>();

        logMap.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        logMap.put("level", event.getLevel().toString());
        logMap.put("thread", event.getThreadName());
        logMap.put("logger", event.getLoggerName());
        logMap.put("message", maskSensitiveData(event.getFormattedMessage()));

        // MDC Context (Trace / Correlation / Payment IDs)
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            if (mdc.containsKey("correlationId")) {
                logMap.put("correlationId", mdc.get("correlationId"));
            }
            if (mdc.containsKey("paymentId")) {
                logMap.put("paymentId", mdc.get("paymentId"));
            }
            if (mdc.containsKey("userId")) {
                logMap.put("userId", mdc.get("userId"));
            }

            Map<String, String> sanitizedMdc = new LinkedHashMap<>();
            mdc.forEach((k, v) -> {
                if (!"correlationId".equals(k) && !"paymentId".equals(k) && !"userId".equals(k)) {
                    if (SENSITIVE_KEY_PATTERN.matcher(k).matches()) {
                        sanitizedMdc.put(k, "***REDACTED***");
                    } else {
                        sanitizedMdc.put(k, maskSensitiveData(v));
                    }
                }
            });
            if (!sanitizedMdc.isEmpty()) {
                logMap.put("mdc", sanitizedMdc);
            }
        }

        // Exception / Stack trace
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            logMap.put("exceptionClass", throwableProxy.getClassName());
            logMap.put("exceptionMessage", maskSensitiveData(throwableProxy.getMessage()));
            logMap.put("stackTrace", ThrowableProxyUtil.asString(throwableProxy));
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(logMap) + "\n";
        } catch (Exception e) {
            return "{\"timestamp\":\"" + Instant.now() + "\",\"level\":\"ERROR\",\"message\":\"Failed to serialize log to JSON: " + e.getMessage() + "\"}\n";
        }
    }

    /**
     * Replaces passwords, JWT tokens, and sensitive headers with masked equivalents.
     */
    public static String maskSensitiveData(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String masked = JWT_PATTERN.matcher(input).replaceAll("***REDACTED_JWT***");
        masked = PASSWORD_JSON_PATTERN.matcher(masked).replaceAll("$1***REDACTED***$3");
        return masked;
    }
}
