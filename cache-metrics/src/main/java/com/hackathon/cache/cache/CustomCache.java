package com.hackathon.cache.cache;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory cache. A single ReentrantLock guards the LinkedHashMap, whose
 * iteration order is kept as recency order (oldest first). LRU evicts the first entry;
 * LFU evicts the lowest frequency, ties resolved by the first (least recently used).
 */
public class CustomCache<K, V> {

    public interface Listener<K> { void onEvent(String type, K key); }

    private final Map<K, CacheEntry<K, V>> map = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStatistics stats = new CacheStatistics();
    private volatile int capacity;
    private volatile EvictionPolicy policy;
    private volatile Listener<K> listener = (t, k) -> { };

    public CustomCache(int capacity, EvictionPolicy policy) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be at least 1");
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void setListener(Listener<K> l) { this.listener = l == null ? (t, k) -> { } : l; }

    /** ttlMillis <= 0 means the entry never expires. */
    public void put(K key, V value, long ttlMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            long exp = ttlMillis > 0 ? now + ttlMillis : 0;
            CacheEntry<K, V> e = map.remove(key);
            if (e != null && e.isExpired(now)) {
                stats.expiration();
                listener.onEvent("EXPIRATION", key);
                e = null;
            }
            if (e == null) {
                makeRoom(now);
                e = new CacheEntry<>(key, value, now, exp);
            } else {
                e.update(value, now, exp);
            }
            map.put(key, e); // re-inserted at the most-recent end
            stats.put();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the value, or null on a miss (absent or expired). */
    public V get(K key) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            CacheEntry<K, V> e = map.get(key);
            if (e == null) {
                stats.miss();
                return null;
            }
            if (e.isExpired(now)) {
                map.remove(key);
                stats.expiration();
                stats.miss();
                listener.onEvent("EXPIRATION", key);
                return null;
            }
            e.touch(now);
            map.remove(key);
            map.put(key, e);
            stats.hit();
            return e.getValue();
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(K key) {
        lock.lock();
        try {
            CacheEntry<K, V> e = map.remove(key);
            if (e == null) return false;
            if (e.isExpired(System.currentTimeMillis())) {
                stats.expiration();
                listener.onEvent("EXPIRATION", key);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try { map.clear(); } finally { lock.unlock(); }
    }

    public boolean containsKey(K key) {
        lock.lock();
        try {
            CacheEntry<K, V> e = map.get(key);
            if (e == null) return false;
            if (e.isExpired(System.currentTimeMillis())) {
                map.remove(key);
                stats.expiration();
                listener.onEvent("EXPIRATION", key);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            purgeExpired(System.currentTimeMillis());
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    public void setCapacity(int newCapacity) {
        if (newCapacity < 1) throw new IllegalArgumentException("Capacity must be at least 1");
        lock.lock();
        try {
            this.capacity = newCapacity;
            purgeExpired(System.currentTimeMillis());
            while (map.size() > newCapacity) evictOne();
        } finally {
            lock.unlock();
        }
    }

    public void setEvictionPolicy(EvictionPolicy p) { this.policy = Objects.requireNonNull(p, "policy"); }
    public int getCapacity() { return capacity; }
    public EvictionPolicy getEvictionPolicy() { return policy; }
    public CacheStatistics getStatistics() { return stats; }

    /** Snapshot of live (non-expired) entries, oldest-access first. */
    public List<CacheEntry<K, V>> entries() {
        lock.lock();
        try {
            purgeExpired(System.currentTimeMillis());
            List<CacheEntry<K, V>> out = new ArrayList<>(map.size());
            for (CacheEntry<K, V> e : map.values()) out.add(e.copy());
            return out;
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> metrics() {
        lock.lock();
        try {
            purgeExpired(System.currentTimeMillis());
            long hits = stats.getHits(), misses = stats.getMisses(), total = hits + misses;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalGets", total);
            m.put("hits", hits);
            m.put("misses", misses);
            m.put("hitRate", total == 0 ? 0.0 : Math.round(hits * 10000.0 / total) / 100.0);
            m.put("missRate", total == 0 ? 0.0 : Math.round(misses * 10000.0 / total) / 100.0);
            m.put("puts", stats.getPuts());
            m.put("evictions", stats.getEvictions());
            m.put("expirations", stats.getExpirations());
            m.put("currentSize", map.size());
            m.put("capacity", capacity);
            m.put("evictionPolicy", policy.name());
            return m;
        } finally {
            lock.unlock();
        }
    }

    // ---- internals (caller holds the lock) ----

    private void makeRoom(long now) {
        if (map.size() >= capacity) purgeExpired(now); // expired entries go before live ones
        while (map.size() >= capacity) evictOne();
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<K, CacheEntry<K, V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, CacheEntry<K, V>> en = it.next();
            if (en.getValue().isExpired(now)) {
                it.remove();
                stats.expiration();
                listener.onEvent("EXPIRATION", en.getKey());
            }
        }
    }

    private void evictOne() {
        K victim = null;
        if (policy == EvictionPolicy.LRU) {
            victim = map.keySet().iterator().next();
        } else {
            long min = Long.MAX_VALUE;
            for (CacheEntry<K, V> e : map.values()) { // iteration = LRU first, so strict < keeps LRU tie-break
                if (e.getFrequency() < min) {
                    min = e.getFrequency();
                    victim = e.getKey();
                }
            }
        }
        map.remove(victim);
        stats.eviction();
        listener.onEvent("EVICTION", victim);
    }
}