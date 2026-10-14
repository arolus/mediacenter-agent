# mediacenter-agent

Public repo: the Kotlin APK agent (`android/`, package `com.mediacenter.tv`, foreground service), the TV web UI (`tv/`) and
`tools/tv-manifest.js`. Part of the MediaCenter container folder (not a monorepo). NO secrets in this repo, ever
(keystore, configs and `.env*` live outside git).

## Verification

From the repo root:

    for f in tools/*.js tv/*.js; do node --check "$f" || exit 1; done && echo GREEN

Green = prints `GREEN`, no SyntaxError. TODO: no automated tests; the APK build (`android/`) needs the Android SDK and
device/node checks need live hardware, so they are not part of this command.
A task is not done until this is green and the result is reported.

## Notes

- Nodes update by pulling git after a push (GitHub Action `notify-update`); the agent only exits, the supervisor pulls.
- Shipping is done from the parent folder with `bash ship.sh` (secret scan + syntax check + commit + push).
- Keep the TV markup compatible with old Chrome (Chrome 83): no `inset-*`, `aspect-[…]`, flex `gap-*`.
- Android 12+ blocks background activity starts: launch player/trailer/settings from the foreground app, not the service.
