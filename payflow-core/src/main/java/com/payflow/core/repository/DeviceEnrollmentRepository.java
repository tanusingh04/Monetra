package com.payflow.core.repository;

import com.payflow.core.domain.offline.DeviceEnrollment;
import com.payflow.core.domain.offline.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceEnrollmentRepository extends JpaRepository<DeviceEnrollment, UUID> {

    Optional<DeviceEnrollment> findByUserIdAndDeviceId(UUID userId, String deviceId);

    Optional<DeviceEnrollment> findByDeviceIdAndStatus(String deviceId, DeviceStatus status);

    Optional<DeviceEnrollment> findByDeviceId(String deviceId);
}
