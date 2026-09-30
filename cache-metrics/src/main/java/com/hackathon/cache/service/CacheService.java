package com.hackathon.cache.service;

import com.hackathon.cache.cache.*;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CacheService {

    private final CustomCache<String, String> cache = new CustomCache<>(5, EvictionPolicy.LRU);
    private final Deque<Map<String, Object>> log = new ArrayDeque<>();
    private volatile boolean logging = true;

    public CacheService() {
        cache.setListener((type, key) -> log(type,
                "Key '" + key + "' " + (type.equals("EVICTION") ? "evicted (" + cache.getEvictionPolicy() + ")" : "expired")));
    }

    public void put(String key, String value, long ttlMillis) {
        cache.put(key, value, ttlMillis);
        log("PUT", "Key '" + key + "'" + (ttlMillis > 0 ? " ttl=" + ttlMillis + "ms" : " (no ttl)"));
    }

    public String get(String key) {
        String v = cache.get(key);
        log(v == null ? "GET MISS" : "GET HIT", "Key '" + key + "'");
        return v;
    }

    public boolean remove(String key) {
        boolean removed = cache.remove(key);
        log("REMOVE", "Key '" + key + "'" + (removed ? "" : " (not found)"));
        return removed;
    }

    public void clear() {
        cache.clear();
        log("CLEAR", "Cache cleared");
    }

    public Map<String, Object> metrics() { return cache.metrics(); }

    public List<Map<String, Object>> entries() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CacheEntry<String, String> e : cache.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("value", e.getValue());
            m.put("frequency", e.getFrequency());
            m.put("remainingTtlMs", e.getExpirationTime() == 0 ? -1 : Math.max(0, e.getExpirationTime() - now));
            m.put("status", e.getExpirationTime() == 0 ? "NO_TTL" : "ACTIVE");
            out.add(m);
        }
        return out;
    }

    public synchronized List<Map<String, Object>> activityLog() { return new ArrayList<>(log); }

    public Map<String, Object> configure(int capacity, EvictionPolicy policy) {
        cache.setEvictionPolicy(policy);
        cache.setCapacity(capacity);
        log("CONFIG", "Capacity=" + capacity + ", policy=" + policy);
        return metrics();
    }

    /** Real operations on the real cache: PUT, HIT, MISS, repeated access, eviction, TTL expiry. */
    public Map<String, Object> runSampleWorkload() {
        cache.clear();
        cache.setCapacity(5);
        log("CONFIG", "Sample workload started (capacity=5, policy=" + cache.getEvictionPolicy() + ")");
        for (String k : new String[]{"A", "B", "C", "D"}) put(k, "value-" + k, 0);
        put("T", "temporary", 1500);
        for (int i = 0; i < 3; i++) get("A");                       // A becomes hot
        for (String k : new String[]{"B", "C", "D", "T"}) get(k);   // hits
        get("Z");
        get("Y");                                                   // misses
        put("E", "value-E", 0);                                     // full -> eviction (LRU: A, LFU: B)
        try {
            Thread.sleep(1700);                                     // let T expire
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        get("T");                                                   // expired -> miss + expiration
        put("F", "value-F", 0);
        get("E");
        get("E");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", "Sample workload finished");
        r.put("metrics", metrics());
        return r;
    }

    public Map<String, Object> runConcurrencyTest() {
        final int threads = 8, opsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
        long start = System.nanoTime();
        logging = false;
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    for (int i = 0; i < opsPerThread; i++) {
                        try {
                            String k = "ct-" + r.nextInt(40);
                            if (r.nextInt(100) < 40) cache.put(k, "v" + i, r.nextInt(3) == 0 ? 2000 : 0);
                            else cache.get(k);
                            done.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
            logging = true;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("threads", threads);
        r.put("totalOperations", threads * opsPerThread);
        r.put("completed", done.get());
        r.put("errors", errors.get());
        r.put("durationMs", ms);
        r.put("metrics", metrics());
        log("CONCURRENCY", done.get() + "/" + (threads * opsPerThread) + " ops, " + errors.get() + " errors, " + ms + "ms");
        return r;
    }

    private synchronized void log(String type, String message) {
        if (!logging) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        m.put("type", type);
        m.put("message", message);
        log.addFirst(m);
        while (log.size() > 100) log.removeLast();
    }
}