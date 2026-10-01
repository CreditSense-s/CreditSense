from __future__ import annotations
import hmac, os
from fastapi import Depends, FastAPI, Header, HTTPException
import joblib, numpy as np, pandas as pd, shap
from .schemas import FeatureVector, Prediction, Contribution, ModelInfo
from .training import FEATURES, MODEL_FILE, META_FILE, ensure_model, train

API_KEY = os.getenv("ML_API_KEY", "")

def require_key(x_api_key: str = Header(default="")):
    # The ML service is reachable from the internet on Render; only the backend holds the key.
    if API_KEY and not hmac.compare_digest(x_api_key, API_KEY):
        raise HTTPException(401, "Invalid API key")

app = FastAPI(title="CreditSense ML", version="1.0.0")
model = explainer = registry = None

def load_model():
    global model, explainer, registry
    registry = ensure_model(); model = joblib.load(MODEL_FILE)
    # TreeExplainer receives the fitted underlying XGBoost model and transformed features.
    explainer = shap.TreeExplainer(model.named_steps["model"])

@app.on_event("startup")
def startup(): load_model()

@app.get("/health")
def health(): return {"status": "ok", "model_version": registry["version"]}

@app.get("/model-info", response_model=ModelInfo, dependencies=[Depends(require_key)])
def model_info():
    return {"model_version": registry["version"], "training_date": registry["trained_at"], "metrics": registry["metrics"], "feature_list": FEATURES, "comparisons": registry.get("comparisons", [])}

@app.post("/predict", response_model=Prediction, dependencies=[Depends(require_key)])
def predict(features: FeatureVector):
    if model is None: raise HTTPException(503, "Model unavailable")
    raw = pd.DataFrame([features.model_dump()])[FEATURES]
    probability = float(model.predict_proba(raw)[0, 1])
    transformed = model.named_steps["prep"].transform(raw)
    values = np.asarray(explainer.shap_values(transformed)).reshape(-1)
    names = model.named_steps["prep"].get_feature_names_out()
    ledger = sorted([Contribution(feature=n.replace("numeric__", "").replace("sector__sector_", "sector: "), feature_value=str(raw.iloc[0].get("sector")) if "sector__" in n else round(float(raw.iloc[0][n.replace("numeric__", "")]), 4), shap_contribution=round(float(v), 6)) for n, v in zip(names, values)], key=lambda x: abs(x.shap_contribution), reverse=True)
    return {"probability_of_default": round(probability, 6), "risk_band": "HIGH" if probability >= .55 else "MEDIUM" if probability >= .25 else "LOW", "model_version": registry["version"], "explanation": ledger}

@app.post("/explain", response_model=Prediction, dependencies=[Depends(require_key)])
def explain(features: FeatureVector): return predict(features)

@app.post("/retrain", dependencies=[Depends(require_key)])
def retrain():
    global registry
    registry = train(promote=True)
    if registry.get("version") != getattr(model, "version", None): load_model()
    return {"model_version": registry["version"], "metrics": registry["metrics"], "comparison": registry["comparisons"][-1]}
