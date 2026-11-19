package com.payflow.api.dto.offline;

import com.payflow.core.domain.offline.DeviceEnrollment;
import com.payflow.core.domain.offline.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

public record DeviceEnrollmentResponse(
        UUID id,
        String deviceId,
        String deviceModel,
        String publicKey,
        DeviceStatus status,
        Instant enrolledAt
) {
    public static DeviceEnrollmentResponse from(DeviceEnrollment entity) {
        return new DeviceEnrollmentResponse(
                entity.getId(),
                entity.getDeviceId(),
                entity.getDeviceModel(),
                entity.getPublicKey(),
                entity.getStatus(),
                entity.getEnrolledAt()
        );
    }
}
