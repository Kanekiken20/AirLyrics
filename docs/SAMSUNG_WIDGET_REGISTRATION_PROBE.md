# Samsung widget registration probe

This is a **debug-only host-discovery experiment**, not a verified fix or live AOD implementation. The standard widget was absent from the user's Samsung lock-screen picker, and the user reports Good Lock is incompatible with the phone.

The community author's [One UI widget example](https://github.com/manaratalmuslim/OneUI-LockScreen-Widgets) describes additional resource metadata: `targetHost=lockAndAod`, lock/monotone size flags, tiny layout references and `SamsungBasicInteraction=SEP10`. These fields have **not** been established here as an official Samsung API contract. The claim being tested is whether they affect native picker eligibility. Resource compilation and ordinary Android widget tests cannot prove that claim.

The probe adds those resource declarations only under `app/src/debug`, lowers the debug minimum widget size to 56 dp, requests tiny/small variants and uses a compact two-line layout. The main/release widget descriptor and layout remain unchanged. This makes no private framework calls and adds no permissions, reflection, root, accessibility service, wake locks, background timers or companion app. It does not install or require Good Lock. It does not enable live ambient lyric updates.

Package remains `com.andsi.airlyrics.sandbox`, signed with the existing test key. Version: `1.2.10-test-samsung-probe`. It updates AirLyrics Test; production AirLyrics is separate.

After updating the test APK, close and reopen Samsung's lock-screen editor and inspect its ordinary widget picker for **AirLyrics Panel**. If present, record the offered size/placement and then check actual line changes with the screen locked and awake. A positive listing result does not establish the requested position above the music player, or any AOD visibility/refresh. If absent, record the experiment as failed on this firmware rather than interpreting a successful build as compatibility.

Do not treat the two prior facts (standard picker omission and Good Lock incompatibility) as proof that all Samsung registration mechanisms are unavailable. Equally, these experimental XML declarations do not prove that a third-party app can access Samsung's AOD renderer.

## Verification

Built with `:app:testDebugUnitTest` filtered to the fixed-panel, artwork/palette, presentation-lifecycle and display-settings suites, followed by `:app:assembleDebug`, using `-Pairlyrics.skipRustBuild=true`. Result: **BUILD SUCCESSFUL**, **26 tests, 0 failures/errors/skips**. This is targeted validation of the resource-only debug change; the preceding panel implementation had a separate full-suite run. Lint was not rerun for this probe.

Inspected the compiled APK XML: 56 dp minimum bounds, category value 3, Samsung target/style/size flags value 3, tiny/monotone layout references and `SamsungBasicInteraction=SEP10` are present. Verified package `com.andsi.airlyrics.sandbox`, version `1.2.10-test-samsung-probe`, the unchanged test certificate and 16-KiB alignment.

- APK: `/workspace/AirLyrics-delivery/AirLyrics-Test-Samsung-Probe.apk`
- Bytes: **21,567,970**
- SHA-256: `f838135634988fa59ab6e28a2d7c7c16dee57cbff4354ce203b26c8aa26676da`

No target device is attached. Native picker acceptance, exact placement and live AOD remain unverified. No compatibility claim follows from these build results.
