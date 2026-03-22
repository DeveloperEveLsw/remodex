# Android Chat Render Parity Handoff

Order-specific follow-up:

- For the current Android vs iOS message ordering analysis and next-session execution instructions, read `Docs/HANDOFF-android-chat-render-order-ios-parity.md` first.

Date: 2026-03-14

## Goal

Current goal is not a loose Android adaptation.

The goal is to port the iOS chat rendering pipeline to Android as close to 1:1 as practical, because the iOS app is already the trusted reference for stability and UX quality.

This handoff covers the work done in the current session and the exact next steps.

## What Was Implemented

### 1. Render-time timeline projection was added on Android

New file:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexTimelineProjection.kt`

This ports the iOS `TurnTimelineReducer` behavior at a high level:

- intra-turn ordering
  - user -> thinking -> assistant -> fileChange
- preserve interleaved assistant/thinking flows
- collapse consecutive thinking rows
- dedupe duplicate file-change rows
- dedupe duplicate assistant rows
- compute assistant anchor message for streaming scroll behavior

### 2. Android main conversation pane now uses projected messages

Changed file:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`

`MainConversationPane` no longer renders raw `visibleMessagesFor(...)` directly.

It now:

- computes a projected timeline using `RemodexTimelineProjector.project(...)`
- passes projected messages into `ConversationTimeline`
- passes active turn id into the timeline for anchor behavior

### 3. Android timeline behavior was moved closer to iOS

In `ConversationTimeline`:

- added tail paging with `Load earlier messages`
- added a best-effort assistant response anchor during streaming
- added a `scroll-to-latest` affordance

This is intended to move Android away from a plain transcript list and closer to the iOS timeline interaction model.

### 4. Message row rendering was split by role and system kind

In `RemodexAndroidRoot.kt`:

- `TranscriptMessage(...)` now dispatches by role
  - user
  - assistant
  - system

System rendering now branches by message kind:

- `Thinking`
- `FileChange`
- `CommandExecution`
- `Plan`
- `UserInputPrompt`
- `Chat`

This replaces the previous Android behavior where many system items were visually flattened into a generic assistant-style text block.

### 5. Projection tests were added

New file:

- `AndroidClient/app/src/test/java/app/remodex/android/RemodexTimelineProjectionTests.kt`

Covered behaviors:

- turn ordering
- thinking collapse
- duplicate file-change removal
- duplicate assistant removal
- assistant anchor selection

## Important Reality Check

This is not full 1:1 parity yet.

The current session moved Android onto the same architectural direction as iOS, but several iOS-specific rendering behaviors are still only partially ported or are represented by simpler Android placeholders.

## What Is Still Missing For True iOS-Level Parity

### Markdown / code block rendering

iOS reference:

- `CodexMobile/CodexMobile/Views/Turn/TurnMessageComponents.swift`

Current Android state:

- assistant rows still render plain text
- no markdown segmentation
- no code block rendering
- no diff-style code block rendering

Needed next:

- port iOS markdown segmentation behavior
- render prose and fenced code blocks separately
- add diff-aware code block rendering

### File-change parsing and rich diff UI

iOS reference:

- `CodexMobile/CodexMobile/Views/Turn/TurnFileChangeSummaryParser.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageCaches.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageComponents.swift`

Current Android state:

- file-change rows are still mostly text inside a system card
- no grouped inline file rows
- no diff summary button
- no diff sheet
- no inline commit CTA parity

Needed next:

- port file change summary parser
- port grouping and render-state logic
- add diff detail sheet

### Thinking disclosure parsing

iOS reference:

- `CodexMobile/CodexMobile/Views/Turn/ThinkingDisclosureParser.swift`

Current Android state:

- only basic `Thinking...` normalization
- no disclosure sections
- no collapse/expand UI for reasoning summaries

Needed next:

- port parser logic
- add expandable section UI in Compose

### Command execution rich cards

iOS reference:

- `CodexMobile/CodexMobile/Views/Turn/CommandExecutionViews.swift`
- `CodexMobile/CodexMobile/Views/Turn/TurnMessageCaches.swift`

Current Android state:

- basic status parsing only
- no detail sheet
- no command output / cwd / exit code / duration presentation

Needed next:

- port status parsing cache
- add command detail surface
- use any available runtime detail model if present

### Structured user input submit flow

iOS reference:

- `CodexMobile/CodexMobile/Views/Turn/TurnPlanModeComponents.swift`
- `CodexMobile/CodexMobile/Views/Turn/StructuredUserInputCardView.swift`

Current Android state:

- request card is display-only
- no answer selection state
- no submit action wiring

Needed next:

- confirm Android transport has matching response API
- if not, port the API first
- then port the full iOS submit interaction

### User bubble parity

iOS currently has:

- attachment strip
- mention highlighting for `@file` and `$skill`
- retry/copy affordances
- delivery-state text

Android currently has:

- simple placeholder chips for attachments
- no mention highlighting
- no retry affordance
- basic delivery status text only

## Files Changed In This Session

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexTimelineProjection.kt`
- `AndroidClient/app/src/test/java/app/remodex/android/RemodexTimelineProjectionTests.kt`

## Validation Blocker

Code execution validation could not be completed in this environment.

Missing locally in this session:

- `gradlew`
- `gradle`
- `java`

As a result:

- Android unit tests were not run
- Compose/Kotlin compile errors were not machine-validated

Next session should treat compile/test validation as mandatory before more feature porting.

## Known Risk Areas To Check First In The Next Session

### 1. Compose compile risk in `RemodexAndroidRoot.kt`

The file now contains a much larger amount of row-rendering logic than before.

First checks:

- unresolved imports
- Compose lambda / remember / `LaunchedEffect` issues
- `LazyColumn` item scope issues
- role/kind helper visibility issues

### 2. Timeline auto-scroll behavior

The new timeline now has:

- tail paging
- assistant anchor scrolling
- scroll-to-latest button

This needs device-level validation to ensure:

- it does not fight the user while scrolling
- it does not jump incorrectly when messages stream
- it does not anchor to the wrong row after projection collapse

### 3. System card parity is still shallow

Current cards are architectural placeholders for parity, not final 1:1 reproductions.

Do not stop here and assume parity is done.

## Recommended Next Steps

1. Make the Android build/test environment executable again.
2. Fix any compile issues from the current patch before doing more parity work.
3. Port iOS `ThinkingDisclosureParser` to Android.
4. Port iOS markdown/code-block rendering for assistant rows.
5. Port iOS file-change parser and diff UI.
6. Port structured user input interaction and submission.
7. Add Android tests for projection plus parser behavior.

## Next-Session Checklist

- [ ] Verify the current branch still contains the three intended Android chat-render changes.
- [ ] Run Android compile or unit tests once Java/Gradle tooling is available.
- [ ] Fix any compilation problems in `RemodexAndroidRoot.kt`.
- [ ] Validate `RemodexTimelineProjector` behavior against the iOS `TurnTimelineReducer`.
- [ ] Port `ThinkingDisclosureParser.swift` to Kotlin.
- [ ] Replace Android assistant plain-text rendering with iOS-style markdown/code-block segmentation.
- [ ] Port iOS file-change summary parsing and duplicate handling details that are still missing.
- [ ] Add rich file-change card UI and diff detail surface.
- [ ] Add richer command execution card/detail parity.
- [ ] Check whether Android transport supports structured user-input responses.
- [ ] If supported, port interactive structured user-input submission UI.
- [ ] Add tests for thinking collapse, duplicate handling, and any new parser logic.
- [ ] Manually verify scrolling behavior during live streaming.
- [ ] Compare Android screenshots side-by-side with iOS for the same thread transcript.

## Short Handoff Summary

The session changed Android from raw transcript rendering toward the iOS model by introducing a render-time projector and kind-aware row rendering.

The architecture is now moving in the right direction, but the UI is not yet at true 1:1 parity because the rich iOS message subcomponents and parsers have not all been ported yet.
