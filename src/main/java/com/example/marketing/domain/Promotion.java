package com.example.marketing.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record Promotion(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Size(max = 200) String name,
        @NotNull Type type,
        @NotNull Scope scope,
        @Min(0) @Max(10000) int priority,
        @NotNull @Valid Condition condition,
        @NotNull @Valid Discount discount,
        @NotNull @Valid Stacking stacking,
        @NotNull @Size(min = 1, max = 10) Map<@NotBlank @Size(max = 100) String, @NotNull @Min(0) @Max(10000) Integer> fundingBps,
        @Size(max = 100) String resourceId,
        @Min(0) @Max(10000) long resourceUnits,
        @NotNull @Valid Benefit benefit) {

    public enum Type { FLASH_SALE, GROUP_BUY, COUPON, TARGETED, BUNDLE, FULL_REDUCTION, TIMED_DISCOUNT }
    public enum Scope { ORDER, CART }
    public enum DiscountKind { AMOUNT_OFF, PERCENT_OFF, FIXED_UNIT_PRICE }

    public record Condition(
            @Min(0) @Max(100_000_000_000L) long minSpend,
            @Min(1) @Max(10000) int minQuantity,
            @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 100) String> skuIds,
            @NotNull @Size(max = 1000) Set<@NotBlank @Size(max = 100) String> customerIds,
            Instant startsAt,
            Instant endsAt) {}

    public record Discount(@NotNull DiscountKind kind, @Min(0) @Max(100_000_000L) long value) {}

    public record Stacking(
            boolean allowSameType,
            @NotNull Set<@NotNull Type> compatibleTypes,
            @Size(max = 100) String exclusiveGroup) {}

    public record Benefit(
            @Min(0) @Max(1_000_000) long coins,
            @Size(max = 100) String giftSku,
            @Min(0) @Max(10000) int giftQuantity) {
        public static final Benefit NONE = new Benefit(0, null, 0);
    }
}
