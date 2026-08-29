rootProject.name = "idempotency-spring-boot-starter-parent"

include(
    "idempotency-core",
    "idempotency-store-redis",
    "idempotency-store-jdbc",
    "idempotency-store-caffeine",
    "idempotency-webflux",
    "idempotency-spring-boot-starter",
    "samples:sample-orders-api",
)
