package com.hackathon.cache.cache;

public class CacheEntry<K, V> {
    private final K key;
    private V value;
    private long frequency;
    private long lastAccessTime;
    private long expirationTime; // 0 = never expires

    public CacheEntry(K key, V value, long now, long expirationTime) {
        this.key = key;
        this.value = value;
        this.frequency = 1;
        this.lastAccessTime = now;
        this.expirationTime = expirationTime;
    }

    public boolean isExpired(long now) { return expirationTime > 0 && now >= expirationTime; }

    void touch(long now) { frequency++; lastAccessTime = now; }

    void update(V value, long now, long expirationTime) {
        this.value = value;
        this.expirationTime = expirationTime;
        touch(now);
    }

    CacheEntry<K, V> copy() {
        CacheEntry<K, V> c = new CacheEntry<>(key, value, lastAccessTime, expirationTime);
        c.frequency = frequency;
        return c;
    }

    public K getKey() { return key; }
    public V getValue() { return value; }
    public long getFrequency() { return frequency; }
    public long getLastAccessTime() { return lastAccessTime; }
    public long getExpirationTime() { return expirationTime; }
}