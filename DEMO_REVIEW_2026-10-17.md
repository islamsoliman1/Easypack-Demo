# Standalone demo review — 2026-10-17

Scope: `E:/FreelanceProjects/Easypackjava/demo`, starting from commit `2441384` (`Easypack_Demo`). This review checks the independently maintained demo, including its passwordless visitor session, real PostgreSQL data, browser pages, and two defects previously fixed in the main application. This review made no application source edits. Separate dashboard/frontend edits appeared during validation; their latest frontend build was also checked, and a route mismatch in those edits is recorded below.

## Confirmed bugs

### P2 — New machine detail pages cannot be opened directly or refreshed

Locations: [Dashboard.tsx:63](frontend/src/Dashboard.tsx#L63), [main.tsx:156](frontend/src/main.tsx#L156), and [RailwaySpaConfiguration.java:33](backendjava/src/main/java/com/easyback/web/RailwaySpaConfiguration.java#L33).

The new dashboard links to `/machines/:id`, and React defines that detail page. Spring's SPA security matcher and HTML controller only recognize `/machines/:id/assembly`, not `/machines/:id`. Opening a machine detail in a new tab, following a shared link, or refreshing the detail page therefore fails before React can start. In-app navigation can conceal the missing server route.

A real demo machine URL returned 401 for both anonymous GET and HEAD requests; supported SPA pages return 200 HTML. This concerns the separate frontend changes that arrived during the check. The original running image does not yet contain that new dashboard, while its backend route registration is also unchanged in the current source.

Fix: add a UUID-constrained machine detail path to both the public GET/HEAD matcher and the HTML controller mapping. Extend the SPA security tests and verify a real machine detail reload after rebuilding the combined image. Keep `/api/v1/machines` protected.

Evidence: [direct-route result](output/review/machine-route-result.json), [verification script](output/review/demo-machine-route.mjs).

### P2 — Correct template updates fail at version 128

Location: [TemplateService.java:24](backendjava/src/main/java/com/easyback/operations/TemplateService.java#L24).

Both `version` and `p.version()` are boxed `Long` objects. The `!=` comparison checks object identity instead of their numeric values. The JVM's small-number cache hides the mistake for versions 0 through 127. A regularly edited template becomes impossible to update or archive once its current version is 128.

The demo visitor has `operations.templates.manage`, so this is reachable in the public demo workflow. A real API reproduction created one inactive synthetic template and successfully updated it with input versions 0 through 127. A subsequent update supplied version 128, matching the stored value, but returned `409 STALE_VERSION`.

Fix: compare numeric values using `version.longValue() != p.version().longValue()`. Add regression coverage for matching versions 127, 128, and 1024, actual stale versions, and concurrent updates. Port the already validated main-application fix.

Evidence: [API result](output/review/template-boundary-result.json), [reproduction script](output/review/demo-template-boundary.mjs).

### P2 — Stock update exposes order actions before the new order version arrives

Locations: [Warehouse.tsx:90](frontend/src/Warehouse.tsx#L90) and [OrdersPage.tsx:88](frontend/src/OrdersPage.tsx#L88).

The stock save callback closes its dialog before awaiting `invalidateStock()`, while the parent order's edit/release/cancel actions remain enabled during its refresh. Stock operations also change the order version. Immediately cancelling after issuing stock can therefore submit the previous version and display a stale-version error instead of the applicable stock-return requirement.

A browser reproduction held the parent order refresh after a successful stock issue. The issue dialog was already closed, cancellation remained enabled, and cancellation submitted version 0 while the current server version was 1. This reproduction used synthetic API responses and did not mutate the database.

Fix: await `invalidateStock(client)` before closing the stock dialog, and disable parent order actions while `pending || query.isFetching`. Port the corresponding main-application fixes and preserve the delayed-response browser regression.

Evidence: [browser reproduction](output/review/stock-race-check.log), [reproduction script](output/playwright/check-stock-race.js).

## Validation

| Check | Result |
| --- | --- |
| Frontend TypeScript and production build | Passed, including the separate new dashboard changes |
| Docker combined frontend/backend production image | Built successfully |
| Java unit and security tests | 26 passed, zero failures/errors/skips |
| Shared-core PostgreSQL integration tests | 79 passed, zero failures/errors/skips, using a separate temporary `easyback_test` database |
| Existing `scripts/Verify-Demo.mjs` | All four verification groups passed |
| Edge browser pages and direct URLs | 19 existing routes passed on the original running image; new machine detail direct route fails as described above |
| Browser JavaScript errors | None during the page checks |
| Unexpected HTTP failures during page checks | None |
| Desktop 1440 px and tablet 834 px | Rendered and visually inspected; no page-level horizontal overflow |
| Application restart | Readiness recovered to `UP` |
| Saved changes after restart | Inactive template retained its ID and version 128; seven seeded orders retained |

The API verification covers anonymous API rejection, CSRF enforcement, passwordless visitor entry, all seeded order states, nonempty catalog/warehouse/task/defect/operation data, reports and CSV export, direct SPA URLs, a real catalog write and archival, blocked administrative writes, untrusted-origin rejection, refresh, logout revocation, and visitor re-entry.

Browser checks cover the dashboard, models, parts, machines, BOMs, orders, warehouse, raw stock, tasks, defects, preparations, operations, shifts, reports, users, roles, operation templates, and real order/operation detail URLs. The first fresh visitor session intentionally receives a 401 from `/auth/refresh` before automatically entering through `/auth/demo`; that expected fallback is excluded from unexpected HTTP failures.

The Java total is 105 passing tests. The 79 integration tests exercise the shared manufacturing/backend code with the default profile; demo-profile entry, permissions, seeded data, and persistence were checked separately against the real running demo. An initial temporary test-harness attempt used a signing key shorter than the required 64 bytes and could not start the test context. Correcting that harness configuration produced the passing run; no application change was needed.

Evidence: [browser result](output/review/browser-result.json), [desktop screenshot](output/playwright/demo-desktop.png), [tablet screenshot](output/playwright/demo-tablet.png), [persistence result](output/review/persistence-result.json), [integration summary](output/review/integration-reports/failsafe-summary.xml), [integration output](output/review/integration-check.log).

## Fix plan

1. Register the new machine detail SPA route and verify GET, HEAD, direct entry, and reload without weakening API authentication.
2. Port the numeric template-version comparison and its boundary/concurrency regression coverage from the main application.
3. Port the stock refresh ordering and order-action disabling, then rerun the delayed-refresh browser scenario expecting cancellation version 1.
4. Rebuild the demo image, rerun Java and demo API checks, and repeat browser checks and restart persistence, including the new dashboard and machine pages.

The three confirmed bugs mean the current demo source cannot be described as fully validated without those fixes. Normal page navigation, visitor access, and the tested persistence behavior work locally on the original running image. The new dashboard frontend compiles, but its full browser flow was not included in the original 19-route run. Public Railway deployment, HTTPS/cookies on a public hostname, load testing, and every manufacturing transition were not independently exercised in this review.

The local demo remains available at [http://localhost:18090](http://localhost:18090). Existing database data and the separate localization plan were preserved. Verification added an archived catalog fixture and one inactive template fixture to the local demo; no production deployment was changed.
