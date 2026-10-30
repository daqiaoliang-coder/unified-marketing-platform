package com.example.marketing.engine;

/** Pure monetary calculation; eligibility, capping and allocation belong to the engine. */
@FunctionalInterface
public interface DiscountPlugin {
    /** subtotal and quantity include only SKU-matched lines, at their current balances. */
    long discount(long subtotal, long quantity, long value);
}
