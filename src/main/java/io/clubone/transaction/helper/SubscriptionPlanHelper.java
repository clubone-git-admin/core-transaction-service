package io.clubone.transaction.helper;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.v2.vo.CyclePriceDTO;
import io.clubone.transaction.v2.vo.DiscountCodeDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;

@Repository
public class SubscriptionPlanHelper {

	@Autowired
	@Qualifier("cluboneJdbcTemplate")
	private JdbcTemplate cluboneJdbcTemplate;

    public InvoiceEntityDTO fetchInvoiceEntity(UUID invoiceId, UUID transactionId) {
        String sql = """
            SELECT ie.invoice_entity_id,
                   ie.entity_id,
                   ie.entity_type_id,
                   ie.contract_start_date,
                   ie.contract_start_date + INTERVAL '1 month' AS contract_end_date, -- or use actual logic
                   i.created_by
            FROM transaction.invoice_entity ie
            JOIN transaction.invoice i ON i.invoice_id = ie.invoice_id
            JOIN transaction.transaction t ON t.invoice_id = i.invoice_id
            WHERE i.invoice_id = ? AND t.transaction_id = ?
        """;

        return cluboneJdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            InvoiceEntityDTO dto = new InvoiceEntityDTO();
            dto.setInvoiceEntityId((UUID) rs.getObject("invoice_entity_id"));
            dto.setEntityId((UUID) rs.getObject("entity_id"));
            dto.setEntityTypeId((UUID) rs.getObject("entity_type_id"));
            dto.setContractStartDate(rs.getTimestamp("contract_start_date").toLocalDateTime().toLocalDate());
            //dto.setContractEndDate(rs.getTimestamp("contract_end_date").toLocalDateTime().toLocalDate());
            //dto.setCreatedBy((UUID) rs.getObject("created_by"));
            return dto;
        }, invoiceId, transactionId);
    }

    public List<CyclePriceDTO> fetchCyclePrices(UUID invoiceEntityId) {
        String sql = """
            SELECT price_cycle_band_id, unit_price, is_price_overridden
            FROM transaction.invoice_entity_price_band
            WHERE invoice_entity_id = ? AND COALESCE(is_active, true) = true
        """;
        return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
            CyclePriceDTO dto = new CyclePriceDTO();
            dto.setPriceCycleBandId((UUID) rs.getObject("price_cycle_band_id"));
            dto.setUnitPrice(rs.getBigDecimal("unit_price"));
            //dto.setPriceOverridden(rs.getBoolean("is_price_overridden"));
            return dto;
        }, invoiceEntityId);
    }

    public List<DiscountCodeDTO> fetchDiscounts(UUID invoiceEntityId) {
        String sql = """
            SELECT discount_id, discount_amount, adjustment_type_id, calculation_type_id
            FROM transaction.invoice_entity_discount
            WHERE invoice_entity_id = ? AND COALESCE(is_active, true) = true
        """;
        return cluboneJdbcTemplate.query(sql, (rs, rowNum) -> {
            DiscountCodeDTO dto = new DiscountCodeDTO();
            dto.setDiscountId((UUID) rs.getObject("discount_id"));
            //dto.setDiscountAmount(rs.getBigDecimal("discount_amount"));
            //dto.setAdjustmentTypeId((UUID) rs.getObject("adjustment_type_id"));
            //dto.setCalculationTypeId((UUID) rs.getObject("calculation_type_id"));
            return dto;
        }, invoiceEntityId);
    }

    // Similar fetchers can be added for PromoDTO and EntitlementDTO if you link them
    
    public SubscriptionPlanCreateRequest buildRequest(UUID invoiceId, UUID transactionId) {
        // Step 1: Get root invoice entity info
        InvoiceEntityDTO invoiceEntity = fetchInvoiceEntity(invoiceId, transactionId);

        // Step 2: Build request
        SubscriptionPlanCreateRequest request = new SubscriptionPlanCreateRequest();
        //request.setCreatedBy(invoiceEntity.getCreatedBy());
        request.setEntityId(invoiceEntity.getEntityId());
        request.setEntityTypeId(invoiceEntity.getEntityTypeId());
        request.setContractStartDate(invoiceEntity.getContractStartDate());
        //request.setContractEndDate(invoiceEntity.getContractEndDate());

        // Step 3: Children
       // request.setCyclePrices(fetchCyclePrices(invoiceEntity.getInvoiceEntityId()));
        request.setDiscountCodes(fetchDiscounts(invoiceEntity.getInvoiceEntityId()));
        // request.setEntitlements(...);
        // request.setPromos(...);

        return request;
    }
}

