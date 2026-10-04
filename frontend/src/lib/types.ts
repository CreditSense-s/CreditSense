export type Role = 'APPLICANT' | 'LOAN_OFFICER' | 'ADMIN'
export type Sector = 'MANUFACTURING' | 'TRADING' | 'SERVICES' | 'RETAIL' | 'AGRI_ALLIED' | 'HOSPITALITY'
export type Status =
  | 'SUBMITTED'
  | 'COMPLIANCE_REVIEW'
  | 'COMPLIANCE_FAILED'
  | 'RISK_SCORED'
  | 'APPROVED'
  | 'REJECTED'
  | 'MANUAL_REVIEW'
export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH'
export type Decision = 'APPROVE' | 'REJECT'
export type LoanPurpose = 'WORKING_CAPITAL' | 'EQUIPMENT' | 'EXPANSION' | 'INVENTORY' | 'REFINANCE'
export type DocumentType = 'PAN' | 'UDYAM' | 'ADDRESS_PROOF' | 'BANK_STATEMENT' | 'GST_CERTIFICATE'
export type CheckType = 'GST_VALIDITY' | 'KYC_COMPLETENESS' | 'DUPLICATE_PAN' | 'ADDRESS_MISMATCH' | 'BLACKLIST' | 'VELOCITY'

export interface User {
  id: number
  email: string
  fullName: string
  role: Role
}

/** The refresh token is not here: the server keeps it in an HttpOnly cookie that scripts cannot read. */
export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export interface Summary {
  id: number
  reference: string
  businessName: string
  sector: Sector
  amountRequested: number
  purpose: LoanPurpose
  status: Status
  riskBand: RiskBand | null
  probabilityOfDefault: number | null
  modelRecommendation: Decision | null
  decision: Decision | null
  submittedAt: string
  decidedAt: string | null
}

export interface Contribution {
  feature: string
  featureValue: number | string
  shapContribution: number
}

export interface ComplianceCheck {
  checkType: CheckType
  category: 'GST' | 'KYC' | 'FRAUD'
  passed: boolean
  reason: string
  details: Record<string, unknown> | null
  evaluatedAt: string
}

export interface Detail {
  id: number
  reference: string
  status: Status
  amountRequested: number
  purpose: LoanPurpose
  tenureMonths: number
  submittedAt: string
  business: {
    businessName: string
    ownerName: string
    sector: Sector
    pan: string
    gstin: string
    udyamNumber: string | null
    addressLine: string
    city: string
    stateCode: string
    pincode: string
    businessStartDate: string
  }
  financials: {
    monthlyRevenues: number[]
    existingDebt: number
    avgMonthlyInflow: number
    avgMonthlyOutflow: number
    avgBankBalance: number
    gstOnTimeFilingPct: number
    tradeReferences: number
    delinquencyEvents: number
    digitalTxnPerMonth: number
  }
  documents: { type: DocumentType; documentNumber: string; monthsCovered: number | null; verified: boolean; verificationNote: string | null }[]
  kycScore: number | null
  complianceChecks: ComplianceCheck[]
  riskAssessment: {
    probabilityOfDefault: number
    riskBand: RiskBand
    modelVersion: string
    baseValue: number
    contributions: Contribution[]
    featureVector: Record<string, number | string>
    assessedAt: string
  } | null
  modelRecommendation: Decision | null
  manualReviewReason: string | null
  /** In manual review only because the model was asleep; the server scores it once the model answers. */
  awaitingModel: boolean
  decision: { decision: Decision; reason: string | null; override: boolean; decidedBy: string | null; decidedAt: string } | null
  outcome: 'REPAID' | 'DEFAULTED' | null
  outcomeRecordedAt: string | null
  timeline: { at: string; action: string; actorEmail: string; actorRole: string; before: unknown; after: unknown }[]
}

export interface Page<T> {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

export interface AuditEntry {
  id: number
  createdAt: string
  actorId: number | null
  actorEmail: string
  actorRole: string
  action: string
  entityType: string
  entityId: string | null
  beforeState: unknown
  afterState: unknown
}

export interface ModelMetrics {
  auc_roc: number
  pr_auc: number
  ks: number
  brier: number
  ece: number
  calibration_curve: { mean_predicted: number; observed_rate: number; count: number }[]
  confusion_matrix: { threshold: number; tn: number; fp: number; fn: number; tp: number }
  n: number
  default_rate: number
}

export interface ModelInfo {
  model_version: string
  trained_at: string
  algorithm: string
  baseline: string
  features: string[]
  hyperparameters: Record<string, number>
  cv_auc: number | null
  metrics: ModelMetrics
  baseline_metrics: ModelMetrics
  holdout_auc: number | null
  training_rows: number
  feedback_rows: number
  /** SHA-256 of the exact training pool; absent for models trained before fingerprints were recorded */
  training_data_sha256?: string | null
}

export interface Comparison {
  at: string
  event: 'bootstrap' | 'retrain'
  champion_version: string | null
  challenger_version: string
  champion_holdout_auc: number | null
  challenger_holdout_auc: number
  promoted: boolean
  holdout_rows: number
  feedback_rows: number
}

export interface Analytics {
  kpis: {
    totalApplications: number
    pendingReview: number
    approved: number
    rejected: number
    complianceFailed: number
    manualReview: number
    approvalRate: number | null
    averageApprovedPd: number | null
    observedDefaultRate: number | null
    maturedLoans: number
    overrideRate: number | null
  }
  statusCounts: Record<Status, number>
  riskBandCounts: Record<RiskBand, number>
  pdHistogram: { from: number; to: number; count: number }[]
  defaultRateTrend: { month: string; approved: number; matured: number; defaulted: number; defaultRate: number | null; averagePd: number | null }[]
  model?: ModelInfo
  modelHistory?: { serving_version: string; comparisons: Comparison[] }
  modelServiceAvailable: boolean
  circuitBreaker: string
}
