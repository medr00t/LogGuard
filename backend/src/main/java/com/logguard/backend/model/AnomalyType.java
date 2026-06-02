package com.logguard.backend.model;

public enum AnomalyType {
    KEYWORD_MATCH,
    ERROR_RATE_SPIKE,
    REPEATED_MESSAGE,
    SIMILAR_MESSAGE,
    PREDICTIVE_ALERT,
    CASCADE_FAILURE
}
