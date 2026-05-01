package com.platizio.wealthtech.dto;

import java.util.List;

public class OnboardingPipelineDto {
    private List<OnboardingCardDto> kycPending;
    private List<OnboardingCardDto> bankPending;
    private List<OnboardingCardDto> readyToInvest;

    public OnboardingPipelineDto() {}

    public OnboardingPipelineDto(List<OnboardingCardDto> kycPending, List<OnboardingCardDto> bankPending, List<OnboardingCardDto> readyToInvest) {
        this.kycPending = kycPending;
        this.bankPending = bankPending;
        this.readyToInvest = readyToInvest;
    }

    public List<OnboardingCardDto> getKycPending() { return kycPending; }
    public void setKycPending(List<OnboardingCardDto> kycPending) { this.kycPending = kycPending; }
    public List<OnboardingCardDto> getBankPending() { return bankPending; }
    public void setBankPending(List<OnboardingCardDto> bankPending) { this.bankPending = bankPending; }
    public List<OnboardingCardDto> getReadyToInvest() { return readyToInvest; }
    public void setReadyToInvest(List<OnboardingCardDto> readyToInvest) { this.readyToInvest = readyToInvest; }
}
