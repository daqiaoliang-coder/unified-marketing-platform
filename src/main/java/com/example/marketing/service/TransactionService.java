package com.example.marketing.service;

import com.example.marketing.domain.*;
import com.example.marketing.domain.Quote.AppliedPromotion;
import com.example.marketing.domain.TransactionModels.*;
import com.example.marketing.persistence.MarketingStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {
    private enum Action { TRY, CONFIRM, TCC_CANCEL, ORDER_CANCEL, PAID }
    private final MarketingStore store;
    private final QuoteService quotes;
    private final Clock clock;

    public TransactionService(MarketingStore store, QuoteService quotes, Clock clock) {
        this.store = store;
        this.quotes = quotes;
        this.clock = clock;
    }

    @Transactional
    public Snapshot tryOrder(Submit command) {
        store.lock();
        return prepare(command);
    }

    @Transactional
    public Snapshot placeOrder(Submit command) {
        store.lock();
        var snapshot = prepare(command);
        dealPromotionAfter(snapshot, Action.CONFIRM);
        return get(command.transactionId());
    }

    @Transactional
    public Snapshot confirm(String id) {
        store.lock();
        dealPromotionAfter(get(id), Action.CONFIRM);
        return get(id);
    }

    @Transactional
    public Snapshot cancel(String id) {
        store.lock();
        var existing = store.snapshot(id);
        if (existing.isEmpty()) {
            var now = clock.instant();
            store.insertSnapshot(new Snapshot(id, State.CANCELLED, null, null, now, now));
        } else {
            dealPromotionAfter(existing.get(), Action.TCC_CANCEL);
        }
        return get(id);
    }

    public Snapshot get(String id) {
        return store.snapshot(id).orElseThrow(() -> new BusinessException("SNAPSHOT_NOT_FOUND", "Transaction snapshot not found", 404));
    }

    public List<Reward> rewards(String id) {
        get(id);
        return store.rewards(id);
    }

    void handleEvent(OrderEvent event) {
        dealPromotionAfter(get(event.transactionId()), event.type() == EventType.PAID ? Action.PAID : Action.ORDER_CANCEL);
    }

    private Snapshot prepare(Submit command) {
        var existing = store.snapshot(command.transactionId());
        if (existing.isPresent()) {
            var snapshot = existing.get();
            if (snapshot.status() == State.CANCELLED)
                throw new BusinessException("TRANSACTION_CANCELLED", "Cancelled transaction ids cannot be reused", 409);
            if (!snapshot.request().equals(command.cart()) || snapshot.quote().payableTotal() != command.expectedPayable())
                throw new BusinessException("IDEMPOTENCY_CONFLICT", "Transaction id was used with a different request", 409);
            return snapshot;
        }
        if (store.cartExists(command.cart().cartId()))
            throw new BusinessException("CART_ALREADY_SUBMITTED", "Cart already belongs to another transaction", 409);
        var quote = quotes.calculate(command.cart());
        if (quote.payableTotal() != command.expectedPayable())
            throw new BusinessException("PRICE_CHANGED", "Recalculate the quote before submitting", 409);
        var now = clock.instant();
        var snapshot = new Snapshot(command.transactionId(), State.TRYING, command.cart(), quote, now, now);
        store.insertSnapshot(snapshot);
        dealPromotionAfter(snapshot, Action.TRY);
        return get(command.transactionId());
    }

    private void dealPromotionAfter(Snapshot snapshot, Action action) {
        var state = snapshot.status();
        var id = snapshot.transactionId();
        if (action == Action.TRY) {
            for (var entry : resources(snapshot).entrySet()) {
                if (!store.reserve(entry.getKey(), entry.getValue()))
                    throw new BusinessException("RESOURCE_EXHAUSTED", "Insufficient promotion resource: " + entry.getKey(), 409);
            }
        } else if (action == Action.CONFIRM) {
            if (state == State.CANCELLED)
                throw new BusinessException("TRANSACTION_CANCELLED", "Cannot confirm a cancelled transaction", 409);
            if (state == State.CONFIRMED) return;
            resources(snapshot).forEach(store::consume);
            store.state(id, State.CONFIRMED, clock.instant());
        } else if (action == Action.TCC_CANCEL || action == Action.ORDER_CANCEL) {
            if (state == State.CANCELLED) return;
            if (action == Action.TCC_CANCEL && state == State.CONFIRMED)
                throw new BusinessException("TRANSACTION_CONFIRMED", "Confirmed TCC transactions require an order cancellation event", 409);
            resources(snapshot).forEach((resource, units) -> store.release(resource, units, state == State.CONFIRMED));
            store.revokeRewards(id);
            store.state(id, State.CANCELLED, clock.instant());
        } else {
            if (state == State.CANCELLED) return;
            if (state != State.CONFIRMED)
                throw new BusinessException("ORDER_NOT_CONFIRMED", "Payment event must wait for transaction confirmation", 409);
            for (var promotion : snapshot.quote().promotions()) {
                if (promotion.benefit().coins() > 0)
                    grant(id, promotion, "COINS", "COIN", promotion.benefit().coins());
                if (promotion.benefit().giftQuantity() > 0)
                    grant(id, promotion, "GIFT", promotion.benefit().giftSku(), promotion.benefit().giftQuantity());
            }
        }
    }

    private Map<String, Long> resources(Snapshot snapshot) {
        Map<String, Long> result = new TreeMap<>();
        for (var promotion : snapshot.quote().promotions()) {
            if (promotion.resourceId() != null)
                result.merge(promotion.resourceId(), promotion.resourceUnits(), Math::addExact);
        }
        return result;
    }

    private void grant(String id, AppliedPromotion promotion, String kind, String reference, long quantity) {
        if (!store.rewardExists(id, promotion.orderId(), promotion.promotionId(), kind))
            store.insertReward(new Reward(UUID.randomUUID().toString(), id, promotion.orderId(), promotion.promotionId(),
                    kind, reference, quantity, "ACTIVE"));
    }
}
