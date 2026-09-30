package com.hackathon.cache.cache;

import java.util.concurrent.atomic.LongAdder;

public class CacheStatistics {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder expirations = new LongAdder();

    void hit() {
        hits.increment();
    }

    void miss() {
        misses.increment();
    }

    void put() {
        puts.increment();
    }

    void eviction() {
        evictions.increment();
    }

    void expiration() {
        expirations.increment();
    }

    public long getHits() {
        return hits.sum();
    }

    public long getMisses() {
        return misses.sum();
    }

    public long getPuts() {
        return puts.sum();
    }

    public long getEvictions() {
        return evictions.sum();
    }

    public long getExpirations() {
        return expirations.sum();
    }
}