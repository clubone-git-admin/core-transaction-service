package io.clubone.transaction.subscription.billing.controller;

import io.clubone.transaction.security.AccessContext;
import io.clubone.transaction.subscription.billing.dto.*;
import io.clubone.transaction.subscription.billing.service.SubscriptionBillingScheduleManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/subscription-billing")
@Tag(name = "Subscription Billing Schedule", description = "Manage subscription billing schedule rows and adjustments")
public class SubscriptionBillingScheduleManageController {

    private final SubscriptionBillingScheduleManageService scheduleService;

    public SubscriptionBillingScheduleManageController(SubscriptionBillingScheduleManageService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/schedule/client-agreement/{clientAgreementId}")
    @Operation(summary = "Get schedule by client agreement")
    public ResponseEntity<SubscriptionBillingScheduleListResponse> getSchedule(
            @PathVariable UUID clientAgreementId) {
        return ResponseEntity.ok(scheduleService.getScheduleByClientAgreementId(clientAgreementId));
    }

    @PutMapping("/schedule/{billingScheduleId}")
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Update a billing schedule row")
    public ResponseEntity<SimpleActionResponse> updateScheduleRow(
            @PathVariable UUID billingScheduleId,
            @RequestBody UpdateBillingScheduleRequest request,
            @RequestHeader("X-USER-ID") UUID modifiedBy) {
        return ResponseEntity.ok(scheduleService.updateScheduleRow(billingScheduleId, request, modifiedBy));
    }

    @PutMapping("/schedule/bulk-update")
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Bulk update billing schedule rows")
    public ResponseEntity<SimpleActionResponse> bulkUpdateScheduleRows(
            @RequestBody BulkUpdateBillingScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.bulkUpdateScheduleRows(request, AccessContext.actorApplicationUserId()));
    }

    @PostMapping("/schedule/{billingScheduleId}/adjustment")
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Add a billing schedule adjustment")
    public ResponseEntity<SimpleActionResponse> addAdjustment(
            @PathVariable UUID billingScheduleId,
            @RequestBody AddBillingScheduleAdjustmentRequest request) {
        return ResponseEntity.ok(scheduleService.addAdjustment(billingScheduleId, request, AccessContext.actorApplicationUserId()));
    }
    
    @GetMapping("/schedule/{billingScheduleId}/adjustments")
    @Operation(summary = "List adjustments for a schedule row")
    public ResponseEntity<BillingScheduleAdjustmentListResponse> getAdjustments(
            @PathVariable UUID billingScheduleId) {
        return ResponseEntity.ok(scheduleService.getAdjustmentsByBillingScheduleId(billingScheduleId));
    }

    @PutMapping("/schedule/adjustment/{billingScheduleAdjustmentId}")
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Update a billing schedule adjustment")
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
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Deactivate a billing schedule adjustment")
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
    @PreAuthorize("@perm.canManageBilling()")
    @Operation(summary = "Regenerate future billing schedule rows")
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
