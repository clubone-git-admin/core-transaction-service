package io.clubone.transaction.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.clubone.transaction.dao.InvoiceDAO;
import io.clubone.transaction.dao.TransactionDAO;
import io.clubone.transaction.helper.AgreementHelper;
import io.clubone.transaction.helper.SubscriptionPlanHelper;
import io.clubone.transaction.request.CreateInvoiceRequest;
import io.clubone.transaction.request.CreateInvoiceRequestV3;
import io.clubone.transaction.request.CreateTransactionRequest;
import io.clubone.transaction.request.FinalizeTransactionRequest;
import io.clubone.transaction.request.InvoiceResponseDTO;
import io.clubone.transaction.request.SubscriptionPlanBatchCreateRequest;
import io.clubone.transaction.request.SubscriptionPlanCreateRequest;
import io.clubone.transaction.request.TransactionLineItemRequest;
import io.clubone.transaction.response.CreateInvoiceResponse;
import io.clubone.transaction.response.FinalizeTransactionResponse;
import io.clubone.transaction.service.PaymentService;
import io.clubone.transaction.service.SubscriptionPlanService;
import io.clubone.transaction.service.TransactionService;
import io.clubone.transaction.vo.BundleDTO;
import io.clubone.transaction.vo.BundleItemPriceDTO;
import io.clubone.transaction.vo.InvoiceDTO;
import io.clubone.transaction.vo.InvoiceEntityDTO;
import io.clubone.transaction.vo.InvoiceEntityRow;
import io.clubone.transaction.vo.InvoiceEntityTaxDTO;
import io.clubone.transaction.vo.InvoiceFlatRow;
import io.clubone.transaction.vo.InvoiceItemDTO;
import io.clubone.transaction.vo.InvoiceSummaryDTO;
import io.clubone.transaction.vo.ItemPriceDTO;
import io.clubone.transaction.vo.PaymentRequestDTO;
import io.clubone.transaction.vo.TaxRateAllocationDTO;
import io.clubone.transaction.vo.TransactionDTO;
import io.clubone.transaction.vo.TransactionEntityDTO;
import io.clubone.transaction.vo.TransactionEntityTaxDTO;
import io.clubone.transaction.vo.TransactionInfoDTO;

@Service
public class TransactionServiceImpl implements TransactionService {

	@Autowired
	private TransactionDAO transactionDAO;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SubscriptionPlanHelper subscriptionPlanHelper;

	@Autowired
	private SubscriptionPlanService subscriptionPlanService;
	
	@Autowired
	private AgreementHelper agreementHelper;
	
	@Autowired
	private InvoiceDAO invoiceDao;

	private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

	@Override
	public UUID createInvoice(InvoiceDTO dto) {
		return transactionDAO.saveInvoice(dto);
	}

	@Override
	public UUID createTransaction(TransactionDTO dto) {
		return transactionDAO.saveTransaction(dto);
	}

	@Override
	public UUID createAndFinalizeTransaction(CreateTransactionRequest request) {
		Instant now = Instant.now();

		// Step 1: Build InvoiceDTO
		InvoiceDTO invoice = new InvoiceDTO();
		invoice.setInvoiceDate(Timestamp.from(now));
		invoice.setClientRoleId(request.getClientRoleId());
		invoice.setBillingAddress(request.getBillingAddress());
		invoice.setInvoiceStatusId(UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c")); // Use constant or fetch
																								// from DB
		invoice.setSubTotal(request.getSubTotal());
		invoice.setTaxAmount(request.getTaxAmount());
		invoice.setDiscountAmount(request.getDiscountAmount());
		invoice.setTotalAmount(request.getTotalAmount());
		invoice.setPaid(false);

		// Step 2: Build PaymentRequestDTO
		PaymentRequestDTO payment = new PaymentRequestDTO();
		payment.setClientRoleId(request.getClientRoleId());
		payment.setAmount(request.getTotalAmount());
		payment.setPaymentGatewayCode("MANUAL");
		payment.setPaymentMethodCode("CASH");
		payment.setPaymentTypeCode("CASH");
		payment.setCreatedBy(request.getCreatedBy());

		// Step 3: Build TransactionDTO
		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(request.getClientAgreementId());
		txn.setLevelId(request.getLevelId());
		txn.setTransactionDate(Timestamp.from(now));

		if (request.getLineItems() != null) {
			List<TransactionEntityDTO> entityList = new ArrayList<>();
			for (TransactionLineItemRequest li : request.getLineItems()) {
				TransactionEntityDTO entity = new TransactionEntityDTO();
				entity.setEntityTypeId(li.getEntityTypeId());
				entity.setEntityId(li.getEntityId());
				entity.setEntityDescription(li.getEntityDescription());
				entity.setQuantity(li.getQuantity());
				entity.setUnitPrice(li.getUnitPrice());
				entity.setDiscountAmount(li.getDiscountAmount());
				entity.setTaxAmount(li.getTaxAmount());
				entity.setTotalAmount(li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())));
				entity.setTaxes(new ArrayList<>()); // If needed
				entityList.add(entity);
			}
			txn.setLineItems(entityList);
		}

		// Step 4: Create Invoice
		UUID invoiceId = transactionDAO.saveInvoice(invoice);

		// Step 5: Create Payment
		UUID clientPaymentTransactionId = paymentService.processManualPayment(payment);

		// Step 6: Finalize Transaction
		txn.setInvoiceId(invoiceId);
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);

		return transactionDAO.saveTransaction(txn);
	}

	@Override
	@Transactional
	public CreateInvoiceResponse createInvoice(CreateInvoiceRequest req) {

		req.setInvoiceStatusId(UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c"));
		// Validate totals vs line items
		BigDecimal lineSum = req.getLineItems().stream().map(TransactionLineItemRequest::getTotalAmount)
				.filter(Objects::nonNull) // ignore nulls
				.map(bd -> bd.setScale(2, RoundingMode.HALF_UP)) // normalize to currency scale
				.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

		BigDecimal headerTotal = Optional.ofNullable(req.getTotalAmount()).orElse(BigDecimal.ZERO).setScale(2,
				RoundingMode.HALF_UP);

		if (lineSum.compareTo(headerTotal) != 0) {
			logger.error("Invoice total mismatch: header={}, sumOfLines={}, difference={}", headerTotal, lineSum,
					headerTotal.subtract(lineSum));
			/*
			 * throw new IllegalArgumentException( String.
			 * format("Invoice total mismatch: header=%s, sumOfLines=%s, difference=%s",
			 * headerTotal, lineSum, headerTotal.subtract(lineSum)));
			 */
		}

		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(req.getClientRoleId());
		inv.setBillingAddress(req.getBillingAddress());
		inv.setInvoiceStatusId(req.getInvoiceStatusId()); // PENDING_PAYMENT from caller
		inv.setSubTotal(req.getSubTotal());
		inv.setTaxAmount(req.getTaxAmount());
		inv.setDiscountAmount(req.getDiscountAmount());
		inv.setTotalAmount(req.getTotalAmount());
		inv.setPaid(false);
		inv.setCreatedBy(req.getCreatedBy());

		UUID invoiceId = transactionDAO.saveInvoice(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

		return new CreateInvoiceResponse(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	@Override
	@Transactional
	public FinalizeTransactionResponse finalizeTransaction(FinalizeTransactionRequest req) {
		// Idempotency guard: if a transaction already exists for this invoice, return
		// it
		UUID existingTxn = transactionDAO.findTransactionIdByInvoiceId(req.getInvoiceId());
		if (existingTxn != null) {
			UUID existingCpt = transactionDAO.findClientPaymentTxnIdByTransactionId(existingTxn);
			String status = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
			return new FinalizeTransactionResponse(req.getInvoiceId(), status, existingCpt, existingTxn, "");
		}

		// Validate totals vs line items
		BigDecimal lineSum = req.getLineItems().stream().map(TransactionLineItemRequest::getTotalAmount)
				.filter(Objects::nonNull) // ignore nulls
				.map(bd -> bd.setScale(2, RoundingMode.HALF_UP)) // normalize to currency scale
				.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

		BigDecimal headerTotal = Optional.ofNullable(req.getTotalAmount()).orElse(BigDecimal.ZERO).setScale(2,
				RoundingMode.HALF_UP);

		if (lineSum.compareTo(headerTotal) != 0) {
			logger.error("Invoice total mismatch: header={}, sumOfLines={}, difference={}", headerTotal, lineSum,
					headerTotal.subtract(lineSum));
			/*
			 * throw new IllegalArgumentException( String.
			 * format("Invoice total mismatch: header=%s, sumOfLines=%s, difference=%s",
			 * headerTotal, lineSum, headerTotal.subtract(lineSum)));
			 */
		}

		// Call your existing /payment/v1
		PaymentRequestDTO pay = new PaymentRequestDTO();
		pay.setClientRoleId(req.getClientRoleId());
		pay.setAmount(req.getTotalAmount());
		pay.setPaymentGatewayCode(req.getPaymentGatewayCode());
		pay.setPaymentMethodCode(req.getPaymentMethodCode());
		pay.setPaymentTypeCode(req.getPaymentTypeCode());
		pay.setPaymentGatewayCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId());
		pay.setCreatedBy(req.getCreatedBy());

		UUID clientPaymentTransactionId = paymentService.processManualPayment(pay);

		// Build TransactionDTO from request
		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(req.getClientAgreementId());
		txn.setLevelId(req.getLevelId());
		txn.setInvoiceId(req.getInvoiceId());
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);
		txn.setTransactionDate(new Timestamp(System.currentTimeMillis()));
		txn.setCreatedBy(req.getCreatedBy());

		List<TransactionEntityDTO> items = new ArrayList<>();

		boolean hasAgreement = req.getClientAgreementId() != null;
		boolean hasBundle = req.getBundleId() != null;

		if (hasAgreement) {
			// AGREEMENT header (amounts = 0 to avoid double counting)
			UUID agreementTypeId = transactionDAO.findEntityTypeIdByName("AGREEMENT");

			TransactionEntityDTO agreementHeader = new TransactionEntityDTO();
			agreementHeader.setEntityTypeId(agreementTypeId);
			agreementHeader.setEntityId(req.getClientAgreementId());
			agreementHeader.setEntityDescription("Agreement Purchase");
			agreementHeader.setQuantity(1);
			agreementHeader.setUnitPrice(BigDecimal.ZERO);
			agreementHeader.setDiscountAmount(BigDecimal.ZERO);
			agreementHeader.setTaxAmount(BigDecimal.ZERO);
			agreementHeader.setTotalAmount(BigDecimal.ZERO);
			agreementHeader.setTaxes(List.of());
			// no parent for agreement header
			items.add(agreementHeader);
		}

		UUID bundleHeaderEntityTypeId = null;
		if (hasBundle) {
			// BUNDLE header (carry basket totals)
			bundleHeaderEntityTypeId = transactionDAO.findEntityTypeIdByName("BUNDLE");

			TransactionEntityDTO bundleHeader = new TransactionEntityDTO();
			bundleHeader.setEntityTypeId(bundleHeaderEntityTypeId);
			bundleHeader.setEntityId(req.getBundleId());
			bundleHeader.setEntityDescription("Bundle Purchase");
			bundleHeader.setQuantity(1);
			bundleHeader.setUnitPrice(req.getSubTotal()); // or your bundle base price
			bundleHeader.setDiscountAmount(req.getDiscountAmount());
			bundleHeader.setTaxAmount(req.getTaxAmount());
			bundleHeader.setTotalAmount(req.getTotalAmount());
			bundleHeader.setTaxes(List.of());
			// no parent for bundle header
			items.add(bundleHeader);
		}

		for (TransactionLineItemRequest li : req.getLineItems()) {
			TransactionEntityDTO e = new TransactionEntityDTO();
			e.setEntityTypeId(li.getEntityTypeId());
			e.setEntityId(li.getEntityId());
			e.setEntityDescription(li.getEntityDescription());
			e.setQuantity(li.getQuantity());
			e.setUnitPrice(li.getUnitPrice());
			e.setDiscountAmount(li.getDiscountAmount());
			e.setTaxAmount(li.getTaxAmount());
			e.setTotalAmount(li.getTotalAmount());
			if (li.isUpsellItem()) {
				e.setParentTransactionEntityId(req.getClientAgreementId());
			}
			if (li.getTaxes() != null) {
				List<TransactionEntityTaxDTO> taxes = li.getTaxes().stream().map(t -> {
					TransactionEntityTaxDTO tt = new TransactionEntityTaxDTO();
					tt.setTaxRateId(t.getTaxRateId());
					tt.setTaxRate(t.getTaxRate());
					tt.setTaxAmount(t.getTaxAmount());
					return tt;
				}).toList();
				e.setTaxes(taxes);
			} else {
				e.setTaxes(List.of());
			}
			items.add(e);
		}
		txn.setLineItems(items);

		UUID transactionId = transactionDAO.saveTransaction(txn);

		// Mark invoice as PAID
		UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
		transactionDAO.updateInvoiceStatusAndPaidFlag(req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());

		return new FinalizeTransactionResponse(req.getInvoiceId(), "PAID", clientPaymentTransactionId, transactionId,
				"");
	}

	@Override
	public CreateInvoiceResponse createInvoiceV3(CreateInvoiceRequestV3 req) {

		req.setInvoiceStatusId(UUID.fromString("b1ee960e-9966-4f2b-9596-88110158948c"));
		UUID itemEntityTypeId = transactionDAO.findEntityTypeIdByName("Item");
		UUID bundleEntityTypeId = transactionDAO.findEntityTypeIdByName("Bundle");
		System.out.println("Bundle Entity type Id " + bundleEntityTypeId);
		BigDecimal totalTaxAmount = BigDecimal.ZERO;
		BigDecimal totalDiscountAmount = BigDecimal.ZERO;
		BigDecimal totalUnitPrice = BigDecimal.ZERO;
		BigDecimal totalAmount = BigDecimal.ZERO;
		BigDecimal subTotalSum = BigDecimal.ZERO;
		BigDecimal taxSum = BigDecimal.ZERO;
		BigDecimal discountSum = BigDecimal.ZERO;

		InvoiceDTO inv = new InvoiceDTO();
		inv.setInvoiceDate(Timestamp.from(Instant.now()));
		inv.setClientRoleId(req.getClientRoleId());
		inv.setLevelId(req.getLevelId());
		inv.setBillingAddress(req.getBillingAddress());
		inv.setInvoiceStatusId(req.getInvoiceStatusId());

		List<InvoiceEntityDTO> invoiceEntities = new ArrayList<>();

		for (InvoiceEntityDTO invoiceEntityDTO : req.getLineItems()) {
			// Optional<EntityTypeDTO> entityType =
			// transactionDAO.getEntityTypeById(invoiceEntityDTO.getEntityTypeId());
			String bundleDescription = "";
			System.out.println("Invoice Entity type Id " + invoiceEntityDTO.getEntityTypeId());
			if (bundleEntityTypeId.equals(invoiceEntityDTO.getEntityTypeId())) {
				System.out.println("here");
				List<BundleItemPriceDTO> items = transactionDAO.getBundleItemsWithPrices(invoiceEntityDTO.getEntityId(),
						req.getLevelId());
				List<InvoiceEntityDTO> itemsEntity = new ArrayList<>();
				BigDecimal bundleunitPrice = BigDecimal.ZERO;
				BigDecimal bundletaxAmount = BigDecimal.ZERO;
				for (BundleItemPriceDTO bundleprice : items) {
					BigDecimal taxAmount = BigDecimal.ZERO;
					System.out.println("here1");
					List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
					bundleDescription = bundleprice.getDescription();
					InvoiceEntityDTO dto = new InvoiceEntityDTO();
					dto.setEntityId(bundleprice.getItemId());
					dto.setEntityTypeId(itemEntityTypeId);
					// dto.setParentInvoiceEntityId(invoiceEntityDTO.getEntityId());
					dto.setEntityDescription(bundleprice.getItemDescription());
					dto.setQuantity(bundleprice.getItemQuantity().intValue());

					dto.setDiscountAmount(BigDecimal.ZERO);
					dto.setUnitPrice(bundleprice.getItemPrice());
					dto.setTotalAmount(bundleprice.getItemQuantity().multiply(dto.getUnitPrice()));
					subTotalSum = subTotalSum.add(dto.getTotalAmount());
					dto.setUpsellItem(false);
					bundleunitPrice = bundleunitPrice.add(dto.getUnitPrice());

					if (bundleprice.getTaxGroupId() != null) {
						List<TaxRateAllocationDTO> taxAllocations = transactionDAO
								.getTaxRatesByGroupAndLevel(bundleprice.getTaxGroupId(), req.getLevelId());
						if (!CollectionUtils.isEmpty(taxAllocations)) {
							for (TaxRateAllocationDTO taxRate : taxAllocations) {
								InvoiceEntityTaxDTO invoiceEntityTaxDTO = new InvoiceEntityTaxDTO();
								invoiceEntityTaxDTO.setTaxRate(taxRate.getTaxRatePercentage());
								invoiceEntityTaxDTO.setTaxRateId(taxRate.getTaxRateId());
								invoiceEntityTaxDTO.setTaxAmount(dto.getUnitPrice()
										.multiply(bundleprice.getItemQuantity())
										.multiply(taxRate.getTaxRatePercentage()).divide(new BigDecimal("100")));
								taxes.add(invoiceEntityTaxDTO);

								taxAmount = taxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
								bundletaxAmount = taxAmount;
								taxSum = taxSum.add(invoiceEntityTaxDTO.getTaxAmount());

								totalTaxAmount = totalTaxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
							}
							System.out.println("TaxAmount " + taxAmount);
							dto.setTaxAmount(taxAmount);
							System.out.println("Total " + bundleprice.getItemQuantity().multiply(dto.getUnitPrice())
									.add(dto.getTaxAmount()));
							dto.setTotalAmount(
									bundleprice.getItemQuantity().multiply(dto.getUnitPrice()).add(dto.getTaxAmount()));

							dto.setTaxes(taxes);
						}
					}

					totalDiscountAmount = totalDiscountAmount.add(dto.getDiscountAmount());
					totalUnitPrice = totalUnitPrice.add(dto.getUnitPrice());
					totalAmount = totalAmount.add(dto.getTotalAmount());

					itemsEntity.add(dto);

					if (Objects.nonNull(bundleprice.getIsContinuous()) && bundleprice.getIsContinuous()) {

						// For Subscription Plan

					}
				}
				invoiceEntityDTO.setEntityDescription(bundleDescription);
				invoiceEntityDTO.setTaxAmount(bundletaxAmount);
				invoiceEntityDTO.setTotalAmount(bundleunitPrice.add(bundletaxAmount));
				invoiceEntityDTO.setUnitPrice(bundleunitPrice);
				invoiceEntityDTO.setDiscountAmount(totalDiscountAmount);

				invoiceEntities.add(invoiceEntityDTO);
				invoiceEntities.addAll(itemsEntity);
			} else if (itemEntityTypeId.equals(invoiceEntityDTO.getEntityTypeId())) {
				Optional<ItemPriceDTO> items = transactionDAO.getItemPriceByItemAndLevel(invoiceEntityDTO.getEntityId(),
						req.getLevelId());
				if (items.isPresent() && items.get() != null) {
					ItemPriceDTO item = items.get();
					BigDecimal taxAmount = BigDecimal.ZERO;
					InvoiceEntityDTO dto = new InvoiceEntityDTO();
					dto.setEntityId(invoiceEntityDTO.getEntityTypeId());
					dto.setEntityTypeId(itemEntityTypeId);
					dto.setParentInvoiceEntityId(invoiceEntityDTO.getEntityId());
					dto.setEntityDescription(item.getItemDescription());
					dto.setQuantity(invoiceEntityDTO.getQuantity());

					dto.setDiscountAmount(BigDecimal.ZERO);
					dto.setUnitPrice(item.getItemPrice());
					dto.setTotalAmount(BigDecimal.valueOf(invoiceEntityDTO.getQuantity()).multiply(dto.getUnitPrice()));
					subTotalSum = subTotalSum.add(dto.getTotalAmount());
					dto.setUpsellItem(false);

					if (item.getTaxGroupId() != null) {
						List<TaxRateAllocationDTO> taxAllocations = transactionDAO
								.getTaxRatesByGroupAndLevel(item.getTaxGroupId(), req.getLevelId());
						if (!CollectionUtils.isEmpty(taxAllocations)) {
							List<InvoiceEntityTaxDTO> taxes = new ArrayList<>();
							for (TaxRateAllocationDTO taxRate : taxAllocations) {
								InvoiceEntityTaxDTO invoiceEntityTaxDTO = new InvoiceEntityTaxDTO();
								invoiceEntityTaxDTO.setTaxRate(taxRate.getTaxRatePercentage());
								invoiceEntityTaxDTO.setTaxRateId(taxRate.getTaxRateId());
								invoiceEntityTaxDTO.setTaxAmount(dto.getUnitPrice()
										.multiply(BigDecimal.valueOf(invoiceEntityDTO.getQuantity()))
										.multiply(taxRate.getTaxRatePercentage()).divide(new BigDecimal("100")));
								taxAmount = taxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
								taxes.add(invoiceEntityTaxDTO);
								taxSum = taxSum.add(invoiceEntityTaxDTO.getTaxAmount());
								totalTaxAmount = totalTaxAmount.add(invoiceEntityTaxDTO.getTaxAmount());
							}
							dto.setTaxAmount(taxAmount);
							dto.setTotalAmount(BigDecimal.valueOf(invoiceEntityDTO.getQuantity())
									.multiply(dto.getUnitPrice()).add(dto.getTaxAmount()));
							dto.setTaxes(taxes);
						}
					}

					totalTaxAmount = totalTaxAmount.add(dto.getTaxAmount());
					totalDiscountAmount = totalDiscountAmount.add(dto.getDiscountAmount());
					totalUnitPrice = totalUnitPrice.add(dto.getUnitPrice());
					totalAmount = totalAmount.add(dto.getTotalAmount());

					invoiceEntities.add(dto);
				}
			}
		}

		inv.setTotalAmount(subTotalSum.add(taxSum));
		inv.setSubTotal(subTotalSum);
		inv.setTaxAmount(taxSum);
		inv.setDiscountAmount(totalDiscountAmount);
		inv.setPaid(false);
		inv.setCreatedBy(req.getCreatedBy());
		inv.setLineItems(invoiceEntities);

		ObjectMapper mapper = new ObjectMapper();
		try {
			System.out.println("Data " + mapper.writeValueAsString(inv));
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		UUID invoiceId = transactionDAO.saveInvoiceV3(inv);
		String invoiceNumber = transactionDAO.findInvoiceNumber(invoiceId);

		return new CreateInvoiceResponse(invoiceId, invoiceNumber, "PENDING_PAYMENT");
	}

	@Override
	public FinalizeTransactionResponse finalizeTransactionV3(FinalizeTransactionRequest req) {
		// Idempotency guard: if a transaction already exists for this invoice, return
		// it
		UUID existingTxn = transactionDAO.findTransactionIdByInvoiceId(req.getInvoiceId());
		if (existingTxn != null) {
			UUID existingCpt = transactionDAO.findClientPaymentTxnIdByTransactionId(existingTxn);
			String status = transactionDAO.currentInvoiceStatusName(req.getInvoiceId());
			//return new FinalizeTransactionResponse(req.getInvoiceId(), status, existingCpt, existingTxn, "");
		}

		Optional<InvoiceSummaryDTO> invoiceSummary = transactionDAO.getInvoiceSummaryById(req.getInvoiceId());
		System.out.println("clienRoleId "+invoiceSummary.get().getClientRoleId());
		if (invoiceSummary.isPresent() && invoiceSummary.get() != null) {
			req.setClientRoleId(invoiceSummary.get().getClientRoleId());
			req.setTotalAmount(invoiceSummary.get().getTotalAmount());
			System.out.println("Total Amount "+req.getTotalAmount()+" Remaining Amount "+req.getAmountToPayNow());
			BigDecimal tolerance = new BigDecimal("0.01");

			BigDecimal diff = req.getAmountToPayNow()
			        .subtract(req.getTotalAmount())
			        .abs()
			        .setScale(2, RoundingMode.HALF_UP);

			if (diff.compareTo(tolerance) > 0) {
			    /*return new FinalizeTransactionResponse(
			        req.getInvoiceId(),
			        "UNPAID",
			        null,
			        null,
			        "Price not matching with invoice created"
			    );*/
			}

			req.setLevelId(invoiceSummary.get().getLevelId());
		}

		// Validate totals vs line items
		/*
		 * BigDecimal lineSum =
		 * req.getLineItems().stream().map(TransactionLineItemRequest::getTotalAmount)
		 * .filter(Objects::nonNull) // ignore nulls .map(bd -> bd.setScale(2,
		 * RoundingMode.HALF_UP)) // normalize to currency scale
		 * .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
		 * 
		 * BigDecimal headerTotal =
		 * Optional.ofNullable(req.getTotalAmount()).orElse(BigDecimal.ZERO).setScale(2,
		 * RoundingMode.HALF_UP);
		 * 
		 * if (lineSum.compareTo(headerTotal) != 0) { logger.
		 * error("Invoice total mismatch: header={}, sumOfLines={}, difference={}",
		 * headerTotal, lineSum, headerTotal.subtract(lineSum));
		 * 
		 * }
		 */

		//commeting below code for razor pay support
		
		/*
		 * // Call your existing /payment/v1 PaymentRequestDTO pay = new
		 * PaymentRequestDTO(); pay.setClientRoleId(req.getClientRoleId());
		 * pay.setAmount(req.getTotalAmount());
		 * pay.setPaymentGatewayCode(req.getPaymentGatewayCode());
		 * pay.setPaymentMethodCode(req.getPaymentMethodCode());
		 * pay.setPaymentTypeCode(req.getPaymentTypeCode());
		 * pay.setPaymentGatewayCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId());
		 * pay.setCreatedBy(req.getCreatedBy()); UUID clientPaymentTransactionId =null;
		 * try { clientPaymentTransactionId = paymentService.processManualPayment(pay);
		 * }catch (Exception e) { // TODO: handle exception }
		 */

		UUID clientPaymentTransactionId = null;

		final boolean isManual =
		    req.getPaymentGatewayCode() == null
		    || req.getPaymentGatewayCode().trim().isEmpty()
		    || "MANUAL".equalsIgnoreCase(req.getPaymentGatewayCode())
		    || "CASH".equalsIgnoreCase(req.getPaymentGatewayCode()); // optional if you use CASH gateway code

		if (isManual) {
		    // ✅ existing behavior
		    PaymentRequestDTO pay = new PaymentRequestDTO();
		    pay.setClientRoleId(req.getClientRoleId());

		    // ✅ better: charge pay-now amount if available
		    BigDecimal payAmount =
		        (req.getAmountToPayNow() != null) ? req.getAmountToPayNow() : req.getTotalAmount();
		    pay.setAmount(payAmount);

		    pay.setPaymentGatewayCode(req.getPaymentGatewayCode());
		    pay.setPaymentMethodCode(req.getPaymentMethodCode());
		    pay.setPaymentTypeCode(req.getPaymentTypeCode());
		    pay.setPaymentGatewayCurrencyTypeId(req.getPaymentGatewayCurrencyTypeId());
		    pay.setCreatedBy(req.getCreatedBy());

		    clientPaymentTransactionId = paymentService.processManualPayment(pay);

		} else {
		    // ✅ Gateway flow: payment already verified & CPT already created by payment service
		    clientPaymentTransactionId = req.getClientPaymentTransactionId();

		    if (clientPaymentTransactionId == null) {
		        return new FinalizeTransactionResponse(
		            req.getInvoiceId(),
		            "UNPAID",
		            null,
		            null,
		            "Missing clientPaymentTransactionId for gateway finalize"
		        );
		    }

		    // (Optional but recommended) validate CPT belongs to same clientRoleId
		    // and amount matches req.getAmountToPayNow()
		}

		
		
		// Build TransactionDTO from request
		TransactionDTO txn = new TransactionDTO();
		txn.setClientAgreementId(req.getClientAgreementId());
		txn.setLevelId(req.getLevelId());
		txn.setInvoiceId(req.getInvoiceId());
		txn.setClientPaymentTransactionId(clientPaymentTransactionId);
		txn.setTransactionDate(new Timestamp(System.currentTimeMillis()));
		txn.setCreatedBy(req.getCreatedBy());
		txn.setTransactionDate(Timestamp.from(Instant.now()));

		UUID transactionId = transactionDAO.saveTransactionV3(txn);
		
		// Mark invoice as PAID
		UUID paidStatusId = transactionDAO.findInvoiceStatusIdByName("PAID");
		System.out.println("paidStatusId " + paidStatusId);
		transactionDAO.updateInvoiceStatusAndPaidFlag(req.getInvoiceId(), paidStatusId, true, req.getCreatedBy());
		transactionDAO.activateAgreementAndClientStatusForInvoice(req.getInvoiceId(), req.getCreatedBy());
		try {
			List<SubscriptionPlanCreateRequest> subscriptionRequest = subscriptionPlanHelper
					.buildRequests(req.getInvoiceId(), transactionId);
			SubscriptionPlanBatchCreateRequest subscriptionPlanBatchCreateRequest = new SubscriptionPlanBatchCreateRequest();
			subscriptionPlanBatchCreateRequest.setPlans(subscriptionRequest);
			ObjectMapper mapper=new ObjectMapper();
			//System.out.println("Data "+mapper.writeValueAsString(subscriptionRequest));
			subscriptionPlanService.createPlans(subscriptionPlanBatchCreateRequest, UUID.randomUUID());
		} catch (Exception e) {
			System.out.println("Error is creating subscription " + e.getMessage());
		}
		/*try {
		MembershipSalesRequestDTO purchaseAgrRequest=agreementHelper.createPurchaseAgreementRequest(req.getInvoiceId());
		ObjectMapper mapper=new ObjectMapper();
		System.out.println("Data "+mapper.writeValueAsString(purchaseAgrRequest));
		if(Objects.nonNull(purchaseAgrRequest)) {
			UUID clientAgreementId=agreementHelper.callMembershipSalesApi(purchaseAgrRequest).getBody();
			System.out.println("ClientAgreementid "+clientAgreementId);
			if(clientAgreementId!=null) {
				System.out.println("Updating clientAgreementId in invoice");
				invoiceDao.updateClientAgreementId(req.getInvoiceId(), clientAgreementId);
			}
		}
		}catch (Exception e) {
			System.err.println("Error in agreement purchase flow "+e.getMessage());
		}*/
		return new FinalizeTransactionResponse(req.getInvoiceId(), "PAID", clientPaymentTransactionId, transactionId,
				"");
	}

	@Override
	@Transactional
	public boolean setClientAgreement(UUID transactionId, UUID clientAgreementId) {
		int updated = transactionDAO.updateClientAgreementId(transactionId, clientAgreementId);
		return updated > 0;
	}

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final RoundingMode RM = RoundingMode.HALF_UP;

	private static BigDecimal nz(BigDecimal v) {
		return v != null ? v : BigDecimal.ZERO;
	}

	// tax = base * (rate% / 100)
	private static BigDecimal pct(BigDecimal base, BigDecimal percent) {
		return nz(base).multiply(nz(percent)).divide(HUNDRED, 2, RM);
	}

	@Override
	public List<InvoiceResponseDTO> getInvoicesByClientRole(UUID clientRoleId) {
		// 1) Load invoices (one row per invoice) with latest txn
		List<InvoiceFlatRow> invoices = transactionDAO.findInvoicesWithLatestTxnByClientRole(clientRoleId);
		if (invoices.isEmpty())
			return List.of();

		// 2) Load all entities for these invoices in one shot
		List<UUID> invoiceIds = invoices.stream().map(InvoiceFlatRow::getInvoiceId).toList();
		List<InvoiceEntityRow> entities = transactionDAO.findEntitiesByInvoiceIds(invoiceIds);

		// 3) Group entities by invoice_id
		Map<UUID, List<InvoiceEntityRow>> entitiesByInvoice = entities.stream()
				.collect(Collectors.groupingBy(InvoiceEntityRow::getInvoiceId));

		// 4) Transform to DTOs with hierarchy
		List<InvoiceResponseDTO> result = new ArrayList<>();
		for (InvoiceFlatRow inv : invoices) {
			InvoiceResponseDTO dto = new InvoiceResponseDTO();
			dto.setInvoiceId(inv.getInvoiceId());
			dto.setInvoiceNumber(inv.getInvoiceNumber());
			dto.setInvoiceDate(inv.getInvoiceDate());
			dto.setTotalAmount(inv.getTotalAmount());
			dto.setSubTotal(inv.getSubTotal());
			dto.setTaxAmount(inv.getTaxAmount());
			dto.setDiscountAmount(inv.getDiscountAmount());

			TransactionInfoDTO tx = new TransactionInfoDTO();
			tx.setTransactionCode(inv.getTransactionCode());
			tx.setClientAgreementId(inv.getClientAgreementId());
			tx.setClientPaymentTransactionId(inv.getClientPaymentTransactionId());
			tx.setTransactionDate(inv.getTransactionDate());
			dto.setTransaction(tx);

			List<InvoiceEntityRow> lines = entitiesByInvoice.getOrDefault(inv.getInvoiceId(), List.of());
			buildHierarchy(dto, lines);

			result.add(dto);
		}
		return result;
	}

	/**
	 * Build hierarchy: - Detect children by parent_invoice_entity_id - Any entity
	 * that has children => treat as BundleDTO - Any entity with parent == null and
	 * no children => standalone item (root-level item)
	 */
	private void buildHierarchy(InvoiceResponseDTO dto, List<InvoiceEntityRow> lines) {
		if (lines.isEmpty())
			return;

		// Index by id, and children list map
		Map<UUID, InvoiceEntityRow> byId = lines.stream()
				.collect(Collectors.toMap(InvoiceEntityRow::getInvoiceEntityId, Function.identity()));

		Map<UUID, List<InvoiceEntityRow>> childrenByParent = new HashMap<>();
		for (InvoiceEntityRow r : lines) {
			if (r.getParentInvoiceEntityId() != null) {
				childrenByParent.computeIfAbsent(r.getParentInvoiceEntityId(), k -> new ArrayList<>()).add(r);
			}
		}

		// Identify roots (parent == null)
		List<InvoiceEntityRow> roots = lines.stream().filter(r -> r.getParentInvoiceEntityId() == null).toList();

		for (InvoiceEntityRow root : roots) {
			List<InvoiceEntityRow> kids = childrenByParent.getOrDefault(root.getInvoiceEntityId(), List.of());
			if (!kids.isEmpty()) {
				// This root is a bundle (has children)
				BundleDTO bundle = toBundle(root);
				for (InvoiceEntityRow ch : kids) {
					bundle.getItems().add(toItem(ch));
				}
				dto.getBundles().add(bundle);
			} else {
				// Standalone item
				dto.getItems().add(toItem(root));
			}
		}

		// (Optional) If there are any entities whose parent is not a root (nested),
		// you can extend this to recursive building. Current model supports 2 levels as
		// per requirement.
	}

	private BundleDTO toBundle(InvoiceEntityRow r) {
		BundleDTO b = new BundleDTO();
		b.setInvoiceEntityId(r.getInvoiceEntityId());
		b.setEntityId(r.getEntityId());
		b.setEntityDescription(r.getEntityDescription());
		b.setQuantity(r.getQuantity());
		b.setUnitPrice(r.getUnitPrice());
		b.setDiscountAmount(r.getDiscountAmount());
		b.setTaxAmount(r.getTaxAmount());
		b.setTotalAmount(r.getTotalAmount());
		return b;
	}

	private InvoiceItemDTO toItem(InvoiceEntityRow r) {
		InvoiceItemDTO it = new InvoiceItemDTO();
		it.setInvoiceEntityId(r.getInvoiceEntityId());
		it.setParentInvoiceEntityId(r.getParentInvoiceEntityId());
		it.setEntityId(r.getEntityId());
		it.setEntityDescription(r.getEntityDescription());
		it.setQuantity(r.getQuantity());
		it.setUnitPrice(r.getUnitPrice());
		it.setDiscountAmount(r.getDiscountAmount());
		it.setTaxAmount(r.getTaxAmount());
		it.setTotalAmount(r.getTotalAmount());
		return it;
	}
}
