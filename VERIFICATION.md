# Demo verification — 2026-10-14

Verified locally against the standalone demo, using a fresh PostgreSQL 17.11 database.

- Frontend: TypeScript and Vite production build passed. Vite reports the existing main bundle size warning (about 527 kB before gzip).
- Backend: 26 Java unit/security tests passed, with zero failures/errors. Includes visitor permission restrictions and rejection of passwordless entry when the demo profile is absent.
- Combined `Dockerfile.Railway` image built successfully; `railway,demo` started and applied all 13 migrations.
- Passwordless entry created a real visitor session. Unauthenticated API access and entry without CSRF were rejected.
- Seeded seven manufacturing orders covering draft, open, assembly, ready for testing, completed/delivered and rework, plus an open defect, stock, raw material and preparation records.
- API checks passed for populated screens, reports/CSV, model creation and archival, blocked administration/password changes, cross-origin rejection, refresh, logout revocation and re-entry.
- Restart persistence passed: orders/models/users/stock-piece counts remained `7/4/1/64`. The fourth model is an archived verification fixture; the initial seed contains three models.
- Edge browser: fresh visitor entry, 15 navigation screens, an order detail deep link and reload passed without application errors. Desktop (1440 px), tablet (768 px), light/dark themes were visually checked. No document-level horizontal overflow at tablet size.
- Source archive inspected: no `.env`, database data, dependency folders, build outputs or Git history.

The service is available locally at `http://localhost:18090`. No GitHub repository was created or pushed, and no Railway resources were created or deployed. Public HTTPS and Railway-specific networking must be checked after the owner deploys the repository using README instructions.

Local screenshots are under ignored `output/playwright/`. The API verification script is `scripts/Verify-Demo.mjs` and creates one archived synthetic model per run.
