# EasyPack branding

The user approved the text-free EasyPack symbol for both main and demo projects
on 2026-10-28. The active asset is
`frontend/public/assets/brand/easypack-symbol.png` (1449 × 1086, PNG with alpha).

This is the approved second ImageGen edit of the logo from
https://easypackegypt.com/. The edit request retained the upper gray/burgundy
emblem, removed EPE and the Arabic lettering, and requested transparency without
additional glow. It is an edited application asset, not an unmodified official
logo download. Both repositories contain the identical approved file.

`frontend/src/Brand.tsx` renders the symbol without a text lockup or white box.
`frontend/index.html` uses it for the browser and touch icons. All references use
`/assets/brand/`, supported by the backend's existing public static-asset handler.

Verification on 2026-10-28: the demo production build and translation audit
passed (908 UI strings). The asset matches the main project's approved PNG
SHA-256 `329192D274C15FD4AD9EA91CE9CD80C1857CF4F23A5BA27B6D76A0F1F38DF9F0`.
Source review confirmed proportional rendering, accessible alternative text,
and base-path-compatible URLs. Authenticated application flows and deployment
were not tested.
