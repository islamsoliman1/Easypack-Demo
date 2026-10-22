# EasyPack - Manufacturing Management System

A full-stack manufacturing workflow application covering machine components,
production orders, warehouse stock, assembly tasks, defect handling, operational
trials, and management reporting. Built with **Java 21, Spring Boot, PostgreSQL,
React, and TypeScript**, with Arabic and English interfaces, RTL/LTR layouts,
and light/dark themes for desktop and tablet use.

This repository contains the **standalone demo edition** of EasyPack. Visitors
open the application without entering credentials and receive an authenticated
guest session automatically. Manufacturing workflows use the real backend and
PostgreSQL database, with server-side permissions and business rules enforced.

The backend is in **`backendjava/`** and the frontend is in **`frontend/`**.
Run the commands below from the directory containing this README.

## Implemented Features

- **Machine and component catalog:** machine models, physical machines, shared
  part definitions, serialized components, and measured raw materials.
- **Versioned bills of materials (BOMs):** editable drafts, immutable published
  revisions, quantities, installation times, and independent machine-specific
  component lists.
- **Manufacturing orders:** drafts, responsible users, delivery dates, frozen
  requirements on release, cancellation reasons, and lifecycle tracking through
  assembly, testing, rework, completion, and delivery.
- **Warehouse management:** serialized receipts, stock balances and movement
  history, automatic reservations, issue and inspected returns, quarantine,
  restoration, and shortage preparation.
- **Raw stock and cutting:** measured material receipts, length/sheet cutting,
  serialized outputs, reusable remnants, and recorded scrap. Cutting uses zero
  calculated kerf.
- **Assembly tracking:** worker assignments, task-scoped access, issued component
  serials, installation/removal history, and machine assembly progress.
- **Defects and repairs:** defect reporting, physical defective receipt, repair,
  warehouse acceptance, and scrap, with component history retained.
- **Operational trials:** reusable checklists, pass/fail results, rework and
  retesting, order completion, and separate delivery confirmation.
- **Work tracking:** work calendars, shifts, breaks, and task work sessions.
- **Dashboards and reports:** searchable machine cards, order status and assembly
  progress, management summaries, CSV export, and printable reports.
- **In-app notifications:** persistent workflow alerts, links to related records,
  read/unread states, and scheduled delivery reminders.
- **Bilingual interface:** Arabic/English switching, RTL/LTR layouts, localized
  system messages and reviewed demo content, and light/dark appearance.

## Technology and Architecture

| Area | Technologies and implementation |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring MVC, REST APIs, JSON DTOs, Bean Validation |
| Security | Spring Security, signed JWT access tokens, rotating refresh tokens, HttpOnly cookies, CSRF protection, permission checks |
| Database | PostgreSQL 17, Spring JDBC, Flyway migrations, transactions, explicit locking, optimistic version checks, audit records |
| Frontend | React, TypeScript, Vite, React Router, TanStack Query, React Hook Form, Zod |
| Localization | i18next, react-i18next, backend localization resources |
| Testing | JUnit, Spring Boot Test, PostgreSQL integration tests, Node.js demo verification |
| Packaging | Docker multi-stage build, Docker Compose, Railway deployment configuration |

The backend is a **modular monolith**, organized into catalog, orders, warehouse,
assembly, defects, operations, identity, notifications, reports, files, and work
tracking modules. Controllers expose HTTP endpoints, services enforce business
rules, and Spring JDBC persists data in PostgreSQL.

The combined Docker image builds the React frontend and serves it from Spring
Boot alongside `/api/v1`. The browser and API share one origin; a separate
frontend API URL is not required for this image. PostgreSQL runs as a separate
service. Notifications use database-backed events and a scheduler.

## Demo Access and Data

On first startup, an empty demo database is populated with synthetic data:
three machine models, seven machines and manufacturing orders at different
workflow stages, published BOMs, serialized stock, raw materials, assembly tasks,
a defect case, and operational trials.

- Visitors can exercise manufacturing workflows and view users and roles.
- Account administration, role/permission changes, password changes, and
  attachment uploads are unavailable to the guest account.
- Visitors share one demo account and dataset. Saved changes are visible to
  other visitors and survive application and database container restarts.
- There is no public reset action or automatic reset on restart. Use a new,
  empty PostgreSQL database for a fresh demo.
- Demo initialization refuses an existing non-demo database containing users.
- Reviewed demo content is localized; arbitrary authored text retains its
  original-language fallback. Language switching preserves original form values.

This edition is isolated from the main application: it does not import the main
project's files, environment settings, or database. Use synthetic data only in
the shared demo.

## Run Locally (PowerShell)

Requires **Docker Desktop with Linux containers** and **PowerShell 7**.
Docker builds Java and frontend dependencies; local Java, Maven, and Node.js
installations are optional unless running their commands directly.

```powershell
./scripts/Initialize-Demo.ps1
docker compose up -d --build
```

Open [EasyPack Demo](http://localhost:18090). No email or password is required.

| Endpoint | Default local URL |
| --- | --- |
| Web application | [http://localhost:18090](http://localhost:18090) |
| REST API base | [http://localhost:18090/api/v1](http://localhost:18090/api/v1) |
| Readiness check | [http://localhost:18090/actuator/health/readiness](http://localhost:18090/actuator/health/readiness) |

The readiness endpoint returns `UP` when the application and database are ready.
Swagger UI and API documentation endpoints are disabled in the default combined
demo configuration.

The initialization script generates a local `.env` with `DB_PASSWORD`,
`JWT_SECRET`, and `DEMO_PORT=18090`, and retains an existing file. Change
`DEMO_PORT` before starting if another application uses the default port.
Database data is stored in the demo's named Docker volume under the
`easypack-public-demo` Compose project.

To stop the services while retaining saved data:

```powershell
docker compose down
```

On Linux or macOS, create a local `.env` with a random `DB_PASSWORD`, a random
`JWT_SECRET` of at least 64 bytes, and `DEMO_PORT=18090`, then run
`docker compose up -d --build`. Keep `.env` out of version control.

## Verify

Recorded acceptance results and their limits are documented in
[VERIFICATION.md](VERIFICATION.md). The commands below run separate checks;
the normal application image build skips backend tests.

With the local demo running and Node.js 22.12+ installed:

```powershell
node scripts/Verify-Demo.mjs
node scripts/Check-Translations.mjs
```

`Verify-Demo.mjs` checks guest sessions, API authentication, CSRF protection,
seeded workflows, reports/CSV, direct frontend routes, database writes,
administrative restrictions, session refresh, and logout revocation. It accepts
localhost targets only and creates then archives a synthetic machine model.
Its seeded-state assertions expect the original sample order states; changes
made while exploring the shared demo may affect those assertions.

To run Java unit/security tests with Docker:

```powershell
docker build --target verify -f Dockerfile.Railway .
```

Alternatively, with Java 21 and Maven installed:

```powershell
mvn -f backendjava/pom.xml test
```

To check the frontend independently with Node.js 22.12+:

```powershell
cd frontend
npm ci
npm run build
```

Database integration tests are separate from these unit checks. `IdentityIT`
requires an explicitly configured, disposable PostgreSQL database named
`easyback_test` and resets its test tables. Do not point it at the running demo
or a database containing data you want to retain.

## In-app Notifications

The header bell and notifications page show persistent workflow alerts with
links to tasks, defects, preparation requests, operational trials, and orders.
Opening the bell does not mark notifications as read.

Workflow events and recipients are recorded within the business transaction.
Access to notifications and linked records is checked against current
permissions. Delivery reminders scan every 15 minutes for orders due within
24 hours or already overdue, including completed but undelivered orders.
Draft, cancelled, and delivered orders are excluded.

## Deploy the Demo to Railway

The repository includes `Dockerfile.Railway` and `railway.json` for the combined
application image and readiness health check. `compose.yaml` is for local use.

1. Push **the contents of this directory** to the root of a GitHub repository.
   Keep `Dockerfile.Railway` and `railway.json` at the repository root, and exclude
   `.env`, local output, dependency folders, and database backups.
2. Create a Railway project with a **new, empty PostgreSQL database**.
3. Add an application service from the GitHub repository with Root Directory `/`.
   The supplied configuration selects the Dockerfile and health check; separate
   build and start commands are unnecessary.
4. Configure these variables on the **application service**. The database
   references assume the PostgreSQL service is named `Postgres`.

| Variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `railway,demo` |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_USER` | `${{Postgres.PGUSER}}` |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | A new random secret containing at least 64 bytes |
| `ALLOWED_ORIGINS` | `https://${{RAILWAY_PUBLIC_DOMAIN}}`, or the full public HTTPS origin without a trailing slash |

5. Generate a public domain under Settings > Networking and deploy the service.
   The application reads Railway's `PORT`; use target port `8080` unless a
   different `PORT` is explicitly configured.
6. Open the domain and confirm the dashboard loads and
   `/actuator/health/readiness` returns `UP`.

Generate a JWT secret locally with Node.js, and copy the output only into the
application service's environment variable:

```powershell
node -e "console.log(require('node:crypto').randomBytes(64).toString('base64'))"
```

`BOOTSTRAP_EMAIL` and `BOOTSTRAP_PASSWORD` are unnecessary for guest access.
Leave `EASYBACK_SECURE_COOKIES` unset on Railway so the profile uses secure
cookies over HTTPS. Local Compose overrides this setting for localhost HTTP.
The database connection uses Railway's internal service network.

For a source-only archive suitable for manual GitHub upload:

```powershell
./scripts/Export-GitHub.ps1
```

Extract the generated ZIP and upload its contents to the repository root. The
export script includes source and this README, and excludes secrets,
dependencies, build outputs, and local database data.

Local verification does not establish public deployment availability. Verify
HTTPS, the public origin, and readiness again in the hosting environment.
