package com.hackathon.cache;

import com.hackathon.cache.cache.CustomCache;
import com.hackathon.cache.cache.EvictionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomCacheTest {

    @Test
    void lruEvictsLeastRecentlyUsed() {
        CustomCache<String, String> c = new CustomCache<>(2, EvictionPolicy.LRU);
        c.put("a", "1", 0);
        c.put("b", "2", 0);
        c.get("a");
        c.put("c", "3", 0);
        assertTrue(c.containsKey("a"));
        assertFalse(c.containsKey("b"));
        assertTrue(c.containsKey("c"));
        assertEquals(1L, c.metrics().get("evictions"));
    }

    @Test
    void lfuEvictsLeastFrequentlyUsed() {
        CustomCache<String, String> c = new CustomCache<>(2, EvictionPolicy.LFU);
        c.put("a", "1", 0);
        c.put("b", "2", 0);
        c.get("a");
        c.get("a");
        c.put("c", "3", 0);
        assertTrue(c.containsKey("a"));
        assertFalse(c.containsKey("b"));
    }

    @Test
    void lfuTieBreaksWithLru() {
        CustomCache<String, String> c = new CustomCache<>(2, EvictionPolicy.LFU);
        c.put("a", "1", 0);
        c.put("b", "2", 0);
        c.put("c", "3", 0); // a and b tie on frequency; a is older
        assertFalse(c.containsKey("a"));
        assertTrue(c.containsKey("b"));
    }

    @Test
    void expiredEntryIsMiss() throws Exception {
        CustomCache<String, String> c = new CustomCache<>(5, EvictionPolicy.LRU);
        c.put("k", "v", 50);
        assertEquals("v", c.get("k"));
        Thread.sleep(120);
        assertNull(c.get("k"));
        assertEquals(1L, c.metrics().get("expirations"));
        assertEquals(1L, c.metrics().get("hits"));
        assertEquals(1L, c.metrics().get("misses"));
        assertEquals(0, c.size());
    }

    @Test
    void ttlIndependentFromEviction() throws Exception {
        CustomCache<String, String> c = new CustomCache<>(2, EvictionPolicy.LRU);
        c.put("a", "1", 50);
        c.put("b", "2", 0);
        Thread.sleep(120);
        c.put("c", "3", 0); // expired "a" is purged, so no capacity eviction is needed
        assertEquals(0L, c.metrics().get("evictions"));
        assertEquals(1L, c.metrics().get("expirations"));
        assertTrue(c.containsKey("b"));
        assertTrue(c.containsKey("c"));
    }

    @Test
    void evictedBeforeTtlWhenCapacityExceeded() {
        CustomCache<String, String> c = new CustomCache<>(1, EvictionPolicy.LRU);
        c.put("a", "1", 60_000);
        c.put("b", "2", 60_000);
        assertFalse(c.containsKey("a"));
        assertEquals(1L, c.metrics().get("evictions"));
    }

    @Test
    void shrinkingCapacityEvicts() {
        CustomCache<String, String> c = new CustomCache<>(5, EvictionPolicy.LRU);
        for (int i = 0; i < 5; i++) c.put("k" + i, "v", 0);
        c.setCapacity(2);
        assertEquals(2, c.size());
        assertEquals(3L, c.metrics().get("evictions"));
    }

    @Test
    void removeAndClear() {
        CustomCache<String, String> c = new CustomCache<>(5, EvictionPolicy.LRU);
        c.put("a", "1", 0);
        assertTrue(c.remove("a"));
        assertFalse(c.remove("a"));
        c.put("b", "2", 0);
        c.clear();
        assertEquals(0, c.size());
    }

    @Test
    void threadSafeUnderConcurrentLoad() throws Exception {
        CustomCache<String, String> c = new CustomCache<>(50, EvictionPolicy.LFU);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger gets = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            tasks.add(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < 2000; i++) {
                    String k = "k" + r.nextInt(100);
                    if (r.nextBoolean()) c.put(k, "v", 0);
                    else { c.get(k); gets.incrementAndGet(); }
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) f.get();
        pool.shutdown();
        assertTrue(c.size() <= 50);
        assertEquals((long) gets.get(), c.metrics().get("totalGets"));
    }
}