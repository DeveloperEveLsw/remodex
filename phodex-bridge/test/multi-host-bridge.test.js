// FILE: multi-host-bridge.test.js
// Purpose: Verifies multi-host bridge metadata and relay role compatibility helpers.
// Layer: Unit test
// Exports: node:test suite

const test = require("node:test");
const assert = require("node:assert/strict");

const {
  createBridgeHostInfo,
  hostPlatformDisplayName,
  serializeBridgeHostInfoNotification,
} = require("../src/host-info");
const { normalizeRelayRole } = require("../../relay/relay-role");

test("normalizeRelayRole keeps backward compatibility for legacy iphone clients", () => {
  assert.equal(normalizeRelayRole("iphone"), "mobile");
  assert.equal(normalizeRelayRole("mobile"), "mobile");
  assert.equal(normalizeRelayRole(" mac "), "mac");
  assert.equal(normalizeRelayRole(undefined), "");
});

test("hostPlatformDisplayName returns stable user-facing labels", () => {
  assert.equal(hostPlatformDisplayName("darwin"), "macOS");
  assert.equal(hostPlatformDisplayName("win32"), "Windows");
  assert.equal(hostPlatformDisplayName("linux"), "Linux");
  assert.equal(hostPlatformDisplayName("freebsd"), "freebsd");
});

test("createBridgeHostInfo reports platform capabilities", () => {
  const info = createBridgeHostInfo({
    refreshEnabled: false,
  }, {
    platform: "win32",
  });

  assert.deepEqual(info, {
    platform: "win32",
    platformDisplayName: "Windows",
    capabilities: {
      desktopRefreshAvailable: false,
      desktopRefreshEnabled: false,
      desktopAppRoutingAvailable: false,
    },
  });
});

test("serializeBridgeHostInfoNotification emits a JSON-RPC-like notification envelope", () => {
  const payload = JSON.parse(serializeBridgeHostInfoNotification({
    refreshEnabled: true,
  }, {
    platform: "darwin",
  }));

  assert.equal(payload.method, "bridge/hostInfo");
  assert.equal(typeof payload.params.platform, "string");
  assert.equal(typeof payload.params.platformDisplayName, "string");
  assert.equal(typeof payload.params.capabilities.desktopRefreshAvailable, "boolean");
  assert.equal(typeof payload.params.capabilities.desktopRefreshEnabled, "boolean");
  assert.equal(typeof payload.params.capabilities.desktopAppRoutingAvailable, "boolean");
});
