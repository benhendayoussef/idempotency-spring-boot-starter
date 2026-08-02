package io.github.benhendayoussef.idempotency.api;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hook to register the application's Jackson modules on the starter's internal payload
 * {@link ObjectMapper}. The starter never reuses the application's own {@code ObjectMapper}
 * (users mutate theirs, and canonical key ordering must stay fixed) — implement this bean to
 * add custom (de)serializers a stored DTO needs instead.
 */
public interface IdempotencyObjectMapperCustomizer {

    void customize(ObjectMapper mapper);
}
