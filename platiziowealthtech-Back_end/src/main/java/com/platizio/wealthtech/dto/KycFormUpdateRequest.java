package com.platizio.wealthtech.dto;

import java.util.List;

/**
 * Fields the partner supplies to complete a Cybrilla POA <code>kyc_form</code>
 * (modify workflow) before it can move to <code>awaiting_esign</code>. All
 * fields are optional on the wire; the caller only needs to send whatever is
 * still listed in <code>requirements.fields_needed</code>.
 */
public record KycFormUpdateRequest(
        String emailAddress,
        String phoneIsd,
        String phoneNumber,
        String residentialStatus,
        String gender,
        String maritalStatus,
        String fatherName,
        String spouseName,
        String occupationType,
        String aadhaarNumber,
        String countryOfBirth,
        String placeOfBirth,
        String incomeSlab,
        String pepDetails,
        List<String> citizenshipCountries,
        String nationalityCountry,
        Boolean taxResidencyOtherThanIndia,
        Double geoLatitude,
        Double geoLongitude
) {
}
