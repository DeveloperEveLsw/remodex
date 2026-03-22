# Plan: Android QR Camera Scanner Parity
> Status: Proposed
> Last updated: 2026-03-14
> Scope: Android onboarding/pairing UX, live camera QR scanning, and iOS 1:1 parity for relay session pairing

## Goal

Port the iOS QR pairing flow to Android as close to 1:1 as practical.

The target is not "add any QR scanner." The target is:

- real device camera scanning
- iOS-matching permission handling and fallback UX
- local-first pairing semantics
- stable reconnect behavior with saved relay pairing
- no hosted-service assumptions and no hardcoded remote domains

## Current Android State

Android currently relies on pasted JSON payload input in the sidebar connection panel.

Current behavior:

- onboarding CTA says `Scan QR Code`
- connection shell exposes an `OutlinedTextField` for raw payload paste
- `RemodexDebugViewModel.resolvePairingForManualConnect()` accepts pasted payload or saved relay pairing
- there is no actual camera scanner implementation
- there is no runtime camera permission flow
- there is no iOS-style dedicated scanner screen

This means the UX text already promises a scanner that does not exist.

## iOS Reference

Reference files:

- [QRScannerView.swift](/home/lws19/codex_android/remodex/CodexMobile/CodexMobile/Views/QRScannerView.swift)
- [ContentView.swift](/home/lws19/codex_android/remodex/CodexMobile/CodexMobile/ContentView.swift)
- [OnboardingView.swift](/home/lws19/codex_android/remodex/CodexMobile/CodexMobile/Views/Onboarding/OnboardingView.swift)

Confirmed iOS behavior:

- scanner is a dedicated full-screen surface, not an inline text field
- scanner uses the real camera feed immediately after permission is granted
- permission flow distinguishes `checking`, `authorized`, and `denied`
- successful scan triggers a scan lock so the same QR does not fire repeatedly
- invalid scans show an error and release the scan lock
- scan result must decode into JSON with:
  - `relay`
  - `sessionId`
- reconnect shell is still shown when saved pairing exists, so relaunch does not force a new scan

## Android Parity Target

Android should match these rules:

- when disconnected and no saved relay pairing exists, the main entry flow should present onboarding and then the camera scanner
- when saved relay pairing exists, Android should preserve the reconnect shell instead of forcing the user to rescan
- scanner should be a dedicated full-screen camera experience, not an embedded text box workflow
- scanner overlay should be visually simple and close to iOS:
  - dark background
  - live camera preview
  - framed scan target
  - short instruction label
- permission denied state should show:
  - camera icon
  - short explanation
  - `Open Settings` action
- invalid QR payloads must show a user-facing error and then unlock scanning
- successful scans must hand off to the existing pairing parser and connect flow instead of duplicating transport logic

## Non-Negotiable Guardrails

- Do not remove saved relay pairing just because the scanner screen opens.
- Do not force a fresh scan on relaunch when reconnect can use a valid saved session.
- Do not add hosted relay defaults or remote production URLs.
- Do not fork pairing validation logic between scanner and manual paste flows.
- Do not bury scanner state management inside `RemodexAndroidRoot.kt`.
- Do not make QR scanning Android-only behaviorally. Match the iOS state machine first.
- Keep manual payload paste available as a fallback for development and desktop testing, but demote it from the primary happy path.

## Recommended Android Implementation

### Scanner stack

Use:

- CameraX for preview lifecycle and permission-safe camera integration
- ML Kit barcode scanning for QR detection

Reasoning:

- both are on-device and local-first
- lifecycle integration is better than rolling low-level camera code directly in Compose
- this is the closest Android-native equivalent to iOS AVFoundation scanning
- it avoids inventing a bridge-side QR decode dependency

### Proposed file layout

Add new Android files:

- `AndroidClient/app/src/main/java/app/remodex/android/RemodexQrScannerScreen.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexQrScannerController.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexQrPayloadDecoder.kt`

Likely touched existing files:

- `AndroidClient/app/build.gradle.kts`
- `AndroidClient/app/src/main/AndroidManifest.xml`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexAndroidRoot.kt`
- `AndroidClient/app/src/main/java/app/remodex/android/RemodexDebugViewModel.kt`

### State ownership

Keep state split like this:

- `RemodexDebugViewModel`
  - owns pairing result handling
  - owns manual connect/connect/reconnect orchestration
  - owns saved relay session state
- scanner controller/screen
  - owns camera permission state
  - owns scanner lock
  - owns transient scan error state
  - emits validated raw QR payload upward

This mirrors iOS separation where the scanner view owns capture state but not transport ownership.

## Detailed Implementation Phases

### Phase 1. Add scanner dependencies and manifest permissions

Add:

- CameraX core
- CameraX lifecycle
- CameraX view
- ML Kit barcode scanning

Manifest changes:

- add `android.permission.CAMERA`

Deliverable:

- project compiles with camera stack available

### Phase 2. Build scanner screen parity shell

Create a dedicated scanner screen that matches iOS structure:

- black full-screen background
- camera preview area
- square rounded scan frame
- instruction text: `Scan QR code from Remodex CLI`
- loading state while permission is being checked
- denied-permission state with `Open Settings`

Deliverable:

- Android scanner visually behaves like iOS before QR decoding is wired

### Phase 3. Implement permission state machine

Port iOS logic conceptually:

- `checking permission`
- `authorized`
- `not determined -> request`
- `denied/restricted -> show settings CTA`

Android parity rule:

- scanner screen should not silently fail or show a blank preview

Deliverable:

- predictable permission UX

### Phase 4. Implement scan lock and QR decoding

Port iOS behavior:

- first successful detection locks the scanner
- invalid QR payload shows error and releases lock
- valid QR payload keeps lock and hands control to connect flow

Validation rules:

- QR must decode to UTF-8 text
- payload must parse as JSON
- payload must contain `relay`
- payload must contain `sessionId`

Deliverable:

- scanner is behaviorally aligned with iOS error handling

### Phase 5. Connect scanner to existing pairing flow

Do not create a second pairing pipeline.

Required integration:

- scanner produces raw payload or parsed `(relayUrl, sessionId)`
- existing pairing parser / saved-session logic remains the source of truth
- success path should call the same connect pathway used by manual paste

Deliverable:

- scan and paste end at the same transport codepath

### Phase 6. Rework Android entry flow to match iOS

Target behavior:

- onboarding CTA opens scanner
- disconnected + no saved pairing -> scanner-first flow
- saved pairing exists -> reconnect shell remains available
- manual paste becomes fallback inside connection setup, not the main primary path

Deliverable:

- Android pairing entry flow matches iOS product expectation

## UX Decisions For Parity

### Primary flow

Primary happy path should be:

1. open app
2. onboarding
3. tap `Scan QR Code`
4. real camera scanner opens
5. valid QR is scanned
6. existing connect flow starts

### Fallback flow

Fallback should still exist for:

- emulator testing
- desktop screenshots
- permission-denied situations
- copied pairing payloads from local relay sessions

Recommended Android fallback:

- keep the existing manual JSON payload field inside the bridge/setup shell
- add a clear secondary action from scanner to manual entry

## Validation Plan

### Compile validation

- `:app:assembleDebug`
- `:app:testDebugUnitTest`

### Manual scanner validation

- first launch with no saved pairing opens onboarding and scanner path
- permission prompt appears on first scan attempt
- denied permission shows settings CTA
- valid Remodex QR connects successfully
- malformed QR shows error and unlocks scanning
- scanning the same code repeatedly does not fire multiple connects
- relaunch with saved pairing shows reconnect shell without forcing scan

### iOS parity validation

Compare Android against iOS for:

- scanner entry point
- camera permission UX
- scanner overlay copy and layout
- invalid scan alert behavior
- scan success locking behavior
- reconnect-with-saved-session behavior

## Risks

### 1. Root file sprawl

`RemodexAndroidRoot.kt` is already carrying too much responsibility.

Mitigation:

- put scanner screen/controller in dedicated files

### 2. Duplicate pairing logic

If scanner path parses and connects differently from manual paste, Android will drift quickly.

Mitigation:

- scanner only emits validated payload into the existing pairing flow

### 3. Permission edge cases across background/resume

Camera permission and preview lifecycle can break across app backgrounding.

Mitigation:

- keep preview state inside a controller tied to lifecycle-aware CameraX bindings
- explicitly retest foreground return after permission denial and after successful scan

### 4. Reconnect regression

It is easy to accidentally force QR rescans after adding a scanner-first flow.

Mitigation:

- preserve `hasSavedRelaySession` as the reconnect source of truth
- keep reconnect shell logic intact while layering scanner flow above it

## Definition Of Done

This work is done only when all of the following are true:

- Android uses the actual device camera to scan QR pairing codes
- scanner permission/error/success behavior matches iOS closely
- successful scan feeds the existing pairing/connect flow
- saved pairing still supports reconnect without forced rescanning
- onboarding copy and CTA now reflect a real scanner, not a placeholder
- manual paste remains available as fallback only
- the implementation does not reintroduce remote-host assumptions

## Recommended Next Implementation Order

1. Add CameraX + ML Kit dependencies and camera permission.
2. Build standalone scanner screen and permission state machine.
3. Wire QR decode + scan lock + error reset.
4. Route valid scans into the existing pairing/connect path.
5. Move Android onboarding/setup flow to scanner-first parity.
6. Validate relaunch and reconnect behavior against iOS.
