package com.platizio.wealthtech.dto;

import java.math.BigDecimal;

public class SipTrendDto {
    private String month;
    private BigDecimal amount;
    private int count;

    public SipTrendDto() {}

    public SipTrendDto(String month, BigDecimal amount, int count) {
        this.month = month;
        this.amount = amount;
        this.count = count;
    }

    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
