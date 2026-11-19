# PayFlow – Production Observability Architecture & Metrics Guide

## 1. Executive Summary & Architecture Overview

The **PayFlow Distributed Payment Processing Engine** implements enterprise-grade observability across its modular architecture. Built on **Spring Boot Actuator**, **Micrometer**, **Prometheus**, and **Grafana**, the observability pipeline captures granular real-time telemetry, transaction latency percentiles, backpressure queues, cache efficiency, distributed database pool health, and structured JSON logs with correlation tracing and automated credential redaction.

```
+-----------------------------------------------------------------------------------------+
|                                    PayFlow API Service                                  |
|   [ Controllers ] -> [ Payment Engine ] -> [ AtomicTransferExecutor ] -> [ Outbox DB ]  |
|          |                    |                         |                      |        |
|  CorrelationIdFilter   PayFlowMetricsService    StructuredJsonLayout   DistributedHealth|
+----------+--------------------+-------------------------+----------------------+--------+
           |                    |                         |                      |
           | HTTP /actuator     | Micrometer Meters       | JSON Stdout (MDC)    | Health
           v                    v                         v                      v
     [ Prometheus ] <-------------------------- (Scrape 5s)             [ Docker Engine ]
           |                                                                     |
           v                                                                     v
     [ Grafana Dashboard ] (Port 3000)                               [ Central Log Shipper ]
```

### Core Observability Pillars
1. **Metrics & Telemetry**: Exposed via `/actuator/prometheus` and scraped every 5 seconds by Prometheus.
2. **Infrastructure Health Indicators**: Continuous connectivity and latency verification for PostgreSQL, Redis, and Apache Kafka.
3. **Structured JSON Logging**: Centralized logging adhering to strict PCI-DSS and security guidelines: automatic masking of JWTs, passwords, and secrets; correlation ID propagation across REST and asynchronous Kafka boundaries.
4. **Dashboards & Real-Time Visualization**: Auto-provisioned Grafana dashboard (`PayFlow :: Production Distributed Observability`).

---

## 2. Custom Metrics Catalog

PayFlow exposes 11 domain-specific metric instruments capturing financial throughput, latency percentiles, error modes, and infrastructure saturation:

| Metric Name | Type | Description | Unit / Labels | Source Component | Interpretation & Operational Thresholds |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `payflow.payments.total` | Counter | Total number of payment transfer requests received | `count` | `PayFlowMetricsService` | Tracks gross inbound payment demand. |
| `payflow.payments.success` | Counter | Total successfully processed transfers | `count` | `PayFlowMetricsService` | Used to calculate the real-time success rate. Expected: `> 99.0%`. |
| `payflow.payments.failure` | Counter | Total payment failures (business or system) | `count` | `PayFlowMetricsService` | Spikes indicate upstream banking or database degradation. |
| `payflow.payments.latency` | Timer | Latency distribution of atomic transfers | `seconds` (P50, P95, P99) | `PayFlowMetricsService` | Tracks execution time of `executeTransferInTransaction`. Alert if P99 `> 500ms`. |
| `payflow.idempotency.conflicts` | Counter | Duplicate request collisions or payload mismatches | `count` | `IdempotencyService` | High rate indicates aggressive client retry loops or replay attacks. |
| `payflow.payments.insufficient_balance` | Counter | Transfers rejected due to insufficient wallet funds | `count` | `PaymentProcessingService` | Expected business failure rate under normal user activity. |
| `payflow.kafka.consumer.lag` | Gauge | Estimated consumer offset lag across topic partitions | `messages` | `PayFlowWorkerMetricsService` | Backpressure indicator. Alert if lag `> 50` sustained for 1 minute. |
| `payflow.outbox.pending.count` | Gauge | Count of pending outbox events awaiting Kafka publish | `records` | `PayFlowMetricsService` | If queue grows monotonically, Kafka broker or publisher thread is stalled. Alert if `> 100`. |
| `payflow.outbox.publishing.failures` | Counter | Cumulative failures during outbox Kafka dispatch | `count` | `OutboxPublisherService` | Broker disconnection, SSL handshake failures, or timeout errors. |
| `payflow.redis.cache.hits` | Counter | Successful Redis cache reads for wallet balance | `count` | `WalletBalanceCacheService` | Indicates cache efficiency. |
| `payflow.redis.cache.misses` | Counter | Redis cache misses falling back to PostgreSQL | `count` | `WalletBalanceCacheService` | Cache hit ratio calculated as `hits / (hits + misses)`. Expected: `> 80%`. |
| `payflow.database.pool.utilization` | Gauge | Ratio of active HikariCP connections to max pool size | `ratio (0.0 - 1.0)` | `PayFlowMetricsService` | Database saturation indicator. Alert if utilization `> 0.85` sustained. |
| `payflow.reconciliation.failures` | Counter | Financial discrepancies detected during reconciliation | `count` | `ReconciliationService` | Critical financial invariant alert. MUST ALWAYS BE `0`. Any increment requires immediate investigation. |

---

## 3. Distributed Infrastructure Health Checks

Spring Boot Actuator's health framework is extended via [`DistributedInfraHealthIndicator`](file:///Users/arjunsingh/Documents/paypay%20project%203/payflow-api/src/main/java/com/payflow/api/health/DistributedInfraHealthIndicator.java), actively querying all 3 distributed components:

```json
GET /actuator/health
{
  "status": "UP",
  "components": {
    "distributedInfrastructure": {
      "status": "UP",
      "details": {
        "postgresql": {
          "status": "UP",
          "latencyMs": 2,
          "catalog": "payflow_db"
        },
        "redis": {
          "status": "UP",
          "latencyMs": 1
        },
        "kafka": {
          "status": "UP",
          "latencyMs": 5,
          "clusterId": "M0tD9z4TRr-p8bZ7V2l67A",
          "nodes": 1
        }
      }
    },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

### Health Check Semantics:
- **PostgreSQL**: Validates active connection pool acquisition and executes `SELECT 1` within 2 seconds.
- **Redis**: Sends a lightweight `PING` command expecting `PONG` response within 1 second.
- **Kafka**: Queries cluster metadata via `AdminClient.describeCluster()` confirming broker presence within 2 seconds.

---

## 4. Structured JSON Logging & Sensitive Data Masking

All log messages across `payflow-api` and `payflow-worker` are formatted using [`StructuredJsonLayout`](file:///Users/arjunsingh/Documents/paypay%20project%203/payflow-core/src/main/java/com/payflow/core/logging/StructuredJsonLayout.java).

### Log Event Format
```json
{
  "timestamp": "2026-11-18T21:55:00.124Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-4",
  "logger": "com.payflow.api.service.PaymentProcessingService",
  "message": "Payment transfer initiated for amount 500.00 INR",
  "correlationId": "48995a82-2616-4318-ae38-b1fc682cb9b4",
  "paymentId": "319da888-51e8-4764-b70e-0218a33bf58e",
  "userId": "1f8e2851-40de-440d-8380-60b8686e0ff0"
}
```

### Tracing & Correlation Flow:
1. **HTTP Requests**: [`CorrelationIdFilter`](file:///Users/arjunsingh/Documents/paypay%20project%203/payflow-api/src/main/java/com/payflow/api/security/CorrelationIdFilter.java) extracts `X-Correlation-ID` header from incoming requests. If absent, a new UUID is generated and set on the response header.
2. **Security Context**: [`JwtAuthenticationFilter`](file:///Users/arjunsingh/Documents/paypay%20project%203/payflow-api/src/main/java/com/payflow/api/security/JwtAuthenticationFilter.java) populates `userId` into MDC upon successful token validation.
3. **Domain Operations**: `paymentId` is populated into MDC during transfer lifecycle execution.
4. **Kafka Asynchronous Worker**: `PaymentEventConsumer` extracts `eventId` and `paymentId` from the Kafka record and establishes MDC context during worker processing, allowing seamless end-to-end trace correlation from HTTP client through Kafka worker.

### Sensitive Data Masking & Redaction:
To comply with PCI-DSS and banking security standards, the layout automatically enforces regex redaction:
- **JWT Tokens**: Matches Bearer headers and compact JWT format (`eyJ...`) -> `***REDACTED_JWT***`.
- **Passwords & Keys**: Matches JSON fields `password`, `client_secret`, `pin`, `cvv` -> `***REDACTED***`.
- **MDC Sanitization**: Any MDC key containing `password`, `secret`, `token`, `jwt`, `auth`, `pin` is redacted prior to output.

---

## 5. Grafana Dashboard Specification

The dashboard is auto-provisioned upon deployment from `monitoring/grafana/dashboards/payflow-overview.json` with Prometheus set as default datasource.

### Dashboard Panels & Visualizations:

1. **Top Stat KPI Tiles**:
   - **Total Payment Requests**: Gross request volume counter.
   - **Payment Success Rate**: Percentage ratio (`success / total * 100`). Green `>= 99%`, Orange `95-99%`, Red `< 95%`.
   - **Outbox Pending Queue**: Current backlog of uncommitted events. Green `< 50`, Orange `50-200`, Red `> 200`.
   - **Redis Cache Hit Ratio**: Real-time read efficiency. Green `> 85%`, Yellow `60-85%`, Orange `< 60%`.
   - **DB Pool Utilization**: Active Hikari connections vs max pool size. Green `< 70%`, Yellow `70-90%`, Red `> 90%`.
   - **Kafka Consumer Lag**: Total unprocessed event lag across worker consumer groups.

2. **Payment Processing Engine Panel**:
   - **Payment Throughput (RPS)**: Multi-series rate of total requests, successful transfers, and failed transfers over a 1-minute window.
   - **Payment Latency (P50, P95, P99)**: Millisecond latency percentiles computed from Prometheus histograms.

3. **Concurrency & Fault Tolerance Panel**:
   - **Idempotency Conflicts & Insufficient Funds**: Tracks simultaneous duplicate keys and balance exhaustion events.
   - **Transactional Outbox Health**: Real-time pending queue size vs broker delivery failure rate.

4. **Infrastructure & Asynchronous Worker Panel**:
   - **Redis Cache Operations**: Operations per second comparing hits vs misses.
   - **Event Worker Consumer Pipeline**: Rates of events processed, duplicate events suppressed, retries scheduled, and dead-letter queue (DLQ) escalations.

---

## 6. SRE Alerting Rules & Thresholds

Recommended Prometheus alerting rules for production deployment:

```yaml
groups:
  - name: payflow-alerts
    rules:
      - alert: HighPaymentFailureRate
        expr: (sum(rate(payflow_payments_failure_total[2m])) / sum(rate(payflow_payments_total[2m]))) * 100 > 5
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "PayFlow payment failure rate exceeded 5% over 2 minutes."

      - alert: OutboxQueueBacklogSpike
        expr: payflow_outbox_pending_count > 100
        for: 2m
        labels:
          severity: high
        annotations:
          summary: "Transactional outbox pending event count exceeds 100 for more than 2 minutes."

      - alert: DatabaseConnectionPoolSaturation
        expr: payflow_database_pool_utilization > 0.85
        for: 1m
        labels:
          severity: high
        annotations:
          summary: "HikariCP active database connections exceeded 85% of maximum pool size."

      - alert: ReconciliationDiscrepancyDetected
        expr: increase(payflow_reconciliation_failures_total[5m]) > 0
        for: 0m
        labels:
          severity: page
        annotations:
          summary: "CRITICAL FINANCIAL ALERT: Double-entry ledger or wallet balance discrepancy detected."
```

---

## 7. Running the Monitoring Stack Locally

The monitoring stack is fully configured within [`docker-compose.yml`](file:///Users/arjunsingh/Documents/paypay%20project%203/docker-compose.yml).

### Starting Services:
```bash
docker compose up -d postgres redis kafka prometheus grafana payflow-api payflow-worker
```

### Access URLs & Credentials:
- **PayFlow API**: `http://localhost:8080` (Swagger UI: `http://localhost:8080/swagger-ui.html`)
- **Prometheus UI**: `http://localhost:9090` (Metrics endpoint: `http://localhost:9090/targets`)
- **Grafana UI**: `http://localhost:3000`
  - **Username**: `admin`
  - **Password**: `payflow_admin`
  - **Pre-loaded Dashboard**: *Dashboards -> PayFlow -> PayFlow :: Production Distributed Observability*
- **Worker Actuator**: `http://localhost:8081/actuator/health`
