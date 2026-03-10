# AndroidClient

Android scaffold for the Remodex mobile client.

## Modules

- `app`: Android application shell and Compose entry point
- `core-protocol`: JSON-RPC and generic JSON value types
- `core-model`: shared Remodex domain models ported from the iOS client

## Current Scope

This scaffold intentionally stops at build structure plus protocol/model porting.
Transport, persistence, pairing, and feature modules will be added in later steps.

## Ported Model Areas

- JSON-RPC envelope and generic JSON helpers
- thread, message, collaboration, runtime, skill, and attachment models
- approval, host capability, git action, and revert/change-set models
- flexible timestamp serializers for bridge payload compatibility

## Tooling Baseline

- Android Gradle Plugin `8.10.0`
- Kotlin `2.2.20`
- JDK `17`
- Compile SDK `36`

## Notes

- The Gradle wrapper was not generated in this workspace because `gradle` was not available in PATH.
- Open this folder in Android Studio or generate the wrapper later in an environment with Gradle installed.
