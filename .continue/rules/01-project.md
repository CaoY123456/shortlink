---
name: Project Goals
alwaysApply: true
---

# Shortlink — Study Project

A URL shortener built to practice designing, developing, and deploying a
complete project. Non-technical goal: the owner is practicing English, so
all code, comments, commit messages, and docs are written in English on
purpose. In chat, explain things in Chinese, and gently correct the
owner's English when they write something wrong.

## Roadmap (do not reorder)

- [x] **M1** — shorten / redirect / stats against MySQL
- [ ] **M2** — Redis cache on the redirect path, counter in Redis, non-guessable codes
- [ ] **M3** — Dockerfile, whole stack under Compose
- [ ] **M4** — deployed behind Nginx with HTTPS
- [ ] **M5** — polish, published

Current state: M1 is complete. The next planned work is M2 (Redis).
Do not introduce Redis, Docker, or Nginx concerns before their milestone.

## Stack

- Java 17, Spring Boot (Gradle Kotlin DSL, `build.gradle.kts`)
- MySQL 8.4 via Docker Compose, Flyway migrations, no JPA / no Spring Data
- Layers: `web/` (controllers, request/response records) → `service/` →
  `repository/` (JdbcClient) — keep this layering; don't skip layers.
