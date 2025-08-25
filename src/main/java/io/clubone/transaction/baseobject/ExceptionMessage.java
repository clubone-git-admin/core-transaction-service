package io.clubone.transaction.baseobject;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExceptionMessage implements Serializable {

	private static final long serialVersionUID = 1L;

	@Override
	public int hashCode() {
		return messageID;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final ExceptionMessage other = (ExceptionMessage) obj;
		if (messageID == null) {
			if (other.messageID != null)
				return false;
		} else if (!messageID.equals(other.messageID))
			return false;
		return true;
	}

	private Integer messageID;

	private String errorMessage;

	private String friendlyMessage;

	private String messageType;

	public final static String TYPE_UNCAUGHT = "Uncaught";
}
