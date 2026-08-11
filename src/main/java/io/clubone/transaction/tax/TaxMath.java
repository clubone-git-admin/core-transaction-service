package io.clubone.transaction.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import io.clubone.transaction.vo.TaxRateAllocationDTO;

/**
 * Pure tax math helpers (exclusive + inclusive).
 */
public final class TaxMath {

	private TaxMath() {}

	public static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	public static BigDecimal scale2(BigDecimal v) {
		return nz(v).setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Exclusive: tax = taxableBase * pct / 100.
	 * Inclusive: extract tax from gross-inclusive base: tax = base - base/(1+pct/100).
	 */
	public static List<TaxDeterminationResult.TaxComponent> splitByAllocations(
			BigDecimal lineBase,
			List<TaxRateAllocationDTO> allocs,
			boolean inclusive
	) {
		List<TaxDeterminationResult.TaxComponent> out = new ArrayList<>();
		if (allocs == null || allocs.isEmpty()) {
			return out;
		}
		BigDecimal base = nz(lineBase);
		if (base.compareTo(BigDecimal.ZERO) < 0) {
			base = BigDecimal.ZERO;
		}

		BigDecimal totalPct = BigDecimal.ZERO;
		for (TaxRateAllocationDTO a : allocs) {
			totalPct = totalPct.add(nz(a.getTaxRatePercentage()));
		}

		BigDecimal taxable;
		BigDecimal totalTax;
		if (inclusive) {
			if (totalPct.compareTo(BigDecimal.ZERO) <= 0) {
				taxable = scale2(base);
				totalTax = BigDecimal.ZERO;
			} else {
				BigDecimal divisor = BigDecimal.ONE.add(totalPct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
				taxable = scale2(base.divide(divisor, 8, RoundingMode.HALF_UP));
				totalTax = scale2(base.subtract(taxable));
			}
		} else {
			taxable = scale2(base);
			totalTax = scale2(taxable.multiply(totalPct).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
		}

		BigDecimal allocated = BigDecimal.ZERO;
		for (int i = 0; i < allocs.size(); i++) {
			TaxRateAllocationDTO a = allocs.get(i);
			BigDecimal pct = scale2(nz(a.getTaxRatePercentage()));
			BigDecimal thisTax;
			if (i == allocs.size() - 1) {
				thisTax = scale2(totalTax.subtract(allocated));
			} else if (totalPct.compareTo(BigDecimal.ZERO) == 0) {
				thisTax = BigDecimal.ZERO;
			} else {
				thisTax = scale2(totalTax.multiply(pct).divide(totalPct, 8, RoundingMode.HALF_UP));
				allocated = allocated.add(thisTax);
			}
			out.add(new TaxDeterminationResult.TaxComponent(
					a.getTaxRateId(), a.getTaxRateAllocationId(), pct, thisTax));
		}
		return out;
	}

	public static BigDecimal sumTax(List<TaxDeterminationResult.TaxComponent> components) {
		BigDecimal sum = BigDecimal.ZERO;
		if (components == null) return scale2(sum);
		for (TaxDeterminationResult.TaxComponent c : components) {
			sum = sum.add(nz(c.getAmount()));
		}
		return scale2(sum);
	}

	public static BigDecimal exclusiveTaxableBase(BigDecimal unitPrice, int qty, BigDecimal discount) {
		BigDecimal gross = nz(unitPrice).multiply(BigDecimal.valueOf(Math.max(qty, 0)));
		BigDecimal base = gross.subtract(nz(discount));
		return base.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : scale2(base);
	}
}
