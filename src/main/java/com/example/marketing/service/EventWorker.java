package com.example.marketing.service;

import com.example.marketing.domain.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventWorker {
    private static final Logger log = LoggerFactory.getLogger(EventWorker.class);
    private final EventService events;

    public EventWorker(EventService events) { this.events = events; }

    @Scheduled(fixedDelayString = "${marketing.events.delay-ms:1000}", initialDelayString = "${marketing.events.delay-ms:1000}")
    public void runOnce() {
        for (var id : events.dueEvents()) {
            try {
                events.process(id);
            } catch (RuntimeException error) {
                events.recordFailure(id, error instanceof BusinessException business ? business.code() : "PROCESSING_FAILED");
                log.warn("Order event {} failed and was recorded for retry", id, error);
            }
        }
    }
}
