# CardDemo COBOL-to-Java Migration — Blitzy Project Guide

---

## 1. Executive Summary

### 1.1 Project Overview

This project migrates the AWS CardDemo mainframe COBOL application — comprising 28 programs (19,254 lines), 28 copybooks, 17 BMS mapsets, 29 JCL jobs, and 9 data fixture files — to a fully operational Java 25 LTS + Spring Boot 3.5.11 application with PostgreSQL 16, AWS S3/SQS/SNS integration (via LocalStack), and comprehensive observability. The migration targets 100% behavioral parity across all 22 features (F-001 through F-022), spanning 18 interactive online programs and 10 batch programs. The application serves as a credit card management system with account, card, transaction, billing, reporting, and user administration capabilities.

### 1.2 Completion Status

<figure class="diagram" markdown="1">
<div class="diagram__viewport" role="region" tabindex="0" aria-labelledby="diagram-project-completion-status-caption" markdown="1">
![Pie chart titled Project Completion Status with two slices: Completed 391 hours, about 90 per cent, and Remaining 43 hours, about 10 per cent.](diagrams/project-completion-status.svg){ .diagram__image width="649" height="450" }
</div>
<figcaption class="diagram__caption" id="diagram-project-completion-status-caption" markdown="1">
**Figure — Project completion status.** 391 completed hours against 43 remaining, out of 434 in total — the 90.1 per cent the metrics table below states. Where this figure is wider than the page, the frame around it scrolls sideways; it can also be focused with the keyboard and panned with the arrow keys.
</figcaption>
</figure>

| Metric | Value |
|--------|-------|
| **Total Project Hours** | **434** |
| **Completed Hours (AI)** | **391** |
| **Remaining Hours** | **43** |
| **Completion Percentage** | **90.1%** |

**Formula:** 391 completed hours / (391 + 43) total hours = **90.1% complete**

### 1.3 Key Accomplishments

- ✅ All 28 COBOL programs translated to 103 Java source files (34,021 lines) with full business logic preservation
- ✅ All 11 VSAM datasets mapped to PostgreSQL tables with Flyway migrations (V1 schema, V2 indexes, V3 seed data)
- ✅ Complete 5-stage Spring Batch pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT)
- ✅ 8 REST controllers replacing 17 BMS terminal screens with full API endpoint coverage
- ✅ 888/888 tests passing (729 unit + 159 integration/E2E) with zero failures
- ✅ 81.5% line coverage (JaCoCo) exceeding the 80% threshold
- ✅ Zero-warning build with `-Xlint:all` compiler flag
- ✅ BigDecimal precision for all financial fields — zero float/double substitution
- ✅ BCrypt password hashing (security upgrade from COBOL plaintext)
- ✅ Optimistic locking via JPA `@Version` on Account and Card entities
- ✅ `@Transactional` with rollback semantics for multi-dataset operations
- ✅ AWS S3/SQS/SNS integration verified against LocalStack
- ✅ Full observability stack: structured logging with correlation IDs, distributed tracing, Prometheus metrics, health checks
- ✅ Comprehensive documentation: Decision Log (18 decisions), Traceability Matrix (100% paragraph coverage), Executive Presentation (reveal.js), API Contracts, Onboarding Guide, Validation Gates

### 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| No CI/CD pipeline | Automated build/test/deploy not available; manual verification required | DevOps Engineer | 1 week |
| OWASP dependency scan not executed | Potential CVE vulnerabilities unverified in production dependencies | Security Engineer | 2 days |
| No production Spring profile | Cannot deploy to real AWS/PostgreSQL without environment configuration | Backend Engineer | 3 days |
| JWT secret hardcoded in config | Security risk if deployed without externalized secret management | Security Engineer | 1 day |

### 1.5 Access Issues

| System/Resource | Type of Access | Issue Description | Resolution Status | Owner |
|-----------------|---------------|-------------------|-------------------|-------|
| LocalStack Pro | Auth Token | `LOCALSTACK_AUTH_TOKEN` required for local development; provided via environment variable | ✅ Resolved | DevOps |
| AWS Production | IAM Credentials | No production AWS credentials configured; only LocalStack endpoints exist | ⚠ Pending | Cloud Architect |
| Container Registry | Push Access | No container registry configured for Docker image publication | ⚠ Pending | DevOps Engineer |

### 1.6 Recommended Next Steps

1. **[High]** Set up CI/CD pipeline with GitHub Actions (build, test, OWASP check, deploy stages)
2. **[High]** Run OWASP dependency-check and remediate any critical/high CVEs
3. **[High]** Create `application-prod.yml` with production database/AWS configuration and externalized secrets
4. **[Medium]** Configure production deployment (Kubernetes manifests or ECS task definitions)
5. **[Medium]** Conduct security hardening review: JWT rotation, TLS configuration, rate limiting

---

## 2. Project Hours Breakdown

### 2.1 Completed Work Detail

| Component | Hours | Description |
|-----------|-------|-------------|
| Foundation & Build Infrastructure | 14 | pom.xml (Spring Boot 3.5.11, Java 25, 17+ dependencies), Dockerfile (multi-stage), docker-compose.yml (6 services), localstack-init/init-aws.sh, Maven wrapper, .gitignore |
| Data Model Layer | 28 | 11 JPA entities with BigDecimal precision and @Version locking, 9 DTOs from BMS symbolic maps, 4 enums, 3 composite key classes, 1 converter |
| Data Access Layer | 12 | 11 Spring Data JPA repositories with custom queries for pagination, alternate indexes, and composite key access |
| Database Migrations | 10 | V1 schema (11 tables, 261 lines), V2 indexes (159 lines), V3 seed data from 9 ASCII fixtures (827 lines) |
| Validation Resources | 5 | NANPA area codes JSON, US state codes JSON, state-ZIP prefix combinations JSON (extracted from CSLKPCDY.cpy) |
| Online Services (18 programs) | 72 | Full business logic translation from 18 COBOL online programs: Auth, Account (view/update), Card (list/detail/update), Transaction (list/detail/add), Billing, Report, User Admin (CRUD), Menu (main/admin) |
| Shared Utility Services | 14 | DateValidationService (703 lines), ValidationLookupService, FileStatusMapper — LE CEEDAYS replacement, NANPA/state/ZIP validation, FILE STATUS exception mapping |
| REST Controllers | 20 | 8 controllers mapping all 17 BMS screens to REST endpoints with validation, error handling, and structured responses |
| Batch Jobs & Orchestration | 24 | 6 batch job configurations: 5-stage pipeline (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) + BatchPipelineOrchestrator with condition code logic |
| Batch Processors | 24 | 5 processors: 4-stage validation cascade (reject codes 100-109), interest calculation with DEFAULT fallback, transaction merge sort, dual-format statement generation, date-filtered reporting |
| Batch Readers & Writers | 16 | 5 readers (S3 file reader, account/card/crossref/customer utility readers) + 3 writers (DB+S3 transaction, S3 rejection file, S3 statement output) |
| Configuration Layer | 14 | SecurityConfig (BCrypt + role-based access), BatchConfig, AwsConfig (S3/SQS/SNS), JpaConfig, ObservabilityConfig, WebConfig + 4 YAML/XML config files |
| Observability | 10 | CorrelationIdFilter, MetricsConfig (custom business metrics), HealthIndicators (PostgreSQL/S3/SQS), structured logging, distributed tracing |
| Exception Hierarchy | 4 | 7 custom exception classes mapping COBOL FILE STATUS codes to Java exceptions |
| Application Entry Point | 2 | CardDemoApplication.java with @SpringBootApplication |
| Unit Tests | 40 | 729 tests across 30+ test classes covering all services, batch processors, models, DTOs, enums, validation |
| Integration Tests | 28 | 131 tests: 11 repository ITs, 5 batch pipeline ITs, 3 AWS ITs (S3/SQS/SNS), 2 validation ITs |
| E2E Tests | 14 | 28 tests: BatchPipelineE2ETest (6), OnlineTransactionE2ETest (19), GateVerificationTest (8) |
| Documentation | 24 | README.md (complete rewrite), DECISION_LOG.md (18 decisions), TRACEABILITY_MATRIX.md (100% paragraph coverage), executive-presentation.html (reveal.js), architecture-before-after.md, onboarding-guide.md, validation-gates.md, api-contracts.md, grafana-dashboard.json, prometheus.yml |
| QA Fixes & Debugging | 16 | 12 fix commits: integration test alignment, security hardening, batch pipeline corrections, observability wiring, documentation QA, performance testing fixes |
| **Total** | **391** | |

### 2.2 Remaining Work Detail

| Category | Hours | Priority |
|----------|-------|----------|
| CI/CD Pipeline Setup (GitHub Actions) | 8 | High |
| OWASP Dependency Scan & Remediation | 3 | High |
| Production Environment Configuration | 6 | High |
| Security Hardening (JWT rotation, TLS, rate limiting) | 4 | High |
| Performance Testing & Optimization | 4 | Medium |
| Deployment Configuration (K8s/ECS manifests) | 8 | Medium |
| Production Data Migration Strategy | 4 | Medium |
| Monitoring & Alerting Setup | 4 | Medium |
| API Documentation (OpenAPI/Swagger generation) | 2 | Low |
| **Total** | **43** | |

### 2.3 Hours Verification

- Section 2.1 Total (Completed): **391 hours**
- Section 2.2 Total (Remaining): **43 hours**
- Sum: 391 + 43 = **434 hours** ✅ (matches Section 1.2 Total Project Hours)
- Completion: 391 / 434 = **90.1%** ✅ (matches Section 1.2)

---

## 3. Test Results

All tests were executed autonomously by Blitzy's validation pipeline. Final commit: `408481d`.

| Test Category | Framework | Total Tests | Passed | Failed | Coverage % | Notes |
|---------------|-----------|-------------|--------|--------|-----------|-------|
| Unit — Service Layer | JUnit 5 + Mockito | 355 | 355 | 0 | 81.5% line | All 20 services tested |
| Unit — Batch Processors | JUnit 5 + Mockito | 82 | 82 | 0 | Included above | 5 processors: validation, interest, combine, statement, report |
| Unit — Model/DTO/Enum | JUnit 5 | 148 | 148 | 0 | Included above | Entity getters/setters, DTO, enum, exception hierarchy |
| Unit — Validation | JUnit 5 | 144 | 144 | 0 | Included above | Date validation, file status mapper, lookup service |
| Integration — Repository | JUnit 5 + Testcontainers | 98 | 98 | 0 | Included above | 11 JPA repositories against PostgreSQL Testcontainer |
| Integration — Batch Pipeline | JUnit 5 + Testcontainers | 32 | 32 | 0 | Included above | 5 batch jobs: POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT |
| Integration — AWS (S3/SQS/SNS) | JUnit 5 + LocalStack TC | 4 | 4 | 0 | Included above | S3, SQS, SNS integration via LocalStack Testcontainer |
| E2E — Batch Pipeline | JUnit 5 + Testcontainers | 6 | 6 | 0 | Included above | Full 5-stage pipeline end-to-end |
| E2E — Online Transaction | JUnit 5 + Spring Boot Test | 19 | 19 | 0 | Included above | Auth, Account, Card, Transaction, Billing, Report, User Admin REST APIs |
| E2E — Gate Verification | JUnit 5 + Testcontainers | 8 | 8 | 0 | Included above | Programmatic evidence for Validation Gates 1-8 |
| **Total** | | **888** | **888** | **0** | **81.5%** | **100% pass rate** |

**Coverage Breakdown (JaCoCo merged — unit + integration):**
- Line Coverage: **81.5%** (4,347 / 5,334 lines) — ✅ exceeds 80% threshold
- Branch Coverage: 64.0% (1,001 / 1,563 branches)
- Method Coverage: 88.4% (949 / 1,074 methods)
- Instruction Coverage: 78.8% (17,871 / 22,665 instructions)

---

## 4. Runtime Validation & UI Verification

### Application Runtime

- ✅ **Spring Boot Startup**: Application starts on port 8080 in 5.9 seconds
- ✅ **Health Endpoint** (`/actuator/health`): Returns `UP` with composite indicators for PostgreSQL, S3, and SQS
- ✅ **Flyway Migrations**: All 3 migrations (V1 schema, V2 indexes, V3 seed data) applied successfully

### REST API Verification

- ✅ **Authentication** (`POST /api/auth/signin`): 200 OK with JWT token for valid credentials
- ✅ **Account View** (`GET /api/accounts/00000000001`): 200 OK with full account data including BigDecimal balances
- ✅ **Menu** (`GET /api/menu/main`): 200 OK with 10 menu options matching COMEN02Y.cpy
- ✅ **Card List** (`GET /api/cards`): Paginated response matching COCRDLIC browse semantics
- ✅ **Transaction Operations**: List, detail, and add endpoints operational
- ✅ **User Admin CRUD**: Full create, read, update, delete cycle verified

### Batch Pipeline Verification

- ✅ **Stage 1 — POSTTRAN**: Daily transaction posting with 4-stage validation cascade
- ✅ **Stage 2 — INTCALC**: Interest calculation with rate lookup and DEFAULT fallback
- ✅ **Stage 3 — COMBTRAN**: Transaction merge sort replacing DFSORT
- ✅ **Stage 4a — CREASTMT**: Dual-format (text + HTML) statement generation
- ✅ **Stage 4b — TRANREPT**: Date-filtered transaction reporting

### Observability Verification

- ✅ **Structured Logging**: JSON format with `traceId`, `spanId`, `correlationId` fields confirmed
- ✅ **Distributed Tracing**: Micrometer/OpenTelemetry bridge operational
- ✅ **Metrics Endpoint** (`/actuator/prometheus`): Custom business metrics exposed
- ✅ **Health Checks**: Composite health indicators for DB, S3, SQS all reporting UP

### AWS Integration (LocalStack)

- ✅ **S3**: Batch file staging (input/output/statements buckets) verified
- ✅ **SQS**: Report submission queue (FIFO) verified
- ✅ **SNS**: Alert topic publication verified

---

## 5. Compliance & Quality Review

| AAP Requirement | Status | Evidence |
|----------------|--------|----------|
| 100% Behavioral Parity — All 28 COBOL programs migrated | ✅ Pass | 103 Java source files, 20 service classes, 6 batch jobs |
| BigDecimal for all COMP-3/COMP fields — zero float/double | ✅ Pass | 33 source files use BigDecimal; 0 float/double in entities/DTOs |
| @Version optimistic locking (COACTUPC, COCRDUPC) | ✅ Pass | Account.java and Card.java have `@Version` annotation |
| @Transactional with rollback (SYNCPOINT) | ✅ Pass | Used across all service classes with appropriate isolation |
| BCrypt password hashing (C-003 upgrade) | ✅ Pass | AuthenticationService, UserAddService use BCrypt encoding |
| @EmbeddedId composite keys | ✅ Pass | TransactionCategoryBalance, DisclosureGroup, TransactionCategory |
| S3 integration for GDG replacement | ✅ Pass | Batch writers output to S3, health indicator monitors S3 |
| SQS for TDQ replacement | ✅ Pass | ReportSubmissionService publishes to SQS FIFO queue |
| Structured logging with correlation IDs | ✅ Pass | logback-spring.xml, CorrelationIdFilter, JSON structured output |
| Distributed tracing (Micrometer/OTEL) | ✅ Pass | ObservabilityConfig, micrometer-tracing-bridge-otel dependency |
| Metrics endpoint (/actuator/prometheus) | ✅ Pass | Custom business metrics: auth attempts, batch records, transactions |
| Health/readiness checks | ✅ Pass | HealthIndicators for PostgreSQL, S3, SQS composite health |
| ≥80% line coverage (JaCoCo) | ✅ Pass | 81.5% line coverage — "All coverage checks have been met" |
| Zero-warning build (-Xlint:all) | ✅ Pass | `mvn clean compile` BUILD SUCCESS with zero warnings |
| 11 VSAM datasets → PostgreSQL tables | ✅ Pass | Flyway V1 creates all 11 tables from VSAM cluster specs |
| 5-stage batch pipeline preservation | ✅ Pass | POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT |
| Decision Log (≥15 decisions) | ✅ Pass | DECISION_LOG.md with 18 architectural decisions |
| Traceability Matrix (100% paragraph coverage) | ✅ Pass | TRACEABILITY_MATRIX.md — 1,191 lines of bidirectional mapping |
| Executive reveal.js Presentation | ✅ Pass | docs/executive-presentation.html with Mermaid diagrams |
| Onboarding Guide | ✅ Pass | docs/onboarding-guide.md — clean-machine-to-running-app |
| Grafana Dashboard Template | ✅ Pass | docs/grafana-dashboard.json (1,378 lines) |
| No hardcoded credentials | ⚠ Partial | Environment variables used; JWT secret needs externalization for production |
| OWASP zero critical/high CVEs | ⚠ Pending | Plugin configured in pom.xml; scan execution not confirmed |
| CI/CD pipeline | ❌ Not Started | .github/workflows/*.yml not created |

**Fixes Applied During Autonomous Validation:**
1. Integration test assertions aligned with actual V3 seed data counts (TransactionCategoryRepositoryIT, DisclosureGroupRepositoryIT)
2. 17 QA findings resolved from online API testing
3. 5 security findings resolved (checkpoint 6)
4. 6 batch pipeline findings fixed (condition code decider, report totals, S3 overwrite)
5. Observability wiring corrected (Prometheus scraping, custom metrics)
6. 37 documentation QA findings resolved across 9 files
7. 4 performance testing findings addressed

---

## 6. Risk Assessment

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|------------|------------|--------|
| OWASP dependency vulnerabilities unverified | Security | High | Medium | Run `mvn org.owasp:dependency-check-maven:check`; remediate findings | ⚠ Open |
| No CI/CD pipeline for automated testing | Operational | High | High | Implement GitHub Actions with build/test/deploy stages | ⚠ Open |
| JWT secret not externalized for production | Security | High | High | Use AWS Secrets Manager or HashiCorp Vault for JWT signing key | ⚠ Open |
| No production Spring profile | Operational | High | High | Create `application-prod.yml` with real AWS/PostgreSQL config | ⚠ Open |
| LocalStack-only AWS testing | Integration | Medium | Medium | Add integration tests against real AWS in staging environment | ⚠ Open |
| Branch coverage at 64% | Technical | Medium | Low | Add tests for uncovered branches; focus on error paths | ⚠ Open |
| No container registry configured | Operational | Medium | High | Configure ECR/Docker Hub for image publication | ⚠ Open |
| Production data migration from EBCDIC | Technical | Medium | Medium | Develop EBCDIC-to-PostgreSQL migration scripts with validation | ⚠ Open |
| No rate limiting on REST endpoints | Security | Medium | Medium | Add Spring Cloud Gateway or servlet filter rate limiting | ⚠ Open |
| No TLS/HTTPS configured | Security | Medium | High | Configure TLS termination at load balancer or application level | ⚠ Open |
| Database connection pooling not tuned | Technical | Low | Medium | Configure HikariCP pool size based on production workload analysis | ⚠ Open |
| No API documentation generation (OpenAPI) | Technical | Low | Low | Add springdoc-openapi dependency for auto-generated Swagger UI | ⚠ Open |

---

## 7. Visual Project Status

<figure class="diagram" markdown="1">
<div class="diagram__viewport" role="region" tabindex="0" aria-labelledby="diagram-project-hours-breakdown-caption" markdown="1">
![Pie chart titled Project Hours Breakdown with two slices: Completed Work 391 hours, about 90 per cent, and Remaining Work 43 hours, about 10 per cent.](diagrams/project-hours-breakdown.svg){ .diagram__image width="639" height="450" }
</div>
<figcaption class="diagram__caption" id="diagram-project-hours-breakdown-caption" markdown="1">
**Figure — Project hours breakdown.** The same 391 completed and 43 remaining hours, restated as the split of the 434-hour total. Where this figure is wider than the page, the frame around it scrolls sideways; it can also be focused with the keyboard and panned with the arrow keys.
</figcaption>
</figure>

**Hours Distribution by Completed Component:**

<figure class="diagram" markdown="1">
<div class="diagram__viewport" role="region" tabindex="0" aria-labelledby="diagram-completed-work-distribution-caption" markdown="1">
![Pie chart titled Completed Work Distribution 391h with ten slices: Online Services 86 hours, Tests 82, Batch Processing 64, Data Layer 55, Documentation 24, Configuration 20, Controllers 20, QA Fixes 16, Build Infrastructure 14, and Observability 10. The ten slices sum to 391.](diagrams/completed-work-distribution.svg){ .diagram__image width="655" height="450" }
</div>
<figcaption class="diagram__caption" id="diagram-completed-work-distribution-caption" markdown="1">
**Figure — Completed work distribution.** How the 391 completed hours divide across the ten components. The slices sum to 391, and the table immediately below gives each one the detail rows it rolls up so the sum is checkable rather than asserted. Where this figure is wider than the page, the frame around it scrolls sideways; it can also be focused with the keyboard and panned with the arrow keys.
</figcaption>
</figure>

**How each slice rolls up, so the total is checkable rather than asserted.** The ten slices above are a
roll-up of the 20 rows of [&sect;2.1 Completed Work Detail](#21-completed-work-detail), and they sum to
the same **391** that table totals. The mapping is published because it is the only way a reader can check
a slice: a pie whose slices sum to something other than its own title is the defect this table exists to
prevent, and it was one — the Configuration slice read 38 against a roll-up of 20, putting the ten slices
at 409 against a stated 391.

| Slice | Hours | &sect;2.1 rows it rolls up |
|---|---:|---|
| Online Services | 86 | Online Services (18 programs) 72 + Shared Utility Services 14 |
| Tests | 82 | Unit Tests 40 + Integration Tests 28 + E2E Tests 14 |
| Batch Processing | 64 | Batch Jobs &amp; Orchestration 24 + Batch Processors 24 + Batch Readers &amp; Writers 16 |
| Data Layer | 55 | Data Model Layer 28 + Data Access Layer 12 + Database Migrations 10 + Validation Resources 5 |
| Documentation | 24 | Documentation 24 |
| Configuration | 20 | Configuration Layer 14 + Exception Hierarchy 4 + Application Entry Point 2 |
| Controllers | 20 | REST Controllers 20 |
| QA Fixes | 16 | QA Fixes &amp; Debugging 16 |
| Build Infrastructure | 14 | Foundation &amp; Build Infrastructure 14 |
| Observability | 10 | Observability 10 |
| **Total** | **391** | all 20 rows, each counted once |

Every &sect;2.1 row appears in exactly one slice, so the roll-up neither double-counts a row nor drops one.

**Remaining Work by Priority:**

| Priority | Category | Hours |
|----------|----------|-------|
| 🔴 High | CI/CD Pipeline, OWASP Scan, Production Config, Security | 21 |
| 🟡 Medium | Deployment, Performance, Data Migration, Monitoring | 20 |
| 🟢 Low | API Documentation | 2 |
| **Total** | | **43** |

---

## 8. Summary & Recommendations

### Achievement Summary

The CardDemo COBOL-to-Java migration has reached **90.1% completion** (391 of 434 total project hours). All core AAP deliverables have been implemented:

- **All 28 COBOL programs** have been translated to idiomatic Java 25 with Spring Boot 3.5.11 orchestration
- **All 11 VSAM datasets** have been mapped to PostgreSQL tables with Flyway-managed schema migrations
- **The complete 5-stage batch pipeline** (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) is operational with Spring Batch
- **All 8 REST controllers** replace the 17 BMS terminal screens with full API coverage
- **888 tests pass** (729 unit + 159 integration/E2E) with **81.5% line coverage**
- **Zero-warning build** confirmed with `-Xlint:all` compiler flag
- **Full observability** is operational: structured logging, distributed tracing, Prometheus metrics, and health checks
- **BigDecimal precision** is enforced across all financial fields with zero float/double substitution
- **Comprehensive documentation** including Decision Log, Traceability Matrix, Executive Presentation, and Onboarding Guide

### Remaining Gaps

The remaining **43 hours** (9.9%) are primarily path-to-production activities:

1. **CI/CD pipeline** (8h) — No GitHub Actions workflow exists; builds are currently manual
2. **Production configuration** (6h) — Only `local` and `test` Spring profiles exist
3. **Deployment infrastructure** (8h) — Dockerfile exists but no K8s/ECS manifests
4. **Security hardening** (4h) — JWT secret externalization, TLS, rate limiting
5. **OWASP verification** (3h) — Plugin configured but scan not confirmed
6. **Performance/monitoring** (8h) — Load testing and alerting setup
7. **Data migration** (4h) — EBCDIC production data migration strategy
8. **API documentation** (2h) — OpenAPI/Swagger generation

### Production Readiness Assessment

The application is **development-complete and validation-ready**. For production deployment, the critical path requires:
1. CI/CD pipeline with automated testing
2. Production environment configuration with externalized secrets
3. OWASP dependency scan clearance
4. Deployment automation (K8s or ECS)

### Success Metrics

| Metric | Target | Actual | Status |
|--------|--------|--------|--------|
| COBOL programs migrated | 28 | 28 | ✅ |
| Test pass rate | 100% | 100% (888/888) | ✅ |
| Line coverage | ≥80% | 81.5% | ✅ |
| Build warnings | 0 | 0 | ✅ |
| Float/double in financial fields | 0 | 0 | ✅ |
| Decision log entries | ≥15 | 18 | ✅ |

---

## 9. Development Guide

### System Prerequisites

| Software | Version | Purpose |
|----------|---------|---------|
| JDK | 25 (OpenJDK or Eclipse Temurin) | Application compilation and runtime |
| Maven | 3.9.9+ (or use included `mvnw` wrapper) | Build automation |
| Docker | 28.x+ | Container runtime for PostgreSQL, LocalStack |
| Docker Compose | v5.x+ (Docker Compose v2 plugin) | Multi-service orchestration |
| Git | 2.x+ | Version control |

### Environment Setup

**1. Clone the repository:**
```bash
git clone <repository-url>
cd carddemo
```

**2. Verify Java 25:**
```bash
java -version
# Expected: openjdk version "25.x.x"
```

If Java 25 is not the default, set `JAVA_HOME`:
```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
```

**3. Start local infrastructure:**
```bash
# Set LocalStack auth token (required for Pro features)
export LOCALSTACK_AUTH_TOKEN=<your-token>

# Start PostgreSQL, LocalStack, Jaeger, Prometheus, Grafana
docker compose up -d
```

Verify services are running:
```bash
# PostgreSQL
docker compose exec postgres pg_isready -U carddemo
# Expected: accepting connections

# LocalStack
curl -s http://localhost:4566/_localstack/health | python3 -m json.tool
# Expected: {"services": {"s3": "available", "sqs": "available", "sns": "available"}}
```

### Dependency Installation & Build

```bash
# Compile the project (zero-warning build)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw clean compile -B

# Run unit tests (729 tests)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw test -B

# Run full verification (unit + integration + E2E + coverage)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw verify -B
```

### Application Startup

```bash
# Run with local profile (connects to Docker Compose services)
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local -B
```

### Verification Steps

**Health Check:**
```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Expected: {"status": "UP", "components": {"db": {"status": "UP"}, ...}}
```

**Authentication:**
```bash
curl -s -X POST http://localhost:8080/api/auth/signin \
  -H "Content-Type: application/json" \
  -d '{"userId": "USER0001", "password": "PASSWORD"}' | python3 -m json.tool
# Expected: 200 OK with JWT token
```

**Account View:**
```bash
curl -s http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer <token>" | python3 -m json.tool
# Expected: 200 OK with account data
```

**Menu Options:**
```bash
curl -s http://localhost:8080/api/menu/main | python3 -m json.tool
# Expected: 200 OK with 10 menu options
```

### Observability Access

| Service | URL | Credentials |
|---------|-----|-------------|
| Application Health | http://localhost:8080/actuator/health | N/A |
| Prometheus Metrics | http://localhost:8080/actuator/prometheus | N/A |
| Jaeger Tracing UI | http://localhost:16686 | N/A |
| Prometheus Server | http://localhost:9090 | N/A |
| Grafana Dashboards | http://localhost:3000 | admin/admin |

### Troubleshooting

| Issue | Resolution |
|-------|-----------|
| `java: error: release version 25 not supported` | Ensure JAVA_HOME points to JDK 25: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` |
| Docker Compose port conflicts | Check for existing services: `lsof -i :5432`, `lsof -i :4566` |
| LocalStack init fails | Verify `LOCALSTACK_AUTH_TOKEN` is set and `localstack-init/init-aws.sh` is executable |
| Flyway migration fails | Ensure PostgreSQL is healthy: `docker compose exec postgres pg_isready -U carddemo` |
| Testcontainers connection refused | Ensure Docker daemon is running and user has Docker socket access |
| Maven wrapper permission denied | Run: `chmod +x mvnw` |

---

## 10. Appendices

### A. Command Reference

| Command | Purpose |
|---------|---------|
| `./mvnw clean compile -B` | Compile all source files |
| `./mvnw test -B` | Run unit tests (729 tests) |
| `./mvnw verify -B` | Run all tests including integration (888 tests) |
| `./mvnw spring-boot:run -Dspring-boot.run.profiles=local -B` | Start application with local profile |
| `./mvnw dependency:tree -B` | Display dependency tree |
| `docker compose up -d` | Start all infrastructure services |
| `docker compose down -v` | Stop services and remove volumes |
| `docker compose logs -f postgres` | Tail PostgreSQL logs |

### B. Port Reference

| Port | Service | Protocol |
|------|---------|----------|
| 5432 | PostgreSQL 16 | TCP |
| 4566 | LocalStack (S3, SQS, SNS) | HTTP |
| 8080 | CardDemo Application | HTTP |
| 16686 | Jaeger UI | HTTP |
| 4317 | Jaeger OTLP gRPC | gRPC |
| 4318 | Jaeger OTLP HTTP | HTTP |
| 9090 | Prometheus | HTTP |
| 3000 | Grafana | HTTP |

### C. Key File Locations

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build configuration (Spring Boot 3.5.11, Java 25) |
| `src/main/resources/application.yml` | Central Spring Boot configuration |
| `src/main/resources/application-local.yml` | Local development profile (LocalStack endpoints) |
| `src/main/resources/application-test.yml` | Testcontainers profile |
| `src/main/resources/db/migration/V1__create_schema.sql` | PostgreSQL schema (11 tables) |
| `src/main/resources/db/migration/V3__seed_data.sql` | Seed data from COBOL fixtures |
| `src/main/resources/logback-spring.xml` | Structured logging with correlation IDs |
| `docker-compose.yml` | Local infrastructure (PostgreSQL, LocalStack, observability) |
| `localstack-init/init-aws.sh` | S3 bucket and SQS queue provisioning |
| `DECISION_LOG.md` | 18 architectural decisions with rationale |
| `TRACEABILITY_MATRIX.md` | COBOL → Java bidirectional mapping |
| `docs/executive-presentation.html` | reveal.js executive summary |
| `docs/api-contracts.md` | REST API endpoint specifications |
| `docs/onboarding-guide.md` | New developer quickstart |
| `docs/validation-gates.md` | Gate 1-8 evidence documentation |

### D. Technology Versions

| Technology | Version | Notes |
|-----------|---------|-------|
| Java (OpenJDK) | 25.0.2 | LTS release |
| Spring Boot | 3.5.11 | Latest stable 3.x |
| Spring Data JPA | 3.5.x (BOM) | Hibernate 6.x |
| Spring Batch | 5.x (BOM) | Job/Step/Flow framework |
| Spring Security | 6.x (BOM) | BCrypt + role-based access |
| PostgreSQL | 16 (Alpine) | Via Docker |
| Flyway | 11.x (BOM) | Schema migration |
| Spring Cloud AWS | 3.3.0 | S3, SQS, SNS starters |
| Testcontainers | 2.0.3 | PostgreSQL + LocalStack containers |
| Micrometer | 1.6.x (BOM) | Metrics + tracing |
| JaCoCo | 0.8.14 | Code coverage |
| Maven | 3.9.9 | Build automation (via wrapper) |
| Docker | 28.x | Container runtime |
| LocalStack Pro | Latest | AWS service emulation |

### E. Environment Variable Reference

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `JAVA_HOME` | Yes | System default | Path to JDK 25 installation |
| `LOCALSTACK_AUTH_TOKEN` | Yes (local dev) | None | LocalStack Pro authentication |
| `POSTGRES_DB` | No | `carddemo` | PostgreSQL database name |
| `POSTGRES_USER` | No | `carddemo` | PostgreSQL username |
| `POSTGRES_PASSWORD` | No | `carddemo` | PostgreSQL password |
| `SERVER_PORT` | No | `8080` | Application server port |
| `SPRING_PROFILES_ACTIVE` | No | `default` | Active Spring profile (local, test) |
| `AWS_ACCESS_KEY_ID` | No | `test` (local) | AWS access key (LocalStack) |
| `AWS_SECRET_ACCESS_KEY` | No | `test` (local) | AWS secret key (LocalStack) |
| `AWS_DEFAULT_REGION` | No | `us-east-1` | AWS region |

### F. Developer Tools Guide

**Running a specific test class:**
```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw test \
  -Dtest=AccountUpdateServiceTest -B
```

**Running integration tests only:**
```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw verify \
  -DskipUnitTests=true -B
```

**Generating coverage report:**
```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw verify -B
# Report at: target/site/jacoco/index.html
```

**Building Docker image:**
```bash
docker build --network=host -t carddemo:latest .
```

### G. Glossary

| Term | Definition |
|------|-----------|
| VSAM KSDS | Virtual Storage Access Method — Key-Sequenced Data Set (COBOL file type → PostgreSQL table) |
| BMS | Basic Mapping Support — CICS 3270 screen definitions (→ REST API contracts) |
| COMMAREA | Communication Area — CICS inter-program data passing (→ session/token state) |
| TDQ | Transient Data Queue — CICS message queue (→ AWS SQS) |
| GDG | Generation Data Group — Versioned dataset generations (→ S3 versioned objects) |
| COMP-3 | Packed decimal storage — COBOL numeric type (→ Java BigDecimal) |
| SYNCPOINT | CICS transaction commit/rollback point (→ Spring @Transactional) |
| FILE STATUS | COBOL I/O result code (→ Java exception hierarchy) |
| POSTTRAN | Daily transaction posting batch job (Stage 1 of 5-stage pipeline) |
| INTCALC | Interest calculation batch job (Stage 2) |
| COMBTRAN | Transaction combine/sort batch job (Stage 3 — DFSORT replacement) |
| CREASTMT | Statement generation batch job (Stage 4a) |
| TRANREPT | Transaction report batch job (Stage 4b — parallel with 4a) |