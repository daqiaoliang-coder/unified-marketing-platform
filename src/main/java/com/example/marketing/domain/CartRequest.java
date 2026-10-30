package com.example.marketing.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CartRequest(
        @NotBlank @Size(max = 100) String cartId,
        @NotBlank @Size(max = 100) String customerId,
        @NotNull @Size(min = 1, max = 8) List<@NotNull @Valid Order> orders) {

    public record Order(
            @NotBlank @Size(max = 100) String orderId,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items) {}

    public record Item(
            @NotBlank @Size(max = 100) String lineId,
            @NotBlank @Size(max = 100) String skuId,
            @Min(1) @Max(10000) int quantity,
            @Min(0) @Max(100_000_000L) long unitPrice) {}
}
