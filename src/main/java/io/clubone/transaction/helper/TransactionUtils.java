package io.clubone.transaction.helper;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class TransactionUtils {

	public BigDecimal prorateFactorForCurrentMonth(LocalDate startDate) {
		if (startDate == null)
			startDate = LocalDate.now();
		int daysInMonth = startDate.lengthOfMonth();
		int daysRemaining = daysInMonth - startDate.getDayOfMonth() + 1; // inclusive of today
		// 6 dp for factor, then round unit price to 2 dp later
		return new BigDecimal(daysRemaining).divide(new BigDecimal(daysInMonth), 6, RoundingMode.HALF_UP);
	}

}
