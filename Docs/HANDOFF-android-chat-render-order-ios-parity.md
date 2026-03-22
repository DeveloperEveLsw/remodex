# Android Chat Render Order iOS Parity Handoff

Date: 2026-03-22
Status: Ready for next session
Scope: Android vs iOS chat message render ordering, with focus on `commandExecution`, `thinking`, `fileChange`, streaming completion, and hydration reorder behavior

## Goal

Bring Android chat message render ordering into iOS parity.

This is not a generic Android cleanup task. The target is the current iOS behavior, including its intentional ordering rules and its current raw timeline mutation semantics.

## Important Boundary

Do not "fix" Android by changing behaviors that are already iOS parity.

In particular:

- `fileChange` below assistant text in a normal single-item turn is intentional parity.
- interleaved multi-item turn preservation is intentional parity.
- placeholder-only `thinking` rows being pruned at turn completion is correct and should remain.

## Ground Truth Summary

### 1. Projector priority is already mostly aligned

Android and iOS both project single-item turns with the same broad priority:

- user
- thinking
- commandExecution
- chat / plan
- assistant
- fileChange

Relevant files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexTimelineProjection.kt`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`

### 2. Interleaved turns intentionally preserve chronology on both platforms

If a turn is detected as interleaved, both platforms stop role-priority reordering and preserve non-user chronology by `orderIndex`.

Interleaved is detected when:

- multiple assistant item ids exist in one turn, or
- the timeline shows `thinking -> assistant -> thinking`

This means any late-appended system row will stay below assistant text once the turn is treated as interleaved.

## Confirmed Findings

### A. `fileChange` under assistant text is already iOS parity

Do not change this as part of the parity work.

Android and iOS both intentionally place `fileChange` after assistant text in a normal single-item turn.

### B. Android `commandExecution` can get stuck at the bottom for code-backed reasons

This is not a Compose rendering bug.

Confirmed causes:

1. Android reducer does not handle legacy command events that iOS handles:
   - `codex/event/exec_command_begin`
   - `codex/event/exec_command_output_delta`
   - `codex/event/exec_command_end`
2. Android `item/commandexecution/terminalinteraction` handling is too strict:
   - it requires a nested `item` object
   - iOS can resolve and update command rows without that nested object
3. When Android misses those earlier events, the command row is created later through append/upsert fallback.
4. That late-created row gets a larger `orderIndex`.
5. If the turn is interleaved, projector preserves that `orderIndex`, so the command row stays below assistant text.
6. After `turn/completed`, a late command event without a usable `turnId` can become effectively orphaned and avoid intra-turn reorder entirely.

Primary Android files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationReducer.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationState.kt`

Primary iOS reference:

- `CodexMobile/CodexMobile/Services/CodexService+Incoming.swift`

### C. Android `thinking` can fall below assistant text for code-backed reasons

This is also not a Compose rendering bug.

Confirmed causes:

1. In a normal single-item turn, Android would place `thinking` above assistant.
2. So when `thinking` appears below assistant, the turn is reaching the interleaved path.
3. Android creates or updates reasoning rows from:
   - `item/started` for system reasoning items
   - `item/reasoning/*delta`
4. If the reasoning row is first created after assistant text has already appeared, it gets a later `orderIndex`.
5. Android preserves `orderIndex` when that reasoning row later receives more delta or completion updates.
6. Once the turn is treated as interleaved, projector preserves chronology and does not move the reasoning row back above assistant.
7. Android does already block the late post-completion "create a brand new trailing thinking row" path, so the main problem is mid-stream creation timing plus preserved `orderIndex`, not post-completion placeholder creation.
8. iOS also supports essential activity lines such as `background_event`, `read`, `search`, and `list_files`, merging them into thinking state. Android currently lacks that parity path.

Primary Android files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationReducer.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationState.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexTimelineProjection.kt`

Primary iOS reference:

- `CodexMobile/CodexMobile/Services/CodexService+Incoming.swift`
- `CodexMobile/CodexMobile/Services/CodexService+Messages.swift`

### D. Streaming completion reorder differs between Android and iOS

This is a major parity gap.

Current behavior:

- Android preserves `orderIndex` when an existing system row moves from streaming to completed or receives more output.
- iOS reassigns a fresh `orderIndex` for existing system-row updates.

Effect:

- Android keeps system rows in their original slot.
- iOS can move the same system row later in raw chronology.

If strict iOS parity is the goal, Android must match the iOS system-row `orderIndex` rewrite behavior.

### E. Hydration merge behavior differs between Android and iOS

This is another major parity gap.

Current behavior:

- Android merges hydrated history with live rows, then reassigns sequential `orderIndex` values across the whole merged thread.
- iOS reconciles in place and keeps existing chronology more faithfully.

Effect:

- Android can reshuffle local live rows during hydration.
- iOS is more stable when history arrives during or after streaming.

### F. Android history decode weakens no-turn dedupe

Current behavior:

- Android history decode sets `createdAt = null`.
- Android no-turn assistant dedupe uses timestamps when available.
- iOS history decode fills real or synthetic timestamps.

Effect:

- Android can retain more duplicate assistant rows, which can indirectly affect final projected order.

## Required Change Checklist

### Reducer and event-routing parity

- [ ] Add Android handling for legacy command events:
  - `codex/event/exec_command_begin`
  - `codex/event/exec_command_output_delta`
  - `codex/event/exec_command_end`
- [ ] Relax Android `terminalInteraction` parsing so command rows can be resolved without a nested `item` object when equivalent context exists in params or event payload.
- [ ] Add Android handling for essential activity events that iOS merges into thinking:
  - `codex/event/background_event`
  - `codex/event/read`
  - `codex/event/search`
  - `codex/event/list_files`
- [ ] Confirm reasoning, command, and file-change item-start paths create rows early enough to avoid unnecessary late tail-append behavior.

### State mutation parity

- [ ] Change Android system-row update semantics to match iOS if strict parity is desired:
  - when updating an existing system row, rewrite `orderIndex` like iOS does
  - do not keep the original slot just because the row already exists
- [ ] Re-check duplicate system-row pruning after that change, especially for command and file-change rows.
- [ ] Re-check late command events after `turn/completed` so they rebind to the correct turn or safely no-op instead of creating orphaned trailing rows.

### Hydration and history parity

- [ ] Remove Android's unconditional post-merge `withSequentialOrderIndices()` behavior for hydrated threads.
- [ ] Preserve existing live chronology during history reconciliation, following the iOS merge model more closely.
- [ ] Add real or synthetic `createdAt` during Android history decode.
- [ ] Re-check history reconciliation for:
  - thinking by turn
  - fileChange by turn
  - commandExecution by command key and turn

### Projection and regression coverage

- [ ] Add projector tests for interleaved command and thinking placement, not just single-item priority.
- [ ] Add reducer tests for legacy command event flows.
- [ ] Add reducer tests for `terminalInteraction` payloads without nested `item`.
- [ ] Add reducer/state tests for essential activity events merging into thinking.
- [ ] Add hydration tests that assert projected render order, not only raw state order.
- [ ] Add post-completion late-event tests for both command and thinking.

## Do Not Change Checklist

- [ ] Do not move `fileChange` above assistant text in normal single-item turns.
- [ ] Do not remove interleaved chronology preservation.
- [ ] Do not reintroduce view-layer hacks to reorder rows in Compose.
- [ ] Do not solve parity by adding Android-only compatibility logic that diverges from iOS behavior.
- [ ] Do not weaken item-scoped assistant handling.

## Recommended Implementation Order

### Phase 1. Event-routing parity

Goal:

Stop missing or late-binding rows at the reducer boundary.

Work:

- add legacy command event support
- relax `terminalInteraction` payload resolution
- add essential activity event support

Expected result:

- fewer late-created `commandExecution` rows
- fewer late-created or fragmented `thinking` rows

### Phase 2. System row ordering parity

Goal:

Match iOS chronology behavior for system-row updates.

Work:

- update Android system-row mutation path to mirror iOS `orderIndex` rewrite behavior
- verify command, thinking, and fileChange update flows after this change

Expected result:

- Android raw system-row chronology behaves like iOS during streaming and completion

### Phase 3. Hydration parity

Goal:

Prevent hydration from reordering live Android rows differently than iOS.

Work:

- remove whole-thread sequential reindex after merge
- preserve chronology through reconciliation
- add timestamps during history decode

Expected result:

- hydration no longer reshuffles live rows in Android-only ways

### Phase 4. Regression coverage

Goal:

Lock behavior against future regressions.

Work:

- add reducer, state, and projector tests for all high-risk flows

Expected result:

- order bugs become reproducible and protected in unit tests

## Minimum Test Matrix For The Next Session

- [ ] Legacy command flow:
  - begin -> output delta -> end
- [ ] Command terminal interaction without nested `item`
- [ ] Reasoning delta that starts after assistant text already exists
- [ ] Interleaved turn:
  - `thinking1 -> assistant1 -> thinking2 -> assistant2`
- [ ] Partial interleaved turn:
  - `thinking1 -> assistant1 -> thinking2`
- [ ] Essential activity events merged into thinking:
  - `read`
  - `search`
  - `list_files`
  - `background_event`
- [ ] Hydration after live command/thinking/fileChange rows already exist
- [ ] `turn/completed` followed by late command event
- [ ] `turn/completed` followed by late reasoning delta

## Files Most Likely To Change

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationReducer.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexConversationState.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexTimelineProjection.kt`
- `AndroidClient/core-transport/src/main/kotlin/app/remodex/android/core/transport/RemodexTransportClient.kt`
- `AndroidClient/app/src/test/java/app/remodex/android/RemodexConversationReducerTests.kt`
- `AndroidClient/app/src/test/java/app/remodex/android/RemodexConversationStateTests.kt`
- `AndroidClient/app/src/test/java/app/remodex/android/RemodexTimelineProjectionTests.kt`

## iOS Reference Files

- `CodexMobile/CodexMobile/Services/CodexService+Incoming.swift`
- `CodexMobile/CodexMobile/Services/CodexService+Messages.swift`
- `CodexMobile/CodexMobile/Services/CodexService+History.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnTimelineReducer.swift`
- `CodexMobile/CodexMobileTests/CodexServiceIncomingCommandExecutionTests.swift`
- `CodexMobile/CodexMobileTests/TurnTimelineReducerTests.swift`

## Next Session Instructions

Use this file as the source of truth for the next session.

At the start of the next session:

1. Read this file first.
2. Do not restart from UI symptoms. Start from reducer/state parity.
3. Treat iOS as the reference implementation.
4. Preserve intentional parity behaviors listed in the "Do Not Change Checklist".
5. Implement in phase order unless a test shows a different dependency.
6. Add tests as each phase lands. Do not postpone all verification to the end.
7. Prefer changing Android event routing and state mutation over adding render-layer exceptions.

## Pasteable Prompt For The Next Session

```text
Continue the Android chat render order iOS parity work.

Read /home/lws19/codex_android/remodex/Docs/HANDOFF-android-chat-render-order-ios-parity.md first and use it as the source of truth.

Scope for this session:
- fix Android reducer/state/history behavior so chat render ordering matches iOS
- prioritize commandExecution and thinking ordering issues
- preserve intentional parity behaviors such as fileChange staying below assistant in normal single-item turns and interleaved chronology preservation

Execution rules:
- start from reducer/state parity, not Compose UI
- use iOS files as the reference implementation
- add or update Android tests as each behavior is fixed
- do not introduce Android-only render hacks

Suggested first implementation phase:
- add legacy command event handling
- relax terminalInteraction parsing
- add essential activity event handling for thinking
```

## Validation Note

This handoff is based on static code analysis in the current session.

No Android build, Android test run, or iOS build/test run was executed in this session.
