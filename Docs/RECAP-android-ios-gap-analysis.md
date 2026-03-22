# Recap: Android iOS Gap Analysis
> Generated: 2026-03-21
> Scope: `AndroidClient` current implementation vs validated iOS logic in `CodexMobile`

---

## Summary

Android is no longer a thin scaffold. It already covers relay connection, reconnect recovery, thread selection, thread hydration, timeline projection, branch status refresh, branch checkout, turn start, and turn interrupt. The biggest remaining gap is not basic transport. The biggest gap is the interactive runtime loop that lets a real coding session finish end-to-end on device.

In priority order, the main missing areas are:

1. server-request handling parity for approvals and structured user input
2. composer/context pipeline parity for attachments, file mentions, and skill mentions
3. workspace operation parity beyond branch switching
4. product hardening around secure storage, notifications, and Android-side validation coverage

---

## What Android Already Has

- foreground reconnect and thread-state recovery are present in the Android view model
- selected-thread hydration already merges `thread/read` history into local conversation state
- turn lifecycle and streaming deltas already flow through a reducer-driven conversation store
- branch state is already sourced from host git RPC instead of stale thread metadata

Key references:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt:245`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt:868`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationState.kt:287`
- `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt:322`
- `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt:638`

---

## Highest Priority Gaps

### 1. Interactive runtime loop is still incomplete

This is the highest-priority gap because it blocks real sessions under `On-Request` access mode and plan-driven flows.

Android today:

- exposes access-mode choices in UI
- receives server-initiated RPC requests
- only records the last request method instead of routing it into approval or answer flows
- renders structured input cards but does not submit answers back to the runtime

Evidence:

- Android access mode UI: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:2362`
- Android server-request handling only records method: `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt:217`
- Android structured-input block is display-only: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:3351`

Validated iOS behavior:

- routes `item/tool/requestUserInput` and approval requests from inbound server RPC
- auto-approves in full-access mode or surfaces pending approval in UI
- sends accept/decline responses and structured-input answers back through RPC

Evidence:

- iOS server request routing: `CodexMobile/CodexMobile/Services/CodexService+Incoming.swift:59`
- iOS approval and structured-input responses: `CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift:339`
- iOS structured-input submit path: `CodexMobile/CodexMobile/Views/Turn/TurnPlanModeComponents.swift:62`

Impact:

- Android can connect and stream, but complex turns can still dead-end when the runtime asks for approval or structured answers.

### 2. Composer and context pipeline parity is still shallow

This is the next biggest product gap because Android currently behaves like a text-first shell, while iOS supports the richer session loop the bridge already expects.

Android today:

- `turn/start` request building is effectively text-only
- composer hints `@` files and `$` skills, but there is no parity path for fuzzy file search or live skill listing
- the add/attachment affordance is present in UI, but no image attachment pipeline is wired

Evidence:

- Android text-only `turn/start` payload shape: `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt:1254`
- Android composer affordance only: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:2715`
- Android add button with no attachment workflow: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:2729`

Validated iOS behavior:

- `startTurn` supports attachments and skill mentions
- file autocomplete uses `fuzzyFileSearch`
- skill autocomplete uses `skills/list`
- camera and photo-library intake feed the composer attachment pipeline

Evidence:

- iOS start-turn attachment and skill support: `CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift:75`
- iOS fuzzy file search: `CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift:248`
- iOS skills list: `CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift:294`
- iOS camera/photo attachment pipeline: `CodexMobile/CodexMobile/Views/Turn/TurnViewModel.swift:520`

Impact:

- Android can run basic chat turns, but it cannot yet match the validated iOS workflow for image-assisted prompts, file mentions, or skill-assisted prompting.

### 3. Workspace operation parity is still narrow

Android already covers branch status and checkout, but iOS has a broader local-workspace control surface.

Android today:

- transport directly supports `git/status`, `git/branchesWithStatus`, and `git/checkout`
- there is no Android parity layer yet for commit, push, pull, reset-to-remote, revert-patch, compact-context, archive/unarchive, rename, or real search
- archived chats in settings are still informational rather than operational
- sidebar search is currently decorative text, not live filtering

Evidence:

- Android implemented git surface: `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt:322`
- Android settings archived-chats placeholder: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:1587`
- Android sidebar search placeholder: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:637`

Validated iOS behavior:

- git commit, push, pull, reset-to-remote, remote-url, and branch listings are wired
- revert preview/apply exists for assistant change-set rollback
- thread archive, unarchive, and rename are wired

Evidence:

- iOS git actions: `CodexMobile/CodexMobile/Services/GitActionsService.swift:58`
- iOS revert patch flow: `CodexMobile/CodexMobile/Services/CodexService+AIChangeSets.swift:138`
- iOS archive/unarchive/rename flows: `CodexMobile/CodexMobile/Services/CodexService+Sync.swift:299`

Impact:

- Android is good enough for observing and continuing a thread, but still incomplete as a full local workspace controller.

### 4. Hardening and platform-complete behavior still lag iOS

These are not the first features to port, but they matter for stability and release readiness.

Android today:

- relay pairing is stored in `SharedPreferences`
- notifications are explicitly noted as future work in the settings UI
- there is unit coverage, but no Android `androidTest` instrumentation layer in the app module

Evidence:

- Android relay session storage: `AndroidClient/app/src/main/java/app/remodex/android/RemodexRelaySessionStore.kt:14`
- Android notifications still pending: `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt:1607`
- no Android instrumentation test tree under `AndroidClient/app/src/androidTest`

Validated iOS behavior:

- relay pairing is stored in Keychain
- notification permission, background completion alerts, and notification routing are wired

Evidence:

- iOS secure storage: `CodexMobile/CodexMobile/Services/SecureStore.swift:15`
- iOS notification flow: `CodexMobile/CodexMobile/Services/CodexService+Notifications.swift:92`

Impact:

- Android may be functionally correct for active foreground use but is still behind iOS on security posture and background-product behavior.

---

## Stability Risk Observations

Android has also accumulated a structural risk: too much behavior is concentrated in a small number of very large files.

Notable hotspots:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`
- `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt`

This does not mean the current logic is wrong. It means the next parity pass will get harder to validate if approvals, attachment intake, autocomplete, git actions, and archive/search flows are layered onto the same files instead of being split into service/coordinator style modules like iOS.

---

## Recommended Port Order

### Phase 1: unblock real runtime sessions

- implement Android server-request routing for approvals and structured user input
- add UI state and RPC response paths for accept, decline, and structured answers
- keep full-access auto-approve behavior aligned with iOS

### Phase 2: close the composer parity gap

- extend Android `turn/start` payload construction to support attachments and skill mentions
- implement file autocomplete via `fuzzyFileSearch`
- implement skill autocomplete via `skills/list`
- add local image attachment intake and preview flow

### Phase 3: expand workspace control parity

- port git commit, push, pull, reset, and remote-url flows
- port revert preview/apply
- port compact-context
- implement archive, unarchive, rename, and live conversation search

### Phase 4: harden release readiness

- move relay pairing storage to an encrypted store equivalent on Android
- add notification permission and run-completion notification routing
- add Android instrumentation coverage for the highest-risk UI flows

---

## Bottom Line

Android is already credible in connection, recovery, and timeline basics. The current weakest point is the missing interactive runtime loop, followed by the incomplete composer/context surface. If the goal is to make Android feel like the validated iOS app rather than a debug client, those two areas should be treated as the immediate next milestone.

---

## Verification Notes

- This recap is based on repository code inspection only.
- No Android emulator run, device run, or build verification was performed for this document.
