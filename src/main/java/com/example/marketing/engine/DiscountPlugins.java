package com.example.marketing.engine;

import com.example.marketing.domain.Promotion.DiscountKind;

import java.math.BigInteger;
import java.util.Map;

/** Immutable plugin registry; plugins are stateless and never access request state. */
final class DiscountPlugins {
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    static final Map<DiscountKind, DiscountPlugin> STANDARD = Map.of(
            DiscountKind.AMOUNT_OFF, (subtotal, quantity, value) -> Math.min(subtotal, value),
            DiscountKind.PERCENT_OFF, (subtotal, quantity, value) ->
                    BigInteger.valueOf(subtotal).multiply(BigInteger.valueOf(value))
                            .divide(BASIS_POINTS).min(BigInteger.valueOf(subtotal)).longValueExact(),
            DiscountKind.FIXED_UNIT_PRICE, (subtotal, quantity, value) ->
                    BigInteger.valueOf(subtotal)
                            .subtract(BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(value)))
                            .max(BigInteger.ZERO).longValueExact());

    private DiscountPlugins() {}
}
