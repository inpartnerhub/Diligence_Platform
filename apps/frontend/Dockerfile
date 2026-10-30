# syntax=docker/dockerfile:1.7
# Build context: apps/frontend

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Public (browser) configuration is baked in at build time; server configuration is injected at runtime.
ARG NEXT_PUBLIC_APP_ENVIRONMENT
ARG NEXT_PUBLIC_SENTRY_DSN
RUN --mount=type=secret,id=sentry_auth_token,env=SENTRY_AUTH_TOKEN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
COPY --from=build --chown=app:app /app/.next/standalone ./
COPY --from=build --chown=app:app /app/.next/static ./.next/static
COPY --from=build --chown=app:app /app/public ./public
COPY --from=build --chown=app:app /app/messages ./messages
USER 10001:10001
EXPOSE 3000
CMD ["node", "server.js"]
