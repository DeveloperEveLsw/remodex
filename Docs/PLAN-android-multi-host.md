# Plan: Android Client + Multi-Host Runtime Expansion
> Status: Living document
> Last updated: 2026-03-12
> Scope: `remodex` bridge, relay, and new Android client

## Progress Snapshot

- In progress: protocol and terminology generalization
- In progress: iOS timeline/state architecture parity for Android tracked in `Docs/PLAN-android-ios-thread-parity.md`
- Done: Android + multi-host roadmap defined
- Done: relay now accepts legacy `iphone` and new `mobile` client role semantics
- Done: bridge now emits host platform/capability metadata for mobile clients
- Done: iOS client now connects with `mobile` role and surfaces host capability info in Settings
- Done: `AndroidClient` scaffold added with `app`, `core-protocol`, and `core-model` modules
- Done: Android core models now cover RPC, threads, messages, collaboration, approval, git, and revert DTOs
- Done: Android `core-pairing` now parses QR payloads and normalizes relay session URLs
- Done: Android `core-transport` now opens relay WebSockets, performs initialize fallback, and surfaces host info
- Done: Android app shell now includes a manual pairing/connection debug screen for pre-camera verification
- Done: Android transport now falls back from `mobile` to legacy `iphone` role for hosted relay compatibility
- Done: Android debug shell now loads `thread/list` results and can inspect `thread/read(includeTurns=true)` history
- Verified externally: Windows emulator build and hosted-relay handshake work against a WSL bridge host
- Done: Android diagnostics panel now shows raw RPC payloads, error metadata, and thread-list fallback stages
- Done: Android `collaborationMode/list` now probes with explicit params for newer Codex CLI builds
- Done: Android debug shell now exposes a basic `turn/start` composer path with sandbox and approval fallback
- Done: Android shell layout now mirrors the original Remodex visual structure with sidebar, focused conversation pane, and bottom composer while preserving live debug controls
- Pending verification: latest `collaborationMode/list` and `turn/start` debug shell changes still need Windows build confirmation

## Goal

Extend Remodex from an iPhone-to-Mac remote into a mobile-to-host remote system that:

- supports Android as a first-class mobile client
- supports macOS, Windows, and Linux as Codex host machines
- preserves the current local-first bridge model
- keeps existing iOS behavior working during the transition

This document is the working implementation plan and should be updated as scope, sequencing, or risks change.

## Current State

### What already exists

- The bridge already runs Codex through `codex app-server` or an existing WebSocket endpoint.
- The bridge already contains Windows-aware spawn/shutdown handling.
- The relay is mostly mobile-platform agnostic, but the client role is named `iphone`.
- The iOS app already implements the full mobile remote workflow:
  - QR pairing
  - reconnect and handshake
  - thread list and thread history
  - turn start and interrupt
  - streaming timeline rendering
  - approvals and structured input
  - git actions and workspace revert
  - notifications and background recovery

### What is host-specific today

- Codex Desktop refresh is macOS-only.
- `remodex resume` is macOS-only because it opens `Codex.app`.
- several user-facing strings still assume `iPhone` or `Mac`

### What is iOS-specific today

- camera and QR scanning
- Keychain storage
- local notifications
- background grace task behavior
- image picking and pasteboard flows
- SwiftUI presentation and interaction patterns

## Strategy

Build this in two layers:

1. Generalize the bridge/relay/runtime terminology and host capability model.
2. Implement a new Android client against the same JSON-RPC protocol, reusing bridge behavior but not reusing the iOS UI code.

The Android app should match the iOS app in information architecture and feature coverage, but it should be implemented with Android-native UI and lifecycle patterns rather than a literal visual clone.

## Target Architecture

### Shared runtime model

- `mobile client` connects to relay
- `host bridge` runs on macOS, Windows, or Linux
- `host bridge` talks to `codex app-server`
- `host bridge` exposes capability differences to clients

### Bridge responsibilities

- spawn or attach to Codex
- relay JSON-RPC between host and mobile
- intercept `git/*` and `workspace/*`
- persist active thread state
- expose host capability metadata
- optionally perform host-specific desktop refresh integrations

### Client responsibilities

- pair by QR
- connect and initialize session
- render thread list and conversation timeline
- send turns and display streaming output
- handle approvals, plan mode, and file/git actions
- preserve local state for reconnect and UX continuity

## UI Feasibility For Android

## Verdict

The current iOS UI is implementable on Android.

### Can be preserved directly at the product level

- onboarding flow
- QR pairing flow
- sidebar + thread grouping model
- conversation timeline model
- composer behavior and controls
- approvals and diff/revert interactions
- settings structure

### Must be adapted to Android-native patterns

- side drawer gestures and layout
- bottom sheets and dialogs
- glass/material styling
- camera and gallery integrations
- clipboard and share behavior
- background/reconnect lifecycle

### Recommended Android UI stack

- Kotlin
- Jetpack Compose
- ViewModel + repository/state holder split
- CameraX for QR scanning
- Android Keystore-backed secure persistence
- DataStore for non-sensitive preferences
- Coil or similar for image loading if needed

## Workstreams

## 1. Protocol And Terminology Generalization

Goal: remove iPhone/Mac assumptions without breaking existing clients.

Tasks:

- replace user-facing `iPhone`/`Mac` wording with `mobile`/`host` where appropriate
- decide role migration strategy:
  - safe path: keep Android using role `iphone` temporarily for compatibility
  - final path: add `mobile` role and keep `iphone` as a legacy alias
- add host metadata to initialization or bridge-managed payloads
- define host capability flags:
  - `desktopRefresh`
  - `resumeThreadInDesktopApp`
  - `localCodexDesktopApp`
  - `platform`

Exit criteria:

- iOS still connects without behavior regression
- Android can connect without special-case relay hacks
- UI can tell users which host features are available

## 2. Multi-Host Bridge Expansion

Goal: make the bridge explicitly support macOS, Windows, and Linux.

Tasks:

- audit bridge startup, shutdown, and local path assumptions per OS
- keep macOS desktop refresh behind a host capability flag
- disable unsupported desktop integrations cleanly on Windows/Linux
- review git and workspace handlers for OS path behavior
- add a host information endpoint or startup event

Exit criteria:

- host OS is surfaced to the client
- unsupported host-only features degrade cleanly
- bridge behavior is documented for each supported host OS

## 3. Android App Foundation

Goal: establish the Android project and shared app skeleton.

Tasks:

- create Android app project structure
- define modules:
  - `app`
  - `core-model`
  - `core-protocol`
  - `core-transport`
  - `core-storage`
  - `feature-onboarding`
  - `feature-pairing`
  - `feature-threads`
  - `feature-turn`
  - `feature-settings`
  - `feature-git`
- port core models:
  - `RPCMessage`
  - `JSONValue`
  - `CodexThread`
  - `CodexMessage`
  - approval and plan models
  - git/workspace DTOs
- implement secure storage and preferences

Exit criteria:

- project builds
- app can store pairing info
- core models decode real bridge traffic

## 4. Android Connection Layer

Goal: reproduce the iOS connection lifecycle on Android.

Tasks:

- implement QR payload parsing
- implement WebSocket connection with headers
- implement `initialize` and `initialized`
- implement pending request tracking and timeouts
- implement reconnect and backoff
- classify permanent vs transient disconnects
- persist pairing and auto-reconnect on app relaunch

Exit criteria:

- Android can pair with the existing bridge
- reconnect works after temporary relay interruption
- stale session errors clear saved pairing correctly

## 5. Android MVP UI

Goal: ship a usable Android client with the core remote workflow.

In scope:

- onboarding
- QR scan
- connection shell
- thread list
- open thread
- timeline rendering for user/assistant/system text
- send turn
- interrupt turn
- approval prompt
- basic settings

Out of scope for MVP:

- image attachments
- git branch switching UI
- workspace revert flow
- advanced diff sheet
- local notifications
- file and skill autocomplete

Exit criteria:

- user can pair, browse threads, send tasks, and stop tasks from Android
- conversation remains usable across reconnect

## 6. Android Parity Phase

Goal: close the functional gap with iOS where it matters.

Tasks:

- image attachments from gallery/camera
- code block copy and diff presentation
- file change summary UI
- git status, branch actions, commit/push flows
- workspace revert preview/apply flow
- plan mode UI
- structured user input cards
- local notifications with deep link routing
- file and skill autocomplete

Exit criteria:

- Android reaches practical parity for daily use
- known intentional gaps are documented

## 7. Host-Aware UX

Goal: make multi-host support visible and understandable.

Tasks:

- show connected host OS in settings or connection card
- show capability-dependent UI states
- hide or disable unsupported actions cleanly
- update onboarding copy for multi-host support
- update QR and CLI instructions to be mobile-neutral

Exit criteria:

- users can tell what their current host supports
- Windows/Linux users are not shown macOS-only actions without explanation

## Milestones

## Milestone A: Bridge Compatibility Baseline

- terminology cleanup started
- host capability model defined
- Android can connect to the existing relay/bridge

## Milestone B: Android MVP

- QR pairing works
- thread list works
- turn send/interrupt works
- approvals work
- reconnect works

## Milestone C: Multi-Host Productization

- host OS surfaced in UI
- unsupported features gated by capability
- Windows/Linux host flow documented and tested

## Milestone D: Android Feature Parity

- attachments
- git
- diff/revert
- notifications
- advanced timeline features

## Prioritized Execution Order

1. Generalize relay/bridge terminology and role handling.
2. Add host capability reporting.
3. Create Android project and port core models/protocol.
4. Implement Android transport, handshake, and reconnect.
5. Build Android MVP screens.
6. Validate Android against macOS host first.
7. Validate Android against Windows host.
8. Validate Android against Linux host.
9. Add parity features in descending user value order.

## Risks

## Protocol Drift Risk

The event surface is broad and not limited to simple chat messages. Android must match the iOS handling of:

- streaming deltas
- late completion events
- approvals
- structured user input
- file change and command execution events

Mitigation:

- port protocol models before UI
- capture and replay real bridge traffic in tests

## Overgrown Client State Risk

The current iOS service layer is large. Copying it 1:1 into Android would create a hard-to-maintain client.

Mitigation:

- split Android into repositories and feature state holders
- keep transport, sync, and UI state separate

## Host Capability Confusion Risk

Desktop refresh and desktop app routing are macOS-only, but git/workspace features are not.

Mitigation:

- explicit host capability flags
- capability-aware UI labels and disabled states

## Background Behavior Risk

iOS and Android background execution differ materially.

Mitigation:

- design Android around reconnect resilience, not iOS-style background grace equivalence
- only add long-running background behavior when clearly justified

## Acceptance Criteria

The project is successful when:

- Android can control Codex running on a host machine through the existing bridge model
- the host machine can be macOS, Windows, or Linux
- users can understand host-specific limitations from the UI
- iOS remains functional during the transition
- bridge features continue to run locally on the host

## Immediate Next Steps

1. Update relay and bridge terminology to support a `mobile` client concept.
2. Define a host capability payload and where it is sent.
3. Scaffold the Android app and core modules.
4. Port protocol models and transport layer.
5. Build QR pairing and connection shell.

## Maintenance Rules

When this file is updated:

- keep milestone status honest
- record scope cuts explicitly instead of silently dropping them
- update risks when a new host-specific blocker appears
- update the execution order if dependencies change
- prefer additive notes over vague rewrite-only edits
