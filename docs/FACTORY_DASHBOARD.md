# Factory dashboard, machine chat and designs

The demo landing page at `/` combines factory statistics with its existing
machine overview, quick-create machine/order actions and `/machines/:id` details.
Passwordless visitor entry and the demo banner remain unchanged. Statistics
require `reports.view`; machine cards require `machines.view`.

Visitors share one synthetic guest identity and its messages/read cursors.
Chat is shared demo content, not a private per-visitor conversation. File uploads
and deletion remain disabled for visitors; existing visuals can be viewed.
This update does not import main-project data or add sample design files.

## Dashboard

`GET /api/v1/factory-dashboard` requires `reports.view`. It returns all-time
order counts and status distribution, active machines, completed and delivered
orders, undelivered overdue orders, open defects, available and quarantined
pieces, running work sessions, unassigned tasks, shortages and assembly totals.
Shortages and assembly totals exclude draft, cancelled and completed orders.
Completed orders awaiting delivery remain eligible for overdue delivery counts.
Counts represent recorded workflow data, including open sessions that have not
been stopped; they do not measure physical machine utilization.

Machine cards use the existing machine and manufacturing-order endpoints.
Order visibility retains the existing full or assigned-order permission scope.
Factory statistics require the independent reporting permission. The cards can
be searched and filtered, and refresh every 15 seconds.

## Machine chat

Migration `V102__machine_chat.sql` creates persistent messages and per-user read
cursors. Chats are internal to authenticated users with `machines.view`;
this permission provides access to every machine chat. A chat is separate for
each physical machine, rather than each model or manufacturing order.

Endpoints:

- `GET /api/v1/machine-chat/unread`: unread counts grouped by machine, excluding
  messages sent by the current user.
- `GET /api/v1/machines/{id}/chat?before={messageId}`: up to 50 chronological
  messages from the newest matching page, plus `hasMore`.
- `POST /api/v1/machines/{id}/chat`: `{ "body": "..." }`, 1–2000 characters.
- `PUT /api/v1/machines/{id}/chat/read`: `{ "through": 123 }`. The cursor only
  advances and must reference a message from the same machine.

The interface polls every five seconds and loads older messages on demand.
Only an open, focused chat marks messages as read. Sending requires Bearer
authentication and current machine viewing permission; revocation applies to existing sessions.
Machine cards and the machine catalog both show the chat button and unread count.

## Designs

Machine cards, the machine catalog, the part catalog and BOM component rows
open the existing file service with the appropriate `MACHINE` or `PART` ID.
Uploads remain persisted in PostgreSQL, with server-side access checks,
uploader metadata, download, preview and version-checked removal. Multiple
design files can be attached. Files for a part definition are shared wherever
that part appears; there is no separate drawing for each stock serial number.

PDF, PNG and JPEG previews are supported along with native CAD storage and
STL/GLB browser viewing. See [Catalog designs](CATALOG_DESIGNS.md) for formats,
model inheritance and viewer limitations. Upload and removal require
`files.manage` in addition to target access; the demo visitor lacks this permission.

## Verification

Frontend: `npm run build` from `frontend/`, and
`node scripts/Check-Translations.mjs` from the project root.

Backend: run the existing dedicated-database integration suite. New cases in
`IdentityIT` cover dashboard counts and shortage rules; chat persistence,
machine isolation, unread counts, monotonic read cursors, pagination, input
validation, CSRF, anonymous access and live permission revocation. The suite
requires a disposable database explicitly named `easyback_test` and resets it.

Run `node scripts/Verify-Demo.mjs` against an isolated local demo instance.
It checks passwordless entry, seeded workflows, dashboard totals, machine-chat
persistence/isolation, visual API reads and denial of guest uploads, plus session
refresh and administrative restrictions. Never run it against a shared deployment.

The application must be rebuilt to apply the new migrations and serve the updated
frontend and backend together. No deployment is performed by merging this code locally.
