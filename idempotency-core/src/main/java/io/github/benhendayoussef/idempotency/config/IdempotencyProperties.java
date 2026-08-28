package io.github.benhendayoussef.idempotency.config;

import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

/**
 * Binds to the {@code idempotency.*} namespace. Per-endpoint {@code @Idempotent} attributes
 * override these defaults; anything left as {@code DEFAULT} on the annotation falls through here.
 */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /** Master switch. */
    private boolean enabled = true;

    /**
     * How responses are captured and replayed. ASPECT stores the handler return value and
     * re-serializes it; FILTER stores the real HTTP response bytes, so anything written straight to
     * the response - or added by a later filter - replays exactly. Mutually exclusive.
     */
    private Mode mode = Mode.ASPECT;

    /** Which {@code IdempotencyStore} backs replay. {@code AUTO} picks Redis when it's on the classpath. */
    private StoreType store = StoreType.AUTO;

    /** How long a claimed key (and its completed record) is retained when {@code @Idempotent#ttl()} is not set. */
    private Duration defaultTtl = Duration.ofHours(24);

    /** Request header carrying the client-supplied idempotency key, when {@code @Idempotent#keyHeader()} is not set. */
    private String headerName = "Idempotency-Key";

    /** Reject requests with no idempotency key instead of passing them through. */
    private boolean requireKey = false;

    /** What to do when a duplicate request arrives while the first is still {@code IN_PROGRESS}. */
    private ConflictPolicy onConflict = ConflictPolicy.WAIT;

    /** Maximum time a {@code WAIT}-policy duplicate polls for the in-flight request before returning 409. */
    private Duration waitTimeout = Duration.ofSeconds(5);

    /**
     * Default namespace for storage keys, when {@code @Idempotent#scope()} is {@code DEFAULT}.
     * {@code GLOBAL} needs no Spring Security; {@code USER}/{@code TENANT} do and fail startup
     * fast with an actionable message if it's absent (see {@code NoScopeResolverConfiguredException}).
     */
    private IdempotencyScope scope = IdempotencyScope.GLOBAL;

    /** JWT claim read by the built-in TENANT scope resolver. */
    private String tenantClaim = "tenant_id";

    /**
     * What to do when a {@code USER}/{@code TENANT}-scoped request reaches the aspect with no
     * resolvable authenticated principal. {@code GLOBAL} falls back to the global namespace (still
     * idempotent, just not per-caller); {@code SKIP} executes the handler unprotected; {@code REJECT}
     * returns 400. Never leaves a raw {@code IllegalStateException} to become an unhandled 500.
     */
    private OnMissingPrincipal onMissingPrincipal = OnMissingPrincipal.GLOBAL;

    /** {@code PROCEED} executes unprotected with a WARN when the store is unreachable; {@code FAIL} returns 503. */
    private OnStoreFailure onStoreFailure = OnStoreFailure.PROCEED;

    /** Register the RFC 9457 {@code ProblemDetail} exception advice. */
    private boolean problemDetails = true;

    /** Ordering of the {@code IdempotencyAspect} relative to other AOP advice (e.g. {@code @Transactional}). */
    private int aspectOrder = Ordered.LOWEST_PRECEDENCE - 100;

    /** Responses larger than this are not stored; the request executes but replay is skipped. */
    private DataSize maxPayloadSize = DataSize.ofKilobytes(256);

    /** Which outcomes release the claimed key instead of completing it. */
    private Set<ReleaseOn> releaseOn = EnumSet.of(ReleaseOn.FIVE_XX, ReleaseOn.TIMEOUT);

    private final Redis redis = new Redis();
    private final Jdbc jdbc = new Jdbc();
    private final Filter filter = new Filter();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public boolean isRequireKey() {
        return requireKey;
    }

    public void setRequireKey(boolean requireKey) {
        this.requireKey = requireKey;
    }

    public ConflictPolicy getOnConflict() {
        return onConflict;
    }

    public void setOnConflict(ConflictPolicy onConflict) {
        this.onConflict = onConflict;
    }

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
        this.waitTimeout = waitTimeout;
    }

    public IdempotencyScope getScope() {
        return scope;
    }

    public void setScope(IdempotencyScope scope) {
        this.scope = scope;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    public OnMissingPrincipal getOnMissingPrincipal() {
        return onMissingPrincipal;
    }

    public void setOnMissingPrincipal(OnMissingPrincipal onMissingPrincipal) {
        this.onMissingPrincipal = onMissingPrincipal;
    }

    public OnStoreFailure getOnStoreFailure() {
        return onStoreFailure;
    }

    public void setOnStoreFailure(OnStoreFailure onStoreFailure) {
        this.onStoreFailure = onStoreFailure;
    }

    public boolean isProblemDetails() {
        return problemDetails;
    }

    public void setProblemDetails(boolean problemDetails) {
        this.problemDetails = problemDetails;
    }

    public int getAspectOrder() {
        return aspectOrder;
    }

    public void setAspectOrder(int aspectOrder) {
        this.aspectOrder = aspectOrder;
    }

    public DataSize getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public void setMaxPayloadSize(DataSize maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    public Set<ReleaseOn> getReleaseOn() {
        return releaseOn;
    }

    public void setReleaseOn(Set<ReleaseOn> releaseOn) {
        this.releaseOn = releaseOn;
    }

    public Filter getFilter() {
        return filter;
    }

    public Redis getRedis() {
        return redis;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public enum StoreType { AUTO, REDIS, JDBC, MEMORY }

    public enum OnStoreFailure { PROCEED, FAIL }

    public enum OnMissingPrincipal { GLOBAL, SKIP, REJECT }

    public enum ReleaseOn { FIVE_XX, TIMEOUT }

    public enum Mode { ASPECT, FILTER }

    public static class Filter {

        /**
         * Response headers replayed verbatim, by name. An allowlist rather than everything:
         * replaying Set-Cookie would hand a second caller the first one's session, and replaying a
         * stale Date or Content-Length would contradict the response actually being written. Add
         * your own headers here if clients depend on them.
         */
        private List<String> replayHeaders = new ArrayList<>(List.of(
                "Content-Type", "Location", "ETag", "Cache-Control"));

        public List<String> getReplayHeaders() {
            return replayHeaders;
        }

        public void setReplayHeaders(List<String> replayHeaders) {
            this.replayHeaders = replayHeaders;
        }
    }

    public static class Redis {

        /** Prefix applied to every Redis key the store writes. */
        private String keyPrefix = "idempotency:";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class Jdbc {

        /** Name of the table created by {@code db/idempotency/postgres.sql}. */
        private String tableName = "idempotency_record";

        /** Run a background task that deletes expired rows; off by default since the atomic claim already reclaims them. */
        private boolean sweeperEnabled = false;

        /** How often the sweeper runs, when enabled. */
        private Duration sweeperInterval = Duration.ofMinutes(15);

        /**
         * Run the handler and the completion write in one shared transaction, so they commit or roll
         * back together (exactly-once) instead of committing separately (at-least-once).
         *
         * <p>Off by default because it changes execution semantics for every advised method, not
         * just transactional ones: a handler with no {@code @Transactional} of its own is pulled
         * into a transaction it never asked for, and a handler's own
         * {@code @Transactional(timeout = ...)} stops applying once it joins. Opt in per
         * application, once those implications have been checked.
         */
        private boolean joinTransaction = false;

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public boolean isSweeperEnabled() {
            return sweeperEnabled;
        }

        public void setSweeperEnabled(boolean sweeperEnabled) {
            this.sweeperEnabled = sweeperEnabled;
        }

        public Duration getSweeperInterval() {
            return sweeperInterval;
        }

        public void setSweeperInterval(Duration sweeperInterval) {
            this.sweeperInterval = sweeperInterval;
        }

        public boolean isJoinTransaction() {
            return joinTransaction;
        }

        public void setJoinTransaction(boolean joinTransaction) {
            this.joinTransaction = joinTransaction;
        }
    }
}
