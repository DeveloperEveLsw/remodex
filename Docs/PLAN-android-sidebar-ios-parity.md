# Plan: Android Sidebar iOS Parity
> Status: Living document
> Last updated: 2026-03-22
> Companion checklist: `Docs/CHECKLIST-android-sidebar-ios-parity.md`
> Related plans: `Docs/PLAN-android-ios-thread-parity.md`, `Docs/PLAN-android-ios-gap-closure.md`

## Goal

Rebuild the Android side panel so it behaves and reads like the iOS product surface instead of a debug drawer.

This plan has two equal goals:

- match the iOS sidebar hierarchy and visual density closely enough that both apps feel like the same product
- reshape the Android file structure so future iOS sidebar features can be ported into parallel Android components instead of into `RemodexAndroidRoot.kt`

## Progress Snapshot

- Completed: sidebar-specific parity plan and checklist now exist
- Completed: Android sidebar rendering has been extracted into dedicated component files under `AndroidClient/app/src/main/java/app/remodex/android/sidebar`
- Completed: Android search is now a real editable field with live filtering
- Completed: Android new-chat row and thread rows no longer depend on generic drawer items
- Completed: the header/settings layout no longer depends on a wrapping text button
- Pending: archive, rename, delete, and archived-chats parity
- Pending: full relocation of bridge/pairing management into settings-level surfaces

## Why This Plan Exists

Current Android sidebar issues are structural, not cosmetic:

- navigation, local pairing, debug controls, and session diagnostics are mixed into one drawer surface
- `ModalNavigationDrawer` and `NavigationDrawerItem` impose a generic settings-drawer feel instead of a product-specific sidebar feel
- the sidebar code is embedded inside `RemodexAndroidRoot.kt`, which slows down parity work and encourages one-off UI logic
- the current search strip is decorative rather than functional
- row density, badge style, and action placement diverge from the iOS reference

## Source Of Truth

Primary implementation references:

- `CodexMobile/CodexMobile/Views/SidebarView.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarHeaderView.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarSearchField.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarNewChatButton.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarThreadListView.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarThreadRowView.swift`
- `CodexMobile/CodexMobile/Views/Sidebar/SidebarFloatingSettingsButton.swift`
- `CodexMobile/CodexMobile/Views/SettingsView.swift`

Android baseline references:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `Docs/RECAP-android-ios-gap-analysis.md`

## Non-Negotiable Guardrails

- Keep the repo local-first. Do not reintroduce hosted-service assumptions or production domains.
- Preserve cross-project new-chat flow. Do not regress to repo-scoped filtering.
- Keep connection state grounded in saved relay pairing and local host truth.
- Treat the sidebar as a product navigation surface first, not as a debug shell.
- Move shared sidebar logic into dedicated helpers/components instead of growing `RemodexAndroidRoot.kt`.
- Favor file names and responsibilities that mirror the iOS sidebar components where practical.

## Target Android File Layout

The Android sidebar should converge toward this structure:

- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarDrawer.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarHeader.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarSearchField.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarNewChatButton.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarThreadList.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarThreadRow.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarConnectionPanel.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarModels.kt`

Expected mapping to iOS:

- `SidebarView.swift` -> `RemodexSidebarDrawer.kt`
- `SidebarHeaderView.swift` -> `RemodexSidebarHeader.kt`
- `SidebarSearchField.swift` -> `RemodexSidebarSearchField.kt`
- `SidebarNewChatButton.swift` -> `RemodexSidebarNewChatButton.kt`
- `SidebarThreadListView.swift` -> `RemodexSidebarThreadList.kt`
- `SidebarThreadRowView.swift` -> `RemodexSidebarThreadRow.kt`
- `SidebarFloatingSettingsButton.swift` -> Android footer/settings action inside sidebar components

## Product End State

The Android drawer should feel like the iOS product in these ways:

- header is compact and stable on narrow widths
- search is real, editable, and clears cleanly
- new-chat affordance is lightweight and does not look like a generic settings row
- conversation rows are dense, scannable, and mostly icon-light
- run state is shown with subtle indicators rather than bulky text pills
- settings action is visually separated from the title row
- bridge controls are reduced to a compact product surface by default
- deep pairing/debug controls appear only when actually needed

## Workstreams

## 1. Sidebar Architecture Extraction

Goal: pull sidebar code out of `RemodexAndroidRoot.kt` into files that match the iOS breakdown.

Implementation targets:

- extract project choice and grouping helpers
- extract header, search, new-chat, row, list, and connection panel composables
- keep state ownership in `RemodexAndroidRoot.kt` only where orchestration is required
- reduce sidebar-specific imports and helper functions from the root file

Exit criteria:

- the root file no longer owns sidebar rendering details
- sidebar UI pieces can be iterated independently
- future iOS sidebar features have obvious Android landing files

## 2. Information Architecture Alignment

Goal: make the drawer prioritize conversation navigation over bridge diagnostics.

Implementation targets:

- remove header overflow cases on smaller widths
- move settings action out of the crowded title row
- make the bridge area compact by default
- keep manual pairing affordances available without dominating the drawer

Exit criteria:

- the drawer reads as conversations first, bridge second
- the connected state no longer exposes a full JSON/pairing shell by default
- the screen remains usable when translated labels get longer

## 3. List And Search Parity

Goal: make the Android conversation list behave like a real product list, not a placeholder.

Implementation targets:

- replace the static search strip with an editable field
- filter visible conversations by project, title, preview, and working directory
- remove arbitrary thread truncation in the drawer
- add compact relative-time metadata and lighter run-state indicators

Exit criteria:

- search actually filters conversations
- long-lived thread sets stay navigable
- row density matches the iOS reference direction

## 4. Future Portability

Goal: keep future iOS sidebar features easy to mirror on Android.

Implementation targets:

- keep naming aligned with iOS sidebar components
- isolate grouping and formatting helpers away from composables
- isolate connection-panel state mapping from the rendering layer
- document the intended Android file landing spot for future iOS sidebar work

Exit criteria:

- new sidebar features can be added to Android without reopening a monolithic root file
- parity work can be assigned by component instead of by one large screen file

## Execution Order

1. Create the sidebar plan and checklist.
2. Extract sidebar components into dedicated Android files.
3. Ship the first visible parity pass: header, search, new chat, thread rows, settings placement.
4. Compact the connected-state bridge panel.
5. Continue with archive, rename, delete, and other sidebar behavior parity from iOS.

## Current Iteration Scope

This iteration should land:

- sidebar plan and checklist docs
- Android sidebar component file structure
- a real search field and filtered group rendering
- lighter custom sidebar rows instead of generic drawer items
- a header/settings layout that no longer breaks on small widths

Deliberately deferred until the next pass:

- archive, rename, and delete behavior parity
- full bridge-control relocation into settings
- Android instrumentation coverage for the sidebar

## Sign-Off Standard

This plan is complete when:

- Android and iOS sidebars are recognizably the same product surface
- sidebar code is no longer concentrated in `RemodexAndroidRoot.kt`
- future iOS sidebar additions have a stable Android component target
