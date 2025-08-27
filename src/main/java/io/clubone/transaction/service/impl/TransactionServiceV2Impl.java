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
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.helper.TransactionUtils;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.service.TransactionServicev2;
import io.clubone.transaction.v2.vo.Bundle;
import io.clubone.transaction.v2.vo.BundlePriceCycleBandDTO;
import io.clubone.transaction.v2.vo.DiscountDetailDTO;
import io.clubone.transaction.v2.vo.Entity;
import io.clubone.transaction.v2.vo.InvoiceEntityDiscountDTO;
import io.clubone.transaction.v2.vo.InvoiceRequest;
import io.clubone.transaction.v2.vo.Item;
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

	@Override
	@Transactional(rollbackFor = Exception.class)
	public CreateInvoiceResponse createInvoice(InvoiceRequest request) {
		final UUID agreementTypeId = transactionDAO.findEntityTypeIdByName("Agreement");
		final UUID bundleTypeId = transactionDAO.findEntityTypeIdByName("Bundle");
		final UUID itemTypeId = transactionDAO.findEntityTypeIdByName("Item");
		final UUID invoiceStatusId = UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c");

		BigDecimal subTotal = BigDecimal.ZERO;
		BigDecimal taxTotal = BigDecimal.ZERO;
		BigDecimal discountTotal = BigDecimal.ZERO;

		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(request.getClientRoleId());
		inv.setLevelId(request.getLevelId());
		inv.setBillingAddress(request.getBillingAddress());
		inv.setInvoiceStatusId(invoiceStatusId);
		inv.setPaid(false);
		inv.setCreatedBy(request.getCreatedBy());

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
			container.setUnitPrice(nz(container.getUnitPrice())); // keep 0 for container
			container.setDiscountAmount(childDisc);
			container.setTaxAmount(childTax);
			container.setTotalAmount(childSub.add(childTax).subtract(childDisc));
		};

		if (request.getEntities() != null) {
			for (Entity e : request.getEntities()) {
				Optional<EntityTypeDTO> entityTypeOpt = transactionDAO.getEntityTypeById(e.getEntityTypeId());
				String t = entityTypeOpt.get().getEntityType();
				t=t.toLowerCase();

				switch (t) {
				case "agreement": {
					InvoiceEntityDTO agreement = new InvoiceEntityDTO();
					agreement.setEntityId(UUID.randomUUID());
					agreement.setEntityTypeId(agreementTypeId);
					agreement.setEntityId(e.getEntityId());
					agreement.setEntityDescription("Agreement");
					agreement.setQuantity(def(e.getQuantity(), 1));
					agreement.setUnitPrice(BigDecimal.ZERO);
					agreement.setDiscountAmount(BigDecimal.ZERO);
					agreement.setTaxAmount(BigDecimal.ZERO);
					agreement.setContractStartDate(e.getStartDate());
					lines.add(agreement);

					if (e.getBundles() != null) {
						for (Bundle b : e.getBundles()) {
							InvoiceEntityDTO bundle = new InvoiceEntityDTO();
							bundle.setEntityId(UUID.randomUUID());
							bundle.setParentInvoiceEntityId(agreement.getEntityId());
							bundle.setEntityTypeId(bundleTypeId);
							bundle.setEntityId(b.getEntityId());
							bundle.setEntityDescription("Bundle");
							bundle.setQuantity(def(b.getQuantity(), 1));
							bundle.setUnitPrice(BigDecimal.ZERO);
							bundle.setDiscountAmount(BigDecimal.ZERO);
							bundle.setTaxAmount(BigDecimal.ZERO);
							lines.add(bundle);

							if (b.getItems() != null) {
								for (Item it : b.getItems()) {
									System.out.println("here");
									InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, bundle.getEntityId(),
											itemTypeId,e.getStartDate());
									computeTaxesFromItemOnly(itemLine, it, request.getLevelId());
									finalizeLeaf(itemLine);
									subTotal = subTotal.add(lineSub(itemLine));
									taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
									discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));
									lines.add(itemLine);
								}
							}
							rollup.accept(bundle, lines);
						}
					}
					rollup.accept(agreement, lines);
					break;
				}

				case "bundle": {
					InvoiceEntityDTO bundle = new InvoiceEntityDTO();

					UUID bundleInvoiceEntityId = UUID.randomUUID(); // invoice line id for bundle
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

					if (e.getItems() != null) {
						for (Item it : e.getItems()) {
							// Build child line under this bundle (business parent id if your builder uses
							// it)
							InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, bundleBusinessEntityId,
									itemTypeId,e.getStartDate());

							// Link to parent bundle invoice line (for payments/allocations)
							itemLine.setParentInvoiceEntityId(bundleInvoiceEntityId);

							// 1) taxes (DB-based, unchanged)
							computeTaxesFromItemOnly(itemLine, it, request.getLevelId());

							// 2) discounts (use best among provided discountIds)
							if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
								Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
										it.getEntityId(), // itemId from payload
										request.getLevelId(), // levelId
										e.getDiscountIds()); // candidate discount ids

								best.ifPresent(d -> {
									BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
									BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty); // base for %
									BigDecimal discAmt = BigDecimal.ZERO;

									switch (d.getCalculationMode()) {
									case PERCENTAGE -> {
										BigDecimal pct = nz(d.getDiscountRate()); // e.g. 10.00 = 10%
										discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2,
												RoundingMode.HALF_UP);
									}
									case AMOUNT_PER_QTY -> {
										discAmt = nz(d.getDiscountAmount()).multiply(qty);
									}
									case AMOUNT_PER_LINE -> {
										discAmt = nz(d.getDiscountAmount());
									}
									// default: no-op
									}

									itemLine.setDiscountAmount(scale2(discAmt));

									// attach discount row so it persists
									InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
									row.setDiscountId(d.getDiscountId());
									row.setDiscountAmount(scale2(discAmt));
									// Optionally include additional identifiers if your saver expects them:
									row.setCalculationTypeId(d.getCalculationTypeId());
									row.setAdjustmentTypeId(d.getAdjustmentTypeId());
									itemLine.setDiscounts(Collections.singletonList(row));
								});
							}

							// 3) finalize and accumulate totals
							finalizeLeaf(itemLine);

							subTotal = subTotal.add(lineSub(itemLine));
							taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
							discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));

							// Bundle rollup (use gross pre-discount for visibility; adjust if you prefer
							// net)
							BigDecimal qty = BigDecimal.valueOf(def(itemLine.getQuantity(), 1));
							BigDecimal childGross = nz(itemLine.getUnitPrice()).multiply(qty);
							bundleGross = bundleGross.add(childGross);
							bundleDiscount = bundleDiscount.add(nz(itemLine.getDiscountAmount()));
							bundleTax = bundleTax.add(nz(itemLine.getTaxAmount()));

							lines.add(itemLine);
						}
					}

					// Set rolled-up amounts on the bundle line
					bundle.setUnitPrice(bundleGross); // pre-discount, pre-tax total of children
					bundle.setDiscountAmount(bundleDiscount);
					bundle.setTaxAmount(bundleTax);

					rollup.accept(bundle, lines);
					break;
				}

				case "item": {
					if (e.getItems() != null && !e.getItems().isEmpty()) {
						for (Item it : e.getItems()) {
							InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, null, itemTypeId,e.getStartDate());

							// 1) taxes (DB-based, unchanged)
							computeTaxesFromItemOnly(itemLine, it, request.getLevelId());

							// 2) discounts (based on discountIds passed in payload)
							if (e.getDiscountIds() != null && !e.getDiscountIds().isEmpty()) {
								Optional<DiscountDetailDTO> best = transactionDAO.findBestDiscountForItemByIds(
										it.getEntityId(), request.getLevelId(), e.getDiscountIds());

								best.ifPresent(d -> {
									BigDecimal qty = BigDecimal.valueOf(def(it.getQuantity(), 1));
									BigDecimal lineSub = nz(itemLine.getUnitPrice()).multiply(qty); // base for %
																									// discounts
									BigDecimal discAmt = BigDecimal.ZERO;

									switch (d.getCalculationMode()) {
									case PERCENTAGE -> {
										BigDecimal pct = nz(d.getDiscountRate()); // e.g., 10.00 = 10%
										discAmt = lineSub.multiply(pct).divide(new BigDecimal("100"), 2,
												RoundingMode.HALF_UP);
									}
									case AMOUNT_PER_QTY -> {
										discAmt = nz(d.getDiscountAmount()).multiply(qty);
									}
									case AMOUNT_PER_LINE -> {
										discAmt = nz(d.getDiscountAmount());
									}
									}

									itemLine.setDiscountAmount(scale2(discAmt));

									// (optional but recommended) also attach a discount row for persistence
									InvoiceEntityDiscountDTO row = new InvoiceEntityDiscountDTO();
									row.setDiscountId(d.getDiscountId());
									row.setDiscountAmount(scale2(discAmt));
									row.setCalculationTypeId(d.getCalculationTypeId()); // if your save layer
									// expects these
									row.setAdjustmentTypeId(d.getAdjustmentTypeId());
									itemLine.setDiscounts(Collections.singletonList(row));
								});
							}

							// 3) totals
							finalizeLeaf(itemLine); // uses unitPrice, qty, taxAmount, discountAmount
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
						it.setPricePlanTemplateId(e.getPricePlanTemplateId()); // ignored for tax
						it.setUpsellItem(Boolean.TRUE.equals(e.getUpsellItem()));
						// it.setDiscountIds(e.getDiscountIds().);

						InvoiceEntityDTO itemLine = buildItemLineFromPayload(it, null, itemTypeId,e.getStartDate());
						computeTaxesFromItemOnly(itemLine, it, request.getLevelId());
						finalizeLeaf(itemLine);
						subTotal = subTotal.add(lineSub(itemLine));
						taxTotal = taxTotal.add(nz(itemLine.getTaxAmount()));
						discountTotal = discountTotal.add(nz(itemLine.getDiscountAmount()));
						lines.add(itemLine);
					}
					break;
				}

				default:
					// ignore or throw
					break;
				}
			}
		}

		inv.setSubTotal(scale2(subTotal));
		inv.setTaxAmount(scale2(taxTotal));
		inv.setDiscountAmount(scale2(discountTotal));
		inv.setTotalAmount(scale2(subTotal.add(taxTotal).subtract(discountTotal)));
		inv.setLineItems(lines);

		UUID invoiceId = transactionDAO.saveInvoiceV3(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);
		return new CreateInvoiceResponse(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	/* ======== Helpers (unchanged, except for tax computation) ======== */

	private InvoiceEntityDTO buildItemLineFromPayload(Item it, UUID parentId, UUID itemTypeId, LocalDate startDate) {
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
		// Resolve type name (if you don't already have it in context)
		// String typeName = itemTypeLookupDAO.getTypeNameOrThrow(it.getBundleItemId());

		boolean isProrateApplicable = transactionDAO.isProrateApplicable(it.getPricePlanTemplateId());
		System.out.println("isProrateApplicable "+isProrateApplicable );
		if (!CollectionUtils.isEmpty(line.getPriceBands())) {
		    List<BundlePriceCycleBandDTO> bundlePriceBands =
		            transactionDAO.findByPriceCycleBandId(line.getPriceBands().get(0).getPriceCycleBandId());

		    if (!CollectionUtils.isEmpty(bundlePriceBands)) {
		        BundlePriceCycleBandDTO band = bundlePriceBands.get(0);
		        int dpUnits = def(band.getDownPaymentUnits(), 1);

		        // Full unit price (2 dp)
		        BigDecimal fullUnit = scale2(bd(band.getUnitPrice().doubleValue()));

		        if (isProrateApplicable) {
		            // Proration only for Access
		        	System.out.println("here");
		            LocalDate start = (line.getContractStartDate() != null) ? line.getContractStartDate() : LocalDate.now();
		            BigDecimal factor = transactionUtils.prorateFactorForCurrentMonth(start);
		            BigDecimal proratedUnit = scale2(fullUnit.multiply(factor)); // round to 2 dp

		            if (dpUnits <= 1) {
		                // Only one unit: charge prorated for the current month
		            	System.out.println("here2");
		                line.setQuantity(1);
		                line.setUnitPrice(proratedUnit);
		            } else {
		            	System.out.println("here1");
		                // First unit prorated on the current line
		            	BigDecimal remainingunitDownPayment=band.getUnitPrice().multiply(BigDecimal.valueOf(dpUnits-1));
		                line.setQuantity(1);
		                line.setUnitPrice(proratedUnit.add(remainingunitDownPayment));		               
		            }
		        } else {
		            // Non-Access: your original behavior
		            line.setQuantity(dpUnits);
		            line.setUnitPrice(fullUnit);
		        }
		    }
		} else {
		    // No price band; keep your existing fallback
		    line.setQuantity(def(it.getQuantity(), 1));
		    line.setUnitPrice(scale2(bd(it.getPrice())));
		}

		line.setDiscountAmount(BigDecimal.ZERO);
		line.setTaxAmount(BigDecimal.ZERO);
		line.setUpsellItem(Boolean.TRUE.equals(it.getUpsellItem()));
		

		/*
		 * if (it.getDiscountIds() != null && !it.getDiscountIds().isEmpty()) {
		 * List<InvoiceEntityDiscountDTO> discounts = new ArrayList<>(); for (UUID dId :
		 * it.getDiscountIds()) { InvoiceEntityDiscountDTO d = new
		 * InvoiceEntityDiscountDTO(); d.setDiscountId(dId);
		 * d.setDiscountAmount(BigDecimal.ZERO); // plug discount engine if any
		 * discounts.add(d); } line.setDiscounts(discounts); }
		 */

		return line;
	}

	/** TAX: item-level only */
	private void computeTaxesFromItemOnly(InvoiceEntityDTO line, Item it, UUID levelId) {

		System.out.println("computeTaxesFromItemOnly");
		UUID taxGroupId = null;
		try {
			taxGroupId = transactionDAO.findTaxGroupIdForItem(it.getEntityId(), levelId);
		} catch (Exception ignore) {
		}

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
		BigDecimal base = nz(line.getUnitPrice()).multiply(qty);

		List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
		BigDecimal taxAmount = BigDecimal.ZERO;

		for (TaxRateAllocationDTO tr : taxAllocs) {
			InvoiceEntityTaxDTO t = new InvoiceEntityTaxDTO();
			t.setTaxRateId(tr.getTaxRateId());
			t.setTaxRate(scale2(nz(tr.getTaxRatePercentage()))); // percent
			BigDecimal thisTax = base.multiply(t.getTaxRate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
			t.setTaxAmount(thisTax);
			taxes.add(t);
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

	// --- small utils ---
	// private static BigDecimal nz(BigDecimal v) { return v == null ?
	// BigDecimal.ZERO : v; }
	// private static BigDecimal scale2(BigDecimal v) { return (v == null ?
	// BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP); }
	private static <T> List<T> nvlList(List<T> v) {
		return v == null ? Collections.emptyList() : v;
	}
	// private static int def(Integer v, int d) { return v == null ? d : v; }

	// Merge discount ids from different scopes without duplicates; order preserved
	private static List<UUID> mergeDiscountIds(List<UUID>... lists) {
		LinkedHashSet<UUID> s = new LinkedHashSet<>();
		for (List<UUID> l : lists)
			if (l != null)
				s.addAll(l);
		return new ArrayList<>(s);
	}

	/**
	 * Promotion precedence: item > bundle > agreement. Returns the first non-null.
	 */
	private static UUID resolvePromotion(UUID itemPromo, UUID bundlePromo, UUID agreementPromo) {
		return itemPromo != null ? itemPromo : (bundlePromo != null ? bundlePromo : agreementPromo);
	}

	/** Compute line-subtotal (gross before adjustments). */
	/*
	 * private static BigDecimal lineSub(InvoiceEntityDTO line) { BigDecimal q =
	 * BigDecimal.valueOf(def(line.getQuantity(), 1)); return
	 * nz(line.getUnitPrice()).multiply(q); }
	 */

}
