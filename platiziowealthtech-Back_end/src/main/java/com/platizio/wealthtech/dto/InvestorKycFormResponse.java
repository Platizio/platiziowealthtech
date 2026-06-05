package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.InvestorKycForm;

/**
 * Response returned to the distributor frontend for the KYC modify workflow.
 *
 * <p>{@code form} is the persisted local mirror (status, proof/esign URLs,
 * fields still needed). {@code external} is the latest raw {@code kyc_form}
 * object from Cybrilla so the UI can drive the Digilocker / eSign redirects
 * directly. {@code external} may be {@code null} when only local state is read.
 */
public record InvestorKycFormResponse(InvestorKycForm form, JsonNode external) {
}
