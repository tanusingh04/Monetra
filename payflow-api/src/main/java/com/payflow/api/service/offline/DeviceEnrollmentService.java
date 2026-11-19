package com.payflow.api.service.offline;

import com.payflow.api.dto.offline.DeviceEnrollmentResponse;
import com.payflow.api.dto.offline.EnrollDeviceRequest;
import com.payflow.core.domain.offline.AllowanceStatus;
import com.payflow.core.domain.offline.DeviceEnrollment;
import com.payflow.core.domain.offline.DeviceStatus;
import com.payflow.core.domain.offline.OfflineAllowance;
import com.payflow.core.domain.user.User;
import com.payflow.core.exception.BusinessException;
import com.payflow.core.exception.ResourceNotFoundException;
import com.payflow.core.repository.DeviceEnrollmentRepository;
import com.payflow.core.repository.OfflineAllowanceRepository;
import com.payflow.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(DeviceEnrollmentService.class);

    private final DeviceEnrollmentRepository deviceEnrollmentRepository;
    private final UserRepository userRepository;
    private final OfflineAllowanceRepository allowanceRepository;
    private final OfflineCryptoService cryptoService;

    public DeviceEnrollmentService(DeviceEnrollmentRepository deviceEnrollmentRepository,
                                   UserRepository userRepository,
                                   OfflineAllowanceRepository allowanceRepository,
                                   OfflineCryptoService cryptoService) {
        this.deviceEnrollmentRepository = deviceEnrollmentRepository;
        this.userRepository = userRepository;
        this.allowanceRepository = allowanceRepository;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public DeviceEnrollmentResponse enrollDevice(EnrollDeviceRequest request, UUID userId) {
        // Validate ECDSA public key structure
        try {
            PublicKey pk = cryptoService.parsePublicKey(request.publicKey());
            if (!"EC".equalsIgnoreCase(pk.getAlgorithm())) {
                throw new IllegalArgumentException("Key must be Elliptic Curve (EC) algorithm");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Base64 X.509 EC public key: " + e.getMessage(), e);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        Optional<DeviceEnrollment> existingOpt = deviceEnrollmentRepository.findByUserIdAndDeviceId(userId, request.deviceId());
        DeviceEnrollment enrollment;
        if (existingOpt.isPresent()) {
            enrollment = existingOpt.get();
            // Re-enroll / update public key if previously revoked or replaced
            enrollment = new DeviceEnrollment(user, request.deviceId(), request.deviceModel(), request.publicKey());
            deviceEnrollmentRepository.delete(existingOpt.get());
        } else {
            enrollment = new DeviceEnrollment(user, request.deviceId(), request.deviceModel(), request.publicKey());
        }

        enrollment = deviceEnrollmentRepository.save(enrollment);
        log.info("Successfully enrolled device {} for user {}", request.deviceId(), userId);
        return DeviceEnrollmentResponse.from(enrollment);
    }

    @Transactional
    public void revokeDevice(String deviceId, UUID userId) {
        DeviceEnrollment enrollment = deviceEnrollmentRepository.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + deviceId));

        enrollment.revoke();
        deviceEnrollmentRepository.save(enrollment);

        // Also revoke all active allowances allocated to this device
        List<OfflineAllowance> activeAllowances = allowanceRepository.findByUserIdAndStatus(userId, AllowanceStatus.ACTIVE);
        for (OfflineAllowance allowance : activeAllowances) {
            if (deviceId.equals(allowance.getDeviceId())) {
                allowance.markRevoked();
                allowanceRepository.save(allowance);
                log.warn("Revoked active allowance {} associated with revoked device {}", allowance.getId(), deviceId);
            }
        }

        log.info("Device {} revoked for user {}", deviceId, userId);
    }
}
