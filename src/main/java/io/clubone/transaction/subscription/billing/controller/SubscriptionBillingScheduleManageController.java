package io.clubone.transaction.subscription.billing.controller;

import io.clubone.transaction.subscription.billing.dto.*;
import io.clubone.transaction.subscription.billing.service.SubscriptionBillingScheduleManageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/subscription-billing")
public class SubscriptionBillingScheduleManageController {

    private final SubscriptionBillingScheduleManageService scheduleService;

    public SubscriptionBillingScheduleManageController(SubscriptionBillingScheduleManageService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/schedule/client-agreement/{clientAgreementId}")
    public ResponseEntity<SubscriptionBillingScheduleListResponse> getSchedule(
            @PathVariable UUID clientAgreementId) {
        return ResponseEntity.ok(scheduleService.getScheduleByClientAgreementId(clientAgreementId));
    }

    @PutMapping("/schedule/{billingScheduleId}")
    public ResponseEntity<SimpleActionResponse> updateScheduleRow(
            @PathVariable UUID billingScheduleId,
            @RequestBody UpdateBillingScheduleRequest request,
            @RequestHeader("X-USER-ID") UUID modifiedBy) {
        return ResponseEntity.ok(scheduleService.updateScheduleRow(billingScheduleId, request, modifiedBy));
    }

    @PutMapping("/schedule/bulk-update")
    public ResponseEntity<SimpleActionResponse> bulkUpdateScheduleRows(
            @RequestBody BulkUpdateBillingScheduleRequest request,
            @RequestHeader("X-USER-ID") UUID modifiedBy) {
        return ResponseEntity.ok(scheduleService.bulkUpdateScheduleRows(request, modifiedBy));
    }

    @PostMapping("/schedule/{billingScheduleId}/adjustment")
    public ResponseEntity<SimpleActionResponse> addAdjustment(
            @PathVariable UUID billingScheduleId,
            @RequestBody AddBillingScheduleAdjustmentRequest request,
            @RequestHeader("X-USER-ID") UUID createdBy) {
        return ResponseEntity.ok(scheduleService.addAdjustment(billingScheduleId, request, createdBy));
    }
    
    @GetMapping("/schedule/{billingScheduleId}/adjustments")
    public ResponseEntity<BillingScheduleAdjustmentListResponse> getAdjustments(
            @PathVariable UUID billingScheduleId) {
        return ResponseEntity.ok(scheduleService.getAdjustmentsByBillingScheduleId(billingScheduleId));
    }

    @PutMapping("/schedule/adjustment/{billingScheduleAdjustmentId}")
    public ResponseEntity<SimpleActionResponse> updateAdjustment(
            @PathVariable UUID billingScheduleAdjustmentId,
            @RequestBody UpdateBillingScheduleAdjustmentRequest request,
            @RequestHeader("X-USER-ID") UUID modifiedBy,
            @RequestHeader(value = "X-USER-EMAIL", required = false) String userEmail,
            @RequestHeader(value = "X-IP-ADDRESS", required = false) String ipAddress,
            @RequestHeader(value = "X-USER-AGENT", required = false) String userAgent) {
        return ResponseEntity.ok(
                scheduleService.updateAdjustment(
                        billingScheduleAdjustmentId,
                        request,
                        modifiedBy,
                        userEmail,
                        ipAddress,
                        userAgent
                )
        );
    }

    @DeleteMapping("/schedule/adjustment/{billingScheduleAdjustmentId}")
    public ResponseEntity<SimpleActionResponse> deactivateAdjustment(
            @PathVariable UUID billingScheduleAdjustmentId,
            @RequestHeader("X-USER-ID") UUID modifiedBy,
            @RequestHeader(value = "X-USER-EMAIL", required = false) String userEmail,
            @RequestHeader(value = "X-IP-ADDRESS", required = false) String ipAddress,
            @RequestHeader(value = "X-USER-AGENT", required = false) String userAgent) {
        return ResponseEntity.ok(
                scheduleService.deactivateAdjustment(
                        billingScheduleAdjustmentId,
                        modifiedBy,
                        userEmail,
                        ipAddress,
                        userAgent
                )
        );
    }

    @PostMapping("/schedule/regenerate")
    public ResponseEntity<SimpleActionResponse> regenerateFutureSchedule(
            @RequestBody RegenerateBillingScheduleRequest request,
            @RequestHeader("X-USER-ID") UUID modifiedBy,
            @RequestHeader(value = "X-USER-EMAIL", required = false) String userEmail,
            @RequestHeader(value = "X-IP-ADDRESS", required = false) String ipAddress,
            @RequestHeader(value = "X-USER-AGENT", required = false) String userAgent) {
        return ResponseEntity.ok(
                scheduleService.regenerateFutureSchedule(
                        request,
                        modifiedBy,
                        userEmail,
                        ipAddress,
                        userAgent
                )
        );
    }

}
