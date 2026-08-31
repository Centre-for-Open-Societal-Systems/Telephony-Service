package com.registry.telephony.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "telephony_call_lead_ingest_log")
@Getter
@Setter
@NoArgsConstructor
public class CallLeadIngestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "call_unique_id", nullable = false, length = 64)
    private String callUniqueId;

    @Column(name = "call_linked_id", length = 64)
    private String callLinkedId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private CallLeadProcessingStatus processingStatus;

    @Type(JsonBinaryType.class)
    @Column(name = "raw_event_snapshot", columnDefinition = "jsonb")
    private JsonNode rawEventSnapshot;

    @Type(JsonBinaryType.class)
    @Column(name = "normalized_lead_payload", columnDefinition = "jsonb")
    private JsonNode normalizedLeadPayload;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "external_response_snippet", length = 2048)
    private String externalResponseSnippet;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
}

