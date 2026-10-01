"""Synthetic MSME risk data generation and champion/challenger model lifecycle."""
from __future__ import annotations
import json, os
from datetime import datetime, timezone
from pathlib import Path
import joblib, numpy as np, pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, confusion_matrix,
                             roc_auc_score, roc_curve)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBClassifier

DATA_DIR = Path(os.getenv("DATA_DIR", "data")); MODEL_DIR = Path(os.getenv("MODEL_DIR", "models"))
DATA_DIR.mkdir(parents=True, exist_ok=True); MODEL_DIR.mkdir(parents=True, exist_ok=True)
DATA_FILE, MODEL_FILE, META_FILE = DATA_DIR / "synthetic_loans.csv", MODEL_DIR / "champion.joblib", MODEL_DIR / "registry.json"
NUMERIC = ["business_vintage_years", "monthly_revenue", "revenue_volatility", "gst_filing_consistency", "debt_to_revenue_ratio", "inflow_outflow_ratio", "average_bank_balance", "trade_references", "delinquency_buckets", "loan_to_revenue_ratio", "kyc_completeness_score", "digital_transaction_frequency"]
FEATURES = NUMERIC + ["sector"]

def generate_synthetic_data(n: int = 20000, seed: int = 42) -> pd.DataFrame:
    """Generate a non-linear, noisy synthetic loan book; this is intentionally not real borrower data."""
    r = np.random.default_rng(seed)
    sector = r.choice(["manufacturing", "retail", "services", "hospitality", "construction", "agriculture"], n, p=[.22,.24,.20,.10,.14,.10])
    revenue = r.lognormal(13.0, .8, n)
    df = pd.DataFrame({
        "business_vintage_years": np.clip(r.gamma(2.2, 2.8, n), .1, 30),
        "monthly_revenue": revenue, "revenue_volatility": r.beta(2, 7, n),
        "gst_filing_consistency": r.beta(11, 2, n), "debt_to_revenue_ratio": r.beta(2, 5, n) * 2,
        "inflow_outflow_ratio": np.clip(r.normal(1.18, .28, n), .2, 3),
        "average_bank_balance": revenue * r.uniform(.03, .35, n),
        "trade_references": r.poisson(4, n), "delinquency_buckets": r.poisson(.45, n),
        "loan_to_revenue_ratio": r.beta(2, 6, n) * 1.5, "sector": sector,
        "kyc_completeness_score": r.beta(12, 2, n), "digital_transaction_frequency": r.lognormal(4.4, .65, n)
    })
    sector_effect = pd.Series(sector).map({"hospitality": .55, "construction": .35, "retail": .16, "manufacturing": .08, "agriculture": .22, "services": -.18}).to_numpy()
    # Delinquency has a sharp effect, while weak GST and low vintage interact: deliberately non-linear.
    logit = (-3.25 + 1.6 * df.debt_to_revenue_ratio + 1.25 * df.loan_to_revenue_ratio +
             1.1 * df.revenue_volatility + .58 * df.delinquency_buckets - 2.0 * df.gst_filing_consistency -
             1.35 * df.kyc_completeness_score - .10 * df.business_vintage_years - .008 * df.trade_references -
             .0017 * df.digital_transaction_frequency + sector_effect +
             1.1 * ((df.business_vintage_years < 2) & (df.gst_filing_consistency < .75)) +
             .65 * ((df.inflow_outflow_ratio < .9) & (df.average_bank_balance < revenue * .08)) + r.normal(0, .6, n))
    df["defaulted_within_12_months"] = r.binomial(1, 1 / (1 + np.exp(-logit)))
    return df

def _metrics(y, p):
    fpr, tpr, _ = roc_curve(y, p); threshold = .35; cm = confusion_matrix(y, p >= threshold).ravel().tolist()
    return {"auc_roc": round(float(roc_auc_score(y, p)), 4), "pr_auc": round(float(average_precision_score(y, p)), 4),
            "ks": round(float(np.max(tpr - fpr)), 4), "brier_score": round(float(brier_score_loss(y, p)), 4),
            "threshold": threshold, "tn": cm[0], "fp": cm[1], "fn": cm[2], "tp": cm[3]}

def train(df: pd.DataFrame | None = None, promote: bool = True) -> dict:
    if df is None: df = pd.read_csv(DATA_FILE) if DATA_FILE.exists() else generate_synthetic_data()
    df.to_csv(DATA_FILE, index=False)
    X_train, X_test, y_train, y_test = train_test_split(df[FEATURES], df.defaulted_within_12_months, test_size=.2, stratify=df.defaulted_within_12_months, random_state=42)
    prep = ColumnTransformer([("numeric", StandardScaler(), NUMERIC), ("sector", OneHotEncoder(handle_unknown="ignore"), ["sector"])])
    imbalance = (y_train == 0).sum() / (y_train == 1).sum()
    xgb = Pipeline([("prep", prep), ("model", XGBClassifier(n_estimators=350, max_depth=4, learning_rate=.045, subsample=.85, colsample_bytree=.9, scale_pos_weight=imbalance, eval_metric="logloss", random_state=42))])
    baseline = Pipeline([("prep", prep), ("model", LogisticRegression(max_iter=1000, class_weight="balanced"))])
    xgb.fit(X_train, y_train); baseline.fit(X_train, y_train)
    xgb_metrics, baseline_metrics = _metrics(y_test, xgb.predict_proba(X_test)[:, 1]), _metrics(y_test, baseline.predict_proba(X_test)[:, 1])
    registry = json.loads(META_FILE.read_text()) if META_FILE.exists() else {"comparisons": []}
    previous_auc = registry.get("metrics", {}).get("auc_roc", -1)
    promoted = promote and xgb_metrics["auc_roc"] > previous_auc
    version = datetime.now(timezone.utc).strftime("xgb-%Y%m%dT%H%M%SZ")
    comparison = {"at": datetime.now(timezone.utc).isoformat(), "challenger_version": version, "champion_auc": previous_auc, "challenger_auc": xgb_metrics["auc_roc"], "promoted": promoted}
    registry.setdefault("comparisons", []).append(comparison)
    if promoted:
        joblib.dump(xgb, MODEL_FILE)
        registry.update({"version": version, "trained_at": datetime.now(timezone.utc).isoformat(), "metrics": xgb_metrics, "baseline_metrics": baseline_metrics})
    META_FILE.write_text(json.dumps(registry, indent=2))
    return registry

def ensure_model() -> dict:
    return json.loads(META_FILE.read_text()) if MODEL_FILE.exists() and META_FILE.exists() else train()
