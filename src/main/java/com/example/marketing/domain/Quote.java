package com.example.marketing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Quote(
        String cartId,
        String customerId,
        Instant calculatedAt,
        long originalTotal,
        long discountTotal,
        long payableTotal,
        Map<String, Long> linePayables,
        List<AppliedPromotion> promotions,
        Map<String, Long> funding) {

    public record AppliedPromotion(
            String promotionId,
            String orderId,
            Promotion.Scope scope,
            long discount,
            Map<String, Long> allocations,
            Map<String, Long> funding,
            String resourceId,
            long resourceUnits,
            Promotion.Benefit benefit) {}
}
