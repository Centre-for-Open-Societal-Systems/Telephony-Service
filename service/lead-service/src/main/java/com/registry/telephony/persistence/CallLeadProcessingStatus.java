package com.registry.telephony.persistence;

public enum CallLeadProcessingStatus {
    RECEIVED,
    SENDING,
    SENT,
    FAILED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    SKIPPED_NO_REGISTRY_URL
}

