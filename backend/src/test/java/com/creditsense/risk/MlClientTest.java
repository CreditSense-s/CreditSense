package com.creditsense.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditsense.common.ApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class MlClientTest {

    MockWebServer server;
    MlClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        CircuitBreakerRegistry cbs = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(WebClientRequestException.class, WebClientResponseException.ServiceUnavailable.class)
                .ignoreExceptions(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
                .build());
        TimeLimiterRegistry limiters = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(500)).build());
        client = new MlClient(WebClient.builder(), new MlProperties(server.url("/").toString(), "tkn", null, 0.2),
                cbs, retries, limiters);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse ok() {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(TestPredictions.json(0.08, "MEDIUM"));
    }

    @Test
    void sendsTheServiceTokenAndParsesTheLedger() throws Exception {
        server.enqueue(ok());
        MlPrediction p = client.predict(Map.of("sector", "Retail"));
        assertThat(p.contributions()).hasSize(13);
        assertThat(server.takeRequest().getHeader("X-Service-Token")).isEqualTo("tkn");
    }

    @Test
    void retriesTransientFailures() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(ok());
        assertThat(client.predict(Map.of()).probabilityOfDefault()).isEqualTo(0.08);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void doesNotRetryBadRequests() {
        server.enqueue(new MockResponse().setResponseCode(422));
        assertThatThrownBy(() -> client.predict(Map.of())).isInstanceOf(MlUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void timesOutSlowResponses() {
        for (int i = 0; i < 3; i++) server.enqueue(ok().setBodyDelay(2, TimeUnit.SECONDS));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.predict(Map.of()))
                .isInstanceOf(MlUnavailableException.class).hasMessageContaining("timed out")
                .hasMessageContaining("scored once it answers");
        // one attempt only: a timeout is not retried, so the caller waits one time budget, not three
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void opensTheCircuitAfterRepeatedFailuresAndFailsFast() {
        for (int i = 0; i < 12; i++) server.enqueue(new MockResponse().setResponseCode(503));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.predict(Map.of())).isInstanceOf(MlUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = server.getRequestCount();
        assertThatThrownBy(() -> client.predict(Map.of())).hasMessageContaining("circuit breaker open");
        assertThat(server.getRequestCount()).isEqualTo(before); // no call reached the service
    }

    @Test
    void aBadRequestIsNotReportedAsAnOutage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertThat(client.reconnectIfUp()).isTrue();
        server.enqueue(new MockResponse().setResponseCode(422));
        assertThatThrownBy(() -> client.predict(Map.of())).isInstanceOf(MlUnavailableException.class)
                .hasMessageNotContaining("starting up");
        assertThat(client.inOutage()).isFalse();
    }

    @Test
    void reconnectClosesAnOpenCircuitOnceTheServiceAnswersAgain() throws Exception {
        // 3 attempts, then 1 more opens the breaker (window of 4) and the remaining retry is refused
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setResponseCode(503));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.predict(Map.of())).isInstanceOf(MlUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(server.getRequestCount()).isEqualTo(4);
        assertThat(client.inOutage()).isTrue();

        server.enqueue(new MockResponse().setResponseCode(503)); // still starting: the breaker stays open
        assertThat(client.reconnectIfUp()).isFalse();
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        server.enqueue(new MockResponse().setResponseCode(200)); // up: the next score is attempted at once
        assertThat(client.reconnectIfUp()).isTrue();
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(client.inOutage()).isFalse();
        server.enqueue(ok());
        assertThat(client.predict(Map.of()).probabilityOfDefault()).isEqualTo(0.08);
        for (int i = 0; i < 4; i++) server.takeRequest();
        assertThat(server.takeRequest().getPath()).isEqualTo("/health");
    }

    @Test
    void reportsARetrainAlreadyInProgressAsAConflictWithoutRetrying() {
        server.enqueue(new MockResponse().setResponseCode(409));
        assertThatThrownBy(client::retrain)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
