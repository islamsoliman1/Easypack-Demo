# Demo localization and compatibility review

Reviewed on 18 October 2026 by the Sol 6.1 reviewer; implementation by Astra.

## Decision

**Approved for the scoped bilingual demo release.** Sol 6.1 approves the reviewed source and locally verified combined application image `sha256:ebb0f7dcb15abdbc956cca3c0c866c9c11d5e67d023f20ad70f0d51e50fe05b3`. The completed regression, fresh initialization, preserved-data upgrade, localized API/CSV/security checks and targeted browser acceptance checks passed. No unresolved demo blocker remains from this review.

This approves demo interface localization and immutable reviewed content within the documented limits. It does not claim exhaustive end-to-end coverage, bilingual main implementation, arbitrary-text translation or paired-language authoring. No existing database reset, permission relaxation or main-application deployment was used to obtain a clean result. Public-host HTTPS/networking remains a deployment-environment check.

## Scope and plan alignment

The reviewed implementation is the standalone `E:/FreelanceProjects/Easypackjava/demo` application. The active main application is `E:/FreelanceProjects/EasyPack/EasyBackBackEnd/Easypackjava`; the neighboring .NET/Angular prototype is explicitly marked legacy in its README.

The demo now has Arabic/English interface catalogs, a persisted per-browser toggle, RTL/LTR document direction, live display formatting, request-specific API language, localized errors and CSV output, and reviewed translations for demo/system/notification content. The toggle is also available on bootstrap/error screens and inside native modal dialogs.

The broader paired-column and bilingual-authoring stages in `LOCALIZATION_PLAN.md` remain proposals. This implementation uses immutable reviewed dictionaries, not mutable global translations or rewritten database text. Arbitrary newly authored text, personal names and uploaded file contents are not automatically translated. Missing reviewed translations preserve the original and are explained in the interface and API metadata. English edit dialogs show reviewed previews while editing original stored text.

## API, state and historical data

Backend localization is request-scoped and resolves weighted `Accept-Language` values to `ar` or `en`, with Arabic fallback. API responses include `Content-Language` and vary by language. Controller and security-filter errors retain their existing status and error codes.

An explicit human-field allowlist projects localized display values and adds per-object `originalText` and `translationMissing`. Identifiers, codes, serials, enum values, permissions, dates, quantities and attachment filenames retain their machine contracts. The frontend restores originals before caching or editing, so an English display value is not silently submitted over its Arabic source. Nested inspection checks and historical snapshots use the same source-preserving contract.

Queries include language while preserving invalidation prefixes. During a language-only refetch, the same base query can retain normalized original data to keep open forms mounted; display translation reads the current language. Different record keys do not reuse that placeholder. Modal state, route state and authored field values remain separate from the language preference.

Historical translations are keyed to recorded source wording rather than a current catalog lookup. A later catalog rename cannot replace an earlier snapshot's original wording. CSV preserves UTF-8 BOM, escaping/formula protection, exact numerical values and existing UTC timestamp semantics; on-screen date formatting remains in Africa/Cairo.

## Main-project and schema compatibility

- All shared V1–V12 migration files are byte-identical between main and demo. Applied baseline migrations were not edited.
- Backend `pom.xml` is byte-identical: Java 21 and Spring Boot 4.0.6, including the managed Jackson 3.1.2 baseline. Frontend localization adds i18next/react-i18next to the existing React/Vite stack.
- Demo V100 remains its installation marker. Notifications use additive V101 in the demo, while main has its own V13 notification history. These database histories are not interchangeable.
- Localization requires no schema migration, translation write endpoint, original-field rewrite or data backfill.
- Main and demo use separate compose project names, databases, volumes and ports. The main source/database/application was not changed or deployed by this localization work.

The demo deliberately uses its shared visitor account, disables account/password/permission editing and file management for that visitor, and preserves the server's workflow authorization. Notification examples and read state are shared among visitors; language remains a browser preference. Notifications are now integrated with bilingual bell/inbox screens and specific task/preparation links, with unavailable-state handling and existing permission checks.

## Material issues corrected during review

- Replaced boxed `Long` reference comparison in template version checks with value comparison, matching main.
- Removed obsolete collection limits that rejected a valid set of all 51 supported permissions.
- Restored the same-worker assignment no-op, avoiding an unintended timer close/task reset.
- Aligned file metadata queries with main so metadata retrieval does not load binary file contents.
- Added the existing machine detail page to the server's UUID-constrained SPA deep-link allowlist.
- Corrected language-key handling, same-record placeholders and modal-accessible toggles to preserve unsaved forms.
- Corrected embedded record-name translations, joined weekday labels, direct bilingual searches, display spacing, error/status coverage and notification catalog wiring.
- Fixed the response-advice serializer regression: returning `List<Map>` for a declared `List<DTO>` retained the DTO serializer and caused cast failures. The advice now returns an explicit JSON tree. Real MVC tests cover typed lists, individual records and nested records/lists.
- Verified that the localization conversion preserves a high-precision `BigDecimal` and `Long.MAX_VALUE` on the wire. No speculative numerical conversion change was made.
- Fixed asynchronous select hydration in demo order and machine forms: displayed IDs follow original form state as options arrive, registered handlers remain intact, and changing a machine clears the dependent BOM. Final browser checks passed.

## Verification evidence and current limit

Completed checks:

- Recursive catalog audit passed for 906 UI keys and 56 reviewed seed strings, including frontend/backend system and notification catalog parity and interpolation checks.
- Demo frontend production build passed; the combined application image built successfully.
- The complete isolated backend run passed 31 unit/security/localization tests and 91 `IdentityIT` database integration tests, with zero failures/errors/skips.
- Targeted real Jackson/MVC tests passed for typed list/record/nested serialization and Arabic/English responses. A later test-only extension also passed exact raw numeric-token checks for `999999999999999999.123456` and `9223372036854775807` in both languages.
- Main baseline frontend build and 25 unit/security tests passed. Main database integration tests were not run as part of that baseline check.

An earlier run of 91 integration tests recorded 67 failures caused by the typed-list serializer defect. That failure was investigated and fixed; it is not presented as a successful test run. The corrected full integration rerun progressed without the recorded serializer-cast failures, but it did **not** produce a completed 91-test result.

The corrected rerun was interrupted by host storage failure: C: had approximately 18 MB free, Docker VM logs recorded buffer-I/O/EXT4 write errors around the same time as the test JVM's `ClassFormatError` and closed database connections, and Docker inspection/start commands stopped responding. This evidence establishes an infrastructure interruption; it does not establish an integration pass or exclude every possible application defect.

Before any live upgrade, the verification owner saved `backups/easypack-before-bilingual-20261018.dump` and V100 row baselines. At capture, the existing demo contained 5 models, 8 machines, 8 orders and 23 tasks. No live upgrade was performed before the interruption.

After recovery, `demo/.local/localization-recovery-regression.log` records 31 unit tests and 91 `IdentityIT` database integration tests, all with zero failures/errors/skips, and `BUILD SUCCESS` at 2026-10-18T10:23:47Z. This supersedes the interrupted run as integration evidence; it does not by itself verify the live demo upgrade or browser behavior.

The remaining release gates were subsequently completed as recorded below and in `VERIFICATION.md`.

The first fresh-demo startup after recovery exposed damaged local application/base-image runtime layers: Java failed with an exec-format error and `libselinux.so.1` was reported as too short. Re-pulling the base image reused the affected local blobs. No live upgrade occurred during that failed attempt. The regression used a separate verifier image. Runtime-image recovery and a successful clean rebuild resolved this startup blocker; the final verified image is identified in the decision above.

Subsequent browser verification found a concrete form defect: an existing draft's machine, BOM and responsible-person IDs remained in React Hook Form state, but their native selects displayed empty placeholders after asynchronous options arrived. This could mislead users and block required-field submission. Release approval was withheld until the demo fix and final-image browser checks passed.

The narrow demo correction is source-approved: controlled values follow the original form IDs while registered handlers remain intact, and changing the machine clears its dependent BOM. The machine model select received the same correction. Independent source inspection confirms the corresponding uncontrolled asynchronous selects still exist in active main; that is a source finding, not a reproduced main runtime failure. Main remains unchanged.

Following image recovery, fresh initialization through all 14 migrations, 306 fresh-demo and 320 upgraded-demo bilingual API assertions, and exact preservation of the V100 baseline hashes for eight orders, eight machines and 23 tasks passed. Final post-test comparisons remain identical. Catalog, order, machine and operations forms retained source/unsaved values across language toggles; corrected async selectors showed their original IDs. All five notification target kinds, notification restart identity/read-state preservation, independent browser locales, persistence, deep links, themes and loaded 390px Arabic/English layouts passed targeted checks. Tables scroll within their components without document overflow. Order payload verification used an intercepted PUT with an intentional 422 and did not persist an existing-order edit. Detailed evidence and limitations are recorded in `VERIFICATION.md`.
