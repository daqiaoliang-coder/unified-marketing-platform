package com.example.marketing.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class TransactionModels {
    private TransactionModels() {}

    public enum State { TRYING, CONFIRMED, CANCELLED }
    public enum EventType { PAID, CANCELLED }
    public enum EventState { PENDING, PROCESSED, FAILED }

    public record Submit(
            @NotBlank @Size(max = 100) String transactionId,
            @NotNull @Valid CartRequest cart,
            @Min(0) long expectedPayable) {}

    public record ResourceInput(
            @NotBlank @Size(max = 100) String id,
            @Min(0) @Max(1_000_000_000) long total) {}

    public record Resource(String id, long total, long available, long reserved, long consumed) {}

    public record Snapshot(String transactionId, State status, CartRequest request, Quote quote,
                           Instant createdAt, Instant updatedAt) {}

    public record EventInput(
            @NotBlank @Size(max = 100) String eventId,
            @NotBlank @Size(max = 100) String transactionId,
            @NotNull EventType type) {}

    public record OrderEvent(String eventId, String transactionId, EventType type, EventState status,
                             int attempts, Instant nextAttemptAt, String lastError, Instant createdAt) {}

    public record Reward(String id, String transactionId, String orderId, String promotionId,
                         String kind, String referenceCode, long quantity, String status) {}
}
