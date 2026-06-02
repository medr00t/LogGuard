from fastapi import FastAPI
from pydantic import BaseModel
from typing import Optional, List
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity
from sklearn.linear_model import LinearRegression
import numpy as np
from datetime import datetime, timedelta

app = FastAPI(title="LogGuard AI Engine")


# ─── Models ──────────────────────────────────────────────────

class RecentLog(BaseModel):
    source: str
    level: str
    message: str
    timestamp: str


class SimilarRequest(BaseModel):
    message: str
    source: str
    recent_logs: List[RecentLog]


class PredictRequest(BaseModel):
    source: str
    error_counts_per_minute: List[int]


class CascadeRequest(BaseModel):
    source: str
    timestamp: str
    recent_logs: List[RecentLog]


class AnomalyResult(BaseModel):
    type: str
    severity: str
    description: str


class AnalysisResponse(BaseModel):
    anomaly: Optional[AnomalyResult] = None


# ─── Helpers ─────────────────────────────────────────────────

def _parse_ts(ts: str) -> datetime:
    try:
        return datetime.fromisoformat(ts.rstrip("Z"))
    except ValueError:
        return datetime.utcnow()


# ─── Endpoints ───────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/analyze/similar", response_model=AnalysisResponse)
def analyze_similar(req: SimilarRequest):
    """TF-IDF + Cosine Similarity: detects semantically similar repeated messages."""
    same_source = [log for log in req.recent_logs if log.source == req.source]
    if len(same_source) < 3:
        return AnalysisResponse()

    messages = [log.message for log in same_source]
    all_docs = messages + [req.message]

    try:
        vectorizer = TfidfVectorizer()
        tfidf = vectorizer.fit_transform(all_docs)
    except ValueError:
        return AnalysisResponse()

    new_vec = tfidf[-1]
    recent_matrix = tfidf[:-1]
    sims = cosine_similarity(new_vec, recent_matrix)[0]

    max_sim = float(sims.max())
    similar_count = int((sims >= 0.80).sum())

    if max_sim >= 0.85 and similar_count >= 3:
        return AnalysisResponse(anomaly=AnomalyResult(
            type="SIMILAR_MESSAGE",
            severity="MEDIUM",
            description=(
                f"Found {similar_count} semantically similar messages from '{req.source}' "
                f"(max cosine similarity: {max_sim:.2f})"
            ),
        ))

    return AnalysisResponse()


@app.post("/analyze/predict", response_model=AnalysisResponse)
def analyze_predict(req: PredictRequest):
    """Linear Regression on sliding window: predicts error rate breach before it happens."""
    counts = req.error_counts_per_minute
    if len(counts) < 3 or sum(counts) == 0:
        return AnalysisResponse()

    X = np.arange(len(counts)).reshape(-1, 1)
    y = np.array(counts, dtype=float)

    model = LinearRegression().fit(X, y)
    slope = float(model.coef_[0])

    if slope <= 0:
        return AnalysisResponse()

    predicted = float(model.predict([[len(counts)]])[0])
    THRESHOLD = 10.0

    if predicted >= THRESHOLD:
        return AnalysisResponse(anomaly=AnomalyResult(
            type="PREDICTIVE_ALERT",
            severity="HIGH",
            description=(
                f"Error rate from '{req.source}' is trending up — "
                f"predicted {predicted:.1f} errors/min next minute "
                f"(threshold: {int(THRESHOLD)}, slope: +{slope:.2f}/min)"
            ),
        ))

    return AnalysisResponse()


@app.post("/analyze/cascade", response_model=AnalysisResponse)
def analyze_cascade(req: CascadeRequest):
    """Source Correlation: detects cascading failures across multiple services."""
    error_levels = {"ERROR", "FATAL"}
    current_time = _parse_ts(req.timestamp)
    window_start = current_time - timedelta(minutes=2)

    errored_sources: set[str] = set()
    for log in req.recent_logs:
        if log.level.upper() not in error_levels:
            continue
        if _parse_ts(log.timestamp) >= window_start:
            errored_sources.add(log.source)

    if len(errored_sources) >= 3:
        services = ", ".join(sorted(errored_sources))
        return AnalysisResponse(anomaly=AnomalyResult(
            type="CASCADE_FAILURE",
            severity="CRITICAL",
            description=(
                f"Cascading failure detected across {len(errored_sources)} services "
                f"in the last 2 minutes: {services}"
            ),
        ))

    return AnalysisResponse()
