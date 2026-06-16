package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.IfscLookupResponse;
import com.platizio.wealthtech.dto.PincodeLookupResponse;
import com.platizio.wealthtech.service.InvestorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/banks")
public class BankReferenceController {

    private final InvestorService investorService;

    public BankReferenceController(InvestorService investorService) {
        this.investorService = investorService;
    }

    @GetMapping("/ifsc/{ifscCode}")
    public IfscLookupResponse lookupIfsc(@PathVariable String ifscCode) {
        return investorService.lookupIfsc(ifscCode);
    }

    @GetMapping("/pincodes/{pincode}")
    public PincodeLookupResponse lookupPincode(@PathVariable String pincode) {
        return investorService.lookupPincode(pincode);
    }
}
