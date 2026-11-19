package com.payflow.worker.alert.channel;

import com.payflow.core.domain.notification.Notification;
import com.payflow.core.domain.notification.NotificationChannel;

public interface AlertChannel {
    NotificationChannel getChannel();
    boolean deliver(Notification notification);
}
