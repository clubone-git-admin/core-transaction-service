package io.clubone.transaction.v2.vo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NotificationRequestDTO {

	 private UUID clientId; // clientRoleId
     private List<String> channel;
     private String templateCode;
     private Map<String, Object> params;
     private boolean isAccess;

     public UUID getClientId() { return clientId; }
     public void setClientId(UUID clientId) { this.clientId = clientId; }

     public List<String> getChannel() { return channel; }
     public void setChannel(List<String> channel) { this.channel = channel; }

     public String getTemplateCode() { return templateCode; }
     public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }

     public Map<String, Object> getParams() { return params; }
     public void setParams(Map<String, Object> params) { this.params = params; }
		public boolean isAccess() {
			return isAccess;
		}
		public void setAccess(boolean isAccess) {
			this.isAccess = isAccess;
		}
     
}
