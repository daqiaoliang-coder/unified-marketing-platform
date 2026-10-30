package com.example.marketing.service;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.TransactionModels.*;
import com.example.marketing.persistence.MarketingStore;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private static final int MAX_ATTEMPTS = 8;
    private final MarketingStore store;
    private final TransactionService transactions;
    private final Clock clock;

    public EventService(MarketingStore store, TransactionService transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public OrderEvent accept(EventInput input) {
        store.lock();
        var existing = store.event(input.eventId());
        if (existing.isPresent()) {
            var event = existing.get();
            if (!event.transactionId().equals(input.transactionId()) || event.type() != input.type())
                throw new BusinessException("EVENT_ID_CONFLICT", "Event id was used for another event", 409);
            return event;
        }
        transactions.get(input.transactionId());
        store.insertEvent(input, clock.instant());
        return get(input.eventId());
    }

    public OrderEvent get(String id) {
        return store.event(id).orElseThrow(() -> new BusinessException("EVENT_NOT_FOUND", "Order event not found", 404));
    }

    public List<String> dueEvents() { return store.dueEvents(clock.instant()); }

    @Transactional
    public void process(String id) {
        store.lock();
        var event = get(id);
        if (event.status() != EventState.PENDING || event.nextAttemptAt().isAfter(clock.instant())) return;
        transactions.handleEvent(event);
        store.eventResult(id, EventState.PROCESSED, event.attempts() + 1, clock.instant(), null);
    }

    @Transactional
    public void recordFailure(String id, String errorCode) {
        store.lock();
        var event = get(id);
        if (event.status() != EventState.PENDING) return;
        int attempts = event.attempts() + 1;
        long delay = Math.min(60, 1L << Math.min(attempts, 6));
        store.eventResult(id, attempts >= MAX_ATTEMPTS ? EventState.FAILED : EventState.PENDING,
                attempts, clock.instant().plusSeconds(delay), errorCode);
    }

    @Transactional
    public OrderEvent retry(String id) {
        store.lock();
        var event = get(id);
        if (event.status() != EventState.FAILED)
            throw new BusinessException("EVENT_NOT_FAILED", "Only failed events can be manually retried", 409);
        store.eventResult(id, EventState.PENDING, 0, clock.instant(), null);
        return get(id);
    }
}
