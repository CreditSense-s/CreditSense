package com.creditsense.application;

import com.creditsense.compliance.GstinFormat;
import com.creditsense.domain.DocumentType;
import com.creditsense.domain.LoanPurpose;
import com.creditsense.domain.Sector;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything an applicant submits. Monetary amounts are in INR lakh. */
public record SubmitApplicationRequest(
        @Valid @NotNull BusinessProfile business,
        @Valid @NotNull LoanRequest loan,
        @Valid @NotNull Financials financials,
        @NotNull @Size(min = 1, max = 10) List<@Valid DocumentInput> documents,
        @AssertTrue(message = "you must confirm the research-demo notice and consent to storage of what you submit")
                boolean consent) {

    public record BusinessProfile(
            @NotBlank @Size(max = 200) String businessName,
            @NotBlank @Size(max = 120) String ownerName,
            @NotNull Sector sector,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "PAN must look like ABCPE1234F") String pan,
            @NotBlank @GstinFormat String gstin,
            @Pattern(regexp = "^(?i)UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7}$", message = "Udyam number must look like UDYAM-TS-02-0012345")
                    String udyamNumber,
            @NotBlank @Size(max = 300) String addressLine,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "^[0-9]{2}$", message = "state code must be two digits") String stateCode,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "PIN code must be six digits") String pincode,
            @NotNull @PastOrPresent LocalDate businessStartDate) {}

    public record LoanRequest(
            @NotNull @DecimalMin("0.5") @DecimalMax("5000") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull LoanPurpose purpose,
            @Min(3) @Max(120) int tenureMonths) {}

    public record Financials(
            @NotNull @Size(min = 6, max = 6, message = "provide revenue for each of the last six months")
                    List<@NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal> monthlyRevenues,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal existingDebt,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000") BigDecimal avgMonthlyInflow,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000") BigDecimal avgMonthlyOutflow,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal avgBankBalance,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal gstOnTimeFilingPct,
            @Min(0) @Max(500) int tradeReferences,
            @Min(0) @Max(100) int delinquencyEvents,
            @Min(0) @Max(1000000) int digitalTxnPerMonth) {}

    public record DocumentInput(
            @NotNull DocumentType type,
            @NotBlank @Size(max = 50) String documentNumber,
            @Min(1) @Max(60) Integer monthsCovered) {}
}
