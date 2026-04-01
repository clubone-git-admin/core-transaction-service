package io.clubone.transaction.subscription.billing.dto;

public class SimpleActionResponse {

    private boolean success;
    private String message;
    private Integer affectedCount;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getAffectedCount() { return affectedCount; }
    public void setAffectedCount(Integer affectedCount) { this.affectedCount = affectedCount; }
}
