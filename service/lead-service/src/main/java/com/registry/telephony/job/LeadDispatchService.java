package com.registry.telephony.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.registry.telephony.config.TelephonyProperties;
import com.registry.telephony.persistence.CallLeadIngestLog;
import com.registry.telephony.persistence.CallLeadIngestLogRepository;
import com.registry.telephony.persistence.CallLeadProcessingStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class LeadDispatchService {

    private final CallLeadIngestLogRepository repository;
    private final TelephonyProperties telephonyProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public LeadDispatchService(
            CallLeadIngestLogRepository repository,
            TelephonyProperties telephonyProperties,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Qualifier("telephonyTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.telephonyProperties = telephonyProperties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Async
    public void dispatchAsync(UUID logId) {
        dispatchNow(logId);
    }

    public void dispatchNow(UUID logId) {
        transactionTemplate.executeWithoutResult(s -> executeDispatch(logId));
    }

    private void executeDispatch(UUID logId) {
        CallLeadIngestLog row = repository.findById(logId).orElse(null);
        if (row == null) {
            return;
        }
        if (row.getProcessingStatus() != CallLeadProcessingStatus.RECEIVED
                && row.getProcessingStatus() != CallLeadProcessingStatus.SENDING
                && row.getProcessingStatus() != CallLeadProcessingStatus.FAILED_RETRYABLE) {
            return;
        }

        String url = StringUtils.trimToNull(telephonyProperties.getLeadRegistry().getUrl());
        row.setUpdatedAt(Instant.now());

        if (url == null) {
            row.setProcessingStatus(CallLeadProcessingStatus.SKIPPED_NO_REGISTRY_URL);
            row.setLastError(null);
            row.setSentAt(Instant.now());
            row.setNextRetryAt(null);
            repository.save(row);
            return;
        }

        row.setProcessingStatus(CallLeadProcessingStatus.SENDING);
        repository.saveAndFlush(row);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKeyHeader = StringUtils.trimToNull(telephonyProperties.getLeadRegistry().getApiKeyHeader());
        String apiKeyValue = StringUtils.trimToNull(telephonyProperties.getLeadRegistry().getApiKeyValue());
        if (apiKeyHeader != null && apiKeyValue != null) {
            headers.set(apiKeyHeader, apiKeyValue);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.convertValue(row.getNormalizedLeadPayload(), Map.class);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            row.setProcessingStatus(CallLeadProcessingStatus.SENT);
            row.setLastError(null);
            row.setNextRetryAt(null);
            String snippet = resp.getBody() == null ? "" : resp.getBody();
            row.setExternalResponseSnippet(snippet.length() > 2048 ? snippet.substring(0, 2048) : snippet);
            row.setSentAt(Instant.now());
            log.info("Successfully dispatched lead {}. Registry response: {}", logId, snippet);
        } catch (Exception e) {
            int currentAttempts = row.getAttemptCount() + 1;
            row.setAttemptCount(currentAttempts);
            if (currentAttempts < 5) {
                row.setProcessingStatus(CallLeadProcessingStatus.FAILED_RETRYABLE);
                long delayMinutes = (long) Math.pow(2, currentAttempts);
                row.setNextRetryAt(Instant.now().plus(delayMinutes, java.time.temporal.ChronoUnit.MINUTES));
                log.warn("Lead dispatch attempt {} failed for {}. Scheduled next retry in {} minutes. Error: {}", currentAttempts, logId, delayMinutes, e.getMessage());
            } else {
                row.setProcessingStatus(CallLeadProcessingStatus.FAILED_PERMANENT);
                row.setNextRetryAt(null);
                log.error("Lead dispatch attempt {} failed for {}. Marked as FAILED_PERMANENT. Error: {}", currentAttempts, logId, e.getMessage());
            }
            row.setLastError(StringUtils.abbreviate(e.getMessage(), 4000));
        }

        row.setUpdatedAt(Instant.now());
        repository.save(row);
    }
}

