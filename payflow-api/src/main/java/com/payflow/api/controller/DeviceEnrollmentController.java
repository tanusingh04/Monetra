package com.payflow.api.controller;

import com.payflow.api.dto.common.ApiResponse;
import com.payflow.api.dto.offline.DeviceEnrollmentResponse;
import com.payflow.api.dto.offline.EnrollDeviceRequest;
import com.payflow.api.security.UserPrincipal;
import com.payflow.api.service.offline.DeviceEnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/offline/devices")
@Tag(name = "Offline Devices", description = "Endpoints for client device enrollment and ECDSA key registration")
public class DeviceEnrollmentController {

    private final DeviceEnrollmentService deviceEnrollmentService;

    public DeviceEnrollmentController(DeviceEnrollmentService deviceEnrollmentService) {
        this.deviceEnrollmentService = deviceEnrollmentService;
    }

    @PostMapping("/enroll")
    @Operation(summary = "Enroll mobile device for offline payments with hardware public key")
    public ResponseEntity<ApiResponse<DeviceEnrollmentResponse>> enrollDevice(
            @Valid @RequestBody EnrollDeviceRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        DeviceEnrollmentResponse response = deviceEnrollmentService.enrollDevice(request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Device successfully enrolled for offline transactions", response));
    }

    @PostMapping("/revoke")
    @Operation(summary = "Revoke enrolled device and cancel associated active offline allowances")
    public ResponseEntity<ApiResponse<Void>> revokeDevice(
            @RequestParam String deviceId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        deviceEnrollmentService.revokeDevice(deviceId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Device successfully revoked", null));
    }
}
