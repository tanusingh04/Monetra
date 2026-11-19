package com.payflow.core.domain.offline;

import com.payflow.core.domain.user.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_enrollments", uniqueConstraints = {
        @UniqueConstraint(name = "uq_device_enrollments_user_device", columnNames = {"user_id", "device_id"})
})
public class DeviceEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "device_model", length = 128)
    private String deviceModel;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 30)
    private RevocationReason revocationReason;

    public DeviceEnrollment() {}

    public DeviceEnrollment(User user, String deviceId, String deviceModel, String publicKey) {
        this.user = user;
        this.deviceId = deviceId;
        this.deviceModel = deviceModel;
        this.publicKey = publicKey;
        this.status = DeviceStatus.ACTIVE;
        this.enrolledAt = Instant.now();
    }

    public void revoke() {
        revoke(RevocationReason.OTHER);
    }

    public void revoke(RevocationReason reason) {
        this.status = DeviceStatus.REVOKED;
        this.revokedAt = Instant.now();
        this.revocationReason = reason;
    }

    public RevocationReason getRevocationReason() {
        return revocationReason;
    }

    public boolean isActive() {
        return this.status == DeviceStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
