package com.payflow.worker.alert.channel;

import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationChannel;
import com.payflow.core.domain.notification.NotificationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(LogChannel.class);

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.LOG;
    }

    @Override
    public boolean deliver(Notification notification) {
        String msg = String.format("ALERT [%s] type=%s recipient=%s title='%s' message='%s' ref=%s:%s",
                notification.getSeverity(),
                notification.getType(),
                notification.getRecipientRole() != null ? notification.getRecipientRole() : notification.getRecipientUser() != null ? notification.getRecipientUser().getId() : "ALL",
                notification.getTitle(),
                notification.getMessage(),
                notification.getRefType(),
                notification.getRefId());

        if (notification.getSeverity() == NotificationSeverity.CRITICAL) {
            log.error(msg);
        } else if (notification.getSeverity() == NotificationSeverity.HIGH) {
            log.warn(msg);
        } else {
            log.info(msg);
        }
        return true;
    }
}
