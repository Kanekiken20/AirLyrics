# Widget color and transparent-background test

User feedback after the Samsung registration probe shows two blank tiles under the lock-screen clock and the existing lyric notification below them. This does not establish successful lyric rendering, the requested placement above the player, or AOD support. Two possible app-side causes were identified: panel content is hidden when its separate enable preference is off, and an opaque background may become a solid silhouette when a host applies monochrome rendering. The screenshot alone does not establish which cause applies.

This build adds separate widget controls under Floating → Lock screen, docking & battery:

- **Use album colors for widget:** existing artwork palette, or manually chosen colors when off.
- **Transparent widget background:** on by default; removes the opaque rectangle from both initial layouts and RemoteViews updates.
- **Text color / Background:** existing native color controls. Background color appears only when transparency is off. Text colors are made opaque so an alpha-zero selection cannot accidentally hide the text.

Manual mode skips artwork-worker updates. Privacy placeholders retain neutral colors rather than disclosing album-derived colors. Changing transparency updates existing widgets immediately even if the lyric text did not change. Samsung may still apply its own monochrome colors; exact physical rendering is unverified.

Adding/updating a placed widget now enables the panel only if the user has never explicitly set its enable preference. An explicit off choice remains off, and PUBLIC disclosure is never selected automatically. **Enable fixed lyric widget** and **Public lyrics** still need checking in the app if the widget remains blank.

Package/version: `com.andsi.airlyrics.sandbox`, `1.2.10-test-widget-colors`. The existing sandbox test signature is retained. No new permissions or live AOD mode were added, and Samsung still owns placement.

Validation: 38 targeted widget, palette/artwork, presentation-lifecycle, notification and settings tests passed with no skips/errors/failures. Source/JVM architecture checks and their 27 checker tests passed. Localization passed. Debug APK built; signature and 16-KiB alignment verified. Full-suite tests and lint were not rerun for this increment. No physical phone is connected here.

- APK: `/workspace/AirLyrics-delivery/AirLyrics-Test-Widget-Colors.apk`
- Bytes: `21775696`
- SHA-256: `d25ad8ae939b45f5bdf4a1208280666a1716499af6fde4dc982fded242aa7be8`
