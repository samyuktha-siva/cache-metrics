# Custom Cache Library with Live Metrics Panel
Java 17 + Spring Boot + vanilla JS. Custom LRU/LFU cache with per-entry TTL, thread safety and a live dashboard.

Run: `mvn spring-boot:run`  ·  Test: `mvn test`  ·  Open: http://localhost:8080

API: GET /api/cache/metrics | /entries | /log | /{key}; POST /api/cache; DELETE /api/cache/{key} | /api/cache;
PUT /api/cache/config; POST /api/cache/sample-workload | /concurrency-test