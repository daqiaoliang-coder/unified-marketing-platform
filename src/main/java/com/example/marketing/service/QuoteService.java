package com.example.marketing.service;

import com.example.marketing.domain.CartRequest;
import com.example.marketing.domain.Quote;
import com.example.marketing.engine.PromotionEngine;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class QuoteService {
    private final PromotionQueryService queries;
    private final PromotionEngine engine;
    private final Clock clock;

    public QuoteService(PromotionQueryService queries, PromotionEngine engine, Clock clock) {
        this.queries = queries;
        this.engine = engine;
        this.clock = clock;
    }

    public Quote calculate(CartRequest request) {
        return engine.calculate(request, queries.execute(request), clock.instant());
    }
}
