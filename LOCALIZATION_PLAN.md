# Arabic and English localization plan

## Implementation review — 17 October 2026

The implemented demo uses complete Arabic/English UI catalogs and a reviewed, immutable dictionary for known demo content. Request-scoped backend projection returns localized display fields plus `originalText` and `translationMissing` metadata. The frontend restores original values into form/business state and translates only at display boundaries, preserving source data and historical snapshots. English edit dialogs include a reviewed preview and clearly identify original-language fields.

The broader bilingual-authoring design below remains a future proposal: paired language database columns, arbitrary user-content translation authoring, and a versioned data backfill have **not** been implemented. No migration or original-data rewrite is necessary for the current immutable dictionary. Missing translations retain the original with explicit fallback information. See `LOCALIZATION_STATUS.md` for the shipped contract and remaining limits.

Compatibility scope: this isolated `demo` began from the older V1–V12/V100 snapshot. The active main Java application is `E:/FreelanceProjects/EasyPack/EasyBackBackEnd/Easypackjava`. A separate chat supplied notifications and a V101 migration; the demo now integrates its bilingual bell/inbox and task/preparation notification targets. Combined verification must include this additive migration. Localization changes are demo-only; they are not an automatic migration or synchronization of the main application. Neighboring main and legacy applications remain unchanged by this localization work.

## Scope and intended behavior

This plan targets the standalone EasyPack application in `E:/FreelanceProjects/Easypackjava/demo`: the React/Vite frontend, Spring Boot backend, and PostgreSQL database. Other Angular/.NET and Java copies in neighboring workspace folders are separate applications and are not included in this implementation scope.

Add a visible language button beside the theme button in the top bar. In Arabic, it offers **English**; in English, it offers **العربية**. The choice updates interface text, translated record content, layout direction, formatting, validation messages, reports, and exports. It persists after refresh and works for the anonymous demo visitor.

Use Arabic as the initial default to preserve the current experience. Support only `ar` and `en` initially; use `ar-EG` and `en-GB` for display formatting. Keep the existing business timezone, Africa/Cairo, and keep API dates, identifiers, enum values, and numeric values in their existing machine-readable formats.

## Original baseline findings

- `frontend/index.html` hardcodes `lang="ar"` and `dir="rtl"`.
- `frontend/src/main.tsx`, shared components, and feature pages contain Arabic text directly in JSX, validation schemas, permission labels, and notices. No localization library is currently installed.
- `frontend/src/api.ts` sends no selected-language header. React Query keys currently omit language.
- `frontend/src/ui.tsx` maps backend error codes to Arabic messages. Backend `ApiErrors` and security handlers currently return English messages.
- Multiple pages instantiate date/number formatters with a fixed locale, sometimes at module scope. Some directional icons and content containers explicitly assume RTL.
- Most CSS already uses logical properties such as `padding-inline-start` and `text-align: start`, which provides a useful foundation for LTR support.
- Database text fields generally hold one language. BOM items, order requirements, manufacturing orders, and operation configurations also contain historical name snapshots. Inspection checks exist both as template JSON and trial records.
- Report CSV headers are currently English, and export rows include raw status codes. Demo content is created in Java by `DemoBootstrap`; `V100__demo_installation.sql` creates the seed marker rather than the actual records.

## 1. Establish the translation contract

Inventory every user-visible field and interface string, grouped by feature. Agree a consistent Arabic/English factory glossary for terms such as machine model, BOM, preparation, assembly, defect, and trial.

Use three content rules:

| Content | Approach |
| --- | --- |
| Interface labels, statuses, permissions, units, errors | Translation keys in frontend/backend message catalogs |
| Names, descriptions, locations, order titles, check labels/criteria, notes and reasons | Stored Arabic and English text variants |
| Codes, serial numbers, email addresses, UUIDs, file contents, measurements | Preserve values; localize surrounding labels and display formatting |

People's names and brands remain as entered unless an explicitly authored alternate display name exists. File metadata can be localized; translating uploaded PDF/image contents requires a separate document workflow.

The toggle selects an existing translation. Existing content therefore needs a reviewed backfill, and new translatable content needs bilingual authoring. Missing translations fall back to the original text, with a translation-missing indication where useful. Do not present copied Arabic text as a completed English translation. Automatic translation of arbitrary future notes would require a separate provider and review workflow; it is not a prerequisite for this implementation.

## 2. Add bilingual storage and migrate existing content

Add explicit nullable language columns for translatable relational fields, for example `name_ar`, `name_en`, `description_ar`, and `description_en`. Retain original fields during the compatibility period. Backfill text into its actual source language rather than assuming every stored value is Arabic.

Cover:

- Machine models, parts, machines, locations, and descriptive notes.
- Role names/descriptions and the demo visitor's display label.
- Order titles, notes, cancellation reasons, and stored machine/model names.
- BOM notes and part-name snapshots; order requirement notes and part-name snapshots.
- Stock/preparation/raw-material notes and human-readable references where applicable.
- Assembly installation notes, removal reasons, event notes, defect reasons, and repair/scrap notes.
- Shift/day reasons and work-session reasons.
- Operation trial start/result/resolution/delivery notes and configuration part-name snapshots.
- Inspection template names, bilingual labels/criteria in JSON, and corresponding trial check records/notes. Keep check identity and order independent of the displayed wording.

Store both language snapshots when publishing/copying a BOM, creating order requirements, or starting a trial. Later catalog/template renames must not change historical wording. Add reviewed translations to old snapshots based on the snapshot text itself; preserve the original recorded text.

Use additive Flyway migrations at the next unused demo version after checking all migration locations. The implemented notification feature already occupies V101; a future bilingual-storage migration must use a later unused version. Do not edit applied migrations or reset the database.

Update `DemoBootstrap` to create bilingual content on fresh installations. For already seeded databases, add an idempotent, versioned backfill that runs after schema migration and targets known demo records. Preserve visitor edits: only apply reviewed translations when the stored source still matches the expected seed text. Report unmatched or user-created records as pending translation rather than overwriting them.

## 3. Localize the backend and API

Resolve each request language from `Accept-Language`, normalize regional variants to `ar`/`en`, and fall back to Arabic for missing or unsupported values. Use request-scoped locale resolution; never store the selected language in a shared global variable or shared demo account preference.

Add a common localized-text mapper. Keep existing display properties such as `name`, `partName`, and `title`, but resolve their values for the request language. Return authored variants to authorized edit forms using an additive structure such as:

```json
{
  "id": "same-record-id",
  "code": "PART-001",
  "name": "Mounting bracket",
  "translations": {
    "ar": { "name": "حامل تثبيت" },
    "en": { "name": "Mounting bracket" }
  }
}
```

Update input DTOs and persistence to accept explicit language variants. Do not infer the write language solely from the toggle or save a localized response value over its original counterpart. Legacy requests remain accepted during migration; missing translation properties preserve stored translations, and explicit removal follows a documented rule. Apply current validation limits, permissions, transactions, and optimistic version checks to translation edits.

Search translatable names/titles in both languages while continuing to match codes and serial numbers. Make ordering predictable and keep filtering/status contracts language-independent.

Add Spring message bundles for Arabic and English. Preserve existing error `code` and HTTP status values while localizing `message`, including validation and security-filter errors that bypass controller advice. Set `Content-Language`; where responses can be cached, vary them by `Accept-Language` while preserving existing private/no-store policies.

Localize report titles, CSV headers, display status labels, and human-readable content. Preserve UTF-8 BOM, CSV escaping, numeric precision, and the existing explicitly UTC timestamp semantics; language switching must not change timestamps or calculations.

## 4. Add frontend language infrastructure and the toggle

Introduce `i18next` and `react-i18next`, with typed keys and catalogs organized by feature: common, navigation, administration, catalog, BOMs, orders, warehouse, assembly, defects, operations, shifts, reports, files, and errors.

Initialize the selected language before the first render using a guarded `easypack-language` localStorage setting, with Arabic fallback. Set the document `lang`, `dir`, and localized page title immediately to avoid a flash of the wrong direction. Add an accessible language button to the top bar and the bootstrap/error screen so switching is available even if initial loading fails. Keep it reachable on mobile and in both themes.

Send the selected language through the shared API client, including session bootstrap, refresh, binary exports, and retries. Keep CSRF and authentication behavior intact.

Append locale to query keys for language-dependent responses while preserving current invalidation prefixes, for example `['warehouse', 'raw', 'balances', language]`. Prevent previous-language placeholder data or late responses from appearing as current-language content. Include localized session/user display information in the update flow. Keep the current route, filters, modal, form values, and session when the language changes.

Add shared date, number, duration, unit, and plural helpers. Rebuild locale-dependent validation messages or store message keys and render them in the active language; also translate already visible errors/notices after switching. Preserve browser-compatible date/number input serialization and existing timezone conversions.

## 5. Translate every feature and support bilingual editing

Start with shared UI and one complete catalog flow as a pilot, then apply the same contract to all modules:

1. App shell, demo/bootstrap states, theme labels, users/roles/permissions, and shared loading/error/dialog/table controls.
2. Models, parts, machines, catalog forms, and model/machine BOMs.
3. Manufacturing orders, details, history, and related selectors.
4. Warehouse, preparations, raw stock, dimensions, cutting previews, and movements.
5. Assembly tasks, installations, work time, defects, and repairs/scrap.
6. Operations, templates/checks, trial histories, completion/delivery, and shifts.
7. Dashboard/reports, print views, CSV exports, and attachment metadata/UI.

Translate headings, action buttons, placeholders, tooltips, accessibility labels, empty states, confirmations, validation, notices, status badges, and pluralized counts. Replace module-level Arabic label maps and fixed-locale formatters.

Use explicit Arabic/English fields or tabs for editing translatable values. Both variants stay in form state when switching the display language, and saving must preserve the variant the user did not edit. Both variants are required for records claimed to have complete bilingual coverage; incomplete legacy content remains usable with visible fallback.

Audit LTR/RTL layouts, back/next icons, alignment, truncation, forms, modals, tables, and printed reports. Use logical CSS and intentional direction overrides. Keep codes, serials, dimensions, emails, and numeric inputs readable in both directions; use automatic direction/isolation for mixed-language free text.

## 6. Verify and release

Add meaningful checks for:

- Translation key parity and complete coverage of enums, permissions, and API error codes.
- Locale normalization, fallback, localized errors, and independent Arabic/English requests from different visitors.
- Bilingual create/edit/read, preservation of the other variant, search in either language, and optimistic locking.
- Historical snapshot integrity after later catalog/template edits.
- A fresh database and upgrade of an existing seeded database, including user-edited seed records and restart idempotency.
- Browser switching on every route, deep-link reload, storage persistence/failure fallback, open forms, validation errors, slow requests, and repeated toggling.
- Desktop/mobile layouts, light/dark themes, keyboard access, Arabic plurals, print output, and CSV readability/content in each language.

Run the frontend production build, existing backend unit/security tests, appropriate database integration tests, and an extended demo API verification script. Test the combined Docker application to verify session bootstrap, API headers, persisted data, and exports together.

Apply schema expansion and backend compatibility first, deploy the frontend with the toggle once feature coverage is complete, and finish/review the content backfill before declaring full bilingual coverage. Take a database backup before the upgrade; application rollback should leave additive translation data intact.

Acceptance criteria:

- Arabic remains complete; English covers every interface feature and all reviewed demo content.
- Toggling updates labels and translated record values without changing record IDs, codes, permissions, quantities, workflow status, or calculation results.
- Saved language survives refresh and deep links; users retain current session and unsaved form values.
- Both languages work for reports, CSV exports, errors, search, and historical records.
- Missing translations are explicit and never cause blank values or lost original text.

## Implementation references

- [react-i18next useTranslation and language switching](https://react.i18next.com/latest/usetranslation-hook)
- [i18next fallback rules](https://www.i18next.com/principles/fallback)
- [Spring MVC locale resolution](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/localeresolver.html)
- [TanStack Query keys and request dependencies](https://tanstack.com/query/latest/docs/framework/react/guides/query-keys)
