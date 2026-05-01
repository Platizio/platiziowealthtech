package com.platizio.wealthtech.dto;

public class OnboardingCardDto {
    private String id;
    private String name;
    private String detail;
    private String days;
    private String status;

    public OnboardingCardDto() {}

    public OnboardingCardDto(String id, String name, String detail, String days, String status) {
        this.id = id;
        this.name = name;
        this.detail = detail;
        this.days = days;
        this.status = status;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getDays() { return days; }
    public void setDays(String days) { this.days = days; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
