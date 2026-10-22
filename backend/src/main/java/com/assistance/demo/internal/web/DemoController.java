package com.assistance.demo.internal.web;

import com.assistance.demo.internal.service.DemoSeedService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoSeedService seed;

    public DemoController(DemoSeedService seed) {
        this.seed = seed;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed() {
        return Map.of("ok", true, "eventsGenerated", seed.seed());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        return Map.of("ok", true, "eventsDeleted", seed.reset());
    }
}
