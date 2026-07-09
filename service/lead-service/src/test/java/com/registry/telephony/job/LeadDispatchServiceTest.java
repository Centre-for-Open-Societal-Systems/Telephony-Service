package com.registry.telephony.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.registry.telephony.config.TelephonyProperties;
import com.registry.telephony.persistence.CallLeadIngestLog;
import com.registry.telephony.persistence.CallLeadIngestLogRepository;
import com.registry.telephony.persistence.CallLeadProcessingStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

class LeadDispatchServiceTest {

    private CallLeadIngestLogRepository repository;
    private TelephonyProperties telephonyProperties;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private TransactionTemplate transactionTemplate;
    private LeadDispatchService leadDispatchService;

    @BeforeEach
    void setUp() {
        repository = mock(CallLeadIngestLogRepository.class);
        telephonyProperties = new TelephonyProperties();
        telephonyProperties.getLeadRegistry().setUrl("http://test-registry.com/leads");
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        transactionTemplate = mock(TransactionTemplate.class);

        // Stub TransactionTemplate to run the callback synchronously
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        leadDispatchService = new LeadDispatchService(
                repository,
                telephonyProperties,
                restTemplate,
                objectMapper,
                transactionTemplate
        );
    }

    @Test
    void executeDispatch_onFailure_calculatesExponentialBackoffAndIncrementsAttemptCount() {
        UUID logId = UUID.randomUUID();
        CallLeadIngestLog logRow = new CallLeadIngestLog();
        logRow.setId(logId);
        logRow.setProcessingStatus(CallLeadProcessingStatus.RECEIVED);
        logRow.setAttemptCount(0);

        when(repository.findById(logId)).thenReturn(Optional.of(logRow));
        // Force API failure to trigger retry backoff logic
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Registry connection timeout"));

        leadDispatchService.dispatchNow(logId);

        // Verify status and retry metadata
        assertEquals(1, logRow.getAttemptCount());
        assertEquals(CallLeadProcessingStatus.FAILED_RETRYABLE, logRow.getProcessingStatus());
        assertNotNull(logRow.getNextRetryAt());
        assertTrue(logRow.getNextRetryAt().isAfter(Instant.now()));
        
        // Assert backoff is approximately 2 minutes (2^1 = 2)
        long delaySeconds = logRow.getNextRetryAt().getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(delaySeconds >= 110 && delaySeconds <= 130);

        verify(repository, atLeastOnce()).save(logRow);
    }

    @Test
    void executeDispatch_onRepeatedFailures_marksFailedPermanentOnAttemptExceeded() {
        UUID logId = UUID.randomUUID();
        CallLeadIngestLog logRow = new CallLeadIngestLog();
        logRow.setId(logId);
        logRow.setProcessingStatus(CallLeadProcessingStatus.FAILED_RETRYABLE);
        logRow.setAttemptCount(4); // Previous 4 failed attempts

        when(repository.findById(logId)).thenReturn(Optional.of(logRow));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Fatal server error"));

        leadDispatchService.dispatchNow(logId);

        assertEquals(5, logRow.getAttemptCount());
        assertEquals(CallLeadProcessingStatus.FAILED_PERMANENT, logRow.getProcessingStatus());
        assertNull(logRow.getNextRetryAt());

        verify(repository, atLeastOnce()).save(logRow);
    }
}
