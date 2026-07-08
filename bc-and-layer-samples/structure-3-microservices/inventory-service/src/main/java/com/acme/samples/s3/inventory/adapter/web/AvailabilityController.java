package com.acme.samples.s3.inventory.adapter.web;

import com.acme.samples.s3.inventory.app.CheckAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Synchronous cross-service endpoint called by ordering-service. */
@RestController
public class AvailabilityController {

    public record AvailabilityResponse(String sku, int qty, boolean available) {}

    private final CheckAvailabilityService checkAvailability;

    public AvailabilityController(CheckAvailabilityService checkAvailability) {
        this.checkAvailability = checkAvailability;
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(@RequestParam String sku, @RequestParam int qty) {
        return new AvailabilityResponse(sku, qty, checkAvailability.isAvailable(sku, qty));
    }
}
