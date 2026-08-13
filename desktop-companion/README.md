# Slay the Amethyst Online Desktop Companion

Windows-oriented Rust system tray companion for Slay the Amethyst EasyTier
rooms. It uses the same `online-service` Room API and writes the credential-free
runtime state consumed by the bundled runtime-compat mod.

Right-click the tray icon to refresh rooms or select a room. A successful
connection opens a system dialog and copies the Together in Spire address
`host:33455` to the clipboard. The tray menu has no main window.

## Development

Install a current Rust MSVC toolchain, then run from this directory:

```powershell
cargo run --release
```

Build a distributable executable with:

```powershell
cargo build --release
```

The executable is at `target\release\slay-the-amethyst-online.exe`.

## Configuration

The first run creates:

```text
%APPDATA%\SlayTheAmethystOnline\settings.json
```

Select **Open settings** from the tray menu and set at least
`roomApiBaseUrl` and `easytierExecutable`, then restart the companion after
saving. Example:

```json
{
  "roomApiBaseUrl": "https://example.invalid",
  "playerName": "Player",
  "playerId": "a-stable-uuid",
  "easytierExecutable": "C:\\Tools\\easytier-core.exe",
  "roomPasswords": {
    "private-room": "room password"
  }
}
```

`roomPasswords` is optional. It permits one-click joins for password-protected
rooms, so `settings.json` should remain in the user's private profile and must
not be shared.

Runtime files are stored in `%APPDATA%\SlayTheAmethystOnline\runtime`:

- `connection-state.json`: credential-free state passed to the game JVM.
- `easytier.toml`: per-session EasyTier configuration. It contains the room
  network secret and is deleted when the companion disconnects.
- `easytier.log`: EasyTier child-process output.

Room API session and owner bearer tokens remain memory-only. They are never
written to `settings.json` or `connection-state.json`.

## Required Setup

1. Start an `online-service` instance with EasyTier room support enabled.
2. Download the official Windows `easytier-core.exe` and set its full path in
   `settings.json`.
3. Set the service's public HTTPS URL, player name, and optional room passwords.
4. Start the companion, right-click its notification-area icon, refresh rooms,
   and select a room.

The companion intentionally does not bundle an EasyTier executable. This keeps
the official binary separately replaceable and lets operators update it without
rebuilding the companion.

## EasyTier Notice

EasyTier is an independent project: <https://github.com/EasyTier/EasyTier>.
Its upstream license is LGPL-3.0. This desktop companion invokes an official,
user-supplied `easytier-core.exe` as a separate process and does not modify or
embed it. Any future installer that distributes an EasyTier binary must include
its complete LGPL-3.0 notice, upstream copyright notices and source location,
and must permit replacement of that binary.
