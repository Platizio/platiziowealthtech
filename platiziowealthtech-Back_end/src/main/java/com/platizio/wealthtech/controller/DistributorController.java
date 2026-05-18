package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.dto.DistributorSignupRequest;
import com.platizio.wealthtech.dto.DistributorUpdateRequest;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.DistributorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
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
            Authentication auth
    ) {
        return distributorService.updateStatus(distributorId, status, actorId(auth));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{distributorId}")
    public void deleteDistributor(@PathVariable UUID distributorId, Authentication auth) {
        distributorService.deleteDistributor(distributorId, actorId(auth));
    }

    private UUID actorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}
