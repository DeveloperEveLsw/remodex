# Plan: Android iOS Thread And UI Parity
> Status: Living document
> Last updated: 2026-03-13
> Scope: `AndroidClient/app`, Android conversation state, timeline streaming, thread hydration, and iOS UI parity

## Progress Snapshot

- In progress: architecture gap confirmed between iOS service-driven timeline state and Android snapshot-driven UI state
- In progress: Android per-thread conversation store now owns `activeThreadId`, `messagesByThread`, and revision tokens
- In progress: Android Compose timeline now renders from per-thread conversation state instead of `selectedMessages`
- In progress: Android incoming notification reducer now handles basic turn and assistant lifecycle events
- In progress: iOS UI reference set received and should drive the next Android visual pass
- In progress: Android main conversation screen has been restyled toward the iOS top bar, transcript, and split-composer layout
- Pending: reconnect, unread badge, and stop-state parity with iOS guardrails
- Pending: settings, menus, onboarding, and remaining secondary surfaces still diverge visually from iOS

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

### Android current behavior

- `RemodexDebugViewModel` stores `selectedMessages` inside UI state.
- `selectThread()` fills `selectedMessages` only from `thread/read(includeTurns=true)`.
- `transport.notifications` are currently recorded only as `lastNotificationMethod`; they do not mutate timeline state.
- Compose timeline rendering reads `uiState.selectedMessages`, not a per-thread source of truth.

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

## 7. Verification And Regression Coverage

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

## 8. Port Main Conversation UI To iOS Visual Parity

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

## 9. Port Menus, Sheets, And Settings To iOS Visual Parity

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

1. Add the conversation store and selectors.
2. Remove `selectedMessages` from primary UI rendering.
3. Add assistant lifecycle mutation APIs.
4. Add notification routing for assistant events.
5. Port `prepareThreadForDisplay` semantics.
6. Restyle the main conversation screen to the iOS reference.
7. Add badge and viewed-state rules.
8. Add stop and reconnect recovery.
9. Fill in reasoning, plan, structured input, and other non-chat timeline item parity.
10. Restyle settings, menus, sheets, and onboarding to the iOS reference.
11. Expand tests until the state machine and visual behavior are covered.

## Deferred Until Core Parity Lands

- visual polish beyond what is needed to support the new architecture
- secondary timeline item types that are not required for immediate assistant streaming parity
- broad module reshuffles unrelated to conversation state

## Definition Of Done

This plan is complete only when all of the following are true:

- Android updates the visible thread timeline from incoming events with no manual reload.
- Android no longer relies on `selectedMessages` as the source of truth.
- Android preserves item-scoped assistant streaming and completion behavior.
- Android preserves unread badge semantics for non-visible threads.
- Android can recover active turn state after reconnect and still support Stop.
- Android main conversation UI, settings, onboarding, and lightweight sheets match the iOS reference set closely enough that they read as the same product.
- Automated tests cover the main state transitions and merge edge cases.

## Maintenance Rules

- Update `Progress Snapshot` whenever a workstream changes state.
- Add concrete file paths in this document when new Android store/reducer files are introduced.
- Mark partial parity explicitly; do not mark a workstream done until behavior matches iOS, not merely until code exists.
- If scope changes, update `Required End State` first and then adjust downstream tasks.
