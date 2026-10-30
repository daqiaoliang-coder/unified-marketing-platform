package com.example.marketing.api;

import com.example.marketing.domain.*;
import com.example.marketing.domain.TransactionModels.*;
import com.example.marketing.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class MarketingController {
    private final QuoteService quotes;
    private final CatalogService catalog;
    private final TransactionService transactions;
    private final EventService events;

    public MarketingController(QuoteService quotes, CatalogService catalog, TransactionService transactions, EventService events) {
        this.quotes = quotes;
        this.catalog = catalog;
        this.transactions = transactions;
        this.events = events;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("application", "unified-marketing-platform", "status", "UP"); }

    @PostMapping("/quotes")
    public Quote quote(@Valid @RequestBody CartRequest request) { return quotes.calculate(request); }

    @GetMapping("/promotions")
    public List<Promotion> promotions() { return catalog.promotions(); }

    @PutMapping("/promotions/{id}")
    public Promotion promotion(@PathVariable @NotBlank @Size(max = 100) String id, @Valid @RequestBody Promotion request) {
        if (!id.equals(request.id())) throw new BusinessException("ID_MISMATCH", "Path and promotion ids must match", 400);
        return catalog.savePromotion(request);
    }

    @PostMapping("/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public Resource createResource(@Valid @RequestBody ResourceInput input) { return catalog.createResource(input); }

    @GetMapping("/resources")
    public List<Resource> resources() { return catalog.resources(); }

    @PostMapping("/orders")
    public Snapshot order(@Valid @RequestBody Submit input) { return transactions.placeOrder(input); }

    @PostMapping("/transactions/try")
    public Snapshot tryOrder(@Valid @RequestBody Submit input) { return transactions.tryOrder(input); }

    @PostMapping("/transactions/{id}/confirm")
    public Snapshot confirm(@PathVariable @NotBlank @Size(max = 100) String id) { return transactions.confirm(id); }

    @PostMapping("/transactions/{id}/cancel")
    public Snapshot cancel(@PathVariable @NotBlank @Size(max = 100) String id) { return transactions.cancel(id); }

    @GetMapping("/transactions/{id}")
    public Snapshot transaction(@PathVariable @NotBlank @Size(max = 100) String id) { return transactions.get(id); }

    @GetMapping("/transactions/{id}/rewards")
    public List<Reward> rewards(@PathVariable @NotBlank @Size(max = 100) String id) { return transactions.rewards(id); }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OrderEvent accept(@Valid @RequestBody EventInput input) { return events.accept(input); }

    @GetMapping("/events/{id}")
    public OrderEvent event(@PathVariable @NotBlank @Size(max = 100) String id) { return events.get(id); }

    @PostMapping("/events/{id}/retry")
    public OrderEvent retry(@PathVariable @NotBlank @Size(max = 100) String id) { return events.retry(id); }
}
