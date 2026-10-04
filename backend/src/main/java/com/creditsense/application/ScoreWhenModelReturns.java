package com.creditsense.application;

import com.creditsense.common.Actor;
import com.creditsense.domain.ApplicationStatus;
import com.creditsense.domain.LoanApplication;
import com.creditsense.repo.LoanApplicationRepository;
import com.creditsense.risk.MlClient;
import com.creditsense.risk.RiskAssessmentService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scores the applications that went to manual review only because the risk model could not be reached.
 * On a free host the ML service sleeps when idle and takes a minute or two to start; an application submitted
 * meanwhile waits in manual review. While the model is unreachable this checks every few seconds whether it
 * answers again and, once it does, scores the waiting applications, so applicants see their result without an
 * officer clicking Re-score. Nothing is checked while the model is answering normally.
 */
@Component
public class ScoreWhenModelReturns {

    private static final Logger log = LoggerFactory.getLogger(ScoreWhenModelReturns.class);

    private final MlClient ml;
    private final LoanApplicationRepository applications;
    private final LoanApplicationService service;

    public ScoreWhenModelReturns(MlClient ml, LoanApplicationRepository applications, LoanApplicationService service) {
        this.ml = ml;
        this.applications = applications;
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${creditsense.ml.outage-check-interval:PT15S}")
    void scoreWaitingApplications() {
        if (!ml.inOutage() || !ml.reconnectIfUp()) {
            return;
        }
        List<Long> waiting = applications.findByStatusAndManualReviewReasonStartingWith(ApplicationStatus.MANUAL_REVIEW,
                RiskAssessmentService.MODEL_UNAVAILABLE).stream().map(LoanApplication::getId).toList();
        if (!waiting.isEmpty()) {
            log.info("Risk model answers again; scoring {} application(s) that waited for it", waiting.size());
        }
        for (Long id : waiting) {
            try {
                if (!service.runRisk(id, Actor.SYSTEM).scored()) {
                    return; // unreachable again: the next check picks the rest up
                }
            } catch (RuntimeException e) {
                log.warn("Could not score waiting application {}: {}", id, e.getMessage());
            }
        }
    }
}
