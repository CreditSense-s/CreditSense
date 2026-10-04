package com.creditsense.risk;

import com.creditsense.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * HTTP client for the ML service. Scoring calls run through a TimeLimiter, CircuitBreaker and
 * Retry (innermost to outermost); any failure surfaces as {@link MlUnavailableException} so the
 * caller can route the application to manual review instead of failing silently.
 */
@Component
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);
    public static final String INSTANCE = "mlService";
    /** Failures that mean the service could not be reached: asleep, starting or restarting rather than broken. */
    private static final Set<String> TRANSIENT = Set.of("circuit breaker open", "timed out", "ConnectException",
            "PrematureCloseException", "UnknownHostException", "BadGateway", "ServiceUnavailable", "GatewayTimeout");

    private final WebClient web;
    private final MlProperties props;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final AtomicBoolean outage = new AtomicBoolean(true);

    public MlClient(WebClient.Builder builder, MlProperties props, CircuitBreakerRegistry cbs, RetryRegistry retries,
            TimeLimiterRegistry limiters) {
        this.props = props;
        WebClient.Builder b = builder.baseUrl(props.baseUrl());
        if (props.serviceToken() != null && !props.serviceToken().isBlank()) {
            b = b.defaultHeader("X-Service-Token", props.serviceToken());
        }
        this.web = b.build();
        this.circuitBreaker = cbs.circuitBreaker(INSTANCE);
        this.retry = retries.retry(INSTANCE);
        this.timeLimiter = limiters.timeLimiter(INSTANCE);
    }

    public MlPrediction predict(Map<String, Object> features) {
        return guarded("predict", web.post().uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("features", features))
                .retrieve()
                .bodyToMono(MlPrediction.class));
    }

    public record OutcomeItem(String reference_id, Map<String, Object> features) {}

    public record OutcomeResult(String reference_id, boolean defaulted) {}

    public List<OutcomeResult> simulateOutcomes(List<OutcomeItem> items) {
        return admin("simulate outcomes", web.post().uri("/outcomes/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("items", items))
                .retrieve()
                .bodyToFlux(OutcomeResult.class)
                .collectList());
    }

    public JsonNode retrain() {
        return admin("retrain", web.post().uri("/retrain").retrieve().bodyToMono(JsonNode.class));
    }

    public JsonNode modelInfo() {
        return guarded("model info", web.get().uri("/model-info").retrieve().bodyToMono(JsonNode.class));
    }

    public JsonNode modelHistory() {
        return guarded("model history", web.get().uri("/model-history").retrieve().bodyToMono(JsonNode.class));
    }

    public boolean healthy() {
        try {
            return web.get().uri("/health").retrieve().toBodilessEntity()
                    .timeout(java.time.Duration.ofSeconds(2)).blockOptional()
                    .map(r -> r.getStatusCode().is2xxSuccessful()).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    /** True from start-up until the service first answers, and again after a call fails to reach it. */
    public boolean inOutage() {
        return outage.get();
    }

    /**
     * Checks whether the ML service answers. If it does, any outage is over: a circuit breaker that opened
     * during it (for example while a free-tier service was asleep) is closed, so the next score is attempted
     * at once instead of failing fast for the rest of the open period.
     */
    public boolean reconnectIfUp() {
        if (!healthy()) {
            return false;
        }
        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.HALF_OPEN) {
            log.info("ML service answers again; closing the circuit breaker (was {})", state);
            circuitBreaker.transitionToClosedState();
        }
        outage.set(false);
        return true;
    }

    private <T> T guarded(String what, Mono<T> call) {
        try {
            return call.transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .transformDeferred(RetryOperator.of(retry))
                    .block();
        } catch (RuntimeException e) {
            log.warn("ML service {} failed: {}", what, e.toString());
            String cause = describe(e);
            boolean unreachable = TRANSIENT.contains(cause);
            if (unreachable) {
                outage.set(true);
            }
            String hint = unreachable ? "; it may be starting up, and the application is scored once it answers" : "";
            throw new MlUnavailableException("ML service unavailable (" + cause + hint + ")", e);
        }
    }

    /** Long-running administrative calls: no retry (not idempotent), generous timeout. */
    private <T> T admin(String what, Mono<T> call) {
        try {
            return call.timeout(props.adminTimeout()).block();
        } catch (WebClientResponseException.Conflict e) {
            throw ApiException.conflict("The ML service is already running a " + what + "; try again when it finishes");
        } catch (RuntimeException e) {
            log.warn("ML service {} failed: {}", what, e.toString());
            throw new MlUnavailableException("ML service could not " + what + " (" + describe(e) + ")", e);
        }
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        return switch (name) {
            case "CallNotPermittedException" -> "circuit breaker open";
            case "TimeoutException" -> "timed out";
            default -> name;
        };
    }
}
