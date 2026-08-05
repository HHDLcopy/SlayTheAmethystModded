# Style Notes

## Visual language

The launcher is a compact Material 3 utility surface. Color is semantic and generated from the selected launcher seed; do not hard-code a feature color. Use tonal containers, restrained outlines, and theme-provided text hierarchy. Cards group one settings domain and should not be nested.

## Layout

- Use 16 dp horizontal content padding and 8 dp spacing for related controls.
- Keep labels and values scannable at Android font scaling settings.
- Use full-width fields for URLs and other long developer values.
- Keep commands close to the fields they commit.

## Components and states

- Binary preferences use a switch with a state label and explanatory description.
- Editable endpoints use single-line outlined fields, but must remain horizontally scrollable and readable.
- Disable controls while the owning operation is busy.
- Mark invalid fields with Material error color and supporting text; do not rely on color alone.
- Conditional detail uses a short fade or content-size transition.

## Voice

Use direct, sentence-case operational copy. Name protocols and ports exactly. Explain consequences and prerequisites, especially disconnect requirements. Avoid promotional language, emoji, and decorative labels. Keep English and Chinese resources behaviorally equivalent.

## Accessibility

Retain Material touch targets, semantics, contrast roles, keyboard focus, and screen-reader labels. Dynamic status and validation must be conveyed in text.
