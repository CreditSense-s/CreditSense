package com.creditsense.application;

import com.creditsense.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read models returned by the application endpoints. */
public final class ApplicationDtos {

    private ApplicationDtos() {}

    public record Summary(
            Long id, String reference, String businessName, Sector sector, BigDecimal amountRequested,
            LoanPurpose purpose, ApplicationStatus status, RiskBand riskBand, BigDecimal probabilityOfDefault,
            Decision modelRecommendation, Decision decision, Instant submittedAt, Instant decidedAt) {

        public static Summary of(LoanApplication a) {
            return new Summary(a.getId(), a.getReference(), a.getApplicant().getBusinessName(),
                    a.getApplicant().getSector(), a.getAmountRequested(), a.getPurpose(), a.getStatus(),
                    a.getLatestRiskBand(), a.getLatestPd(), a.getModelRecommendation(), a.getDecision(),
                    a.getSubmittedAt(), a.getDecidedAt());
        }
    }

    public record Business(
            String businessName, String ownerName, Sector sector, String pan, String gstin, String udyamNumber,
            String addressLine, String city, String stateCode, String pincode, LocalDate businessStartDate) {

        static Business of(Applicant a) {
            return new Business(a.getBusinessName(), a.getOwnerName(), a.getSector(), a.getPan(), a.getGstin(),
                    a.getUdyamNumber(), a.getAddressLine(), a.getCity(), a.getStateCode(), a.getPincode(),
                    a.getBusinessStartDate());
        }
    }

    public record Financials(
            List<BigDecimal> monthlyRevenues, BigDecimal existingDebt, BigDecimal avgMonthlyInflow,
            BigDecimal avgMonthlyOutflow, BigDecimal avgBankBalance, BigDecimal gstOnTimeFilingPct,
            int tradeReferences, int delinquencyEvents, int digitalTxnPerMonth) {

        static Financials of(LoanApplication a) {
            return new Financials(a.getMonthlyRevenues(), a.getExistingDebt(), a.getAvgMonthlyInflow(),
                    a.getAvgMonthlyOutflow(), a.getAvgBankBalance(), a.getGstOnTimeFilingPct(),
                    a.getTradeReferences(), a.getDelinquencyEvents(), a.getDigitalTxnPerMonth());
        }
    }

    public record Document(DocumentType type, String documentNumber, Integer monthsCovered, boolean verified,
            String verificationNote) {

        static Document of(KycDocument d) {
            return new Document(d.getDocType(), d.getDocumentNumber(), d.getMonthsCovered(), d.isVerified(),
                    d.getVerificationNote());
        }
    }

    public record Check(CheckType checkType, CheckType.Category category, boolean passed, String reason,
            Map<String, Object> details, Instant evaluatedAt) {

        static Check of(ComplianceCheck c) {
            return new Check(c.getCheckType(), c.getCategory(), c.isPassed(), c.getReason(), c.getDetails(),
                    c.getEvaluatedAt());
        }
    }

    public record Risk(BigDecimal probabilityOfDefault, RiskBand riskBand, String modelVersion, double baseValue,
            List<ShapContribution> contributions, Map<String, Object> featureVector, Instant assessedAt) {

        static Risk of(RiskAssessment r) {
            return new Risk(r.getProbabilityOfDefault(), r.getRiskBand(), r.getModelVersion(), r.getBaseValue(),
                    r.getShapContributions(), r.getFeatureVector(), r.getAssessedAt());
        }
    }

    public record DecisionInfo(Decision decision, String reason, Boolean override, String decidedBy,
            Instant decidedAt) {}

    public record TimelineEntry(Instant at, String action, String actorEmail, String actorRole, JsonNode before,
            JsonNode after) {

        static TimelineEntry of(AuditLog l) {
            return new TimelineEntry(l.getCreatedAt(), l.getAction(), l.getActorEmail(), l.getActorRole(),
                    l.getBeforeState(), l.getAfterState());
        }
    }

    public record Detail(
            Long id, String reference, ApplicationStatus status, BigDecimal amountRequested, LoanPurpose purpose,
            int tenureMonths, Instant submittedAt, Business business, Financials financials, List<Document> documents,
            BigDecimal kycScore, List<Check> complianceChecks, Risk riskAssessment, Decision modelRecommendation,
            String manualReviewReason,
            /** In manual review only because the model was unreachable; it is scored as soon as the model answers. */
            boolean awaitingModel, DecisionInfo decision, Outcome outcome, Instant outcomeRecordedAt,
            /** Audit trail of this application; returned to staff only. */
            List<TimelineEntry> timeline) {}
}
