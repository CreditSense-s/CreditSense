package com.creditsense.web;

import com.creditsense.application.ApplicationDtos.Detail;
import com.creditsense.application.ApplicationDtos.Summary;
import com.creditsense.application.DecisionRequest;
import com.creditsense.application.LoanApplicationService;
import com.creditsense.application.SubmitApplicationRequest;
import com.creditsense.common.Actor;
import com.creditsense.domain.ApplicationStatus;
import com.creditsense.domain.RiskBand;
import com.creditsense.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/applications")
@Tag(name = "Loan applications")
public class ApplicationController {

    private final LoanApplicationService service;

    public ApplicationController(LoanApplicationService service) {
        this.service = service;
    }

    /**
     * Submits an application and runs the pipeline: compliance gate, then (only if it passes)
     * risk scoring. Each step commits on its own, so a submission is never lost.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('APPLICANT')")
    @Operation(summary = "Submit a loan application (applicant)")
    public Detail submit(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SubmitApplicationRequest req) {
        Long id = service.submit(user, req).getId();
        // the automatic pipeline acts as the system, not as the applicant
        if (service.runCompliance(id, Actor.SYSTEM).passed()) {
            service.runRisk(id, Actor.SYSTEM);
        }
        return service.detail(id, user);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('APPLICANT')")
    @Operation(summary = "The signed-in applicant's applications")
    public List<Summary> mine(@AuthenticationPrincipal AuthUser user) {
        return service.mine(user);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LOAN_OFFICER','ADMIN')")
    @Operation(summary = "Loan officer queue, filterable by status and risk band")
    public Page<Summary> queue(@RequestParam(required = false) List<ApplicationStatus> status,
            @RequestParam(required = false) List<RiskBand> band, @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.queue(status, band, q, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Application detail; applicants see only their own")
    public Detail get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return service.detail(id, user);
    }

    @PostMapping("/{id}/compliance-check")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER','ADMIN')")
    @Operation(summary = "Run (or re-run) the compliance gate")
    public Detail complianceCheck(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        service.runCompliance(id, user.actor());
        return service.detail(id, user);
    }

    @PostMapping("/{id}/risk-assessment")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER','ADMIN')")
    @Operation(summary = "Score with the ML service; falls back to manual review if it is unavailable")
    public Detail riskAssessment(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        service.runRisk(id, user.actor());
        return service.detail(id, user);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER','ADMIN')")
    @Operation(summary = "Approve or reject; a reason is required when overriding the model")
    public Detail decide(@PathVariable Long id, @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody DecisionRequest req) {
        service.decide(id, user, req);
        return service.detail(id, user);
    }
}
