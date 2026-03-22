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

- [x] Match iOS settings screen grouped-card structure.
- [x] Match iOS settings section labels and spacing.
- [x] Match iOS connection/status card styling.
- [x] Match iOS onboarding step layout and single primary CTA emphasis.
- [x] Match iOS branch picker grouped list presentation.
- [x] Match iOS action menu grouped sheet presentation.
- [x] Keep secondary surfaces lightweight instead of default Material-heavy.

## Sidebar And Badge Semantics

- [ ] Mark running threads correctly.
- [ ] Mark ready threads only when unread.
- [ ] Mark failed threads only when unread.
- [ ] Clear badges when thread becomes active.
- [ ] Keep active thread output in the timeline instead of unread state.

## Runtime Controls And Sheet Binding

- [ ] Bind composer model chip to real runtime state.
- [ ] Bind composer reasoning chip to real runtime state.
- [ ] Bind runtime/access pills to real request state instead of static labels.
- [ ] Show real runtime defaults in settings instead of hardcoded placeholders.
- [x] Resolve branch state from the selected thread `cwd` instead of thread metadata.
- [x] Add Android `git/branchesWithStatus` parity for current/default/available branches.
- [x] Add Android `git/status` refresh parity for current branch and diff state updates.
- [x] Make branch picker render real branch choices returned by the host bridge.
- [x] Split branch menu state into current branch and PR target selection.
- [ ] Match iOS branch refresh semantics around reconnect, thread-ready, and turn-finished transitions.
- [ ] Match iOS branch refresh failure handling instead of surfacing Android-only global errors.
- [ ] Match iOS branch switch success flow without adding Android-only post-checkout refresh behavior.
- [ ] Add Android debounce-based repo status refresh parity for repo-affecting timeline changes.
- [ ] Preserve the iOS bridge contract exactly; do not add Android-only checkout branch coercion or remote-branch fallback logic.
- [ ] Make action menu rows reflect real supported actions or explicit disabled state.
- [ ] Keep onboarding CTA focused on connection setup / QR pairing flow.

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
- [ ] Unit test Android branch refresh/switch semantics against the current iOS behavior.
- [ ] Verify whether the paired host bridge process is actually running commit `b4ccc66` when reproducing branch checkout failures.

## Sign-Off Criteria

- [ ] Active Android thread updates live without re-entering from the sidebar.
- [ ] No primary flow depends on `selectedMessages`.
- [ ] Assistant rows remain stable during streaming and completion.
- [ ] Unread badges apply only to non-visible threads.
- [ ] Reconnect does not break active turn visibility or interruptibility.
- [ ] Visible runtime controls map to real Android state and request parameters.
- [ ] Branch UI is sourced from host git RPC scoped by thread `cwd`, not from `thread/read` metadata.
- [ ] Android branch popup matches the lightweight floating menu feel of the iOS reference.
- [ ] Android branch lifecycle behavior matches the current iOS implementation before any bridge contract changes are attempted.
- [ ] Android main conversation screen reads as the same product as the iOS reference.
- [ ] Android settings, onboarding, pickers, and menus align with the iOS reference set.
