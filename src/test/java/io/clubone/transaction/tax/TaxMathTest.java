package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.clubone.transaction.vo.TaxRateAllocationDTO;

/**
 * US / UK / CA style tax math coverage (exclusive + inclusive).
 */
class TaxMathTest {

	@Test
	void exclusiveSplitsByAuthorityShare() {
		List<TaxRateAllocationDTO> allocs = List.of(
				alloc("a1", "9.00"),
				alloc("a2", "9.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("100.00"), allocs, false);
		Assertions.assertEquals(2, components.size());
		Assertions.assertEquals(new BigDecimal("9.00"), components.get(0).getAmount());
		Assertions.assertEquals(new BigDecimal("9.00"), components.get(1).getAmount());
		Assertions.assertEquals(new BigDecimal("18.00"), TaxMath.sumTax(components));
	}

	@Test
	void usCombinedStateLocalExclusive() {
		// Typical US split: 6.25% state + 2.00% local = 8.25%
		List<TaxRateAllocationDTO> allocs = List.of(
				alloc("state", "6.25"),
				alloc("local", "2.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("200.00"), allocs, false);
		Assertions.assertEquals(new BigDecimal("12.50"), components.get(0).getAmount());
		Assertions.assertEquals(new BigDecimal("4.00"), components.get(1).getAmount());
		Assertions.assertEquals(new BigDecimal("16.50"), TaxMath.sumTax(components));
	}

	@Test
	void inclusiveExtractsEmbeddedTax() {
		// UK VAT 20% inclusive: 120 gross => 100 net + 20 tax
		List<TaxRateAllocationDTO> allocs = List.of(alloc("vat", "20.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("120.00"), allocs, true);
		Assertions.assertEquals(1, components.size());
		Assertions.assertEquals(new BigDecimal("20.00"), components.get(0).getAmount());
	}

	@Test
	void ukVatInclusivePennyRounding() {
		List<TaxRateAllocationDTO> allocs = List.of(alloc("vat", "20.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("99.99"), allocs, true);
		Assertions.assertEquals(new BigDecimal("16.66"), TaxMath.sumTax(components));
	}

	@Test
	void canadaHstThirteenPercentExclusive() {
		List<TaxRateAllocationDTO> allocs = List.of(alloc("hst", "13.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("628.61"), allocs, false);
		Assertions.assertEquals(new BigDecimal("81.72"), components.get(0).getAmount());
	}

	@Test
	void canadaGstPstSplitExclusive() {
		List<TaxRateAllocationDTO> allocs = List.of(
				alloc("gst", "5.00"),
				alloc("pst", "7.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("100.00"), allocs, false);
		Assertions.assertEquals(new BigDecimal("5.00"), components.get(0).getAmount());
		Assertions.assertEquals(new BigDecimal("7.00"), components.get(1).getAmount());
		Assertions.assertEquals(new BigDecimal("12.00"), TaxMath.sumTax(components));
	}

	@Test
	void exclusiveTaxableBaseSubtractsDiscount() {
		Assertions.assertEquals(
				new BigDecimal("90.00"),
				TaxMath.exclusiveTaxableBase(new BigDecimal("100.00"), 1, new BigDecimal("10.00")));
		Assertions.assertEquals(
				0,
				TaxMath.exclusiveTaxableBase(new BigDecimal("10.00"), 1, new BigDecimal("25.00")).compareTo(BigDecimal.ZERO));
	}

	@Test
	void zeroRateYieldsZeroTax() {
		List<TaxRateAllocationDTO> allocs = List.of(alloc("zero", "0.00"));
		var components = TaxMath.splitByAllocations(new BigDecimal("50.00"), allocs, false);
		Assertions.assertEquals(new BigDecimal("0.00"), TaxMath.sumTax(components));
	}

	private static TaxRateAllocationDTO alloc(String key, String pct) {
		TaxRateAllocationDTO dto = new TaxRateAllocationDTO();
		dto.setTaxRateId(UUID.nameUUIDFromBytes(("rate-" + key).getBytes()));
		dto.setTaxRateAllocationId(UUID.nameUUIDFromBytes(("alloc-" + key).getBytes()));
		dto.setTaxRatePercentage(new BigDecimal(pct));
		return dto;
	}
}
