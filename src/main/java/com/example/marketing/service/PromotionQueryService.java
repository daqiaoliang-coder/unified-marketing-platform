package com.example.marketing.service;

import com.example.marketing.domain.BusinessException;
import com.example.marketing.domain.CartRequest;
import com.example.marketing.domain.Promotion;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Service;

@Service
public class PromotionQueryService {
    @FunctionalInterface
    public interface Callback {
        List<Promotion> load(CartRequest request) throws Exception;
    }

    private final List<Callback> callbacks;

    public PromotionQueryService(List<Callback> callbacks) {
        this.callbacks = List.copyOf(callbacks);
    }

    public List<Callable<List<Promotion>>> plan(CartRequest request) {
        return callbacks.stream().<Callable<List<Promotion>>>map(callback -> () -> callback.load(request)).toList();
    }

    public List<Promotion> execute(CartRequest request) {
        var candidates = new ArrayList<Promotion>();
        for (var query : plan(request)) {
            try {
                candidates.addAll(query.call());
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new BusinessException("PROMOTION_QUERY_FAILED", "Promotion data source query failed", 502);
            }
        }
        return List.copyOf(candidates);
    }
}
