# Checklist: Android iOS Thread And UI Parity
> Status: Working checklist
> Last updated: 2026-03-13
> Companion plan: `Docs/PLAN-android-ios-thread-parity.md`

## Reference Inputs

- [x] Collect current iOS UI reference screenshots.
- [x] Store local UI reference set in `/home/lws19/codex_android/remodex/UI ref`.
- [ ] Annotate any ambiguous iOS states that still need more screenshots.

## Architecture

- [x] Add a dedicated Android conversation store/service for timeline state.
- [x] Move selected thread tracking to `activeThreadId`.
- [x] Replace `selectedMessages` with `messagesByThread`.
- [x] Add `messageRevisionByThread`.
- [x] Add `activeTurnIdByThread`.
- [x] Add `threadIdByTurnId`.
- [ ] Add unread/run badge state sets or equivalent model.

## Thread Selection And Hydration

- [ ] Port Android thread selection to iOS-style `prepareThreadForDisplay` semantics.
- [ ] Mark thread viewed immediately on selection.
- [ ] Resume selected thread when connected.
- [ ] Hydrate history through `thread/read(includeTurns=true)`.
- [ ] Prevent stale history from overwriting live streaming rows.
- [ ] Rehydrate in-flight turn state after reconnect or foreground recovery.

## Incoming Event Routing

- [x] Add an Android notification router/reducer layer.
- [x] Handle `turn/started`.
- [x] Handle `turn/completed`.
- [x] Handle `item/started`.
- [x] Handle `item/completed`.
- [x] Handle `item/agentMessage/delta`.
- [x] Support modern and legacy payload envelopes.
- [x] Maintain per-thread running fallback when `turnId` is absent.

## Timeline Mutation APIs

- [x] Add `beginAssistantMessage(...)`.
- [x] Add `appendAssistantDelta(...)`.
- [x] Add `completeAssistantMessage(...)`.
- [x] Keep assistant rows item-scoped.
- [x] Preserve multiple assistant items in one turn.
- [x] Increment thread-local revision token on every mutation.
- [ ] Update the active-thread derived output after each mutation.

## Compose Integration

- [x] Make the timeline read from `messagesFor(activeThreadId)`.
- [x] Stop rendering from `selectedMessages`.
- [x] Use thread-local revision tokens for invalidation when needed.
- [ ] Keep badge rendering separate from active timeline rendering.

## Main Conversation UI Parity

- [x] Match iOS top bar density and spacing.
- [x] Match iOS title and cwd subtitle hierarchy.
- [x] Match iOS diff stats placement and visual weight.
- [x] Reduce card depth and shadow weight toward the iOS look.
- [x] Reduce overall visual chrome in the conversation screen.
- [x] Match assistant timeline rows to the iOS transcript-like presentation.
- [x] Keep user bubbles compact and right-aligned like iOS.
- [x] Split the composer into iOS-style upper prompt row and lower runtime row.
- [x] Match model/reasoning/send control layout to iOS.
- [x] Match runtime pill bar layout for local/access/branch controls.

## Settings, Menus, And Secondary UI Parity

- [ ] Match iOS settings screen grouped-card structure.
- [ ] Match iOS settings section labels and spacing.
- [ ] Match iOS connection/status card styling.
- [ ] Match iOS onboarding step layout and single primary CTA emphasis.
- [ ] Match iOS branch picker grouped list presentation.
- [ ] Match iOS action menu grouped sheet presentation.
- [ ] Keep secondary surfaces lightweight instead of default Material-heavy.

## Sidebar And Badge Semantics

- [ ] Mark running threads correctly.
- [ ] Mark ready threads only when unread.
- [ ] Mark failed threads only when unread.
- [ ] Clear badges when thread becomes active.
- [ ] Keep active thread output in the timeline instead of unread state.

## Reconnect And Stop Guardrails

- [ ] Recover `activeTurnIdByThread` via `thread/read` when Stop needs it.
- [ ] Keep Stop visible after reconnect/background recovery.
- [ ] Preserve local connection state during reconnect.
- [ ] Ignore late turn-less activity after the thread is inactive.
- [ ] Merge late reasoning deltas into existing rows.

## Verification

- [ ] Unit test conversation store selectors and mutations.
- [ ] Unit test assistant delta streaming merge behavior.
- [ ] Unit test assistant completion deduplication.
- [ ] Unit test history/live merge conflict handling.
- [ ] Unit test thread selection with async hydration.
- [ ] Unit test stale-thread continuation flow.
- [ ] Unit test unread badge behavior for inactive threads.
- [ ] Unit test reconnect and stop recovery.

## Sign-Off Criteria

- [ ] Active Android thread updates live without re-entering from the sidebar.
- [ ] No primary flow depends on `selectedMessages`.
- [ ] Assistant rows remain stable during streaming and completion.
- [ ] Unread badges apply only to non-visible threads.
- [ ] Reconnect does not break active turn visibility or interruptibility.
- [ ] Android main conversation screen reads as the same product as the iOS reference.
- [ ] Android settings, onboarding, pickers, and menus align with the iOS reference set.
