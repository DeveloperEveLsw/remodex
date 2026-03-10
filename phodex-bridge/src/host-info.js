// FILE: host-info.js
// Purpose: Produces host metadata payloads for mobile clients without pulling in transport dependencies.
// Layer: CLI helper
// Exports: createBridgeHostInfo, hostPlatformDisplayName, serializeBridgeHostInfoNotification

function serializeBridgeHostInfoNotification(config, { platform = process.platform } = {}) {
  return JSON.stringify({
    method: "bridge/hostInfo",
    params: createBridgeHostInfo(config, { platform }),
  });
}

function createBridgeHostInfo(config, { platform = process.platform } = {}) {
  const desktopSupportAvailable = platform === "darwin";

  return {
    platform,
    platformDisplayName: hostPlatformDisplayName(platform),
    capabilities: {
      desktopRefreshAvailable: desktopSupportAvailable,
      desktopRefreshEnabled: Boolean(config.refreshEnabled),
      desktopAppRoutingAvailable: desktopSupportAvailable,
    },
  };
}

function hostPlatformDisplayName(platform) {
  switch (platform) {
    case "darwin":
      return "macOS";
    case "win32":
      return "Windows";
    case "linux":
      return "Linux";
    default:
      return platform || "Unknown";
  }
}

module.exports = {
  createBridgeHostInfo,
  hostPlatformDisplayName,
  serializeBridgeHostInfoNotification,
};
