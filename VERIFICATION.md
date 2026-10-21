# Demo verification

## Current localization release status — 2026-10-18

This section supersedes the older dated release evidence below for the combined bilingual demo. **Sol 6.1 approves the scoped demo release**, based on reviewed source and completed local acceptance checks on the final image below. No unresolved demo blocker remains from this review. This does not implement bilingual authoring or localize the active main application; main compatibility evidence is source review, its baseline frontend build and 25 unit/security tests, not exhaustive integration or end-to-end approval.

- Following host storage recovery, the complete isolated backend regression passed: 31 unit/security/localization tests and 91 database integration tests, zero failures/errors/skips. The log is `demo/.local/localization-recovery-regression.log`, ending with `BUILD SUCCESS` at 2026-10-18T10:23:47Z.
- The earlier interrupted run is retained in `COMPATIBILITY_REVIEW.md` as historical failure/infrastructure evidence, not counted as a pass.
- Fresh-demo initialization, V100-to-V101 upgrade with business-row preservation, localized API/CSV/security checks and targeted live browser checks passed. Approval uses October 18 evidence, not the older October 14/17 checks below.
- Historical October 18 failure: the first fresh-demo startup after storage recovery failed in damaged local runtime image layers (Java exec-format error and truncated `libselinux.so.1`); pulling the base image reused affected blobs. No live upgrade occurred during that failed attempt. Runtime recovery and the final clean application build/start resolved this blocker. The completed regression ran in a separate verifier image.
- Historical October 18 browser finding: existing-draft selects displayed empty placeholders after asynchronous option loading while original IDs remained in form state. The scoped demo correction and final-image browser verification below resolved this blocker.

### Recovered application verification before final selector build

The verification owner subsequently recovered the application image and completed the following recovery and upgrade checks, followed by the final corrected-image checks below.

- A clean application image started successfully. A fresh database applied all 14 migrations through V101. The local `output/Verify-Localization.mjs` checks passed 306 assertions on the fresh demo and 320 on the upgraded existing demo, covering bilingual API behavior.
- V100-to-V101 upgrade preserved the recorded business baseline exactly: eight orders, eight machines and 23 tasks retained their pre-upgrade hashes. The initial failed-image attempt occurred before this later successful upgrade.
- Browser checks retained unsaved catalog values through English → Arabic → English; language persisted on reload. A separate new browser started in Arabic while the existing browser remained English. Direction and theme checks passed. Screenshots are under `output/playwright/`.
- All five notification target kinds opened their intended records: task, defect, preparation, operations and order. A task hidden by list status/search filters still opened by its target ID. Locale switching, reload, back navigation and modal close retained the expected target behavior. Preparation selection also survived reload and toggling.
- Notification list/count agreed after reading the examples; unread-only was empty at zero unread. Shared visitor state and initialization-snapshot notices appeared in both languages. On the fresh API, repeated individual-read calls were idempotent and read-all/list/count agreed. Final-image restart persistence passed below.
- Sol 6.1 source-approved the narrow selector fix: order machine/BOM/responsible selects and machine model select now bind their displayed value to original React Hook Form state. Registered change handlers remain intact; a user machine change clears the dependent BOM.
- The same uncontrolled asynchronous-select pattern is confirmed in active main source (`OrdersPage.tsx` and `CatalogPages.tsx`); main runtime reproduction was not performed. This inherited main issue remains outside the demo patch and main was not edited.

### Final corrected image checks

- Final local application image: `sha256:ebb0f7dcb15abdbc956cca3c0c866c9c11d5e67d023f20ad70f0d51e50fe05b3`, running against the fresh demo on port 18091 and upgraded demo on port 18090.
- Fresh-demo restart preserved all five notification IDs, order codes, targets and read timestamps exactly, without duplicate samples (`output/Verify-NotificationState.mjs`).
- Final-image order browser checks passed (`output/Browser-Order-Check.js`): machine/BOM/responsible selects display the correct original IDs after asynchronous options load; original Arabic title and unsaved title/notes retain the same DOM values through English → Arabic → English; selected IDs remain stable; changing machine clears BOM.
- Order submit payload inspection preserved IDs and authored text. The test intercepted the PUT and returned a deliberate 422, so it verified frontend serialization without writing an existing order. Expected console errors from that intercepted response do not count as application failures; this specific check does not claim a persisted order edit.

- Machine/mobile checks passed (`output/Browser-Machine-Mobile-Check.js`): saved model ID and original Arabic source are correct; unsaved machine name retains the same DOM value across toggles; direct machine URL reload works. Loaded 390px layouts were checked and screenshots recaptured in both languages, with no document overflow and tables scrolling within their components.
- Operations checks passed (`output/Browser-Operations-Check.js`): copied check labels/criteria remain original Arabic; unsaved label, criterion, template and trial note retain the same DOM values across English/Arabic toggles without a workflow write.
- The verification owner visually inspected the catalog dialog, Arabic notifications, dark desktop and loaded Arabic/English mobile screenshots. Build/start evidence is `.local/localization-form-fix-build.log` and `.local/localization-final-start.log`.
- Final post-test `.local/localization-upgrade-after.txt` matches the original baseline: orders `8 / e77c6c5c428ec441f6e86a303820eb99`; machines `8 / 3d70411b46757c3109c8a9c57c84ce71`; tasks `23 / 61cc613aaa33c93544cddf913b348e74`. Task hashing excludes `assignment_epoch` consistently with the baseline comparison.

Acceptance is limited to the documented demo contract and tested flows. Arbitrary authored text keeps its explicit original fallback; paired-language authoring remains proposed. Exhaustive browser coverage of every workflow and main database integrations were not performed. Public hosting HTTPS/networking must be checked in the deployment environment. The inherited main asynchronous-select source finding remains unresolved in main; only demo was corrected.

## Original verification — 2026-10-14

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

## Machine dashboard — 2026-10-17

- Frontend production build and combined Docker image build passed. The reviewed image is served locally at http://localhost:18090; database volume preserved. Backend tests were not rerun for this frontend change (Docker packaging skips tests).
- Browser verified all machines as clickable cards, machine detail navigation, preselected machine in new-order form, new machine appearing immediately, new draft becoming the current order at 0%, and release in a second tab automatically updating the dashboard to OPEN without manual refresh. Cancelling that test order restored the prior non-cancelled order and its 33% assembly progress.
- Search and rework filtering passed. Light/dark desktop and 390px mobile screenshots inspected; mobile document width equals viewport width (390px). Existing seeded 100% assembly orders remain visibly distinct as awaiting testing, rework, and delivered.
- Focused helper checks cover latest non-cancelled selection and percentage boundaries. Sol6.1 independently reviewed the implementation; its percentage precision finding was fixed by multiplying before division. Reviewer confirmed 29/100=29, 57/100=57, 58/100=58, 999/1000=99, and 100/100=100. No material findings remain.
- Synthetic fixture DASH-VERIFY-1017 (b757fc92-fbfa-47b0-81ac-a54a63c26533) is archived and remains visible as required. Synthetic DASH-ORDER-1017 (003f5650-844b-4569-865e-dc5d56a8520e), on DEMO-MACHINE-03, was created, released, then cancelled with a verification cleanup reason. Seeded orders were not edited.
- Screenshots: output/playwright/dashboard-astra-desktop.png, dashboard-astra-dark.png, dashboard-astra-mobile.png. The initial refresh401 was the expected passwordless visitor fallback; no application console errors were observed after authentication. Assigned-only/no-order-access behavior was reviewed in source, not exercised with real restricted users.
- Idle-browser observation confirmed automatic requests to both machines and manufacturing-orders within 16 seconds. During the final container restart, two polling requests returned ERR_EMPTY_RESPONSE; after restart/reload the final Dashboard-BMscGZsh.js bundle loaded and all eight cards rendered successfully.
