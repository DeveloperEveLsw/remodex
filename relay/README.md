# Relay

This folder contains the thin WebSocket relay used by the default hosted Remodex pairing flow.

In production, the default hosted relay runs on my VPS. If you want, you can inspect this code, fork it, and host the same relay yourself.

## What It Does

- accepts WebSocket connections at `/relay/{sessionId}`
- pairs one host with one live mobile client for a session
- forwards JSON-RPC traffic between host and mobile
- replays a small in-memory history buffer to a reconnecting mobile client
- exposes lightweight stats for a health endpoint

## What It Does Not Do

- it does not run Codex
- it does not execute git commands
- it does not contain your repository checkout
- it does not persist the local workspace on the server

Codex, git, and local file operations still run on the user's Mac.

## Protocol Notes

- path: `/relay/{sessionId}`
- required header: `x-role: mac` or `x-role: mobile` (`iphone` remains accepted as a legacy alias)
- close code `4000`: invalid session or role
- close code `4001`: previous Mac connection replaced
- close code `4002`: session unavailable / Mac disconnected
- close code `4003`: previous mobile connection replaced

## Usage

`relay.js` exports:

- `setupRelay(wss)`
- `getRelayStats()`

It is meant to be attached to a `ws` `WebSocketServer` from your own HTTP server.
