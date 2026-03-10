// FILE: relay-role.js
// Purpose: Normalizes relay client role names while keeping backward compatibility.
// Layer: Relay helper
// Exports: normalizeRelayRole

function normalizeRelayRole(rawRole) {
  const role = typeof rawRole === "string" ? rawRole.trim().toLowerCase() : "";
  if (role === "iphone") {
    return "mobile";
  }
  return role;
}

module.exports = {
  normalizeRelayRole,
};
