package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.DistributorSignupRequest;
import com.platizio.wealthtech.dto.DistributorUpdateRequest;
import com.platizio.wealthtech.service.DistributorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/distributors")
public class DistributorController {

    private final DistributorService distributorService;

    public DistributorController(DistributorService distributorService) {
        this.distributorService = distributorService;
    }

    @PostMapping("/signup")
    public Distributor signup(@Valid @RequestBody DistributorSignupRequest request) {
        return distributorService.signup(request);
    }

    @PostMapping("/login")
    public Distributor login(@Valid @RequestBody AuthLoginRequest request) {
        return distributorService.login(request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<Distributor> list() {
        return distributorService.findAll();
    }

    @GetMapping("/search")
    public List<Distributor> search(
            @RequestParam String query,
            @RequestParam(required = false) UUID requesterId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return distributorService.search(query, requesterId, limit);
    }

    @GetMapping("/{distributorId}")
    public Distributor getById(@PathVariable UUID distributorId) {
        return distributorService.getDistributor(distributorId);
    }

    @PutMapping("/{distributorId}")
    public Distributor update(
            @PathVariable UUID distributorId,
            @Valid @RequestBody DistributorUpdateRequest request
    ) {
        return distributorService.update(distributorId, request);
    }

    @GetMapping("/sub-distributors")
    public List<Distributor> listSubDistributors(@RequestParam UUID requesterId) {
        return distributorService.findSubDistributors(requesterId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{distributorId}/status")
    public Distributor updateStatus(
            @PathVariable UUID distributorId,
            @RequestParam DistributorStatus status,
            @RequestParam UUID actorId
    ) {
        return distributorService.updateStatus(distributorId, status, actorId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{distributorId}")
    public void deleteDistributor(@PathVariable UUID distributorId, @RequestParam UUID actorId) {
        distributorService.deleteDistributor(distributorId, actorId);
    }
}
