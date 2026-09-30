package com.hackathon.cache.model;

public record CachePutRequest(String key, String value, Long ttlSeconds) { }