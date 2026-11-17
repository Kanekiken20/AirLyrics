# AirLyrics Test — widget colors

Latest test APK: [AirLyrics-Test-Widget-Colors.apk](AirLyrics-Test-Widget-Colors.apk). On GitHub, open the file and choose **Download raw file**.

- Package: `com.andsi.airlyrics.sandbox`
- Version: `1.2.10-test-widget-colors` (code 21)
- Size: 21,775,696 bytes
- Test certificate SHA-256: `76185488cc77e150630aca1866108e9dd8a8f8c61c31f489ef1e97a247734452`
- APK SHA-256: `d25ad8ae939b45f5bdf4a1208280666a1716499af6fde4dc982fded242aa7be8`

Updates the separate AirLyrics Test install; the production app has a different application ID. Keep test storage separate from production storage.

Includes album/manual widget colors, a transparent-background option and experimental Samsung widget registration. Samsung may override colors. Exact placement above the media player and live AOD remain unimplemented/unverified; this is not a complete compatibility claim. The user reported blank tiles in the preceding registration probe; the color/transparent build still needs device verification.

38 targeted tests passed; source/JVM architecture checks and localization passed. See [the implementation and test report](../docs/WIDGET_COLOR_TEST_BUILD.md).
