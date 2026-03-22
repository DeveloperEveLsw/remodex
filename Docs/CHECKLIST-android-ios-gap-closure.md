# Checklist: Android iOS Gap Closure
> Status: Working checklist
> Last updated: 2026-03-21
> Companion recap: `Docs/RECAP-android-ios-gap-analysis.md`
> Companion plan: `Docs/PLAN-android-ios-gap-closure.md`

## Inputs

- [x] Write a baseline recap of the highest Android vs iOS gaps.
- [x] Confirm the priority order from the current codebase instead of from stale README scope.
- [ ] Capture any extra iOS references needed for approval, attachment, archive, and notification flows.

## Phase 1: Server Request Parity

- [ ] Route Android server-initiated RPC requests into real app state instead of only logging the method.
- [ ] Add Android pending approval state.
- [ ] Add Android structured user input request state.
- [ ] Implement accept response path.
- [ ] Implement decline response path.
- [ ] Implement structured user input answer submission path.
- [ ] Match iOS full-access auto-approve behavior.
- [ ] Scope approval and structured-input UI to the correct thread.
- [ ] Preserve request handling across reconnect and thread re-selection.
- [ ] Add unit tests for approval accept/decline transitions.
- [ ] Add unit tests for structured user input submission.

## Phase 2: Composer And Context Parity

- [ ] Add Android composer attachment state.
- [ ] Add Android image attachment intake pipeline.
- [ ] Add attachment preview and removal UI.
- [ ] Extend Android `turn/start` payload construction for attachments.
- [ ] Add Android file autocomplete backed by `fuzzyFileSearch`.
- [ ] Add Android skill autocomplete backed by `skills/list`.
- [ ] Extend Android `turn/start` payload construction for skill mentions.
- [ ] Remove or disable composer affordances that remain unwired during implementation.
- [ ] Add unit tests for richer `turn/start` payload construction.
- [ ] Add tests for attachment state transitions and autocomplete state behavior.

## Phase 3: Workspace Operation Parity

- [ ] Add Android git commit flow.
- [ ] Add Android git push flow.
- [ ] Add Android git pull flow.
- [ ] Add Android git reset-to-remote flow.
- [ ] Add Android git remote-url flow.
- [ ] Add Android revert preview flow.
- [ ] Add Android revert apply flow.
- [ ] Add Android compact-context action.
- [ ] Add Android archive thread flow.
- [ ] Add Android unarchive thread flow.
- [ ] Add Android rename thread flow.
- [ ] Replace decorative conversation search with real filtering/search behavior.
- [ ] Make Android action menu reflect only true supported actions.
- [ ] Add unit coverage for the new workspace actions.

## Phase 4: Hardening And Verification

- [ ] Move relay pairing storage to an encrypted Android store.
- [ ] Add Android notification permission state.
- [ ] Add Android run-completion notification scheduling.
- [ ] Add Android notification-open routing back into thread selection.
- [ ] Add Android instrumentation coverage or equivalent UI verification for high-risk flows.
- [ ] Add regression checks for approval, attachment, archive, and notification flows.

## Refactor Guardrails

- [ ] Avoid adding more unrelated state to `RemodexAndroidRoot.kt`.
- [ ] Avoid adding more unrelated state to `RemodexDebugViewModel.kt`.
- [ ] Avoid turning `RemodexTransportClient.kt` into application-flow orchestration.
- [ ] Move shared logic into services, reducers, or coordinators as each phase lands.

## Sign-Off

- [ ] Android can complete approval-gated turns without desktop or iOS fallback.
- [ ] Android can complete structured-input turns without desktop or iOS fallback.
- [ ] Android composer supports attachments, file mentions, and skill mentions.
- [ ] Android exposes the core workspace operations already validated on iOS.
- [ ] Android secure storage and notification behavior are no longer placeholder-level.
- [ ] New parity code is covered well enough to prevent repeat regressions.
