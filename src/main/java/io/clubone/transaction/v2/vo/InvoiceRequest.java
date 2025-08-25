package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class InvoiceRequest {
	
	private UUID clientRoleId;
    private UUID levelId;
    private String billingAddress;
    private boolean isPaid;
    private UUID createdBy;
    private List<Entity> entities;

}
