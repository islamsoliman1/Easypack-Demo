# Machine dashboard plan

## Goal

Make the EasyPack home page a dashboard containing every registered machine. Each machine is clickable and shows assembly completion, its current order, and its status. Newly added machines and new or opened orders appear automatically.

## Scope and behavior

- Work in the standalone React/Spring Boot app in this `demo` directory.
- Preserve the existing Arabic interface, RTL layout, theme support, permissions, and manufacturing rules.
- Show all registered machines, including machines with no orders and archived machines.
- Calculate assembly completion from installed pieces divided by required pieces. A draft, cancelled order, or machine without an order shows zero. Incomplete assembly must never round up to 100%.
- Use the most recently created non-cancelled order for each machine. Show cancellation explicitly if there are only cancelled orders. Keep the full order history on the machine details page.
- Label the percentage as assembly completion. Keep testing, rework, completion, and delivery statuses separate.
- Refresh machines and visible orders every 15 seconds and on returning to the dashboard. Invalidate the existing query caches after creation or changes.
- Respect assigned-order visibility. Do not infer hidden orders or display unauthorized data.
- Keep the existing factory reports accessible at `/reports`.

## Implementation — Astra (`gpt-6-astra`)

1. Inspect and finish the existing changes in `Dashboard.tsx`, `machineDashboard.ts`, `OrdersPage.tsx`, `CatalogPages.tsx`, `main.tsx`, and `styles.css`.
2. Provide searchable and filterable machine cards, summary counts, accessible links and progress bars, empty/loading/error states, and a responsive layout.
3. Provide machine details and clickable order history, with links to existing BOM and assembly screens where authorized.
4. Reuse existing creation forms. Allow creating a machine or order from the dashboard and preselect the machine when creating an order from its details.
5. Verify the actual local app and database: machine creation, new order, opening a draft, navigation, progress calculations, automatic refresh, theme, and narrow layouts. Use identifiable synthetic verification records and leave their final state documented.
6. Run the frontend production build and inspect the final diff. Preserve unrelated user work, especially `LOCALIZATION_PLAN.md`.

## Review — Sol 6.1 (`gpt-6.1-sol`)

After Astra finishes, independently review the final implementation for correctness, regressions, permission handling, stale data, progress calculation, order selection, accessibility, and integration with existing workflows. Report actionable findings with file and line evidence. Do not change the implementation during the first review pass.

## Completion

Resolve material review findings, repeat affected checks, and deliver the local dashboard link with a concise account of implemented behavior and verification limits.

## Handoff baseline

Initial frontend build passes. Nineteen focused assertions pass for machine inclusion, latest-order selection, cancellation, new drafts, progress boundaries, and overdue/delivered status. Browser verification is in progress. The separate Vite port is rejected by the backend's configured origin policy, so verification will use the rebuilt local Docker app at `http://localhost:18090`.

## Result — 2026-10-17

Astra completed implementation and verified the real local workflow: machine creation, details, machine preselection, draft creation, opening an order, automatic polling, cancellation fallback, search/filter, dark theme, and mobile layout without overflow. Frontend and Docker builds pass. The rebuilt app is served at `http://localhost:18090`.

Sol 6.1 independently reviewed the implementation and found one percentage precision issue. Astra fixed it by multiplying before division; Sol 6.1 confirmed 29/100, 57/100, 58/100, 999/1000, and 100/100 all display correctly. No material review findings remain.

The synthetic verification order is cancelled and the synthetic machine is archived. Restricted-role behavior was reviewed in source; separate role sessions were not exercised. Backend tests were not rerun because no backend code changed. Detailed verification evidence is recorded in `VERIFICATION.md`.
