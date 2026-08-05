# Launcher Product Design System

This system records the existing Android launcher's Material 3 visual and interaction language. It is intended for settings, diagnostics, and operational launcher controls, not marketing pages.

## Sources consulted

- `LauncherTheme.kt`: generated light/dark semantic color schemes and colorless fallback.
- `SettingsDeveloperSections.kt`: section cards, switches, outlined fields, buttons, validation, spacing, and conditional animation.
- Launcher localized string resources: concise operational voice in English, Simplified Chinese, and Traditional Chinese.

## Index

- `tokens/colors_and_type.css`: portable approximations of canonical Compose semantic roles.
- `brand/style-notes.md`: component, layout, motion, and content rules.
- `SKILL.md`: agent-facing usage constraints.

Compose source remains authoritative. CSS tokens are for OpenDesign artifacts and should not replace Android theme code.
