# Samvad

Android messenger (Kotlin, Jetpack Compose) on Firebase. App in `android-native/`, backend in `firebase/`,
specs and status in `docs/`. Local build and run: `docs/LOCAL_BUILD.md`.

## UI/UX skills

Taste, a11y, bans, voice, and icon library for this project live in `.ux-profile.md`.
Run `setup-ui-ux-skills` again only to change those defaults.
Before UI or microcopy work, read `.ux-profile.md`, then the matching domain skill (page-patterns, viewports, forms, surfaces, loaders, empty-states, copy, motion).
Before creating a new page, read `page-patterns` and name the pattern. Do not write page markup until the pattern is named.
Layouts must pass `viewports` at 360dp (compact phone) and 840dp (tablet or unfolded foldable), and at 200% font scale.
Before finishing UI, run `ux-audit`.
