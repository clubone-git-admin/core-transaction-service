package io.clubone.transaction.vo;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public class WorkRole {

	private UUID employeeDesignationId;
	private String designationName;

	private UUID id;

	private String name;

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public UUID getEmployeeDesignationId() {
		return employeeDesignationId;
	}

	public void setEmployeeDesignationId(UUID employeeDesignationId) {
		this.employeeDesignationId = employeeDesignationId;
	}

	public String getDesignationName() {
		return designationName;
	}

	public void setDesignationName(String designationName) {
		this.designationName = designationName;
	}

}
