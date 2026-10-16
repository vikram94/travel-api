package com.example.travel.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    record HealthResponse(String status) {
    }

    @GetMapping("/health")
    HealthResponse health() {
        return new HealthResponse("ok");
    }
}
