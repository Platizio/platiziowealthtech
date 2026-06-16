package com.platizio.wealthtech.dto;

import java.util.List;
import java.util.Map;

public class SipDashboardDto {
    private List<SipTrendDto> trend;
    private List<SipItemDto> sips;
    private List<SipItemDto> pendingSetup;
    private Map<String, Long> statusCounts;

    public SipDashboardDto() {}

    public SipDashboardDto(List<SipTrendDto> trend, List<SipItemDto> sips) {
        this(trend, sips, List.of(), Map.of());
    }

    public SipDashboardDto(List<SipTrendDto> trend, List<SipItemDto> sips, Map<String, Long> statusCounts) {
        this(trend, sips, List.of(), statusCounts);
    }

    public SipDashboardDto(
            List<SipTrendDto> trend,
            List<SipItemDto> sips,
            List<SipItemDto> pendingSetup,
            Map<String, Long> statusCounts
    ) {
        this.trend = trend;
        this.sips = sips;
        this.pendingSetup = pendingSetup;
        this.statusCounts = statusCounts;
    }

    public List<SipTrendDto> getTrend() { return trend; }
    public void setTrend(List<SipTrendDto> trend) { this.trend = trend; }
    public List<SipItemDto> getSips() { return sips; }
    public void setSips(List<SipItemDto> sips) { this.sips = sips; }
    public List<SipItemDto> getPendingSetup() { return pendingSetup; }
    public void setPendingSetup(List<SipItemDto> pendingSetup) { this.pendingSetup = pendingSetup; }
    public Map<String, Long> getStatusCounts() { return statusCounts; }
    public void setStatusCounts(Map<String, Long> statusCounts) { this.statusCounts = statusCounts; }
}
