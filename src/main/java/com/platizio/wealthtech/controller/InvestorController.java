package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.service.InvestorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investors")
@Tag(name = "Investors", description = "Endpoints for managing investors and their KYC/Bank details")
public class InvestorController {

    private final InvestorService investorService;

    public InvestorController(InvestorService investorService) {
        this.investorService = investorService;
    }

    @Operation(summary = "List all investors", description = "Returns a complete list of all investors in the system. Requires ADMIN role.")
    @ApiResponse(responseCode = "200", description = "List of investors", 
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = Investor.class))))
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<Investor> listAll() {
        return investorService.listAll();
    }

    @Operation(summary = "Create a new investor", description = "Registers a new investor in the system with DRAFT status.")
    @ApiResponse(responseCode = "200", description = "Investor created successfully", 
                 content = @Content(schema = @Schema(implementation = Investor.class)))
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

    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{investorId}")
    public void deleteInvestor(@PathVariable UUID investorId, @RequestParam UUID actorId) {
        investorService.deleteInvestor(investorId, actorId);
    }
}
