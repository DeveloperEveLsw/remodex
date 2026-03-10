# AndroidClient

Android scaffold for the Remodex mobile client.

## Modules

- `app`: Android application shell and Compose entry point
- `core-protocol`: JSON-RPC and generic JSON value types
- `core-model`: shared Remodex domain models ported from the iOS client
- `core-pairing`: QR payload parsing and relay URL normalization
- `core-transport`: WebSocket transport, initialize handshake, and reconnect basics

## Current Scope

This scaffold intentionally stops at build structure plus protocol/model porting.
Transport, persistence, pairing, and feature modules will be added in later steps.

## Ported Model Areas

- JSON-RPC envelope and generic JSON helpers
- thread, message, collaboration, runtime, skill, and attachment models
- approval, host capability, git action, and revert/change-set models
- flexible timestamp serializers for bridge payload compatibility

## Current Debug Flow

- paste the QR JSON payload into the app shell
- parse and normalize the relay URL
- connect with `x-role: mobile`
- run `initialize` / `initialized`
- surface `bridge/hostInfo` and basic notification state

## Tooling Baseline

- Android Gradle Plugin `8.10.0`
- Kotlin `2.2.20`
- JDK `17`
- Compile SDK `36`

## Notes

- The Gradle wrapper was not generated in this workspace because `gradle` was not available in PATH.
- Open this folder in Android Studio or generate the wrapper later in an environment with Gradle installed.
