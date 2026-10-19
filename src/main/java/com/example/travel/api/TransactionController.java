package com.example.travel.api;

import com.example.travel.api.dto.CreateTransactionRequest;
import com.example.travel.api.dto.PatchTransactionRequest;
import com.example.travel.api.dto.TransactionListResponse;
import com.example.travel.api.dto.TransactionResponse;
import com.example.travel.transaction.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<TransactionResponse> create(@RequestBody CreateTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{id}")
    TransactionResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    TransactionResponse patch(@PathVariable String id, @RequestBody PatchTransactionRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    TransactionListResponse list(@RequestParam(required = false) String limit,
                                 @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }
}
