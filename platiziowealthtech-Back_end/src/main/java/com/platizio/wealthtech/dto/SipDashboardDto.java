package com.platizio.wealthtech.dto;

import java.util.List;

public class SipDashboardDto {
    private List<SipTrendDto> trend;
    private List<SipItemDto> sips;

    public SipDashboardDto() {}

    public SipDashboardDto(List<SipTrendDto> trend, List<SipItemDto> sips) {
        this.trend = trend;
        this.sips = sips;
    }

    public List<SipTrendDto> getTrend() { return trend; }
    public void setTrend(List<SipTrendDto> trend) { this.trend = trend; }
    public List<SipItemDto> getSips() { return sips; }
    public void setSips(List<SipItemDto> sips) { this.sips = sips; }
}
