package com.creditsense.repo;

import com.creditsense.domain.ApplicationStatus;
import com.creditsense.domain.LoanApplication;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface LoanApplicationRepository
        extends JpaRepository<LoanApplication, Long>, JpaSpecificationExecutor<LoanApplication> {

    List<LoanApplication> findByApplicantUserIdOrderBySubmittedAtDesc(Long userId);

    /** Applications by the same applicant submitted in a time window, excluding the one under review. */
    long countByApplicantIdAndSubmittedAtAfterAndIdNot(Long applicantId, Instant after, Long excludeId);

    List<LoanApplication> findByStatusAndOutcomeIsNullAndDecidedAtBefore(ApplicationStatus status, Instant before);

    List<LoanApplication> findByStatus(ApplicationStatus status);

    List<LoanApplication> findByStatusAndManualReviewReasonStartingWith(ApplicationStatus status, String prefix);

    long countByStatus(ApplicationStatus status);

    @Query("select coalesce(max(a.id), 0) from LoanApplication a")
    long maxId();
}
