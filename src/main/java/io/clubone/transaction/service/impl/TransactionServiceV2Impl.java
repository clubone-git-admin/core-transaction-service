package io.clubone.transaction.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import io.clubone.transaction.v2.vo.CycleBandRef;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.v2.vo.Entity;
import io.clubone.transaction.v2.vo.EntityLevelInfoDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailDTO;
import io.clubone.transaction.v2.vo.InvoiceDetailRaw;
import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPriceBandDTO;
import io.clubone.transaction.v2.vo.InvoiceEntityPromotionDTO;
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

	@Autowired
	private PromotionEffectDAO promotionEffectDAO;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public CreateInvoiceResponse createInvoice(InvoiceRequest request) {

		final UUID agreementTypeId = transactionDAO.findEntityTypeIdByName("Agreement");
		final UUID bundleTypeId = transactionDAO.findEntityTypeIdByName("Bundle");
		final UUID itemTypeId = transactionDAO.findEntityTypeIdByName("Item");
		final UUID invoiceStatusId = UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c");

		BigDecimal subTotal = BigDecimal.ZERO;      // NET (gross-discount)
		BigDecimal taxTotal = BigDecimal.ZERO;      // TAX on NET base
		BigDecimal discountTotal = BigDecimal.ZERO; // informational

		ObjectMapper mapper = new ObjectMapper();
		UUID applicationId = UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011");

		final Map<UUID, UUID> agreementBusinessIdToRootInvoiceEntityId = new HashMap<>();


		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(request.getClientRoleId());

		// kept as-is (your invoice level)
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
				if (container.getInvoiceEntityId().equals(c.getParentInvoiceEntityId())) {
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
								final int parentQty = def(e.getQuantity(), 1); // package qty
								final int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(
										it.getPricePlanTemplateId(),
										UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011")
								);
								final int finalQty = qtyFromParentAndEntitlement(parentQty, entQty);

								itemLine.setQuantity(finalQty);

								System.out.println("[QTY][PACKAGE] parentQty=" + parentQty
										+ " entQty=" + entQty
										+ " finalQty=" + finalQty
										+ " planTemplateId=" + it.getPricePlanTemplateId());

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

								subTotal = subTotal.add(lineNet(itemLine));
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
						//agreementRootInvoiceEntityIds.add(agreementInvoiceEntityId);

						UUID agreementBusinessEntityId = (e.getEntityId() != null) ? e.getEntityId() : UUID.randomUUID();

						isAgreement = true;
						
						agreementBusinessIdToRootInvoiceEntityId.put(agreementBusinessEntityId, agreementInvoiceEntityId);
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

										// Build FULL (no proration). Proration applied AFTER discount+promo (and NOT for FEE items)
										InvoiceEntityDTO itemLine = buildItemLineFromPayload(
												it, bundleBusinessEntityId, itemTypeId, e.getStartDate(), false);

										itemLine.setParentInvoiceEntityId(bundleInvoiceEntityId);

										final int agreementQty = def(e.getQuantity(), 1);
										final int bundleQty = def(b.getQuantity(), 1);
										final int parentQty = agreementQty * bundleQty;

										final int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(
												it.getPricePlanTemplateId(),
												UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011")
										);
										final int finalQty = qtyFromParentAndEntitlement(parentQty, entQty);

										itemLine.setQuantity(finalQty);

										System.out.println("[QTY][AGREEMENT] agreementQty=" + agreementQty
												+ " bundleQty=" + bundleQty
												+ " parentQty=" + parentQty
												+ " entQty=" + entQty
												+ " finalQty=" + finalQty
												+ " planTemplateId=" + it.getPricePlanTemplateId());

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

										// ==========================================================
										// ✅ AGREEMENT PRORATION RULES (supports Monthly/Weekly/Quarterly/Yearly)
										//
										// RULES:
										// 1) If Fee item => charge CURRENT only, FULL (no proration), no next line.
										// 2) Else ALWAYS charge "remaining of current calendar period" prorated.
										// 3) If startDate AFTER billing cutoff in that period => ALSO add NEXT period FULL.
										//
										// Monthly: period = calendar month, cutoff = billing day-of-month (e.g., 15)
										// Weekly:  period = ISO week (Mon..Sun), cutoff = billing weekday (e.g., MON)
										// Quarterly: period = calendar quarter, cutoff = day-of-month within quarter-start month (default 1)
										// Yearly: period = calendar year, cutoff = MM-DD (recommended) or day-of-month in start month
										// ==========================================================

										final LocalDate startDate = e.getStartDate() != null ? e.getStartDate() : LocalDate.now();
										final int billedQty = def(itemLine.getQuantity(), 1);

										boolean isFee = false;
										try {
											isFee = transactionDAO.isFeeItem(it.getEntityId(), applicationId);
										} catch (Exception ex) {
											System.out.println("[FEE_LOOKUP_FAILED] itemId=" + it.getEntityId() + " err=" + ex.getMessage());
										}

										if (isFee) {
											System.out.println("[FEE] current only. No proration, no next. itemId=" + it.getEntityId());

											// keep qty & full gross; do NOT prorate
											itemLine.setQuantity(billedQty);

											computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);
											finalizeLeaf(itemLine);

											subTotal = subTotal.add(lineNet(itemLine));
											taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
											discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

											BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
											BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
											bundleGross = bundleGross.add(childGross);
											bundleDiscount = bundleDiscount.add(nz(itemLine.getDiscountAmount()));
											bundleTax = bundleTax.add(nz(itemLine.getTaxAmount()));

											lines.add(itemLine);
											continue;
										}

										// Resolve frequency + cutoff + calendar period
										final CalendarBillingContext ctx = resolveCalendarBillingContextForPlan(it.getPricePlanTemplateId(), startDate);

										System.out.println("[AGREEMENT][CTX] itemId=" + it.getEntityId()
												+ " freq=" + ctx.freq
												+ " interval=" + ctx.interval
												+ " periodStart=" + ctx.periodStart
												+ " periodEndExclusive=" + ctx.periodEndExclusive
												+ " cutoffDate=" + ctx.cutoffDate
												+ " startAfterCutoff=" + ctx.startAfterCutoff);

										// (A) CURRENT prorated (remaining in current period)
										itemLine.setQuantity(billedQty); // capture qty before collapse
										BigDecimal factor = remainingFactor(startDate, ctx.periodStart, ctx.periodEndExclusive);
										applyAgreementProrationAfterDiscountWithFactor(itemLine, factor);

										computeTaxesFromItemOnly(itemLine, it, invoiceLevelId);
										finalizeLeaf(itemLine);

										subTotal = subTotal.add(lineNet(itemLine));
										taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
										discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

										BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
										BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
										bundleGross = bundleGross.add(childGross);
										bundleDiscount = bundleDiscount.add(nz(itemLine.getDiscountAmount()));
										bundleTax = bundleTax.add(nz(itemLine.getTaxAmount()));

										lines.add(itemLine);

										// (B) NEXT FULL only if startDate AFTER cutoff
										if (ctx.startAfterCutoff) {

											// Next period full line starts at next period start
											LocalDate nextStart = ctx.periodEndExclusive;

											InvoiceEntityDTO nextLine = buildItemLineFromPayload(
													it, bundleBusinessEntityId, itemTypeId, nextStart, false);

											nextLine.setParentInvoiceEntityId(bundleInvoiceEntityId);
											nextLine.setQuantity(billedQty);

											// re-apply discount + promo
											applyDiscountIds(e, it, nextLine, invoiceLevelId);
											if (agreementPromotionId != null) {
												PromotionItemEffectDTO eff = agreementFx.get(it.getEntityId());
												applyPromotionEffectOnLeafLine(nextLine, eff);
											}

											computeTaxesFromItemOnly(nextLine, it, invoiceLevelId);
											finalizeLeaf(nextLine);

											subTotal = subTotal.add(lineNet(nextLine));
											taxTotal = taxTotal.add(nz(nextLine.getTaxAmount()));
											discountTotal = discountTotal.add(nz(nextLine.getDiscountAmount()));

											BigDecimal nQty = BigDecimal.valueOf(def(nextLine.getQuantity(), 1));
											BigDecimal nGross = nz(nextLine.getUnitPrice()).multiply(nQty);
											bundleGross = bundleGross.add(nGross);
											bundleDiscount = bundleDiscount.add(nz(nextLine.getDiscountAmount()));
											bundleTax = bundleTax.add(nz(nextLine.getTaxAmount()));

											lines.add(nextLine);

											System.out.println("[AGREEMENT][NEXT_FULL] itemId=" + it.getEntityId()
													+ " nextStart=" + nextStart);
										}

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
								final int parentQty = def(e.getQuantity(), 1); // bundle qty
								final int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(
										it.getPricePlanTemplateId(),
										UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011")
								);
								final int finalQty = qtyFromParentAndEntitlement(parentQty, entQty);

								itemLine.setQuantity(finalQty);

								System.out.println("[QTY][BUNDLE] parentQty=" + parentQty
										+ " entQty=" + entQty
										+ " finalQty=" + finalQty
										+ " planTemplateId=" + it.getPricePlanTemplateId());

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

								subTotal = subTotal.add(lineNet(itemLine));
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

						System.out.println("Item Case "+e.getClientAgreementId());

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
								final int parentQty = def(e.getQuantity(), 1);
								final int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(
										it.getPricePlanTemplateId(),
										UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011")
								);
								final int finalQty = qtyFromParentAndEntitlement(parentQty, entQty);

								itemLine.setQuantity(finalQty);

								System.out.println("[QTY][ITEM_LIST] parentQty=" + parentQty
										+ " entQty=" + entQty
										+ " finalQty=" + finalQty
										+ " planTemplateId=" + it.getPricePlanTemplateId());

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

								subTotal = subTotal.add(lineNet(itemLine));
								taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
								discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));
								itemLine.setClientAgreementId(e.getClientAgreementId());

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

							final int parentQty = def(e.getQuantity(), 1);
							final int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(
									it.getPricePlanTemplateId(),
									UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011")
							);
							final int finalQty = qtyFromParentAndEntitlement(parentQty, entQty);

							itemLine.setQuantity(finalQty);
							itemLine.setClientAgreementId(e.getClientAgreementId());

							System.out.println("[QTY][ITEM_SINGLE] parentQty=" + parentQty
									+ " entQty=" + entQty
									+ " finalQty=" + finalQty
									+ " planTemplateId=" + e.getPricePlanTemplateId());

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

							subTotal = subTotal.add(lineNet(itemLine));
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

		inv.setSubTotal(scale2(subTotal));                // ✅ NET
		inv.setTaxAmount(scale2(taxTotal));
		inv.setDiscountAmount(scale2(discountTotal));     // informational
		inv.setTotalAmount(scale2(subTotal.add(taxTotal)));// ✅ discount already applied

		inv.setLineItems(lines);

		System.out.println(
				"[INVOICE][TOTALS] netSubTotal=" + scale2(subTotal) +
						" taxTotal=" + scale2(taxTotal) +
						" discountTotal=" + scale2(discountTotal) +
						" finalTotal=" + scale2(subTotal.add(taxTotal))
		);

		/*try {
			if (isAgreement) {
				System.out.println("For Agreement");
				UUID clientAgreementId = caHelper.createClientAgreementFromInvoice(request);
				inv.setClientAgreementId(clientAgreementId);
			}
		} catch (Exception ex) {
			System.out.println("Error in agreement purchase" + ex.getMessage());
		}*/
		UUID firstClientAgreementId = null;

		try {
		    if (isAgreement && request.getEntities() != null && !request.getEntities().isEmpty()) {

		        // Build parent map once for stamping (performance + correctness)
		        final Map<UUID, UUID> parentMap = buildParentMap(lines);

		        for (Entity e : request.getEntities()) {

		            // Only AGREEMENT entities
		            Optional<EntityTypeDTO> entityTypeOpt = transactionDAO.getEntityTypeById(
		                    e.getEntityTypeId() != null ? e.getEntityTypeId()
		                            : UUID.fromString("b1c9b95c-5fe0-4062-af85-12ad47908948"));

		            String t = entityTypeOpt.get().getEntityType().toLowerCase();
		            if (!"agreement".equalsIgnoreCase(t)) continue;

		            final UUID agreementBusinessEntityId = e.getEntityId();
		            if (agreementBusinessEntityId == null) {
		                System.out.println("[AGREEMENT][SKIP] agreement entityId is null");
		                continue;
		            }

		            final UUID agreementRootInvoiceEntityId =
		                    agreementBusinessIdToRootInvoiceEntityId.get(agreementBusinessEntityId);

		            if (agreementRootInvoiceEntityId == null) {
		                System.out.println("[AGREEMENT][SKIP] no root invoiceEntityId found for agreementId=" + agreementBusinessEntityId);
		                continue;
		            }

		            System.out.println("[AGREEMENT][CREATE_CA] agreementId=" + agreementBusinessEntityId
		                    + " rootInvoiceEntityId=" + agreementRootInvoiceEntityId);

		            // ✅ Create a "single-agreement request" and reuse existing helper
		            InvoiceRequest perAgreementReq = new InvoiceRequest();
		            perAgreementReq.setClientRoleId(request.getClientRoleId());
		            perAgreementReq.setBillingAddress(request.getBillingAddress());
		            perAgreementReq.setCreatedBy(request.getCreatedBy());
		            perAgreementReq.setLevelId(request.getLevelId());
		            perAgreementReq.setEntities(List.of(e)); // ONLY this agreement

		            UUID clientAgreementId = caHelper.createClientAgreementFromInvoice(perAgreementReq);

		            if (firstClientAgreementId == null) firstClientAgreementId = clientAgreementId;

		            // ✅ Stamp ONLY this agreement subtree (agreement + bundles + items)
		            stampClientAgreementForRoot(lines, parentMap, agreementRootInvoiceEntityId, clientAgreementId);

		            System.out.println("[AGREEMENT][STAMPED_ROOT] agreementId=" + agreementBusinessEntityId
		                    + " clientAgreementId=" + clientAgreementId);
		        }

		        // ⚠️ Invoice has only one clientAgreementId field.
		        // Keep first one for backward compatibility (optional).
		        if (firstClientAgreementId != null) {
		            inv.setClientAgreementId(firstClientAgreementId);
		        }
		    }
		} catch (Exception ex) {
		    System.out.println("Error in agreement purchase: " + ex.getMessage());
		}



		UUID invoiceId = transactionDAO.saveInvoiceV3(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

		return new CreateInvoiceResponse(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	/* ============================================================
	   DISCOUNTS
	   ============================================================ */

	private void applyDiscountIds(Entity rootEntity, Item it, InvoiceEntityDTO line, UUID invoiceLevelId) {

		if (rootEntity == null || it == null || line == null) return;
		if (invoiceLevelId == null) return;

		if (rootEntity.getDiscountIds() == null || rootEntity.getDiscountIds().isEmpty()) return;

		Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
				it.getEntityId(),
				invoiceLevelId,
				rootEntity.getDiscountIds()
		);

		if (best.isEmpty()) return;

		DiscountDetailDTO d = best.get();

		BigDecimal qty = BigDecimal.valueOf(def(line.getQuantity(), 1));
		BigDecimal lineSub = nz(line.getUnitPrice()).multiply(qty);

		BigDecimal discAmt = BigDecimal.ZERO;

		switch (d.getCalculationMode()) {
			case PERCENTAGE -> {
				BigDecimal pct = nz(d.getDiscountRate());
				discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
			}
			case AMOUNT_PER_QTY -> discAmt = nz(d.getDiscountAmount()).multiply(qty);
			case AMOUNT_PER_LINE -> discAmt = nz(d.getDiscountAmount());
			default -> discAmt = BigDecimal.ZERO;
		}

		line.setDiscountAmount(scale2(nz(line.getDiscountAmount()).add(discAmt)));

		InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
		row.setDiscountId(d.getDiscountId());
		row.setDiscountAmount(scale2(discAmt));
		row.setCalculationTypeId(d.getCalculationTypeId());
		row.setAdjustmentTypeId(d.getAdjustmentTypeId());
		line.setDiscounts(Collections.singletonList(row));

		System.out.println("[DISCOUNT] itemId=" + it.getEntityId()
				+ " qty=" + qty
				+ " lineSub=" + scale2(lineSub)
				+ " mode=" + d.getCalculationMode()
				+ " disc=" + scale2(discAmt)
				+ " totalDiscNow=" + scale2(line.getDiscountAmount()));
	}

	private int qtyFromParentAndEntitlement(int parentQty, int entQty) {
		return Math.max(1, parentQty) * Math.max(1, entQty);
	}

	private int resolveBillingDayOfMonthForPlan(UUID planTemplateId) {
		UUID ruleId = transactionDAO.findBillingDayRuleIdForPlanTemplate(planTemplateId);
		Integer day = transactionDAO.findBillingDayOfMonth(ruleId);
		return (day == null || day < 1 || day > 31) ? 1 : day;
	}

	private static BigDecimal lineNet(InvoiceEntityDTO line) {
		BigDecimal gross = lineSub(line);
		BigDecimal disc = nz(line.getDiscountAmount());
		BigDecimal net = gross.subtract(disc);
		return (net.compareTo(BigDecimal.ZERO) < 0) ? BigDecimal.ZERO : net;
	}

	/* ============================================================
	   PROMOTIONS
	   ============================================================ */

	private static void applyPromotionEffectOnLeafLine(InvoiceEntityDTO line, PromotionItemEffectDTO eff) {

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
		} else if (desc.contains("percentage off")) {
			if (valuePercent.compareTo(BigDecimal.ZERO) > 0) {
				promoDiscount = lineSub.multiply(valuePercent)
						.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
			}
		} else if (desc.contains("set final amount")) {
			BigDecimal finalLine = valueAmount.multiply(qty);
			promoDiscount = lineSub.subtract(finalLine);
		}

		promoDiscount = promoDiscount.max(BigDecimal.ZERO).min(lineSub);

		line.setDiscountAmount(scale2(nz(line.getDiscountAmount()).add(promoDiscount)));

		UUID pvId = eff.getPromotionVersionId();
		UUID paId = eff.getPromotionApplicabilityId();
		UUID peId = eff.getPromotionEffectId();

		if (pvId != null && paId != null && peId != null && promoDiscount.compareTo(BigDecimal.ZERO) > 0) {

			InvoiceEntityPromotionDTO row = new InvoiceEntityPromotionDTO();
			row.setPromotionVersionId(pvId);
			row.setPromotionApplicabilityId(paId);
			row.setPromotionEffectId(peId);
			row.setPromotionAmount(scale2(promoDiscount));

			List<InvoiceEntityPromotionDTO> list =
					(line.getPromotions() == null) ? new ArrayList<>() : new ArrayList<>(line.getPromotions());
			list.add(row);
			line.setPromotions(list);
		}

		System.out.println("[PROMO] itemId=" + line.getEntityId()
				+ " promoDiscount=" + scale2(promoDiscount)
				+ " pvId=" + pvId
				+ " paId=" + paId
				+ " peId=" + peId);
	}

	/* ============================================================
	   AGREEMENT PRORATION (calendar-period based + cutoff)
	   ============================================================ */

	/**
	 * Applies proration AFTER discount+promo.
	 * - Discount remains unchanged
	 * - Net is multiplied by factor
	 * - Quantity collapsed to 1
	 */
	private void applyAgreementProrationAfterDiscountWithFactor(InvoiceEntityDTO line, BigDecimal factor) {
	    if (line == null) return;

	    BigDecimal f = nz(factor);
	    if (f.compareTo(BigDecimal.ZERO) < 0) f = BigDecimal.ZERO;
	    if (f.compareTo(BigDecimal.ONE) > 0) f = BigDecimal.ONE;

	    int qtyInt = def(line.getQuantity(), 1);
	    if (qtyInt < 1) qtyInt = 1;

	    BigDecimal qty = BigDecimal.valueOf(qtyInt);

	    // Totals BEFORE proration
	    BigDecimal grossBefore = nz(line.getUnitPrice()).multiply(qty);      // total gross for all entitled qty
	    BigDecimal discBefore  = nz(line.getDiscountAmount());              // total discount already computed for qty
	    BigDecimal netBefore   = grossBefore.subtract(discBefore);
	    if (netBefore.compareTo(BigDecimal.ZERO) < 0) netBefore = BigDecimal.ZERO;

	    // Prorate NET only
	    BigDecimal netAfter = scale2(netBefore.multiply(f));

	    // Keep discount unchanged; rebuild total gross after proration
	    BigDecimal grossAfter = scale2(netAfter.add(discBefore));
	    if (grossAfter.compareTo(discBefore) < 0) grossAfter = discBefore;

	    // Convert total grossAfter back into PER-UNIT unit price while keeping qty same
	    BigDecimal perUnitAfter = grossAfter.divide(qty, 6, RoundingMode.HALF_UP);
	    perUnitAfter = scale2(perUnitAfter);

	    line.setQuantity(qtyInt);                  // ✅ KEEP entitlement qty (2)
	    line.setUnitPrice(perUnitAfter);           // ✅ per-unit price so totals remain correct
	    line.setDiscountAmount(scale2(discBefore));// total discount remains same

	    System.out.println("[PRORATE][KEEP_QTY] itemId=" + line.getEntityId()
	            + " qty=" + qtyInt
	            + " factor=" + f
	            + " grossBefore=" + scale2(grossBefore)
	            + " discBefore=" + scale2(discBefore)
	            + " netBefore=" + scale2(netBefore)
	            + " netAfter=" + scale2(netAfter)
	            + " grossAfter=" + scale2(grossAfter)
	            + " perUnitAfter=" + perUnitAfter);
	}

	/**
	 * Remaining factor inside a calendar period:
	 * factor = remainingDays / totalDays
	 * where periodEndExclusive is exclusive boundary.
	 */
	private static BigDecimal remainingFactor(LocalDate startDate, LocalDate periodStart, LocalDate periodEndExclusive) {
		if (startDate == null || periodStart == null || periodEndExclusive == null) return BigDecimal.ONE;
		if (!startDate.isBefore(periodEndExclusive)) return BigDecimal.ZERO;

		long total = ChronoUnit.DAYS.between(periodStart, periodEndExclusive);
		if (total <= 0) return BigDecimal.ONE;

		long remaining = ChronoUnit.DAYS.between(startDate, periodEndExclusive);
		if (remaining < 0) remaining = 0;
		if (remaining > total) remaining = total;

		return BigDecimal.valueOf(remaining)
				.divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
	}

	private static class CalendarBillingContext {
		final String freq;                 // MONTHLY / WEEKLY / QUARTERLY / YEARLY / ANNUAL
		final int interval;                // interval count (>=1)
		final LocalDate periodStart;       // calendar period start
		final LocalDate periodEndExclusive;// next period start (exclusive)
		final LocalDate cutoffDate;        // billing cutoff inside the period
		final boolean startAfterCutoff;    // startDate > cutoffDate

		CalendarBillingContext(String freq, int interval,
							   LocalDate periodStart, LocalDate periodEndExclusive,
							   LocalDate cutoffDate, boolean startAfterCutoff) {
			this.freq = freq;
			this.interval = interval;
			this.periodStart = periodStart;
			this.periodEndExclusive = periodEndExclusive;
			this.cutoffDate = cutoffDate;
			this.startAfterCutoff = startAfterCutoff;
		}
	}

	private CalendarBillingContext resolveCalendarBillingContextForPlan(UUID planTemplateId, LocalDate startDate) {

		// defaults
		if (planTemplateId == null || startDate == null) {
			LocalDate ms = LocalDate.now().withDayOfMonth(1);
			LocalDate me = ms.plusMonths(1);
			LocalDate cutoff = ms.withDayOfMonth(1);
			return new CalendarBillingContext("MONTHLY", 1, ms, me, cutoff, false);
		}

		String freq = safeUpper(transactionDAO.findFrequencyNameForPlanTemplate(planTemplateId));
		Integer intervalCount = transactionDAO.findIntervalCountForPlanTemplate(planTemplateId);
		int interval = (intervalCount == null || intervalCount < 1) ? 1 : intervalCount;

		UUID ruleId = transactionDAO.findBillingDayRuleIdForPlanTemplate(planTemplateId);
		String billingDayText = transactionDAO.findBillingDayText(ruleId); // TEXT
		billingDayText = (billingDayText == null) ? "" : billingDayText.trim();

		switch (freq) {

			case "WEEKLY": {
				DayOfWeek weekStartDow = DayOfWeek.MONDAY; // ISO week start
				LocalDate weekStart = startDate.with(TemporalAdjusters.previousOrSame(weekStartDow));
				LocalDate weekEndExcl = weekStart.plusWeeks(interval);

				DayOfWeek cutoffDow = parseDayOfWeekOrNull(billingDayText);
				if (cutoffDow == null) cutoffDow = DayOfWeek.MONDAY;

				// cutoff inside weekStart..weekStart+6
				LocalDate cutoff = weekStart.with(TemporalAdjusters.nextOrSame(cutoffDow));
				if (cutoff.isBefore(weekStart) || !cutoff.isBefore(weekEndExcl)) {
					// if weird (due to interval >1), clamp to weekStart
					cutoff = weekStart;
				}

				boolean after = startDate.isAfter(cutoff);
				return new CalendarBillingContext(freq, interval, weekStart, weekEndExcl, cutoff, after);
			}

			case "QUARTERLY": {
				int monthsPerPeriod = 3 * interval;

				int m = startDate.getMonthValue();
				int baseQuarterStart = ((m - 1) / monthsPerPeriod) * monthsPerPeriod + 1;
				LocalDate periodStart = LocalDate.of(startDate.getYear(), baseQuarterStart, 1);
				LocalDate periodEndExcl = periodStart.plusMonths(monthsPerPeriod);

				int cutoffDom = parseDayOfMonthOrDefault(billingDayText, 1);
				LocalDate cutoff = setSafeDayOfMonth(periodStart, cutoffDom);

				boolean after = startDate.isAfter(cutoff);
				return new CalendarBillingContext(freq, interval, periodStart, periodEndExcl, cutoff, after);
			}

			case "YEARLY":
			case "ANNUAL": {
				LocalDate periodStart = LocalDate.of(startDate.getYear(), 1, 1);
				LocalDate periodEndExcl = periodStart.plusYears(interval);

				LocalDate cutoff = resolveYearlyAnchorWithinYear(startDate.getYear(), startDate, billingDayText);

				boolean after = startDate.isAfter(cutoff);
				return new CalendarBillingContext(freq, interval, periodStart, periodEndExcl, cutoff, after);
			}

			case "MONTHLY":
			default: {
				int monthsPerPeriod = interval;

				int m = startDate.getMonthValue();
				int periodStartMonth = ((m - 1) / monthsPerPeriod) * monthsPerPeriod + 1;
				LocalDate periodStart = LocalDate.of(startDate.getYear(), periodStartMonth, 1);
				LocalDate periodEndExcl = periodStart.plusMonths(monthsPerPeriod);

				int cutoffDom = parseDayOfMonthOrDefault(billingDayText, 1);

				// cutoff is in startDate's month (not necessarily periodStart month if interval>1),
				// but for your "after 15th in this month" requirement, you want cutoff inside the current month.
				// So compute monthCutoff in startDate's month.
				LocalDate monthStart = startDate.withDayOfMonth(1);
				LocalDate cutoff = setSafeDayOfMonth(monthStart, cutoffDom);

				boolean after = startDate.isAfter(cutoff);
				return new CalendarBillingContext(freq, interval, monthStart, monthStart.plusMonths(1), cutoff, after);
			}
		}
	}

	private static LocalDate resolveYearlyAnchorWithinYear(int year, LocalDate startDate, String billingDayText) {
		String s = billingDayText == null ? "" : billingDayText.trim();

		// "MM-DD"
		if (s.matches("^\\d{2}-\\d{2}$")) {
			int mm = Integer.parseInt(s.substring(0, 2));
			int dd = Integer.parseInt(s.substring(3, 5));
			LocalDate base = LocalDate.of(year, mm, 1);
			return setSafeDayOfMonth(base, dd);
		}

		// numeric => day-of-month in startDate's month
		int day = parseDayOfMonthOrDefault(s, 1);
		LocalDate base = LocalDate.of(year, startDate.getMonthValue(), 1);
		return setSafeDayOfMonth(base, day);
	}

	private static String safeUpper(String s) {
		return (s == null ? "" : s.trim()).toUpperCase();
	}

	private static int parseDayOfMonthOrDefault(String billingDayText, int def) {
		if (billingDayText == null) return def;
		String s = billingDayText.trim();

		try {
			int d = Integer.parseInt(s);
			return (d >= 1 && d <= 31) ? d : def;
		} catch (Exception ignore) {
			if ("EOM".equalsIgnoreCase(s) || "END_OF_MONTH".equalsIgnoreCase(s) || "LAST".equalsIgnoreCase(s)) return 31;
			return def;
		}
	}

	private static DayOfWeek parseDayOfWeekOrNull(String s) {
		if (s == null) return null;
		String v = s.trim().toUpperCase();

		// numeric 1..7 => MON..SUN (ISO)
		try {
			int n = Integer.parseInt(v);
			if (n >= 1 && n <= 7) return DayOfWeek.of(n);
		} catch (Exception ignore) {}

		if (v.startsWith("MON")) return DayOfWeek.MONDAY;
		if (v.startsWith("TUE")) return DayOfWeek.TUESDAY;
		if (v.startsWith("WED")) return DayOfWeek.WEDNESDAY;
		if (v.startsWith("THU")) return DayOfWeek.THURSDAY;
		if (v.startsWith("FRI")) return DayOfWeek.FRIDAY;
		if (v.startsWith("SAT")) return DayOfWeek.SATURDAY;
		if (v.startsWith("SUN")) return DayOfWeek.SUNDAY;
		return null;
	}

	private static LocalDate setSafeDayOfMonth(LocalDate baseMonthFirst, int day) {
		int last = baseMonthFirst.lengthOfMonth();
		int safe = Math.min(Math.max(day, 1), last);
		return baseMonthFirst.withDayOfMonth(safe);
	}

	/* ============================================================
	   ITEM COLLECTION HELPERS
	   ============================================================ */

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

	/* ============================================================
	   LINE BUILDING
	   ============================================================ */

	private InvoiceEntityDTO buildItemLineFromPayload(
			Item it,
			UUID parentId,
			UUID itemTypeId,
			LocalDate startDate,
			boolean prorateEnabled
	) {
		InvoiceEntityDTO line = new InvoiceEntityDTO();
		System.out.println("buildItemLineFromPayload");

		line.setInvoiceEntityId(UUID.randomUUID());
		line.setParentInvoiceEntityId(parentId);

		line.setEntityId(it.getEntityId());
		line.setEntityTypeId(itemTypeId);
		line.setEntityDescription("Item");

		line.setPricePlanTemplateId(it.getPricePlanTemplateId());
		line.setPriceBands(it.getPriceBands());
		line.setContractStartDate(startDate);

		// quantity set OUTSIDE
		line.setQuantity(1);

		boolean isProrateApplicable = prorateEnabled;
		System.out.println("isProrateApplicable " + isProrateApplicable);

		BigDecimal unitPrice;

		if (!CollectionUtils.isEmpty(line.getPriceBands())) {

			List<BundlePriceCycleBandDTO> bundlePriceBands = transactionDAO
					.findByPriceCycleBandId(line.getPriceBands().get(0).getPriceCycleBandId());

			if (CollectionUtils.isEmpty(bundlePriceBands)) {
				unitPrice = scale2(bd(it.getPrice()));
			} else {
				BundlePriceCycleBandDTO band = bundlePriceBands.get(0);

				BigDecimal fullUnit = scale2(bd(band.getUnitPrice().doubleValue()));
				System.out.println("Full Unit " + fullUnit);

				unitPrice = fullUnit;

				if (isProrateApplicable) {
					LocalDate start = (line.getContractStartDate() != null)
							? line.getContractStartDate()
							: LocalDate.now();

					BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start);
					unitPrice = scale2(fullUnit.multiply(factor));

					System.out.println("[PRORATE][BANDS] itemId=" + it.getEntityId()
							+ " baseUnit=" + fullUnit
							+ " factor=" + factor
							+ " proratedUnit=" + unitPrice);
				}
			}

		} else {

			unitPrice = scale2(bd(it.getPrice()));

			if (isProrateApplicable) {
				LocalDate start = (line.getContractStartDate() != null)
						? line.getContractStartDate()
						: LocalDate.now();
				BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start);
				unitPrice = scale2(unitPrice.multiply(factor));

				System.out.println("[PRORATE][NO_BANDS] itemId=" + it.getEntityId()
						+ " baseUnit=" + scale2(bd(it.getPrice()))
						+ " factor=" + factor
						+ " proratedUnit=" + unitPrice);
			}
		}

		line.setUnitPrice(unitPrice);

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

	/* ============================================================
	   INVOICE LIST/DETAIL (unchanged)
	   ============================================================ */

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
				(raw.invoiceStatus().equalsIgnoreCase("PAID"))));

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

	/* ============================================================
	   FUTURE INVOICE (unchanged from your latest)
	   ============================================================ */

	public InvoiceRequest buildFutureInvoiceRequest(
			UUID invoiceId,
			int cycleNumber,
			LocalDate billingDate,
			UUID actorId,UUID clientAgreementId
	) {

		if (invoiceId == null) throw new IllegalArgumentException("invoiceId is required");
		if (cycleNumber <= 0) throw new IllegalArgumentException("cycleNumber must be >= 1");
		if (billingDate == null) billingDate = LocalDate.now();

		final var seed = transactionDAO.fetchInvoiceSeed(invoiceId);
		final UUID applicationId = UUID.fromString("5949a200-82fb-4171-9001-0f77ac439011");
		final UUID createdBy = (actorId != null ? actorId : seed.createdBy());
		System.out.println("cycleNumber " + cycleNumber);
		final var oldLines = transactionDAO.fetchBillableLeafLines(invoiceId, cycleNumber,clientAgreementId);
		System.out.println("Client agreement id "+clientAgreementId);
		if (oldLines == null || oldLines.isEmpty()) {
			throw new IllegalStateException("No billable leaf lines found for invoiceId=" + invoiceId);
		}

		UUID promotionId = transactionDAO.findPromotionIdAppliedOnInvoice(invoiceId).orElse(null);

		final List<Item> items = new ArrayList<>();

		for (var ln : oldLines) {
			UUID itemId = ln.entityId();
			UUID planTemplateId = ln.pricePlanTemplateId();
			if (itemId == null || planTemplateId == null) continue;

			CycleBandRef band = transactionDAO.resolveCycleBand(planTemplateId, cycleNumber);
			if (band == null) {
			    throw new IllegalStateException(
			        "No cycle band found for planTemplateId=" + planTemplateId + " cycle=" + cycleNumber
			    );
			}

			int entQty = transactionDAO.resolveDefaultQtyFromEntitlement(planTemplateId, applicationId);
			int parentQty = 1;
			int finalQty = Math.max(1, parentQty) * Math.max(1, entQty);

			Item it = new Item();
			it.setEntityId(itemId);
			it.setPricePlanTemplateId(planTemplateId);
			it.setQuantity(finalQty);

			InvoiceEntityPriceBandDTO bandRef = new InvoiceEntityPriceBandDTO();
			bandRef.setPriceCycleBandId(band.bandId());
			bandRef.setUnitPrice(band.unitPrice()); // ✅ from DB
			it.setPriceBands(List.of(bandRef));

			items.add(it);

			System.out.println("[FUTURE][QTY] itemId=" + itemId
					+ " planTemplateId=" + planTemplateId
					+ " entQty=" + entQty
					+ " finalQty=" + finalQty
					+ " cycle=" + cycleNumber
					+ " bandId=" + bandRef.getPriceCycleBandId());
		}

		Entity root = new Entity();
		root.setEntityTypeId(transactionDAO.findEntityTypeIdByName("Item"));
		LocalDate startDate = billingDate
		        .plusMonths(1)
		        .withDayOfMonth(1);

		root.setStartDate(startDate);

		root.setItems(items);
		root.setClientAgreementId(clientAgreementId);
		System.out.println("Root agreement "+root.getClientAgreementId());
		if (promotionId != null) {
			root.setPromotionId(promotionId);
		}

		InvoiceRequest req = new InvoiceRequest();
		req.setClientRoleId(seed.clientRoleId());
		req.setBillingAddress(seed.billingAddress());
		req.setCreatedBy(createdBy);
		req.setEntities(List.of(root));

		return req;
	}
	
	private static Map<UUID, UUID> buildParentMap(List<InvoiceEntityDTO> lines) {
	    Map<UUID, UUID> parent = new java.util.HashMap<>();
	    if (lines == null) return parent;
	    for (InvoiceEntityDTO l : lines) {
	        if (l == null || l.getInvoiceEntityId() == null) continue;
	        parent.put(l.getInvoiceEntityId(), l.getParentInvoiceEntityId());
	    }
	    return parent;
	}

	private static void stampClientAgreementForRoot(
	        List<InvoiceEntityDTO> lines,
	        Map<UUID, UUID> parentMap,
	        UUID rootInvoiceEntityId,
	        UUID clientAgreementId
	) {
	    if (clientAgreementId == null) return;
	    if (rootInvoiceEntityId == null) return;
	    if (lines == null || lines.isEmpty()) return;

	    for (InvoiceEntityDTO l : lines) {
	        if (l == null || l.getInvoiceEntityId() == null) continue;

	        if (isDescendantOfRoot(l.getInvoiceEntityId(), rootInvoiceEntityId, parentMap)) {
	            l.setClientAgreementId(clientAgreementId);
	        }
	    }
	}

	private static boolean isDescendantOfRoot(UUID nodeId, UUID rootId, Map<UUID, UUID> parentMap) {
	    if (nodeId == null || rootId == null) return false;

	    UUID cur = nodeId;
	    int guard = 0;

	    while (cur != null && guard++ < 1000) {
	        if (cur.equals(rootId)) return true;
	        cur = parentMap.get(cur);
	    }
	    return false;
	}



	@Override
	public CreateInvoiceResponse createFutureInvoice(UUID invoiceId, int cycleNumber, LocalDate billingDate, UUID actorId,UUID clientAgreementId) {
		System.out.println("Building future invoice request");
		InvoiceRequest req = buildFutureInvoiceRequest(invoiceId, cycleNumber, billingDate, actorId, clientAgreementId);
		System.out.println("Creating future invoice");
		return createInvoice(req);
	}
}
