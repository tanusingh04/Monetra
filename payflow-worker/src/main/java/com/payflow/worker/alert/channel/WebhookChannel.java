package com.payflow.worker.alert.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationChannel;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Component
public class WebhookChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PayFlowWorkerMetricsService metricsService;

    @Value("${app.alerts.webhook.enabled:false}")
    private boolean enabled = false;

    @Value("${app.alerts.webhook.url:https://hooks.slack.com/services/mock}")
    private String webhookUrl;

    @Value("${app.alerts.webhook.secret:payflow-alert-webhook-secret-key-32chars}")
    private String secret;

    @Value("${app.alerts.webhook.timeout-sec:5}")
    private int timeoutSeconds = 5;

    @Value("${app.alerts.webhook.max-retries:3}")
    private int maxRetries = 3;

    public WebhookChannel(ObjectMapper objectMapper,
                          @Autowired(required = false) PayFlowWorkerMetricsService metricsService) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper, metricsService);
    }

    public WebhookChannel(HttpClient httpClient,
                          ObjectMapper objectMapper,
                          PayFlowWorkerMetricsService metricsService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.WEBHOOK;
    }

    @Override
    public boolean deliver(Notification notification) {
        if (!enabled) {
            log.debug("Webhook delivery is disabled; skipping webhook for notification {}", notification.getId());
            return true;
        }

        String payload;
        try {
            Map<String, Object> slackPayload = Map.of(
                    "text", String.format("*[%s ALERT]* %s\n%s",
                            notification.getSeverity(), notification.getTitle(), notification.getMessage()),
                    "attachments", java.util.List.of(
                            Map.of(
                                    "color", getColorForSeverity(notification.getSeverity().name()),
                                    "fields", java.util.List.of(
                                            Map.of("title", "Type", "value", notification.getType(), "short", true),
                                            Map.of("title", "Severity", "value", notification.getSeverity().name(), "short", true),
                                            Map.of("title", "Ref", "value", notification.getRefType() + ":" + notification.getRefId(), "short", false)
                                    )
                            )
                    )
            );
            payload = objectMapper.writeValueAsString(slackPayload);
        } catch (Exception ex) {
            log.error("Failed to construct webhook payload for alert {}: {}", notification.getId(), ex.getMessage());
            notification.markFailed();
            if (metricsService != null) metricsService.incrementAlertDeliveryFailed("WEBHOOK");
            return false;
        }

        String signature = computeHmacSha256(payload, secret);

        int attempts = 0;
        long backoffMs = 50;

        while (attempts < maxRetries) {
            attempts++;
            notification.incrementAttempts();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("X-PayFlow-Signature", "sha256=" + signature)
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Webhook successfully delivered for alert {} on attempt {}", notification.getId(), attempts);
                    notification.markDelivered();
                    return true;
                } else {
                    log.warn("Webhook delivery attempt {} for alert {} returned status code {}",
                            attempts, notification.getId(), response.statusCode());
                }
            } catch (Exception ex) {
                log.warn("Webhook delivery attempt {} for alert {} encountered error: {}",
                        attempts, notification.getId(), ex.getMessage());
            }

            if (attempts < maxRetries) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs *= 2;
            }
        }

        log.error("Webhook delivery exhausted {} attempts for alert {}. Marking as FAILED.", maxRetries, notification.getId());
        notification.markFailed();
        if (metricsService != null) {
            metricsService.incrementAlertDeliveryFailed("WEBHOOK");
        }
        return false;
    }

    private String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failure", e);
        }
    }

    private String getColorForSeverity(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "danger";
            case "HIGH" -> "warning";
            case "MEDIUM" -> "#439FE0";
            default -> "good";
        };
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
