package com.creditsense.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_applications")
@Getter
@Setter
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id")
    private Applicant applicant;

    /** INR lakh. */
    @Column(name = "amount_requested", nullable = false)
    private BigDecimal amountRequested;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanPurpose purpose;

    /** When the applicant consented to the research-demo notice and to storage of this submission. */
    @Column(name = "consent_at")
    private Instant consentAt;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    /** Revenue for each of the last six months, INR lakh, oldest first. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monthly_revenues", nullable = false, columnDefinition = "jsonb")
    private List<BigDecimal> monthlyRevenues = new ArrayList<>();

    @Column(name = "existing_debt", nullable = false)
    private BigDecimal existingDebt;

    @Column(name = "avg_monthly_inflow", nullable = false)
    private BigDecimal avgMonthlyInflow;

    @Column(name = "avg_monthly_outflow", nullable = false)
    private BigDecimal avgMonthlyOutflow;

    @Column(name = "avg_bank_balance", nullable = false)
    private BigDecimal avgBankBalance;

    @Column(name = "gst_on_time_filing_pct", nullable = false)
    private BigDecimal gstOnTimeFilingPct;

    @Column(name = "trade_references", nullable = false)
    private int tradeReferences;

    @Column(name = "delinquency_events", nullable = false)
    private int delinquencyEvents;

    @Column(name = "digital_txn_per_month", nullable = false)
    private int digitalTxnPerMonth;

    @Column(name = "kyc_score")
    private BigDecimal kycScore;

    @Column(name = "latest_pd")
    private BigDecimal latestPd;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_risk_band")
    private RiskBand latestRiskBand;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_recommendation")
    private Decision modelRecommendation;

    @Column(name = "manual_review_reason")
    private String manualReviewReason;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "decision_override")
    private Boolean decisionOverride;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @Column(name = "outcome_recorded_at")
    private Instant outcomeRecordedAt;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<KycDocument> documents = new ArrayList<>();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void addDocument(KycDocument doc) {
        doc.setApplication(this);
        documents.add(doc);
    }
}
