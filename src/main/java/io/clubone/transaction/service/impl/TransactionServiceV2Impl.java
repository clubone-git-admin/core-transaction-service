package io.clubone.transaction.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.clubone.transaction.dao.EntityLookupDao;
import io.clubone.transaction.dao.PromotionEffectDAO;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.helper.ClientAgreementCreationHelper;
import io.clubone.transaction.helper.TransactionUtils;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.util.FrequencyUnit;
import io.clubone.transaction.v2.vo.Bundle;
import io.clubone.transaction.v2.vo.BundlePriceCycleBandDTO;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.v2.vo.Entity;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.InvoiceSummaryDTO;
import io.clubone.transaction.v2.vo.Item;
import io.clubone.transaction.v2.vo.PaymentTimelineItemDTO;
import io.clubone.transaction.v2.vo.PromotionItemEffectDTO;
import io.clubone.transaction.vo.EntityTypeDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;

@Service
public class TransactionServiceV2Impl implements TransactionServicev2 {

	@Autowired
	private TransactionDAO transactionDAO;

	@Autowired
	private TransactionUtils transactionUtils;

	@Autowired
	private EntityLookupDao entityLookupDao;

	@Autowired
	private ClientAgreementCreationHelper caHelper;

	// ✅ NEW: Promotion Effect DAO
	@Autowired
	private PromotionEffectDAO promotionEffectDAO;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public CreateInvoiceResponse createInvoice(InvoiceRequest request) {

	    final UUID agreementTypeId = transactionDAO.findEntityTypeIdByName("Agreement");
	    final UUID bundleTypeId = transactionDAO.findEntityTypeIdByName("Bundle");
	    final UUID itemTypeId = transactionDAO.findEntityTypeIdByName("Item");
	    final UUID invoiceStatusId = UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c");

	    BigDecimal subTotal = BigDecimal.ZERO;      // GROSS (as stored in lineSub)
	    BigDecimal taxTotal = BigDecimal.ZERO;      // TAX on NET (gross-discount)
	    BigDecimal discountTotal = BigDecimal.ZERO;

	    ObjectMapper mapper = new ObjectMapper();
	    UUID applicationId = UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011");

	    InvoiceDTO inv = new InvoiceDTO();
	    inv.setInvoiceDate(Timestamp.from(Instant.now()));
	    inv.setClientRoleId(request.getClientRoleId());

	    // kept as-is
	    inv.setLevelId(UUID.fromString("b70ee823-1623-41c1-8c70-260ab4a9a2b6"));

	    inv.setBillingAddress(request.getBillingAddress());
	    inv.setInvoiceStatusId(invoiceStatusId);
	    inv.setPaid(false);
	    inv.setCreatedBy(request.getCreatedBy());

	    // ✅ Always use invoice level id consistently
	    final UUID invoiceLevelId = inv.getLevelId();

	    final List<InvoiceEntityDTO> lines = new ArrayList<>();

	    BiConsumer<InvoiceEntityDTO, List<InvoiceEntityDTO>> rollup = (container, all) -> {
	        BigDecimal childSub = BigDecimal.ZERO, childTax = BigDecimal.ZERO, childDisc = BigDecimal.ZERO;
	        for (InvoiceEntityDTO c : all) {
	            if (container.getEntityId().equals(c.getParentInvoiceEntityId())) {
	                BigDecimal q = BigDecimal.valueOf(def(c.getQuantity(), 1));
	                childSub = childSub.add(nz(c.getUnitPrice()).multiply(q));
	                childTax = childTax.add(nz(c.getTaxAmount()));
	                childDisc = childDisc.add(nz(c.getDiscountAmount()));
	            }
	        }
	        container.setUnitPrice(nz(container.getUnitPrice()));
	        container.setDiscountAmount(childDisc);
	        container.setTaxAmount(childTax);
	        container.setTotalAmount(scale2(childSub.add(childTax).subtract(childDisc)));
	    };

	    boolean isAgreement = false;

	    if (request.getEntities() != null) {
	        for (Entity e : request.getEntities()) {

	            Optional<EntityTypeDTO> entityTypeOpt = transactionDAO.getEntityTypeById(
	                    e.getEntityTypeId() != null ? e.getEntityTypeId()
	                            : UUID.fromString("b1c9b95c-5fe0-4062-af85-12ad47908948"));

	            String t = entityTypeOpt.get().getEntityType().toLowerCase();

	            switch (t) {

	                /* ============================== PACKAGE ============================== */
	                case "package": {

	                    InvoiceEntityDTO pkg = new InvoiceEntityDTO();

	                    UUID pkgInvoiceEntityId = UUID.randomUUID();
	                    UUID pkgBusinessEntityId = (e.getEntityId() != null) ? e.getEntityId() : UUID.randomUUID();

	                    pkg.setInvoiceEntityId(pkgInvoiceEntityId);
	                    pkg.setEntityId(pkgBusinessEntityId);
	                    pkg.setEntityDescription("Package");
	                    pkg.setQuantity(def(e.getQuantity(), 1));
	                    pkg.setUnitPrice(BigDecimal.ZERO);
	                    pkg.setDiscountAmount(BigDecimal.ZERO);
	                    pkg.setTaxAmount(BigDecimal.ZERO);
	                    pkg.setContractStartDate(e.getStartDate());

	                    lines.add(pkg);

	                    BigDecimal pkgGross = BigDecimal.ZERO;
	                    BigDecimal pkgDiscount = BigDecimal.ZERO;
	                    BigDecimal pkgTax = BigDecimal.ZERO;

	                    UUID pkgPromotionId = e.getPromotionId();
	                    Map<UUID, PromotionItemEffectDTO> pkgFx = Collections.emptyMap();
	                    if (pkgPromotionId != null && e.getItems() != null && !e.getItems().isEmpty()) {
	                        Set<UUID> itemIds = collectItemIds(e.getItems());
	                        pkgFx = promotionEffectDAO.fetchEffectsByPromotionForItems(pkgPromotionId, itemIds, applicationId);
	                    }

	                    if (e.getItems() != null) {
	                        for (Item it : e.getItems()) {

	                            // ✅ No proration for package
	                            InvoiceEntityDTO itemLine = buildItemLineFromPayload(
	                                    it, pkgBusinessEntityId, itemTypeId, e.getStartDate(), false);

	                            itemLine.setParentInvoiceEntityId(pkgInvoiceEntityId);

	                            // ✅ 1) discountIds FIRST (ADD, not overwrite)
	                            if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
	                                Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
	                                        it.getEntityId(), invoiceLevelId, e.getDiscountIds());

	                                best.ifPresent(d -> {
	                                    BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                    BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty);
	                                    BigDecimal discAmt = BigDecimal.ZERO;

	                                    switch (d.getCalculationMode()) {
	                                        case PERCENTAGE -> discAmt = lineSub.multiply(nz(d.getDiscountRate()))
	                                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	                                        case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
	                                        case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
	                                    }

	                                    // ✅ ADD
	                                    itemLine.setDiscountAmount(scale2(nz(itemLine.getDiscountAmount()).add(discAmt)));

	                                    InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
	                                    row.setDiscountId(d.getDiscountId());
	                                    row.setDiscountAmount(scale2(discAmt));
	                                    row.setCalculationTypeId(d.getCalculationTypeId());
	                                    row.setAdjustmentTypeId(d.getAdjustmentTypeId());
	                                    itemLine.setDiscounts(Collections.singletonList(row));
	                                });
	                            }

	                            // ✅ 2) promotion SECOND
	                            if (pkgPromotionId != null) {
	                                PromotionItemEffectDTO eff = pkgFx.get(it.getEntityId());
	                                applyPromotionEffectOnLeafLine(itemLine, eff);
	                            }

	                            System.out.println("[DEBUG][BEFORE_TAX][PACKAGE] itemId=" + it.getEntityId()
	                                    + " gross=" + scale2(lineSub(itemLine))
	                                    + " discount=" + scale2(itemLine.getDiscountAmount()));

	                            // ✅ 3) TAX THIRD (NET)
	                            computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);

	                            // 4) finalize
	                            finalizeLeaf(itemLine);

	                            subTotal = subTotal.add(lineSub(itemLine));
	                            taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
	                            discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

	                            BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                            BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
	                            pkgGross = pkgGross.add(childGross);
	                            pkgDiscount = pkgDiscount.add(nz(itemLine.getDiscountAmount()));
	                            pkgTax = pkgTax.add(nz(itemLine.getTaxAmount()));

	                            lines.add(itemLine);
	                        }
	                    }

	                    pkg.setUnitPrice(pkgGross);
	                    pkg.setDiscountAmount(pkgDiscount);
	                    pkg.setTaxAmount(pkgTax);
	                    rollup.accept(pkg, lines);
	                    break;
	                }

	                /* ============================== AGREEMENT ============================== */
	                case "agreement": {

	                    InvoiceEntityDTO agreement = new InvoiceEntityDTO();

	                    UUID agreementInvoiceEntityId = UUID.randomUUID();
	                    UUID agreementBusinessEntityId = (e.getEntityId() != null) ? e.getEntityId() : UUID.randomUUID();

	                    isAgreement = true;

	                    agreement.setInvoiceEntityId(agreementInvoiceEntityId);
	                    agreement.setEntityId(agreementBusinessEntityId);
	                    agreement.setEntityTypeId(agreementTypeId);
	                    agreement.setEntityDescription("Agreement");
	                    agreement.setQuantity(def(e.getQuantity(), 1));
	                    agreement.setUnitPrice(BigDecimal.ZERO);
	                    agreement.setDiscountAmount(BigDecimal.ZERO);
	                    agreement.setTaxAmount(BigDecimal.ZERO);
	                    agreement.setContractStartDate(e.getStartDate());

	                    lines.add(agreement);

	                    BigDecimal agreementGross = BigDecimal.ZERO;
	                    BigDecimal agreementDiscount = BigDecimal.ZERO;
	                    BigDecimal agreementTax = BigDecimal.ZERO;

	                    UUID agreementPromotionId = e.getPromotionId();
	                    Map<UUID, PromotionItemEffectDTO> agreementFx = Collections.emptyMap();
	                    if (agreementPromotionId != null && e.getBundles() != null && !e.getBundles().isEmpty()) {
	                        Set<UUID> ids = collectItemIdsFromBundles(e.getBundles());
	                        agreementFx = promotionEffectDAO.fetchEffectsByPromotionForItems(agreementPromotionId, ids, applicationId);
	                    }

	                    if (e.getBundles() != null) {
	                        for (Bundle b : e.getBundles()) {

	                            InvoiceEntityDTO bundle = new InvoiceEntityDTO();

	                            UUID bundleInvoiceEntityId = UUID.randomUUID();
	                            UUID bundleBusinessEntityId = (b.getEntityId() != null) ? b.getEntityId() : UUID.randomUUID();

	                            bundle.setInvoiceEntityId(bundleInvoiceEntityId);
	                            bundle.setParentInvoiceEntityId(agreementInvoiceEntityId);
	                            bundle.setEntityId(bundleBusinessEntityId);
	                            bundle.setEntityTypeId(bundleTypeId);
	                            bundle.setEntityDescription("Bundle");
	                            bundle.setQuantity(def(b.getQuantity(), 1));
	                            bundle.setUnitPrice(BigDecimal.ZERO);
	                            bundle.setDiscountAmount(BigDecimal.ZERO);
	                            bundle.setTaxAmount(BigDecimal.ZERO);
	                            bundle.setContractStartDate(e.getStartDate());

	                            lines.add(bundle);

	                            BigDecimal bundleGross = BigDecimal.ZERO;
	                            BigDecimal bundleDiscount = BigDecimal.ZERO;
	                            BigDecimal bundleTax = BigDecimal.ZERO;

	                            if (b.getItems() != null) {
	                                for (Item it : b.getItems()) {

	                                    // ✅ AGREEMENT: Build FULL (no proration). Proration applied AFTER discount+promo (and NOT for FEE items)
	                                    InvoiceEntityDTO itemLine = buildItemLineFromPayload(
	                                            it, bundleBusinessEntityId, itemTypeId, e.getStartDate(), false);

	                                    itemLine.setParentInvoiceEntityId(bundleInvoiceEntityId);

	                                    // ✅ 1) discountIds FIRST
	                                    if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
	                                        Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
	                                                it.getEntityId(), invoiceLevelId, e.getDiscountIds());

	                                        best.ifPresent(d -> {
	                                            BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                            BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty);
	                                            BigDecimal discAmt = BigDecimal.ZERO;

	                                            switch (d.getCalculationMode()) {
	                                                case PERCENTAGE -> discAmt = lineSub.multiply(nz(d.getDiscountRate()))
	                                                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	                                                case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
	                                                case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
	                                            }

	                                            itemLine.setDiscountAmount(scale2(nz(itemLine.getDiscountAmount()).add(discAmt)));

	                                            InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
	                                            row.setDiscountId(d.getDiscountId());
	                                            row.setDiscountAmount(scale2(discAmt));
	                                            row.setCalculationTypeId(d.getCalculationTypeId());
	                                            row.setAdjustmentTypeId(d.getAdjustmentTypeId());
	                                            itemLine.setDiscounts(Collections.singletonList(row));
	                                        });
	                                    }

	                                    // ✅ 2) promotion SECOND
	                                    if (agreementPromotionId != null) {
	                                        PromotionItemEffectDTO eff = agreementFx.get(it.getEntityId());
	                                        applyPromotionEffectOnLeafLine(itemLine, eff);
	                                    }

	                                    // ✅ CHANGE: prorate AFTER discount+promo, but ONLY if NOT a FEE item
	                                    applyAgreementProrationAfterDiscountUnlessFee(itemLine, it.getEntityId(), applicationId);

	                                    System.out.println("[DEBUG][BEFORE_TAX][AGREEMENT] itemId=" + it.getEntityId()
	                                            + " gross=" + scale2(lineSub(itemLine))
	                                            + " discount=" + scale2(itemLine.getDiscountAmount()));

	                                    // ✅ 3) tax THIRD (NET)
	                                    computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);

	                                    // 4) finalize
	                                    finalizeLeaf(itemLine);

	                                    subTotal = subTotal.add(lineSub(itemLine));
	                                    taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
	                                    discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

	                                    BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                    BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
	                                    bundleGross = bundleGross.add(childGross);
	                                    bundleDiscount = bundleDiscount.add(nz(itemLine.getDiscountAmount()));
	                                    bundleTax = bundleTax.add(nz(itemLine.getTaxAmount()));

	                                    lines.add(itemLine);
	                                }
	                            }

	                            bundle.setUnitPrice(bundleGross);
	                            bundle.setDiscountAmount(bundleDiscount);
	                            bundle.setTaxAmount(bundleTax);

	                            agreementGross = agreementGross.add(bundleGross);
	                            agreementDiscount = agreementDiscount.add(bundleDiscount);
	                            agreementTax = agreementTax.add(bundleTax);

	                            rollup.accept(bundle, lines);
	                        }
	                    }

	                    agreement.setUnitPrice(agreementGross);
	                    agreement.setDiscountAmount(agreementDiscount);
	                    agreement.setTaxAmount(agreementTax);
	                    rollup.accept(agreement, lines);
	                    break;
	                }

	                /* ============================== BUNDLE (standalone) ============================== */
	                case "bundle": {

	                    System.out.println("Bundle");
	                    InvoiceEntityDTO bundle = new InvoiceEntityDTO();

	                    UUID bundleInvoiceEntityId = UUID.randomUUID();
	                    UUID bundleBusinessEntityId = (e.getEntityId() != null) ? e.getEntityId() : UUID.randomUUID();

	                    bundle.setInvoiceEntityId(bundleInvoiceEntityId);
	                    bundle.setEntityId(bundleBusinessEntityId);
	                    bundle.setEntityTypeId(bundleTypeId);
	                    bundle.setEntityDescription("Bundle");
	                    bundle.setQuantity(def(e.getQuantity(), 1));
	                    bundle.setUnitPrice(BigDecimal.ZERO);
	                    bundle.setDiscountAmount(BigDecimal.ZERO);
	                    bundle.setTaxAmount(BigDecimal.ZERO);
	                    bundle.setContractStartDate(e.getStartDate());

	                    lines.add(bundle);

	                    BigDecimal bundleGross = BigDecimal.ZERO;
	                    BigDecimal bundleDiscount = BigDecimal.ZERO;
	                    BigDecimal bundleTax = BigDecimal.ZERO;

	                    UUID bundlePromotionId = e.getPromotionId();
	                    Map<UUID, PromotionItemEffectDTO> bundleFx = Collections.emptyMap();

	                    if (bundlePromotionId != null && e.getItems() != null && !e.getItems().isEmpty()) {
	                        Set<UUID> ids = collectItemIds(e.getItems());
	                        bundleFx = promotionEffectDAO.fetchEffectsByPromotionForItems(bundlePromotionId, ids, applicationId);
	                        try { System.out.println("Ids " + mapper.writeValueAsString(ids)); }
	                        catch (JsonProcessingException ex) { ex.printStackTrace(); }
	                    }

	                    try { System.out.println("PromoId" + bundlePromotionId + " Data " + mapper.writeValueAsString(bundleFx)); }
	                    catch (JsonProcessingException ex) { ex.printStackTrace(); }

	                    if (e.getItems() != null) {
	                        for (Item it : e.getItems()) {

	                            InvoiceEntityDTO itemLine = buildItemLineFromPayload(
	                                    it, bundleBusinessEntityId, itemTypeId, e.getStartDate(), false);

	                            itemLine.setParentInvoiceEntityId(bundleInvoiceEntityId);

	                            if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
	                                Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
	                                        it.getEntityId(), invoiceLevelId, e.getDiscountIds());

	                                best.ifPresent(d -> {
	                                    BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                    BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty);
	                                    BigDecimal discAmt = BigDecimal.ZERO;

	                                    switch (d.getCalculationMode()) {
	                                        case PERCENTAGE -> {
	                                            BigDecimal pct = nz(d.getDiscountRate());
	                                            discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	                                        }
	                                        case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
	                                        case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
	                                    }

	                                    itemLine.setDiscountAmount(scale2(nz(itemLine.getDiscountAmount()).add(discAmt)));

	                                    InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
	                                    row.setDiscountId(d.getDiscountId());
	                                    row.setDiscountAmount(scale2(discAmt));
	                                    row.setCalculationTypeId(d.getCalculationTypeId());
	                                    row.setAdjustmentTypeId(d.getAdjustmentTypeId());
	                                    itemLine.setDiscounts(Collections.singletonList(row));
	                                });
	                            }

	                            if (bundlePromotionId != null) {
	                                PromotionItemEffectDTO eff = bundleFx.get(it.getEntityId());
	                                applyPromotionEffectOnLeafLine(itemLine, eff);
	                            }

	                            System.out.println("[DEBUG][BEFORE_TAX][BUNDLE] itemId=" + it.getEntityId()
	                                    + " gross=" + scale2(lineSub(itemLine))
	                                    + " discount=" + scale2(itemLine.getDiscountAmount()));

	                            computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);

	                            finalizeLeaf(itemLine);

	                            subTotal = subTotal.add(lineSub(itemLine));
	                            taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
	                            discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

	                            BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                            BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
	                            bundleGross = bundleGross.add(childGross);
	                            bundleDiscount = bundleDiscount.add(nz(itemLine.getDiscountAmount()));
	                            bundleTax = bundleTax.add(nz(itemLine.getTaxAmount()));

	                            lines.add(itemLine);
	                        }
	                    }

	                    bundle.setUnitPrice(bundleGross);
	                    bundle.setDiscountAmount(bundleDiscount);
	                    bundle.setTaxAmount(bundleTax);

	                    rollup.accept(bundle, lines);
	                    break;
	                }

	                /* ============================== ITEM (standalone) ============================== */
	                case "item": {

	                    System.out.println("Item Case");

	                    UUID itemPromotionId = e.getPromotionId();
	                    Map<UUID, PromotionItemEffectDTO> itemFx = Collections.emptyMap();

	                    if (itemPromotionId != null) {
	                        Set<UUID> ids = new HashSet<>();
	                        if (e.getItems() != null && !e.getItems().isEmpty()) {
	                            ids.addAll(collectItemIds(e.getItems()));
	                        } else if (e.getEntityId() != null) {
	                            ids.add(e.getEntityId());
	                        }
	                        if (!ids.isEmpty()) {
	                            itemFx = promotionEffectDAO.fetchEffectsByPromotionForItems(itemPromotionId, ids, applicationId);
	                        }
	                    }

	                    if (e.getItems() != null && !e.getItems().isEmpty()) {
	                        for (Item it : e.getItems()) {

	                            InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, null, itemTypeId, e.getStartDate(), false);

	                            if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
	                                Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
	                                        it.getEntityId(), invoiceLevelId, e.getDiscountIds());

	                                best.ifPresent(d -> {
	                                    BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                    BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty);
	                                    BigDecimal discAmt = BigDecimal.ZERO;

	                                    switch (d.getCalculationMode()) {
	                                        case PERCENTAGE -> {
	                                            BigDecimal pct = nz(d.getDiscountRate());
	                                            discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	                                        }
	                                        case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
	                                        case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
	                                    }

	                                    itemLine.setDiscountAmount(scale2(nz(itemLine.getDiscountAmount()).add(discAmt)));

	                                    InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
	                                    row.setDiscountId(d.getDiscountId());
	                                    row.setDiscountAmount(scale2(discAmt));
	                                    row.setCalculationTypeId(d.getCalculationTypeId());
	                                    row.setAdjustmentTypeId(d.getAdjustmentTypeId());
	                                    itemLine.setDiscounts(Collections.singletonList(row));
	                                });
	                            }

	                            if (itemPromotionId != null) {
	                                PromotionItemEffectDTO eff = itemFx.get(it.getEntityId());
	                                applyPromotionEffectOnLeafLine(itemLine, eff);
	                            }

	                            System.out.println("[DEBUG][BEFORE_TAX][ITEM] itemId=" + it.getEntityId()
	                                    + " gross=" + scale2(lineSub(itemLine))
	                                    + " discount=" + scale2(itemLine.getDiscountAmount()));

	                            computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);

	                            finalizeLeaf(itemLine);

	                            subTotal = subTotal.add(lineSub(itemLine));
	                            taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
	                            discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

	                            lines.add(itemLine);
	                        }

	                    } else {

	                        Item it = new Item();
	                        it.setEntityId(e.getEntityId());
	                        it.setQuantity(def(e.getQuantity(), 1));
	                        it.setPrice(e.getPrice());
	                        it.setPricePlanTemplateId(e.getPricePlanTemplateId());
	                        it.setUpsellItem(Boolean.TRUE.equals(e.getUpsellItem()));

	                        InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, null, itemTypeId, e.getStartDate(), false);

	                        if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
	                            Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
	                                    it.getEntityId(), invoiceLevelId, e.getDiscountIds());

	                            best.ifPresent(d -> {
	                                BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
	                                BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty);
	                                BigDecimal discAmt = BigDecimal.ZERO;

	                                switch (d.getCalculationMode()) {
	                                    case PERCENTAGE -> {
	                                        BigDecimal pct = nz(d.getDiscountRate());
	                                        discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
	                                    }
	                                    case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
	                                    case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
	                                }

	                                itemLine.setDiscountAmount(scale2(nz(itemLine.getDiscountAmount()).add(discAmt)));

	                                InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
	                                row.setDiscountId(d.getDiscountId());
	                                row.setDiscountAmount(scale2(discAmt));
	                                row.setCalculationTypeId(d.getCalculationTypeId());
	                                row.setAdjustmentTypeId(d.getAdjustmentTypeId());
	                                itemLine.setDiscounts(Collections.singletonList(row));
	                            });
	                        }

	                        if (itemPromotionId != null) {
	                            PromotionItemEffectDTO eff = itemFx.get(it.getEntityId());
	                            applyPromotionEffectOnLeafLine(itemLine, eff);
	                        }

	                        System.out.println("[DEBUG][BEFORE_TAX][ITEM_SINGLE] itemId=" + it.getEntityId()
	                                + " gross=" + scale2(lineSub(itemLine))
	                                + " discount=" + scale2(itemLine.getDiscountAmount()));

	                        computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);

	                        finalizeLeaf(itemLine);

	                        subTotal = subTotal.add(lineSub(itemLine));
	                        taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
	                        discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));
	                        lines.add(itemLine);
	                    }

	                    break;
	                }

	                default:
	                    break;
	            }
	        }
	    }

	    inv.setSubTotal(scale2(subTotal));
	    inv.setTaxAmount(scale2(taxTotal));
	    inv.setDiscountAmount(scale2(discountTotal));
	    inv.setTotalAmount(scale2(subTotal.add(taxTotal).subtract(discountTotal)));
	    inv.setLineItems(lines);

	    System.out.println(
	            "[INVOICE][TOTALS] subTotal=" + scale2(subTotal) +
	            " taxTotal=" + scale2(taxTotal) +
	            " discountTotal=" + scale2(discountTotal) +
	            " finalTotal=" + scale2(subTotal.add(taxTotal).subtract(discountTotal))
	    );

	    try {
	        if (isAgreement) {
	            System.out.println("For Agreement");
	            UUID clientAgreementId = caHelper.createClientAgreementFromInvoice(request);
	            inv.setClientAgreementId(clientAgreementId);
	        }
	    } catch (Exception ex) {
	        System.out.println("Error in agreement purchase" + ex.getMessage());
	    }

	    UUID invoiceId = transactionDAO.saveInvoiceV3(inv);
	    String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

	    return new CreateInvoiceResponse(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	/* ===================== Promotion helpers (NEW) ===================== */

	private static void applyPromotionEffectOnLeafLine(InvoiceEntityDTO line, PromotionItemEffectDTO eff) {
		System.out.println("Inside");

		if (eff == null) return;

		BigDecimal qty = BigDecimal.valueOf(def(line.getQuantity(), 1));
		BigDecimal unitPrice = nz(line.getUnitPrice());
		BigDecimal lineSub = unitPrice.multiply(qty);

		String desc = (eff.getEffectTypeDescription() == null) ? "" : eff.getEffectTypeDescription().trim().toLowerCase();
		BigDecimal valueAmount = nz(eff.getValueAmount());
		BigDecimal valuePercent = nz(eff.getValuePercent());

		BigDecimal promoDiscount = BigDecimal.ZERO;

		if (desc.contains("fixed amount off")) {
			promoDiscount = valueAmount.multiply(qty);
		}
		else if (desc.contains("percentage off")) {
			if (valuePercent.compareTo(BigDecimal.ZERO) > 0) {
				promoDiscount = lineSub.multiply(valuePercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
			}
		}
		else if (desc.contains("set final amount")) {
			BigDecimal finalLine = valueAmount.multiply(qty);
			promoDiscount = lineSub.subtract(finalLine);
		}

		promoDiscount = promoDiscount.max(BigDecimal.ZERO).min(lineSub);
		System.out.println("PromoDiscount" + promoDiscount);

		line.setDiscountAmount(scale2(nz(line.getDiscountAmount()).add(promoDiscount)));
	}

	/* ===================== AGREEMENT ONLY: prorate AFTER discount, but skip FEE items ===================== */

	private void applyAgreementProrationAfterDiscountUnlessFee(InvoiceEntityDTO line, UUID itemId, UUID applicationId) {

		// If item group is FEE => NO proration
		boolean isFee = false;
		try {
			isFee = (itemId != null && applicationId != null) && transactionDAO.isFeeItem(itemId, applicationId);
		} catch (Exception ex) {
			// fail-safe: if lookup fails, don't block invoice; default to NOT fee
			System.out.println("[PRORATE][FEE_LOOKUP_FAILED] itemId=" + itemId + " err=" + ex.getMessage());
		}

		if (isFee) {
			System.out.println("[PRORATE][SKIP_FEE] itemId=" + itemId);
			return;
		}

		applyAgreementProrationAfterDiscount(line);
	}

	private void applyAgreementProrationAfterDiscount(InvoiceEntityDTO line) {

	    if (line == null) return;

	    int qtyInt = def(line.getQuantity(), 1);
	    if (qtyInt <= 0) qtyInt = 1;

	    LocalDate start = (line.getContractStartDate() != null) ? line.getContractStartDate() : LocalDate.now();
	    BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start); // 0..1

	    // ratio = (factor + (qty-1)) / qty
	    BigDecimal qty = BigDecimal.valueOf(qtyInt);
	    BigDecimal ratio = factor.add(BigDecimal.valueOf(qtyInt - 1))
	            .divide(qty, 10, RoundingMode.HALF_UP);

	    BigDecimal grossBefore = lineSub(line);            // unitPrice * qty
	    BigDecimal discBefore  = nz(line.getDiscountAmount());  // FULL discount (do NOT prorate)

	    // ✅ NET before proration
	    BigDecimal netBefore = grossBefore.subtract(discBefore);
	    if (netBefore.compareTo(BigDecimal.ZERO) < 0) netBefore = BigDecimal.ZERO;

	    // ✅ prorate ONLY NET
	    BigDecimal netAfter = scale2(netBefore.multiply(ratio));

	    // ✅ Keep discount unchanged, adjust gross so that:
	    // grossAfter - discount = netAfter
	    BigDecimal grossAfter = scale2(netAfter.add(discBefore));

	    // Safety: grossAfter can’t be less than discount
	    if (grossAfter.compareTo(discBefore) < 0) grossAfter = discBefore;

	    // ✅ collapse into 1 line
	    line.setQuantity(1);
	    line.setUnitPrice(grossAfter);
	    line.setDiscountAmount(scale2(discBefore));

	    System.out.println("[PRORATE][AGREEMENT_NET_ONLY] itemId=" + line.getEntityId()
	            + " factor=" + factor
	            + " ratio=" + ratio
	            + " grossBefore=" + scale2(grossBefore)
	            + " discBefore=" + scale2(discBefore)
	            + " netBefore=" + scale2(netBefore)
	            + " netAfter=" + scale2(netAfter)
	            + " grossAfter=" + scale2(grossAfter)
	            + " discAfter=" + scale2(discBefore));
	}


	private static Set<UUID> collectItemIds(List<Item> items) {
		Set<UUID> set = new HashSet<>();
		if (items == null) return set;
		for (Item it : items) {
			if (it != null && it.getEntityId() != null) set.add(it.getEntityId());
		}
		return set;
	}

	private static Set<UUID> collectItemIdsFromBundles(List<Bundle> bundles) {
		Set<UUID> set = new HashSet<>();
		if (bundles == null) return set;
		for (Bundle b : bundles) {
			if (b == null || b.getItems() == null) continue;
			for (Item it : b.getItems()) {
				if (it != null && it.getEntityId() != null) set.add(it.getEntityId());
			}
		}
		return set;
	}

	/* ======== Helpers (unchanged, except for tax computation) ======== */

	private InvoiceEntityDTO buildItemLineFromPayload(
	        Item it,
	        UUID parentId,
	        UUID itemTypeId,
	        LocalDate startDate,
	        boolean prorateEnabled
	) {
	    InvoiceEntityDTO line = new InvoiceEntityDTO();
	    System.out.println("buildItemLineFromPayload");

	    line.setEntityId(UUID.randomUUID());
	    line.setParentInvoiceEntityId(parentId);
	    line.setPricePlanTemplateId(it.getPricePlanTemplateId());
	    line.setEntityTypeId(itemTypeId);
	    line.setEntityId(it.getEntityId());
	    line.setEntityDescription("Item");
	    line.setPriceBands(it.getPriceBands());
	    line.setContractStartDate(startDate);

	    boolean isProrateApplicable = prorateEnabled;
	    System.out.println("isProrateApplicable " + isProrateApplicable);

	    if (!CollectionUtils.isEmpty(line.getPriceBands())) {

	        List<BundlePriceCycleBandDTO> bundlePriceBands = transactionDAO
	                .findByPriceCycleBandId(line.getPriceBands().get(0).getPriceCycleBandId());

	        if (!CollectionUtils.isEmpty(bundlePriceBands)) {
	            BundlePriceCycleBandDTO band = bundlePriceBands.get(0);
	            int dpUnits = def(band.getDownPaymentUnits(), 1);

	            BigDecimal fullUnit = scale2(bd(band.getUnitPrice().doubleValue()));
	            System.out.println("Full Unit " + fullUnit);

	            if (isProrateApplicable) {
	                System.out.println("here " + dpUnits);

	                LocalDate start = (line.getContractStartDate() != null)
	                        ? line.getContractStartDate()
	                        : LocalDate.now();

	                BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start);
	                BigDecimal proratedUnit = scale2(fullUnit.multiply(factor));
	                System.out.println("proratedUnit " + proratedUnit);

	                if (dpUnits <= 1) {
	                    System.out.println("here2");
	                    line.setQuantity(dpUnits);
	                    line.setUnitPrice(proratedUnit);
	                } else {
	                    System.out.println("here1");
	                    BigDecimal remainingunitDownPayment = band.getUnitPrice().multiply(BigDecimal.valueOf(dpUnits - 1));
	                    System.out.println("remainingunitDownPayment " + remainingunitDownPayment);
	                    line.setQuantity(1);
	                    line.setUnitPrice(proratedUnit.add(remainingunitDownPayment));
	                }
	            } else {
	                line.setQuantity(dpUnits);
	                line.setUnitPrice(fullUnit);
	            }
	        }

	    } else {
	        line.setQuantity(def(it.getQuantity(), 1));
	        BigDecimal unit = scale2(bd(it.getPrice()));

	        if (isProrateApplicable) {
	            LocalDate start = (line.getContractStartDate() != null)
	                    ? line.getContractStartDate()
	                    : LocalDate.now();
	            BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start);
	            unit = scale2(unit.multiply(factor));
	            System.out.println("[PRORATE][NO_BANDS] itemId=" + it.getEntityId()
	                    + " baseUnit=" + scale2(bd(it.getPrice()))
	                    + " factor=" + factor
	                    + " proratedUnit=" + unit);
	        }

	        line.setUnitPrice(unit);
	    }

	    line.setDiscountAmount(BigDecimal.ZERO);
	    line.setTaxAmount(BigDecimal.ZERO);
	    line.setUpsellItem(Boolean.TRUE.equals(it.getUpsellItem()));
	    return line;
	}

	/** TAX: item-level only (tax on NET = gross - discount) */
	private void computeTaxesFromItemOnly(InvoiceEntityDTO line, Item it, UUID levelId) {

	    System.out.println("computeTaxesFromItemOnly(net)");

	    UUID taxGroupId = null;
	    try {
	        taxGroupId = transactionDAO.findTaxGroupIdForItem(it.getEntityId(), levelId);
	    } catch (Exception ignore) {}

	    if (taxGroupId == null) {
	        line.setTaxAmount(BigDecimal.ZERO);
	        return;
	    }

	    List<TaxRateAllocationDTO> taxAllocs = transactionDAO.getTaxRatesByGroupAndLevel(taxGroupId, levelId);
	    if (taxAllocs == null || taxAllocs.isEmpty()) {
	        line.setTaxAmount(BigDecimal.ZERO);
	        return;
	    }

	    BigDecimal qty = BigDecimal.valueOf(def(line.getQuantity(), 1));
	    BigDecimal gross = nz(line.getUnitPrice()).multiply(qty);
	    BigDecimal discount = nz(line.getDiscountAmount());

	    BigDecimal taxableBase = gross.subtract(discount);
	    if (taxableBase.compareTo(BigDecimal.ZERO) < 0) taxableBase = BigDecimal.ZERO;

	    System.out.println("[TAX][BASE] itemId=" + it.getEntityId()
	            + " gross=" + scale2(gross)
	            + " discount=" + scale2(discount)
	            + " taxableBase=" + scale2(taxableBase));

	    List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
	    BigDecimal taxAmount = BigDecimal.ZERO;

	    for (TaxRateAllocationDTO tr : taxAllocs) {
	        InvoiceEntityTaxDTO tx = new InvoiceEntityTaxDTO();
	        tx.setTaxRateId(tr.getTaxRateId());
	        tx.setTaxRateAllocationId(tr.getTaxRateAllocationId());
	        tx.setTaxRate(scale2(nz(tr.getTaxRatePercentage())));

	        BigDecimal thisTax = taxableBase.multiply(tx.getTaxRate())
	                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

	        tx.setTaxAmount(thisTax);
	        taxes.add(tx);
	        taxAmount = taxAmount.add(thisTax);
	    }

	    line.setTaxes(taxes);
	    line.setTaxAmount(scale2(taxAmount));
	}

	private static void finalizeLeaf(InvoiceEntityDTO line) {
		System.out.println("finalizeLeaf");
		BigDecimal q = BigDecimal.valueOf(def(line.getQuantity(), 1));
		BigDecimal sub = nz(line.getUnitPrice()).multiply(q);
		BigDecimal total = sub.add(nz(line.getTaxAmount())).subtract(nz(line.getDiscountAmount()));
		line.setTotalAmount(scale2(total));
	}

	private static int def(Integer n, int d) {
		return n == null ? d : n;
	}

	private static BigDecimal bd(Double d) {
		return d == null ? BigDecimal.ZERO : BigDecimal.valueOf(d);
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private static BigDecimal scale2(BigDecimal v) {
		return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
	}

	private static BigDecimal lineSub(InvoiceEntityDTO line) {
		return nz(line.getUnitPrice()).multiply(BigDecimal.valueOf(def(line.getQuantity(), 1)));
	}

	private static <T> List<T> nvlList(List<T> v) {
		return v == null ? Collections.emptyList() : v;
	}

	private static List<UUID> mergeDiscountIds(List<UUID>... lists) {
		LinkedHashSet<UUID> s = new LinkedHashSet<>();
		for (List<UUID> l : lists)
			if (l != null)
				s.addAll(l);
		return new ArrayList<>(s);
	}

	private static UUID resolvePromotion(UUID itemPromo, UUID bundlePromo, UUID agreementPromo) {
		return itemPromo != null ? itemPromo : (bundlePromo != null ? bundlePromo : agreementPromo);
	}

	@Override
	public List<InvoiceSummaryDTO> listInvoicesByClientRole(UUID clientRoleId, Integer limit, Integer offset) {
		if (clientRoleId == null) {
			throw new IllegalArgumentException("clientRoleId is required");
		}
		return transactionDAO.findByClientRole(clientRoleId, limit, offset);
	}

	@Override
	@Transactional(readOnly = true)
	public InvoiceDetailDTO getInvoiceDetail(UUID invoiceId) {
		final InvoiceDetailRaw raw = transactionDAO.loadInvoiceAggregate(invoiceId)
				.orElseThrow(() -> new NoSuchElementException("Invoice not found: " + invoiceId));

		final int currentCycle = Optional.ofNullable(raw.currentCycleNumber()).orElse(0);
		final int totalCycle = Optional.ofNullable(raw.totalCycles()).orElse(0);

		final BigDecimal priceForCurrentCycle = raw.amountGrossInclTax();

		final FrequencyUnit unit = FrequencyUnit.fromDb(raw.frequencyName());
		final int interval = Optional.ofNullable(raw.intervalCount()).orElse(1);
		final LocalDate refDate = Optional.ofNullable(raw.instanceNextBillingDate())
				.orElse(Optional.ofNullable(raw.instanceLastBilledOn()).orElse(raw.instanceStartDate()));

		final String billingFrequencyLabel = switch (unit) {
		case MONTH -> "Monthly on the " + (refDate != null ? refDate.getDayOfMonth() : 1)
				+ getDaySuffix(refDate != null ? refDate.getDayOfMonth() : 1);
		case WEEK -> "Every " + interval + (interval == 1 ? " week" : " weeks");
		case YEAR -> "Yearly";
		case DAY -> "Daily";
		};

		Integer numerator = totalCycle - raw.remainingCycles();
		Integer denominator = totalCycle;

		List<PaymentTimelineItemDTO> timeline = buildTimeline(unit, interval, raw, priceForCurrentCycle);

		UUID parentEntityId = raw.parentEntityId() != null ? raw.parentEntityId() : raw.childEntityTypeId();
		UUID parentEntityTypeId = raw.parentEntityTypeId() != null ? raw.parentEntityTypeId() : raw.childEntityTypeId();
		UUID levelId = raw.levelId();

		Optional<EntityLevelInfoDTO> enityDetail = entityLookupDao.resolveEntityAndLevel(parentEntityTypeId, parentEntityId, levelId);
		String entityName = "";
		String locationName = "";
		if (enityDetail.isPresent()) {
			entityName = enityDetail.get().entityName();
			locationName = enityDetail.get().levelName();
		}

		return new InvoiceDetailDTO(raw.invoiceId(), raw.invoiceNumber(), raw.invoiceDate(), raw.invoiceStatus(),
				raw.invoiceAmount(), raw.invoiceBalanceDue(), raw.invoiceWriteOff(), raw.salesRep(),

				"CONTRACT", "ACTIVE", entityName, locationName, priceForCurrentCycle,

				numerator, denominator, billingFrequencyLabel, raw.contractEndDate(), raw.instanceNextBillingDate(),
				raw.instanceStartDate(), raw.contractStartDate(),

				null,
				null,

				"Membership · Base Membership",
				true,
				"—", null,

				timeline);
	}

	private static List<PaymentTimelineItemDTO> buildTimeline(FrequencyUnit unit, int interval, InvoiceDetailRaw raw,
			BigDecimal cycleAmount) {
		List<PaymentTimelineItemDTO> list = new ArrayList<>();
		list.add(new PaymentTimelineItemDTO(raw.invoiceDate(), raw.invoiceAmount(),
				(raw.invoiceStatus().equalsIgnoreCase("PAID")) ? true : false));

		LocalDate paidDate = raw.instanceLastBilledOn();
		if (paidDate != null) {
			list.add(new PaymentTimelineItemDTO(paidDate, cycleAmount, true));
		}

		LocalDate start = Optional.ofNullable(raw.instanceNextBillingDate())
				.orElseGet(() -> paidDate != null ? addCycles(paidDate, unit, interval, 1) : raw.instanceStartDate());

		LocalDate d = start;
		for (int i = 0; i < raw.remainingCycles() - (paidDate != null ? 1 : 0); i++) {
			list.add(new PaymentTimelineItemDTO(d, cycleAmount, false));
			d = addCycles(d, unit, interval, 1);
			if (raw.instanceEndDate() != null && d.isAfter(raw.instanceEndDate()))
				break;
		}
		return list;
	}

	private static LocalDate addCycles(LocalDate date, FrequencyUnit unit, int interval, int count) {
		int steps = Math.max(1, interval) * Math.max(1, count);
		return switch (unit) {
		case DAY -> date.plusDays(steps);
		case WEEK -> date.plusWeeks(steps);
		case MONTH -> date.plusMonths(steps);
		case YEAR -> date.plusYears(steps);
		};
	}

	private static String getDaySuffix(int day) {
		if (day >= 11 && day <= 13)
			return "th";
		return switch (day % 10) {
		case 1 -> "st";
		case 2 -> "nd";
		case 3 -> "rd";
		default -> "th";
		};
	}
	
	public InvoiceRequest buildFutureInvoiceRequest(UUID invoiceId, int cycleNumber, LocalDate billingDate, UUID actorId) {

	    if (invoiceId == null) throw new IllegalArgumentException("invoiceId is required");
	    if (cycleNumber <= 0) throw new IllegalArgumentException("cycleNumber must be >= 1");
	    if (billingDate == null) billingDate = LocalDate.now();

	    // 1) seed from old invoice
	    final var seed = transactionDAO.fetchInvoiceSeed(invoiceId);

	    // 2) leaf billable items from old invoice (the ones that used cycle bands)
	    final var oldLines = transactionDAO.fetchBillableLeafLines(invoiceId);
	    if (oldLines == null || oldLines.isEmpty()) {
	        throw new IllegalStateException("No billable leaf lines found for invoiceId=" + invoiceId);
	    }

	    // 3) rebuild Items using the *new* price_cycle_band_id for this cycleNumber
	    final List<Item> items = new ArrayList<>();

	    for (var ln : oldLines) {
	        UUID planTemplateId = ln.pricePlanTemplateId();
	        if (planTemplateId == null) continue;

	        UUID newBandId = transactionDAO.resolveCycleBandId(planTemplateId, cycleNumber);
	        if (newBandId == null) {
	            throw new IllegalStateException(
	                "No cycle band found for planTemplateId=" + planTemplateId + " cycle=" + cycleNumber
	            );
	        }

	        Item it = new Item();
	        it.setEntityId(ln.entityId());                 // bill same item again
	        it.setPricePlanTemplateId(planTemplateId);     // needed by your invoice pipeline
	        it.setQuantity(1);                             // recurring invoices are typically 1 cycle at a time

	        // Put the NEW priceCycleBandId into payload so buildItemLineFromPayload() picks correct band.
	        InvoiceEntityPriceBandDTO bandRef = new InvoiceEntityPriceBandDTO();
	        bandRef.setPriceCycleBandId(newBandId);

	        it.setPriceBands(List.of(bandRef));

	        items.add(it);
	    }

	    // 4) wrap into your InvoiceRequest structure.
	    //    Minimal safe structure: use a single "Entity" of type ITEM and attach items list.
	    //    If you want root AGREEMENT/PACKAGE structure, you can enhance this later.
	    Entity root = new Entity();
	    root.setEntityTypeId(transactionDAO.findEntityTypeIdByName("Item")); // reuse your existing lookup
	    root.setStartDate(billingDate);
	    root.setItems(items);

	    InvoiceRequest req = new InvoiceRequest();
	    req.setClientRoleId(seed.clientRoleId());
	    req.setBillingAddress(seed.billingAddress());
	    req.setCreatedBy(actorId != null ? actorId : seed.createdBy());
	    req.setEntities(List.of(root));

	    // Optional (recommended): if your createInvoice uses invoice level id fixed today,
	    // you might want to pass/derive it; your current code hardcodes inv.setLevelId(...)
	    // so either update createInvoice OR keep it consistent.
	    return req;
	}
	
	public CreateInvoiceResponse createFutureInvoice(UUID invoiceId, int cycleNumber, LocalDate billingDate, UUID actorId) {
	    InvoiceRequest req = buildFutureInvoiceRequest(invoiceId, cycleNumber, billingDate, actorId);
	    return createInvoice(req);
	}


}


