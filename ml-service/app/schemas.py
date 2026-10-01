from datetime import datetime
from typing import Literal
from pydantic import BaseModel, Field

SECTORS = ["manufacturing", "retail", "services", "hospitality", "construction", "agriculture"]

class FeatureVector(BaseModel):
    business_vintage_years: float = Field(ge=0, le=100)
    monthly_revenue: float = Field(gt=0)
    revenue_volatility: float = Field(ge=0, le=5)
    gst_filing_consistency: float = Field(ge=0, le=1)
    debt_to_revenue_ratio: float = Field(ge=0, le=20)
    inflow_outflow_ratio: float = Field(ge=0, le=10)
    average_bank_balance: float = Field(ge=0)
    trade_references: int = Field(ge=0, le=100)
    delinquency_buckets: int = Field(ge=0, le=20)
    loan_to_revenue_ratio: float = Field(ge=0, le=20)
    sector: Literal["manufacturing", "retail", "services", "hospitality", "construction", "agriculture"]
    kyc_completeness_score: float = Field(ge=0, le=1)
    digital_transaction_frequency: float = Field(ge=0)

class Contribution(BaseModel):
    feature: str
    feature_value: str | float
    shap_contribution: float

class Prediction(BaseModel):
    probability_of_default: float
    risk_band: Literal["LOW", "MEDIUM", "HIGH"]
    model_version: str
    explanation: list[Contribution]

class ModelInfo(BaseModel):
    model_version: str
    training_date: datetime
    metrics: dict[str, float]
    feature_list: list[str]
    comparisons: list[dict]
