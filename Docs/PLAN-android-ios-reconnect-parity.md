# Plan: Android iOS Reconnect Parity
> Status: In progress
> Last updated: 2026-03-22
> Scope: `AndroidClient/app`, `AndroidClient/core-transport`, Android reconnect recovery, stop recovery, and hydration guardrails

## Goal

Port the iOS reconnect and recovery behavior to Android as closely as possible so temporary relay drops recover with the same operator-facing semantics:

- recoverable drops keep the saved pairing
- reconnect intent is surfaced immediately
- active foreground sessions retry immediately without waiting for a fresh lifecycle bounce
- reconnect and foreground recovery preserve stop availability
- stale `thread/read` hydration does not overwrite live running output

## Confirmed Behavior Gap

### iOS reference behavior

- recoverable receive errors immediately set:
  - `shouldAutoReconnectOnForeground = true`
  - `connectionRecoveryState = .retrying(...)`
- the root view attempts reconnect on both:
  - app returning to `.active`
  - `shouldAutoReconnectOnForeground` flipping to `true` while already active
- reconnect messaging is separate from low-level transport socket state
- running-thread recovery follows this shape:
  - `thread/resume`
  - refresh in-flight turn state
  - if still running, force a fresh resume snapshot
  - request immediate sync
- history hydration skips stale merge while the thread is still active/running

### Android current behavior

- recoverable transport failure mostly becomes `Failed(non-permanent)`
- UI labels that state as `Retry queued`
- actual reconnect is only triggered automatically in a narrower subset of transitions
- root lifecycle currently retries on `ON_START`, but does not observe reconnect intent changes while already foregrounded
- hydration merge still runs through `applyThreadRead(...)` during active/running recovery paths

## Required Patch Points

### 1. Add app-level reconnect recovery state

Android needs an explicit recovery state equivalent to iOS `CodexConnectionRecoveryState`.

Required changes:

- add `RemodexConnectionRecoveryState`
- store it in `RemodexDebugUiState`
- stop deriving reconnect UI solely from `RemodexTransportState.Failed`
- keep low-level transport state and operator-facing reconnect state separate

Primary files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexUiFormatting.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/sidebar/RemodexSidebarConnectionPanel.kt`

### 2. Match iOS reconnect triggers

Android must consume reconnect intent the same way iOS does.

Required changes:

- trigger reconnect when app returns to foreground
- also trigger reconnect when reconnect intent flips on while the app is already foregrounded
- remove the current dependency on `previousState == Connected(isInitialized = true)` as the only immediate retry path

Primary files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`

### 3. Align reconnect loop semantics

Android reconnect should use the same operator-facing behavior as iOS.

Required changes:

- set retrying state immediately for recoverable failures
- preserve saved pairing unless the relay close is permanent
- clear retry state on success or terminal failure
- keep retry copy aligned with iOS:
  - timeout: `Connection timed out. Retrying...`
  - other recoverable drops: `Reconnecting...`

Primary files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`

### 4. Port stop recovery guardrails

Android stop behavior should remain recoverable after reconnect/foreground recovery.

Required changes:

- keep `resolveInterruptibleTurnId(...)` as the final authority when local turn id is stale
- preserve active turn rehydration during reconnect recovery
- keep thread-to-turn mappings refreshed when interrupt retries resolve a newer turn id

Primary files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`

### 5. Port hydration merge guard

Android must not overwrite live running rows with stale `thread/read` snapshots.

Required changes:

- update `applyThreadRead(...)` to refresh in-flight turn state first
- skip hydrated message merge while the refreshed thread is still active/running
- clear loading state without forcing the thread hydrated while the run is still active
- let the next non-running refresh perform the actual history merge

Primary files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationState.kt`

## Verification Targets

Android tests should explicitly cover:

- recoverable failure while backgrounded queues reconnect and succeeds on next foreground
- recoverable failure while already foregrounded retries immediately even if previous transport state was not initialized `Connected`
- retrying status copy is surfaced through UI state
- permanent relay closure clears saved pairing and stops retry intent
- running-thread hydration recovery does not replace live streaming rows with stale history
- stop fallback still resolves via `thread/read(includeTurns = true)` when local turn id is missing

Primary test file:

- `AndroidClient/app/src/test/java/app/remodex/android/RemodexDebugViewModelTests.kt`

## This Pass

- [x] Add Android reconnect recovery state
- [x] Wire foreground reconnect intent observer from root Compose shell
- [x] Broaden immediate retry trigger to match iOS semantics
- [x] Align retry messaging with iOS
- [x] Guard hydrated history merge during active running recovery
- [x] Add targeted Android tests for the new behavior
