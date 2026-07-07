package com.registry.telephony.job;

import com.registry.telephony.config.TelephonyProperties;
import com.registry.telephony.persistence.CallLeadIngestLog;
import com.registry.telephony.persistence.CallLeadIngestLogRepository;
import com.registry.telephony.persistence.CallLeadProcessingStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "telephony.dispatch",
        name = "sweeper-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LeadIngestStaleSweeper {

    private final CallLeadIngestLogRepository repository;
    private final LeadDispatchService leadDispatchService;
    private final TelephonyProperties telephonyProperties;

    @Scheduled(fixedDelayString = "${telephony.dispatch.sweeper-interval-ms:60000}")
    public void sweep() {
        int staleSec = telephonyProperties.getDispatch().getStaleAfterSeconds();
        Instant cutoff = Instant.now().minus(staleSec, ChronoUnit.SECONDS);
        List<CallLeadIngestLog> stuck =
                repository.findStale(
                        List.of(
                                CallLeadProcessingStatus.RECEIVED,
                                CallLeadProcessingStatus.SENDING,
                                CallLeadProcessingStatus.FAILED_RETRYABLE),
                        Instant.now(),
                        cutoff);
        for (CallLeadIngestLog row : stuck) {
            log.info("Retrying stale lead ingest {}", row.getId());
            leadDispatchService.dispatchNow(row.getId());
        }
    }
}

