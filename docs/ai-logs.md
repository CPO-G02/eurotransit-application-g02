# AI Interaction Log — Application Repo

This file records significant AI-assisted development sessions in the
application repo, as required by `ai-guidelines.md` §16. Newest entries first.

---

### 2026-07-08 22:04

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Implement the Catalog service (Phase 1, task 1): `GET /api/v1/catalog/products`
(list) and `GET /api/v1/catalog/products/{train_id}` (single, 404 when missing),
backed by its own PostgreSQL database.

**Files Modified / Created (catalog/)**

- build.gradle.kts — added spring-boot-starter-data-r2dbc + r2dbc-postgresql
- src/main/resources/application.yaml — R2DBC config via env vars, spring.sql.init
- src/main/resources/schema.sql, data.sql — schema + idempotent seed
- src/main/kotlin/.../entities/ — ProductEntity, SeatClassEntity
- src/main/kotlin/.../repositories/ — ProductRepository, SeatClassRepository
- src/main/kotlin/.../dto/CatalogDtos.kt — request/response DTOs (snake_case)
- src/main/kotlin/.../service/ — CatalogService (interface) + DefaultCatalogService
- src/main/kotlin/.../controllers/CatalogController.kt
- src/main/kotlin/.../exceptions/CatalogExceptionHandler.kt (+ ProductNotFoundException)
- src/test/kotlin/.../CatalogControllerTest.kt — standalone WebTestClient tests
- src/test/kotlin/.../CatalogApplicationTests.kt — fixed wrong package (inventory -> catalog)

**Summary**

Reactive WebFlux service with Kotlin coroutines. Persistence via Spring Data
R2DBC (not JPA, which is blocking and would break the reactive stack): two
tables (products, seat_classes) assembled into the contract's nested response in
the service layer. Snake_case wire keys pinned with @JsonProperty (including the
reserved "class" key). 404 returns {"error":"product_not_found"} via a
@RestControllerAdvice. Seed loaded idempotently by spring.sql.init. Package
layout kept flat/unGrouped by role: entities, repositories, controllers,
exceptions, dto, service.

**Verification**

- ./gradlew clean test: 3 controller tests pass, 1 context-load test skipped
  (deferred to Phase 2 Testcontainers).
- End-to-end run against a throwaway Dockerised PostgreSQL: list, single, and
  404 responses all correct; column mapping, TIMESTAMPTZ->Instant and
  NUMERIC->BigDecimal verified. Environment torn down afterwards.

**Potential Risks / Assumptions**

- Catalog owning a database was a human-approved decision. The pushed
  architecture-design.md currently still says "Catalog: no DB", so code and doc
  diverge until the doc is reconciled (config repo, out of scope here).
- Infra not yet created: catalog-db CloudNativePG cluster manifest and the
  SPRING_R2DBC_* secret/env for deployment.
- Prices serialize as JSON numbers (e.g. 25.0), numerically equal to the
  contract's 25.00.

**Confidence**

High — compiles, unit tests and a real DB-backed end-to-end run all pass.

**Notes**

Naming inconsistency across project docs: ai-guidelines.md §16 uses ai-logs.md
(this file), while dod.md / the architecture app-repo structure reference
agent-log.md. Team should converge on one name.
