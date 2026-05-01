package com.platizio.wealthtech.dto;

import java.math.BigDecimal;

public class SipItemDto {
    private String id;
    private String investor;
    private String fund;
    private String amount;
    private String status;
    private String nextDue;
    private String mandate;

    public SipItemDto() {}

    public SipItemDto(String id, String investor, String fund, String amount, String status, String nextDue, String mandate) {
        this.id = id;
        this.investor = investor;
        this.fund = fund;
        this.amount = amount;
        this.status = status;
        this.nextDue = nextDue;
        this.mandate = mandate;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvestor() { return investor; }
    public void setInvestor(String investor) { this.investor = investor; }
    public String getFund() { return fund; }
    public void setFund(String fund) { this.fund = fund; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNextDue() { return nextDue; }
    public void setNextDue(String nextDue) { this.nextDue = nextDue; }
    public String getMandate() { return mandate; }
    public void setMandate(String mandate) { this.mandate = mandate; }
}
