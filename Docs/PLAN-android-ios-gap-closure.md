# Plan: Android iOS Gap Closure
> Status: Living document
> Last updated: 2026-03-21
> Companion recap: `Docs/RECAP-android-ios-gap-analysis.md`
> Companion checklist: `Docs/CHECKLIST-android-ios-gap-closure.md`

## Goal

Close the highest-value Android product gaps that still separate the app from the validated iOS runtime flow.

This plan is not a replacement for `Docs/PLAN-android-ios-thread-parity.md`. That plan covers the earlier thread, timeline, and UI-baseline port. This plan starts from the current Android baseline and focuses on the remaining product-completion gaps:

- interactive runtime loop parity
- composer and context parity
- workspace operation parity
- platform hardening and verification

## Progress Snapshot

- Completed: Android now has a working per-thread conversation store, timeline projection, reconnect recovery, thread hydration, branch status refresh, branch checkout, turn start, and turn interrupt
- Completed: Android no longer looks like a pure transport scaffold; the main remaining gaps are now product-level and workflow-level
- Pending: server-request handling for approvals and structured user input
- Pending: image attachments, file autocomplete, and skill autocomplete
- Pending: workspace operations beyond branch switching
- Pending: Android-equivalent secure storage, notifications, and higher-confidence UI verification

## Source Of Truth

Primary references for this plan:

- `Docs/RECAP-android-ios-gap-analysis.md`
- `CodexMobile/CodexMobile/Services`
- `CodexMobile/CodexMobile/Views/Turn`
- `CodexMobile/CodexMobile/Views/Sidebar`

## Non-Negotiable Guardrails

- Keep the repo local-first. Do not introduce hosted-service assumptions or remote-only workflows.
- Preserve the iOS bridge contract unless the bridge itself is intentionally changed.
- Do not solve Android parity by adding Android-only compatibility hacks that iOS does not need.
- Keep new logic in services/coordinators/state holders rather than growing `RemodexAndroidRoot.kt` and `RemodexDebugViewModel.kt` further.
- Prefer narrow, testable state transitions over UI-driven one-off conditionals.

## Current Gap Ranking

### 1. Interactive runtime loop

Highest priority because this blocks real work sessions when the runtime needs a response from the device.

Current Android gap:

- approval requests are not routed into accept/decline flows
- structured user input is rendered but not answerable
- server-initiated RPC is observed but not productized

Required end state:

- Android can accept, decline, or auto-approve runtime approval requests
- Android can submit structured user input answers
- these flows survive reconnect, thread switching, and active-thread changes

### 2. Composer and context pipeline

Second priority because Android is still effectively text-first while iOS supports richer turn payloads.

Current Android gap:

- no image attachment intake pipeline
- no file autocomplete via runtime search
- no skill autocomplete via runtime skill listing
- `turn/start` payload does not yet match the richer iOS path

Required end state:

- Android can send turns with attachments and skill mentions
- Android can surface file and skill suggestions from real runtime data
- the composer supports the same practical session flow as iOS

### 3. Workspace operations

Third priority because Android can inspect and switch branches, but not yet act like a full local workspace controller.

Current Android gap:

- no commit/push/pull/reset flow
- no revert preview/apply flow
- no compact-context action
- no archive/unarchive/rename/search flow parity

Required end state:

- Android supports the core local workspace actions already proven on iOS
- unsupported actions are explicit until fully wired

### 4. Hardening and verification

Fourth priority because these do not unblock core use, but they matter for release confidence.

Current Android gap:

- pairing storage is not in an encrypted store
- notifications are not wired
- no Android instrumentation coverage for highest-risk UI flows

Required end state:

- sensitive pairing data uses an Android-appropriate secure store
- run-completion notifications work when app is backgrounded
- high-risk interaction flows have stronger regression coverage

## Workstreams

## 1. Server Request Parity

Goal: make Android able to participate in server-driven runtime flows instead of only observing them.

Implementation targets:

- add a dedicated Android server-request state holder
- track pending approval request state
- track structured-user-input request state
- add response methods that call transport `sendResponse(...)`
- keep full-access auto-approve aligned with iOS

Suggested Android split:

- `RemodexServerRequestState.kt`
- `RemodexServerRequestReducer.kt`
- `RemodexServerRequestUi.kt`

Exit criteria:

- approval requests can be accepted or declined from Android
- structured user input can be answered and submitted
- no active flow depends on the user switching to iOS or desktop to continue

## 2. Composer Context Parity

Goal: make the Android composer capable of the same practical request shapes as iOS.

Implementation targets:

- add image attachment intake and preview state
- add `fuzzyFileSearch` integration for `@` mentions
- add `skills/list` integration for `$` mentions
- extend `turn/start` payload shaping for attachments and skill mentions
- preserve Android UI parity with current iOS composer structure

Suggested Android split:

- `RemodexComposerState.kt`
- `RemodexAttachmentPipeline.kt`
- `RemodexFileAutocompleteService.kt`
- `RemodexSkillAutocompleteService.kt`

Exit criteria:

- Android can send text + attachments
- Android can mention files and skills from real runtime data
- the composer no longer advertises capabilities it does not actually support

## 3. Workspace Operation Parity

Goal: move Android from branch control toward full local workspace control.

Implementation targets:

- add git commit/push/pull/reset/remote-url parity
- add revert preview/apply parity
- add compact-context action parity
- add archive/unarchive/rename/search parity

Suggested Android split:

- `RemodexGitActionsService.kt`
- `RemodexThreadManagementService.kt`
- `RemodexChangeSetService.kt`

Exit criteria:

- Android can perform the core local workspace operations already supported on iOS
- action menus reflect true capability state
- archive/search affordances are no longer placeholders

## 4. Hardening And Verification

Goal: improve Android security posture and confidence in release behavior.

Implementation targets:

- move relay pairing storage to encrypted storage
- wire local notification permission and run-completion routing
- add instrumentation or equivalent high-value UI verification
- add regression coverage for the new approval/composer/workspace flows

Exit criteria:

- pairing storage is no longer plain shared preferences
- background completion notifications work
- high-risk flows have stable regression protection

## Recommended Execution Order

1. Server request parity
2. Composer context parity
3. Workspace operation parity
4. Hardening and verification

Reasoning:

- phase 1 removes the most immediate workflow blocker
- phase 2 closes the biggest visible UX gap
- phase 3 expands product usefulness after the core loop is intact
- phase 4 hardens the result once the main workflows exist

## Refactor Expectations

This gap closure work should reduce concentration in these Android hotspots:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`
- `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt`

The goal is not cosmetic file splitting. The goal is to stop future parity work from becoming harder to verify because unrelated state machines live in the same files.

## Sign-Off Criteria

- Android can complete approval-gated and structured-input-driven turns without leaving the device
- Android composer supports the same practical prompt/context workflow as iOS
- Android exposes core local workspace actions beyond branch switching
- Android pairing and background behavior are hardened enough for release-quality usage
- new Android parity work is implemented in reusable services/state layers instead of extending existing hotspot files indiscriminately
