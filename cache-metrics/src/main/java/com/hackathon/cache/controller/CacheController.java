package com.hackathon.cache.controller;

import com.hackathon.cache.cache.EvictionPolicy;
import com.hackathon.cache.model.*;
import com.hackathon.cache.service.CacheService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final CacheService service;

    public CacheController(CacheService service) { this.service = service; }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() { return service.metrics(); }

    @GetMapping("/entries")
    public List<Map<String, Object>> entries() { return service.entries(); }

    @GetMapping("/log")
    public List<Map<String, Object>> log() { return service.activityLog(); }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String key) {
        String v = service.get(key);
        if (v == null) return ResponseEntity.status(404).body(Map.of("key", key, "hit", false, "message", "MISS"));
        return ResponseEntity.ok(Map.of("key", key, "hit", true, "value", v));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> put(@RequestBody CachePutRequest req) {
        if (req.key() == null || req.key().isBlank()) throw new IllegalArgumentException("Key is required");
        if (req.value() == null) throw new IllegalArgumentException("Value is required");
        long ttl = req.ttlSeconds() == null ? 0 : req.ttlSeconds();
        if (ttl < 0) throw new IllegalArgumentException("TTL must be >= 0");
        service.put(req.key().trim(), req.value(), ttl * 1000);
        return ResponseEntity.status(201).body(Map.of("message", "Stored", "key", req.key().trim()));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String key) {
        if (service.remove(key)) return ResponseEntity.ok(Map.of("message", "Removed", "key", key));
        return ResponseEntity.status(404).body(Map.of("message", "Key not found", "key", key));
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        service.clear();
        return Map.of("message", "Cache cleared");
    }

    @PutMapping("/config")
    public Map<String, Object> config(@RequestBody CacheConfigRequest req) {
        if (req.capacity() == null || req.capacity() < 1 || req.capacity() > 100000)
            throw new IllegalArgumentException("Capacity must be between 1 and 100000");
        if (req.policy() == null) throw new IllegalArgumentException("Policy must be LRU or LFU");
        EvictionPolicy p;
        try {
            p = EvictionPolicy.valueOf(req.policy().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Policy must be LRU or LFU");
        }
        return service.configure(req.capacity(), p);
    }

    @PostMapping("/sample-workload")
    public Map<String, Object> sample() { return service.runSampleWorkload(); }

    @PostMapping("/concurrency-test")
    public Map<String, Object> concurrency() { return service.runConcurrencyTest(); }
}