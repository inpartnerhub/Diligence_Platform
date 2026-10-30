# API

## Source of truth

The OpenAPI 3 document is generated from the backend code by springdoc-openapi:

| Environment | Spec | UI |
|-------------|------|----|
| local | http://localhost:8080/v3/api-docs | http://localhost:8080/swagger-ui.html |
| dev / staging | `https://api.<env>.example.com/v3/api-docs` (when `API_DOCS_ENABLED=true`) | `/swagger-ui.html` |
| prod | disabled | disabled |

Swagger UI authenticates with Keycloak (Authorization Code + PKCE) through the `keycloak` security scheme
configured in `shared/config/OpenApiConfiguration.java`.

The frontend consumes the spec with `npm run generate:api` (openapi-typescript), producing
`apps/frontend/src/lib/api/schema.gen.ts`.

## Guidelines

- Resource-oriented paths under `/api/v1`, plural kebab-case nouns, camelCase JSON.
- `GET` is safe and idempotent; `PUT`/`DELETE` idempotent; use `POST` for creation and commands.
- Pagination: `?page=0&size=20&sort=createdAt,desc`; responses include `page` metadata.
- Errors: RFC 9457 `application/problem+json` with a stable `type` URI (`urn:diligence:problem:<code>`).
- Correlation: clients may send `X-Request-Id`; Kong generates one otherwise and echoes it back.
- Every operation has `@Operation(summary)`, a `@Tag` matching its module, and documented error responses.
- Versioning: additive changes only within a major version; breaking changes → new major path.
