package com.payflow.worker.alert.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationDeliveryStatus;
import com.payflow.core.domain.notification.NotificationSeverity;
import com.payflow.worker.metrics.PayFlowWorkerMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WebhookChannelTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private PayFlowWorkerMetricsService metricsService;

    private ObjectMapper objectMapper;
    private WebhookChannel webhookChannel;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        webhookChannel = new WebhookChannel(httpClient, objectMapper, metricsService);
        webhookChannel.setWebhookUrl("https://hooks.slack.com/services/mock");
        webhookChannel.setSecret("test-secret-key-for-webhook-hmac-32chars");
    }

    @Test
    @DisplayName("Webhook channel skips delivery when disabled")
    void testWebhookDisabled() {
        webhookChannel.setEnabled(false);

        Notification notification = new Notification(
                UUID.randomUUID(), "FRAUD_PROOF", NotificationSeverity.CRITICAL,
                null, "ROLE_ADMIN", "Title", "Message", "FRAUD_PROOF", "1"
        );

        boolean delivered = webhookChannel.deliver(notification);

        assertThat(delivered).isTrue();
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Webhook delivery succeeds on 200 OK with HMAC-SHA256 header")
    void testWebhookDeliverySuccess() throws Exception {
        webhookChannel.setEnabled(true);

        when(httpResponse.statusCode()).thenReturn(200);
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        Notification notification = new Notification(
                UUID.randomUUID(), "FRAUD_PROOF", NotificationSeverity.CRITICAL,
                null, "ROLE_ADMIN", "Title", "Message", "FRAUD_PROOF", "1"
        );

        boolean delivered = webhookChannel.deliver(notification);

        assertThat(delivered).isTrue();
        assertThat(notification.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.DELIVERED);
        verify(httpClient).send(argThat(request ->
                request.headers().firstValue("X-PayFlow-Signature").isPresent() &&
                request.headers().firstValue("X-PayFlow-Signature").get().startsWith("sha256=")
        ), any());
    }

    @Test
    @DisplayName("Webhook delivery retries on failure and marks FAILED without throwing exception")
    void testWebhookDeliveryFailureResilience() throws Exception {
        webhookChannel.setEnabled(true);

        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("Connection refused"));

        Notification notification = new Notification(
                UUID.randomUUID(), "FRAUD_PROOF", NotificationSeverity.CRITICAL,
                null, "ROLE_ADMIN", "Title", "Message", "FRAUD_PROOF", "1"
        );

        // Does NOT throw exception
        boolean delivered = webhookChannel.deliver(notification);

        assertThat(delivered).isFalse();
        assertThat(notification.getDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(notification.getAttempts()).isGreaterThanOrEqualTo(3);
        verify(metricsService).incrementAlertDeliveryFailed("WEBHOOK");
    }
}
