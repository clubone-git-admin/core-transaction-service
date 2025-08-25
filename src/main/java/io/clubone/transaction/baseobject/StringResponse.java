package io.clubone.transaction.baseobject;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class StringResponse {

	Boolean success;

	ResponseMessage responseMessage;

	String result;
}
