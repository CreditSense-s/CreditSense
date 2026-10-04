package com.creditsense.risk;

import com.creditsense.audit.AuditService;
import com.creditsense.common.Actor;
import com.creditsense.common.ApiException;
import com.creditsense.common.AppClock;
import com.creditsense.domain.ApplicationStatus;
import com.creditsense.domain.Decision;
import com.creditsense.domain.LoanApplication;
import com.creditsense.domain.RiskAssessment;
import com.creditsense.domain.RiskBand;
import com.creditsense.domain.ShapContribution;
import com.creditsense.repo.RiskAssessmentRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scores an application that has cleared the compliance gate. A score is persisted only with a
 * valid explanation; if the model is unavailable or its explanation is invalid, the application
 * goes to MANUAL_REVIEW with the reason recorded.
 */
@Service
public class RiskAssessmentService {

    public record Result(boolean scored, RiskAssessment assessment, String manualReviewReason) {}

    /** Start of the manual-review reason when the model could not be reached (as opposed to a rejected score). */
    public static final String MODEL_UNAVAILABLE = "Risk model unavailable: ";

    private final MlClient ml;
    private final ExplanationContract contract;
    private final FeatureVectorBuilder features;
    private final RiskAssessmentRepository assessments;
    private final AuditService audit;
    private final MlProperties props;
    private final AppClock clock;

    public RiskAssessmentService(MlClient ml, ExplanationContract contract, FeatureVectorBuilder features,
            RiskAssessmentRepository assessments, AuditService audit, MlProperties props, AppClock clock) {
        this.ml = ml;
        this.contract = contract;
        this.features = features;
        this.assessments = assessments;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Result assess(LoanApplication app, Actor actor) {
        if (!ApplicationStatus.SCORABLE.contains(app.getStatus())) {
            throw ApiException.conflict(app.getStatus() == ApplicationStatus.COMPLIANCE_FAILED
                    ? "application failed the compliance gate and cannot be scored"
                    : "application in status " + app.getStatus() + " cannot be scored");
        }
        ApplicationStatus before = app.getStatus();
        Map<String, Object> vector = features.build(app);

        MlPrediction p;
        try {
            p = ml.predict(vector);
            contract.validate(p, FeatureVectorBuilder.FEATURES);
        } catch (MlUnavailableException | ExplanationContractViolation e) {
            String reason = e instanceof ExplanationContractViolation
                    ? "Score rejected: " + e.getMessage()
                    : MODEL_UNAVAILABLE + e.getMessage();
            app.setStatus(ApplicationStatus.MANUAL_REVIEW);
            app.setManualReviewReason(truncate(reason, 500));
            app.setModelRecommendation(null);
            audit.record(actor, "ROUTED_TO_MANUAL_REVIEW", "LoanApplication", app.getId(),
                    Map.of("status", before), Map.of("status", app.getStatus(), "reason", app.getManualReviewReason()));
            return new Result(false, null, app.getManualReviewReason());
        }

        RiskAssessment ra = new RiskAssessment();
        ra.setApplication(app);
        ra.setProbabilityOfDefault(BigDecimal.valueOf(p.probabilityOfDefault()).setScale(6, RoundingMode.HALF_UP));
        ra.setRiskBand(RiskBand.valueOf(p.riskBand()));
        ra.setModelVersion(p.modelVersion());
        ra.setBaseValue(p.baseValue());
        ra.setShapContributions(p.contributions().stream()
                .map(c -> new ShapContribution(c.feature(), c.featureValue(), c.shapContribution()))
                .toList());
        ra.setFeatureVector(vector);
        ra.setAssessedAt(clock.now());
        assessments.save(ra);

        Decision recommendation = p.probabilityOfDefault() < props.approveBelow() ? Decision.APPROVE : Decision.REJECT;
        app.setLatestPd(ra.getProbabilityOfDefault());
        app.setLatestRiskBand(ra.getRiskBand());
        app.setModelRecommendation(recommendation);
        app.setManualReviewReason(null);
        app.setStatus(ApplicationStatus.RISK_SCORED);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", app.getStatus());
        after.put("probabilityOfDefault", p.probabilityOfDefault());
        after.put("riskBand", p.riskBand());
        after.put("modelVersion", p.modelVersion());
        after.put("recommendation", recommendation);
        audit.record(actor, "RISK_ASSESSED", "LoanApplication", app.getId(), Map.of("status", before), after);
        return new Result(true, ra, null);
    }

    /** Waiting in manual review only because the model could not be reached, so a score can still follow. */
    public static boolean awaitingModel(LoanApplication app) {
        return app.getStatus() == ApplicationStatus.MANUAL_REVIEW && app.getManualReviewReason() != null
                && app.getManualReviewReason().startsWith(MODEL_UNAVAILABLE);
    }

    /** See {@code LoanApplicationService#reconnectModelIfBack}. */
    public void reconnectModelIfBack() {
        if (ml.circuitState() != CircuitBreaker.State.CLOSED) {
            ml.reconnectIfUp();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
