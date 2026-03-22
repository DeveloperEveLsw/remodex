# Checklist: Android Sidebar iOS Parity
> Status: Working checklist
> Last updated: 2026-03-22
> Companion plan: `Docs/PLAN-android-sidebar-ios-parity.md`

## Inputs

- [x] Confirm the current Android sidebar is structurally different from the iOS sidebar.
- [x] Identify the Android file concentration problem in `RemodexAndroidRoot.kt`.
- [x] Define a dedicated sidebar parity plan instead of hiding the work inside broader Android parity documents.

## Phase 1: Componentize The Sidebar

- [x] Create an Android sidebar component directory.
- [x] Extract sidebar project-choice and grouping helpers into a dedicated file.
- [x] Extract the drawer shell into a dedicated file.
- [x] Extract the header into a dedicated file.
- [x] Extract the search field into a dedicated file.
- [x] Extract the new-chat action into a dedicated file.
- [x] Extract the thread-list surface into a dedicated file.
- [x] Extract the thread-row surface into a dedicated file.
- [x] Extract the connection panel into a dedicated file.
- [x] Remove obsolete sidebar-only helpers from `RemodexAndroidRoot.kt`.

## Phase 2: Match The iOS Sidebar Hierarchy

- [x] Stop placing the settings action as wrapping text inside the title row.
- [x] Make the Android header stable on narrow widths.
- [x] Make search a real editable field with clear behavior.
- [x] Remove the generic `NavigationDrawerItem` look from the new-chat row.
- [x] Remove the generic `NavigationDrawerItem` look from thread rows.
- [x] Replace bulky run-state text pills with lighter indicators.
- [x] Add compact row metadata suitable for scanning.
- [x] Remove arbitrary per-project thread truncation from the drawer.

## Phase 3: Reduce Debug-Shell Weight

- [x] Make the connected-state bridge panel compact by default.
- [x] Keep deep pairing controls available without dominating the drawer.
- [x] Prevent long bridge labels and URLs from breaking the layout.
- [x] Preserve refresh, reconnect, and scanner access while reducing visual weight.

## Phase 4: Keep Future iOS Ports Cheap

- [x] Keep Android sidebar file names aligned with the iOS sidebar responsibilities.
- [x] Keep grouping and formatting logic outside composable rendering code.
- [x] Document deferred sidebar parity items that still exist on iOS only.
- [x] Leave clear landing spots for archive, rename, delete, and archived-chats parity.

## Current Iteration Sign-Off

- [x] Docs exist for the sidebar-specific parity effort.
- [x] Android sidebar rendering is no longer owned entirely by `RemodexAndroidRoot.kt`.
- [x] The Android drawer visibly feels closer to the iOS sidebar.
- [x] Search works on Android instead of acting as decorative placeholder UI.
- [x] Settings placement no longer causes the header to break on narrow screens.
