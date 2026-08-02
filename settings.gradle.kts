rootProject.name = "idempotency-spring-boot-starter-parent"

include(
    "idempotency-core",
    "idempotency-store-redis",
    "idempotency-store-jdbc",
    "idempotency-spring-boot-starter",
    "samples:sample-orders-api",
)
