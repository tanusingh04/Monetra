package com.payflow.worker.alert.channel;

import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationChannel;
import com.payflow.core.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InAppChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppChannel.class);

    private final NotificationRepository notificationRepository;

    public InAppChannel(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public boolean deliver(Notification notification) {
        try {
            notification.incrementAttempts();
            notification.markDelivered();
            notificationRepository.save(notification);
            log.debug("Successfully saved in-app notification {}", notification.getId());
            return true;
        } catch (Exception ex) {
            log.error("Failed to persist in-app notification {}: {}", notification.getId(), ex.getMessage());
            notification.markFailed();
            return false;
        }
    }
}
