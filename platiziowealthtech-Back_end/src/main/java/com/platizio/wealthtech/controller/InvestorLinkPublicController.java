package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.InvestorLinkActionRequest;
import com.platizio.wealthtech.dto.InvestorLinkProfileRequest;
import com.platizio.wealthtech.service.InvestorLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, token-authenticated investor approval link (investor.md R7a/R7b). No session:
 * the opaque link token (delivered to the investor's verified email) is the possession
 * factor. These paths are permitted in SecurityConfig ahead of the ROLE_INVESTOR rule.
 */
@RestController
@RequestMapping("/api/v1/investor/link")
@Tag(name = "Investor Link", description = "Public investor onboarding-approval link (no session)")
public class InvestorLinkPublicController {

    private final InvestorLinkService linkService;

    public InvestorLinkPublicController(InvestorLinkService linkService) {
        this.linkService = linkService;
    }

    @Operation(summary = "Review the onboarding details behind a link token")
    @GetMapping("/review")
    public Map<String, Object> review(@RequestParam String token) {
        return linkService.review(token);
    }

    @Operation(summary = "Approve the onboarding link (links distributor by PAN)")
    @PostMapping("/approve")
    public Map<String, Object> approve(@Valid @RequestBody InvestorLinkActionRequest request, HttpServletRequest http) {
        return linkService.approve(
                request.token(), Boolean.TRUE.equals(request.consentAccepted()),
                clientIp(http), http.getHeader("User-Agent"));
    }

    @Operation(summary = "Submit the completed onboarding form behind a link token",
            description = "No-auth: the token is the only credential. Links the distributor, applies the "
                    + "investor-completed profile, sets linking_status=READY, auto-attests, and notifies the distributor.")
    @PostMapping("/submit-profile")
    public Map<String, Object> submitProfile(
            @Valid @RequestBody InvestorLinkProfileRequest request, HttpServletRequest http) {
        return linkService.submitProfileByToken(
                request.token(), request.profile(), request.consentAccepted(),
                clientIp(http), http.getHeader("User-Agent"));
    }

    @Operation(summary = "Reject the onboarding link")
    @PostMapping("/reject")
    public Map<String, Object> reject(@Valid @RequestBody InvestorLinkActionRequest request, HttpServletRequest http) {
        return linkService.reject(request.token(), clientIp(http), http.getHeader("User-Agent"));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
