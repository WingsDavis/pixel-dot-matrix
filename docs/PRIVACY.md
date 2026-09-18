# Privacy

Pixel Dot Matrix is designed as a local-first application.

## Stored Data

The phone may store timer sessions, task names, incidents, DNS block rules,
watch-face settings, and selected logo artwork. The watch stores the latest
synchronized timer and watch-face configuration needed for offline display.

## Device Communication

Phone/watch synchronization uses the Wear OS Data Layer between paired
devices. The project does not require a project-operated cloud account or
analytics service.

## Permissions

- Physical activity supports step-based recovery flows.
- Overlay permission supports the optional full-screen intervention.
- VPN permission supports user-enabled local DNS distraction filtering.
- Sensor permissions support optional watch biometric features.

Permissions are requested for the relevant feature and can be revoked through
Android settings. Local records and watch artwork can be deleted from the app.
