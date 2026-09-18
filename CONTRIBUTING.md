# Contributing to Pixel Dot Matrix

Thank you for contributing.

## Before You Start

1. Search existing issues and pull requests.
2. Open an issue before large architectural or user-facing changes.
3. Keep changes focused on one feature or fix.

## Development Setup

Use JDK 17 and Android SDK API 36. Create `local.properties` locally; never
commit SDK paths, credentials, keystores, or `.env` files.

Run the required checks before opening a pull request:

```bash
./gradlew testDebugUnitTest \
  :app:assembleDebug \
  :wear:assembleDebug \
  :watchface:assembleDebug
```

## Branches

Name branches for the change itself:

- `feature/<feature-name>`
- `fix/<bug-name>`
- `docs/<documentation-name>`
- `refactor/<area-name>`

Examples: `feature/task-queue`, `fix/crown-navigation`, and
`docs/privacy-model`.

## Pull Requests

- Explain the problem and the chosen solution.
- Link the relevant issue when one exists.
- Include phone/watch screenshots for visible UI changes.
- Add focused tests for non-trivial logic.
- Do not change application IDs without prior discussion; they affect updates
  and phone/watch pairing.
- Update `IMPLEMENTATION.md` or `FEATURE_ROADMAP.md` when behavior or roadmap
  status changes.

## Code Style

- Follow existing Kotlin and Jetpack Compose patterns.
- Prefer platform and existing project APIs over new dependencies.
- Keep round-screen layout constraints and accessibility in mind.
- Avoid unrelated formatting or refactoring in feature pull requests.

All participants must follow the [Code of Conduct](CODE_OF_CONDUCT.md).
