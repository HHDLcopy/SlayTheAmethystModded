# Slay the Amethyst Launcher Product UI

Use this system for launcher settings and operational product surfaces.

## Source of truth

- `app/src/main/java/io/stamethyst/ui/theme/LauncherTheme.kt`
- `app/src/main/java/io/stamethyst/ui/settings/sections/SettingsDeveloperSections.kt`
- `app/src/main/java/io/stamethyst/ui/settings/components/`
- `app/src/main/res/values*/strings.xml`

## Rules

- Use Material 3 components and semantic `MaterialTheme` roles.
- Support the launcher's light, dark, and user-selected seed-color schemes.
- Keep settings compact, task-focused, and grouped in `SettingsSectionCard` containers.
- Use `OutlinedTextField` for editable addresses and show validation beside the affected input.
- Use switches only for binary settings and disable controls while work is busy.
- Prefer short fade or size transitions for conditional settings; avoid decorative motion.
- Reuse localized string resources. Developer copy should state effects and prerequisites directly.
- Preserve minimum Android touch targets and visible error states.
