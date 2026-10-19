package com.example.travel.api;

import com.example.travel.api.dto.InvoiceListResponse;
import com.example.travel.api.dto.InvoiceResponse;
import com.example.travel.api.dto.InvoiceTransactionsRequest;
import com.example.travel.invoice.InvoiceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping("/invoices")
    ResponseEntity<InvoiceResponse> create(@RequestBody InvoiceTransactionsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/invoices/{id}")
    InvoiceResponse get(@PathVariable String id,
                        @RequestParam(name = "items_limit", required = false) String itemsLimit,
                        @RequestParam(name = "items_cursor", required = false) String itemsCursor) {
        return service.get(id, itemsLimit, itemsCursor);
    }

    @PatchMapping("/invoices/{id}")
    InvoiceResponse patch(@PathVariable String id, @RequestBody InvoiceTransactionsRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/invoices/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invoices")
    InvoiceListResponse list(@RequestParam(required = false) String limit,
                             @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }
}
