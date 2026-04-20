package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.service.InvestorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investors")
public class InvestorController {

    private final InvestorService investorService;

    public InvestorController(InvestorService investorService) {
        this.investorService = investorService;
    }

    @GetMapping
    public List<Investor> listAll() {
        return investorService.listAll();
    }

    @PostMapping
    public Investor create(@Valid @RequestBody InvestorCreateRequest request) {
        return investorService.createInvestor(request);
    }

    @GetMapping("/by-distributor/{distributorId}")
    public List<Investor> listByDistributor(@PathVariable UUID distributorId) {
        return investorService.listByDistributor(distributorId);
    }

    @GetMapping("/by-distributor/{distributorId}/visible-to/{requesterId}")
    public List<Investor> listByDistributorForRequester(
            @PathVariable UUID distributorId,
            @PathVariable UUID requesterId
    ) {
        return investorService.listVisibleToDistributor(requesterId, distributorId);
    }

    @GetMapping("/visible-to/{requesterId}")
    public List<Investor> listVisibleToRequester(@PathVariable UUID requesterId) {
        return investorService.listVisibleToMaster(requesterId);
    }

    @PatchMapping("/{investorId}/kyc")
    public Investor updateKycStatus(
            @PathVariable UUID investorId,
            @RequestParam KycStatus status,
            @RequestParam UUID actorId
    ) {
        return investorService.updateKycStatus(investorId, status, actorId);
    }

    @PostMapping("/{investorId}/bank-accounts")
    public InvestorBankAccount addBank(
            @PathVariable UUID investorId,
            @Valid @RequestBody InvestorBankRequest request,
            @RequestParam UUID actorId
    ) {
        return investorService.addBankAccount(investorId, request, actorId);
    }

    @PatchMapping("/{investorId}/bank-verify")
    public Investor verifyBank(@PathVariable UUID investorId, @RequestParam UUID actorId) {
        return investorService.verifyBank(investorId, actorId);
    }

    @GetMapping("/{investorId}")
    public Investor getInvestorById(@PathVariable UUID investorId) {
        return investorService.getInvestor(investorId);
    }

    @PutMapping("/{investorId}")
    public Investor updateInvestor(
            @PathVariable UUID investorId,
            @Valid @RequestBody InvestorUpdateRequest request
    ) {
        return investorService.updateInvestor(investorId, request);
    }

    @GetMapping("/by-postal-code/{postalCode}")
    public List<Investor> getInvestorsByPostalCode(@PathVariable String postalCode) {
        return investorService.findByPostalCode(postalCode);
    }

    @DeleteMapping("/{investorId}")
    public void deleteInvestor(@PathVariable UUID investorId, @RequestParam UUID actorId) {
        investorService.deleteInvestor(investorId, actorId);
    }
}
