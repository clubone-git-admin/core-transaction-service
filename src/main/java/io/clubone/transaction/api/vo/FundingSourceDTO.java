package io.clubone.transaction.api.vo;

import lombok.Data;

@Data
public class FundingSourceDTO {
	private String name;
	private double responsibility;
	private boolean recurring;
	private boolean onAccount;
	private boolean pos;

}
