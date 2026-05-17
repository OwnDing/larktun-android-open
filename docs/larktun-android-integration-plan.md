# Larktun Android Integration Plan

## Background

`larktun-android-open` is an open source Kotlin and Jetpack Compose Android app. It already has a complete manual connection model based on `ConnectionProfile`, plus app-only WireGuard and Tailscale routing support through `core:tunnel`.

Larktun needs a first-party account flow on top of that base:

- The app display name should be `Larktun`.
- The launcher icon should use Larktun brand assets.
- The Connect screen should expose a `My` entry for Larktun login and account state.
- After login, the Connect screen should show devices from the user's Larktun tailnet.
- Tailnet access must stay app-only and must not rely on Android system VPN.

## Current Structure

Important modules and files:

- `app`: app shell, manifest, navigation host, launcher resources.
- `feature:connections`: Connect screen, manual connection list, connection actions.
- `feature:tunnel`: existing manual WireGuard and Tailscale tunnel configuration UI.
- `core:data`: Room database, repositories, preferences, encrypted storage helpers.
- `core:tunnel`: app-only tunnel abstraction and existing `TailscaleTunnel`.
- `rclone-android/go/tsbridge`: gomobile Go bridge wrapping `tailscale.com/tsnet`.

The existing Connect list is driven by Room rows in `ConnectionProfile`. Manual connections should remain independent from Larktun account devices.

## Design Decision

Use an account-scoped app-only tailnet runtime and render Larktun devices as their own section on the Connect screen.

Do not auto-write every Larktun device into `ConnectionProfile`. A device can be turned into a saved manual connection only after explicit user action.

This keeps the existing manual connection model stable, avoids accidental edits/deletes during peer refresh, and lets the app display live peer state directly from tsnet.

## Brand Changes

1. Change app display name from `Haven` to `Larktun`.
2. Replace Android launcher icon resources with Larktun adaptive icon assets generated from existing iOS or shared brand files.
3. Keep `applicationId`, package names, and Android authorities unchanged for the first implementation phase unless a release migration decision is made separately.

## Larktun Account Layer

Add a small account module under `core:data` or a new focused package such as `core:data/larktun`.

Responsibilities:

- Fetch captcha from `GET /api/auth/captcha`.
- Login through `POST /api/auth/device-login`.
- Store account session fields:
  - `authKey`
  - `serverUrl`
  - `accessToken`
  - `account`
  - `entitlement`
  - login timestamp
- Encrypt sensitive values at rest using the existing Android Keystore and Tink helpers.
- Provide observable account state to the UI.

The API model should follow the iOS implementation:

```json
{
  "authKey": "tskey-auth-...",
  "serverUrl": "https://hs.larktun.com",
  "accessToken": "...",
  "account": {},
  "entitlement": {}
}
```

`meshyra-android` has an older login implementation that can be used as a reference, but the Android open app should align with the newer iOS response shape.

## App-only Tailnet Runtime

Extend the existing `tsbridge` Go bridge and Kotlin tunnel wrapper into an account-scoped runtime:

- Start tsnet with the login response `authKey` and `serverUrl`.
- Use a stable state directory such as `filesDir/larktun-tailnet`.
- Reuse node state across app launches so reusable auth keys are not required for every start.
- Expose status JSON similar to the iOS `AppEngine.StatusJSON()` payload:
  - current status and backend state
  - self node hostname, DNS name, and Tailscale IPs
  - tailnet name and MagicDNS metadata
  - peers with ID, display name, DNS name, IPs, OS, online, active, relay, last seen, and key expiry
- Expose optional ping/check helpers after the device list is working.

This runtime is app-only. It does not create or request Android `VpnService`.

## Connect Screen UX

Add a `My` icon to the Connect screen top bar.

Unauthenticated state:

- The `My` icon opens the Larktun login screen.
- The existing manual connection quick-connect field, empty state, add button, and bottom navigation remain available.

Authenticated state:

- The `My` icon opens an account panel.
- A `Larktun Devices` section appears above manual connections.
- Devices are loaded from app-only tsnet status peers.
- Devices sort by active, online, offline, then by display name.
- Each row should show name, primary IP or MagicDNS, OS if available, and a compact status indicator.

If no peers are returned:

- Show a small empty row explaining that no Larktun devices are available yet.
- Do not hide manual connections.

## Device Actions

Current action scope:

- Tap or long-press a Larktun device to open a menu using the same `DropdownMenu` style as the existing manual connection rows.
- Menu entries should include:
  - `Ping`: run an app-only tsnet ping against the peer's primary Tailscale IP and show latency/route feedback.
  - `SSH`: create an ephemeral SSH profile for the selected peer and connect through the logged-in Larktun app-only runtime. The user supplies SSH username and authentication details through the existing password/key dialog.
  - `Open Web`: open the peer's HTTP service through a short-lived loopback reverse proxy so the Android browser talks to `127.0.0.1`, while the app forwards bytes to the peer through tsnet. Default to port `80` for the first pass. This keeps web browsing app-only and does not require system VPN.
  - `Copy Address`: copy MagicDNS name or primary Tailscale IP.
- Larktun peer actions must not mutate or delete the live peer list.
- Larktun peer actions should not create manual `ConnectionProfile` rows unless the user explicitly chooses a future `Save as connection` action.
- When an action requires a running Larktun runtime, show a clear error if the user has signed out or the runtime is still starting.

Optional actions after the first working pass:

- Save as manual SSH connection.
- Taildrop send file.
- SFTP/file browser through the app-only SSH connection path.

## Integration With Manual Connections

Manual connections continue to use `ConnectionProfile`.

When the user chooses `Save as connection`, create a regular `ConnectionProfile` with:

- `connectionType = "SSH"` by default.
- `host` set to MagicDNS or primary Tailscale IP.
- `tunnelConfigId` or an equivalent Larktun runtime reference so the connection routes through app-only tsnet.

If the current `TunnelConfig` model is not expressive enough for the logged-in Larktun runtime, add a separate Larktun tunnel route path instead of storing auth material in every profile.

## My Account Panel

Show:

- Account name or username.
- Plan and entitlement summary when available.
- Device usage and limit when available.
- Control server host.
- App-only tailnet status.
- Current Android node hostname and IPs.
- Refresh devices.
- Sign out.

Sign out should:

- Stop the Larktun app-only tailnet runtime.
- Clear stored account session.
- Clear UI state and peer list.
- Leave manual `ConnectionProfile` rows untouched.
- Ask separately before deleting the tsnet state directory, because that removes this Android node identity.

## Implementation Phases

### Phase 1: Brand and Documentation

- Write this implementation plan.
- Change visible app name to `Larktun`.
- Replace launcher icon assets with Larktun brand icons.

### Phase 2: Account Foundation

- Add Larktun API client and models.
- Add encrypted account session storage.
- Add account repository with observable auth state.
- Add Hilt bindings.
- Add focused unit tests for API response parsing and session storage.

### Phase 3: Login UI

- Add login dialog or full-screen overlay from the Connect screen `My` icon.
- Implement captcha loading and refresh.
- Implement username/password/captcha login.
- Add manual auth key and control URL advanced path.
- Add loading, error, and retry states.

### Phase 4: App-only Tailnet Runtime

- Extend `tsbridge` with status JSON and peer extraction.
- Add Kotlin manager for account-scoped tsnet lifecycle.
- Start runtime after login.
- Resume runtime from stored session on app startup.
- Stop runtime on sign out.

### Phase 5: Device List

- Map `StatusJSON().peers` into stable Compose device row models.
- Render `Larktun Devices` above manual connections.
- Add refresh and polling behavior.
- Add empty, loading, and error states.

### Phase 6: Device Connection Actions

- Add a Larktun device menu matching the existing manual connection dropdown style.
- Implement Ping through the Go `tsbridge` runtime and surface the result in a toast/snackbar.
- Implement SSH by using the existing Android SSH password/key dialog with a special shared Larktun tunnel route, so the peer connection reuses the logged-in app-only tsnet runtime.
- Implement Open Web with a loopback reverse proxy backed by the shared Larktun tunnel. The proxy should be replaced when another peer is opened and cleaned up automatically after a short idle window.
- Add `Save as connection` after the temporary connection path is stable.

## Verification

Minimum checks for each implementation slice:

- `./gradlew :app:assembleArm64Debug`
- Unit tests for newly added repositories, parsers, and mappers.
- Manual login with a real Larktun account.
- Verify that the Android node appears in the Larktun/headscale backend.
- Verify that peers appear on the Connect screen after login.
- Verify that manual connections still render and connect as before.
- Verify sign out stops the account runtime and does not delete manual connections.

## Open Decisions

- Whether to change `applicationId` from `sh.haven.app` to a Larktun package id. This is a release and migration decision, not required for the first app display-name change.
- Whether to keep all existing Haven terminal features visible in the open app or gradually simplify the top bar around Larktun workflows.
- Whether saved Larktun device connections should reference a shared logged-in runtime or generate their own `TunnelConfig` rows.
