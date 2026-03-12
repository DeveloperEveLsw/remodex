# Plan: Android iOS Thread And UI Parity
> Status: Living document
> Last updated: 2026-03-13
> Scope: `AndroidClient/app`, Android conversation state, timeline streaming, thread hydration, and iOS UI parity

## Progress Snapshot

- Completed: Android per-thread conversation store now owns `activeThreadId`, `messagesByThread`, and revision tokens
- Completed: Android Compose timeline now renders from per-thread conversation state instead of `selectedMessages`
- Completed: Android incoming notification reducer now handles basic turn and assistant lifecycle events
- Completed: Android main conversation screen, onboarding landing, settings, action menu, and branch sheet now follow the current iOS visual direction
- Pending: `prepareThreadForDisplay` parity is still incomplete around viewed-state, `thread/resume`, hydration merge, and reconnect recovery
- Pending: sidebar unread/running/failed badge semantics are not wired yet
- Pending: Stop recovery and reconnect guardrails are not wired yet
- Pending: composer/runtime controls now partially drive real request state, but branch/git surfaces still do not follow the iOS `cwd -> git RPC -> host` flow
- Pending: remaining parity gaps are now primarily behavioral and state-machine related

## Goal

Port the original iOS conversation architecture and UI language to Android so the currently open thread updates immediately from incoming runtime events and the app visually matches the iOS product's information hierarchy, density, and interaction model.

## UI Reference Source

Use the local reference set in `/home/lws19/codex_android/remodex/UI ref` together with the chat-attached screenshots from 2026-03-13 as the current design source for Android parity work.

### Confirmed iOS UI traits from the reference set

- top bar is compact and low-chrome, with title, cwd subtitle, diff stats, and a small trailing action affordance
- overall visual weight is light, flat, and quiet; shadows and tinted surfaces are restrained
- assistant content reads more like a document/log stream than a chat-first messenger UI
- user messages remain compact right-aligned bubbles
- composer is a two-tier layout:
  - upper row for prompt entry, model, reasoning, send, and quick affordances
  - lower runtime bar for local/runtime/access/branch pills
- settings use section labels plus grouped rounded cards rather than Android-style utility panels
- branch picker and action menus use lightweight sheet/list presentation instead of heavy dropdown styling
- onboarding is clean and centered, with simple numbered steps and one dominant CTA

## Confirmed Current Gap

### iOS source behavior

- `TurnView.swift` renders directly from `codex.messages(for: thread.id)` and `codex.messageRevision(for: thread.id)`.
- `prepareThreadForDisplay(threadId:)` sets `activeThreadId`, marks the thread viewed, resumes the thread, hydrates history, and refreshes in-flight turn state.
- Incoming assistant events update per-thread state immediately through `beginAssistantMessage`, `appendAssistantDelta`, and `completeAssistantMessage`.
- Message mutations bump a thread-local revision token and refresh the active output cache.
- Unread threads use badge state; the currently viewed thread updates its timeline instead of waiting for a later reload.
- Branch state does not come from `thread/read` history or thread metadata. iOS resolves the selected thread's `cwd`, then uses `GitActionsService` to call `git/branchesWithStatus`, `git/status`, and `git/checkout` against the host bridge.

### Android current behavior

- `RemodexDebugViewModel` now keeps thread-local conversation state under `conversation.activeThreadId`, `messagesByThread`, and revision tokens.
- `transport.notifications` already feed `RemodexConversationReducer` and mutate the per-thread timeline.
- `selectThread()` sets the active thread immediately, but it still behaves like a thin `thread/read(includeTurns=true)` wrapper instead of full iOS `prepareThreadForDisplay(threadId:)`.
- `applyThreadRead(...)` still replaces thread history wholesale, so stale hydration can overwrite rows that were already created by live events.
- composer controls and runtime pills are now partially connected to Android request state, but branch UI still reads like a visual shell because it is not yet backed by the iOS-style git RPC flow.

## Required End State

Android must match these behavioral rules:

- Keep the selected thread as `activeThreadId`.
- Store timeline state in `messagesByThread[threadId]`.
- Merge incoming assistant lifecycle events directly into that per-thread timeline.
- Drive Compose from the store for the active thread, not from a copied snapshot.
- Increment a per-thread revision token on every timeline mutation.
- Use unread/running/failed badges only for threads the user is not actively viewing.
- Preserve iOS guardrails around reconnect, stop recovery, item-scoped assistant rows, and late-event reconciliation.

Android must also match these UI rules:

- preserve the iOS information hierarchy before introducing Android-specific decoration
- keep the conversation screen visually sparse and document-like
- separate composer controls into the same two-tier structure as iOS
- keep the top bar compact and avoid oversized Android-style headers
- keep menus, pickers, and settings grouped, lightweight, and close to the iOS layout
- avoid replacing iOS layout intent with generic Material defaults

## Non-Negotiable Porting Guardrails

- Do not reintroduce repo filtering in sidebar or content.
- Do not keep `selectedMessages` as the primary timeline source.
- Do not push event parsing logic into composables.
- Do not flatten assistant items into a single turn-level bubble.
- Do not use `thread/read` as the main real-time update mechanism for the visible thread.
- Do not clear pairing or local connection state prematurely during reconnect flows.
- Do not "Android-ify" the UI by defaulting to stock Material panel density where the iOS app is intentionally lighter.
- Do not collapse the composer into a single oversized card if the iOS design uses stacked control rows.
- Do not treat current Android shell styling as a parity target; the iOS reference set is the target.

## Target Android Architecture

### Store layer

Introduce a conversation store or service responsible for:

- `activeThreadId`
- `messagesByThread`
- `messageRevisionByThread`
- `activeTurnIdByThread`
- `threadIdByTurnId`
- `runningThreadIds`
- `readyThreadIds`
- `failedThreadIds`
- `hydratedThreadIds`
- thread loading and resume state

This layer owns all timeline mutations and all event-to-state reconciliation.

### UI layer

Compose should:

- read the selected thread id from the state holder
- derive `messagesFor(activeThreadId)` from the store
- use `messageRevisionByThread[activeThreadId]` as the invalidation token when projection or expensive mapping is needed
- keep badges and timeline rendering separate
- map screen sections to the iOS layout model:
  - compact title bar
  - lightly framed timeline
  - split composer
  - lightweight sheets/menus

### Visual language layer

The Android UI should explicitly preserve:

- lighter backgrounds and lower-contrast surfaces
- reduced shadow depth
- compact top and bottom bars
- runtime metadata as pills rather than large controls
- a timeline that feels like a working session transcript, not a consumer chat app

### Transport integration layer

The Android transport remains responsible for JSON-RPC connectivity only. A new reducer/router layer must interpret incoming notifications and translate them into store mutations.

## Workstreams

## 1. Establish The Android Conversation Store

Goal: replace snapshot-based UI state with per-thread source-of-truth state.

Tasks:

- add a dedicated Android conversation state holder under `AndroidClient/app`
- move thread selection into `activeThreadId`
- replace `selectedMessages` with `messagesByThread`
- add `messageRevisionByThread`
- add thread-local running and turn-id maps
- expose selectors like `messagesFor(threadId)` and `messageRevisionFor(threadId)`

Exit criteria:

- no UI path depends on `selectedMessages`
- all thread timelines are readable from one store
- timeline changes can invalidate only the affected thread

## 2. Port Thread Selection And Hydration Semantics

Goal: make Android selection behave like iOS `prepareThreadForDisplay(threadId:)`.

Tasks:

- on selection, set `activeThreadId` immediately
- mark the thread as viewed before async hydration completes
- call `thread/resume` for the selected thread when connected
- call `thread/read(includeTurns=true)` for initial history hydration
- refresh in-flight turn metadata after reconnect or foreground recovery
- avoid stale history overwriting live streaming rows

Exit criteria:

- opening a thread does not depend on a blocking full reload
- the visible thread can continue updating while hydration is in flight
- stop-state and running-state recover after reconnect

## 3. Port Incoming Assistant Event Handling

Goal: merge live runtime output directly into per-thread Android state.

Tasks:

- add an Android notification router for:
  - `turn/started`
  - `turn/completed`
  - `item/started`
  - `item/completed`
  - `item/agentMessage/delta`
- resolve `threadId`, `turnId`, and `itemId` from modern and legacy envelopes
- create message mutation APIs equivalent to:
  - `beginAssistantMessage`
  - `appendAssistantDelta`
  - `completeAssistantMessage`
- maintain `threadIdByTurnId` for incomplete payloads
- preserve a per-thread running fallback when `turn/started` lacks a usable `turnId`

Exit criteria:

- the active Android thread updates immediately during assistant streaming
- assistant completions finalize the existing bubble instead of adding duplicates
- turn lifecycle state no longer depends on a manual thread re-entry

## 4. Port Timeline Reconciliation Rules

Goal: preserve iOS ordering and row identity behavior.

Tasks:

- keep assistant rows item-scoped
- support multiple assistant items within one turn without flattening
- merge late reasoning deltas into existing rows
- ignore late turn-less activity if the turn is already inactive
- reconcile history snapshots with item-aware matching, not `turnId`-only matching

Exit criteria:

- no duplicate assistant bubbles from live + hydrated data
- no timeline reorder regressions during streaming
- no fake extra "Thinking..." rows from late deltas

## 5. Port Sidebar Badge And Viewed-State Rules

Goal: match iOS unread and run badge semantics.

Tasks:

- add `running`, `ready`, and `failed` badge state tracking
- clear badges when a thread becomes active
- mark completion badges only for non-visible threads
- keep the active thread's output in the timeline instead of showing it as unread

Exit criteria:

- active thread receives immediate timeline updates
- background threads surface run completion through badges
- no thread is simultaneously treated as viewed and unread

## 6. Port Stop And Reconnect Guardrails

Goal: preserve operator confidence during long-running turns and reconnects.

Tasks:

- if Stop is tapped and `activeTurnIdByThread` is missing, resolve via `thread/read(includeTurns=true)`
- rehydrate active turn state on reconnect and foreground recovery
- preserve reconnect behavior without clearing local pairing state too early
- suppress benign disconnect noise in Android logging/UI if Android adds similar transport callbacks later

Exit criteria:

- Stop remains visible or recoverable after reconnect
- running thread state survives background/foreground transitions
- the active thread does not silently lose interruptibility

## 7. Bind Runtime Controls And Secondary Surfaces

Goal: convert the current visual shells into real runtime controls without regressing the iOS layout work.

Tasks:

- bind composer model and reasoning chips to Android state instead of hardcoded labels
- surface actual runtime defaults in settings from host/runtime metadata where available
- wire access/runtime controls so the values shown in composer and settings reflect real request parameters
- resolve branch state from the selected thread `cwd`, not from thread metadata
- add Android git service calls equivalent to iOS `git/branchesWithStatus`, `git/status`, and `git/checkout`
- make the branch sheet/picker render real current/default/available branches returned by the host bridge
- make the action menu call real actions where the Android transport already supports them, and keep unsupported actions explicitly disabled instead of pretending they work
- keep onboarding CTA focused on local connection setup and QR pairing rather than a generic empty-state affordance

Exit criteria:

- the visible runtime values in composer, settings, and sheets come from state rather than hardcoded placeholders
- branch label, branch choices, and branch refresh behavior come from git RPC results scoped by the selected thread `cwd`
- unsupported actions are visually clear and not misleading
- the Android UI no longer suggests that model/reasoning/branch controls are interactive when they are not

## 8. Verification And Regression Coverage

Goal: make the port durable.

Tasks:

- add unit tests for conversation store mutation APIs
- add tests for event routing from notification payloads to per-thread messages
- add tests for thread selection and async hydration
- add tests for continuation thread switching after stale-thread recovery
- add tests for unread badge behavior on inactive threads
- add tests for reconnect and stop recovery
- add tests for history/live merge conflicts

Exit criteria:

- the Android port has deterministic coverage for the state machine, not just transport calls
- regressions in thread visibility or streaming merge behavior are caught without manual QA

## 9. Port Main Conversation UI To iOS Visual Parity

Goal: make the Android main conversation screen visually align with the iOS app now that the core state model is converging.

Tasks:

- restyle the top bar to match iOS density and spacing
- match title, cwd subtitle, diff stats, and trailing action placement
- reduce card/shadow weight across the conversation screen
- adjust timeline spacing and message presentation toward the iOS transcript-like look
- keep user bubbles compact and right-aligned
- ensure assistant rows feel document-like rather than oversized chat cards
- port the split composer layout:
  - prompt row
  - runtime pills row
- match the visual weight of send/model/reasoning/runtime controls
- preserve mobile usability on Android without changing the iOS hierarchy

Exit criteria:

- Android main conversation screen is recognizably the same product as the iOS reference
- top bar, timeline, and composer align with the iOS layout hierarchy
- no major Android-specific visual divergence remains in the primary conversation screen

## 10. Port Menus, Sheets, And Settings To iOS Visual Parity

Goal: make secondary surfaces feel consistent with the iOS app instead of the current Android shell.

Tasks:

- restyle settings groups to match iOS section labels and rounded grouped cards
- restyle branch picker to match iOS lightweight grouped list presentation
- restyle action menus to match iOS grouped sheet behavior
- keep icons, spacing, and dividers visually light
- align onboarding screen structure and emphasis with the iOS reference

Exit criteria:

- Android settings, branch picker, action menus, and onboarding no longer look like a separate product
- grouped cards, sheets, and CTA emphasis track closely to the iOS reference set

## Suggested Implementation Order

1. Finish `prepareThreadForDisplay` semantics and merge-safe hydration.
2. Add badge and viewed-state rules.
3. Add stop and reconnect recovery.
4. Bind runtime controls and secondary sheets to real state/actions, including iOS-style git branch sourcing via `cwd`.
5. Fill in reasoning, plan, structured input, and other non-chat timeline item parity.
6. Expand tests until the state machine and visual behavior are covered.

## Deferred Until Core Parity Lands

- visual polish beyond what is needed to support the new architecture or unblock misleading placeholder controls
- secondary timeline item types that are not required for immediate assistant streaming parity
- broad module reshuffles unrelated to conversation state

## Definition Of Done

This plan is complete only when all of the following are true:

- Android updates the visible thread timeline from incoming events with no manual reload.
- Android no longer relies on `selectedMessages` as the source of truth.
- Android preserves item-scoped assistant streaming and completion behavior.
- Android preserves unread badge semantics for non-visible threads.
- Android can recover active turn state after reconnect and still support Stop.
- Android no longer ships misleading placeholder runtime controls; visible composer/settings values map to real state and requests, and branch UI is sourced from host git RPC rather than thread metadata.
- Android main conversation UI, settings, onboarding, and lightweight sheets match the iOS reference set closely enough that they read as the same product.
- Automated tests cover the main state transitions and merge edge cases.

## Maintenance Rules

- Update `Progress Snapshot` whenever a workstream changes state.
- Add concrete file paths in this document when new Android store/reducer files are introduced.
- Mark partial parity explicitly; do not mark a workstream done until behavior matches iOS, not merely until code exists.
- If scope changes, update `Required End State` first and then adjust downstream tasks.
