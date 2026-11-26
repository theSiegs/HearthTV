# esphome-proxy (vendored)

The ESPHome Bluetooth proxy that "Share Bluetooth with Home Assistant" runs (`BtProxy.java`,
`HearthBtProxyService.java`). Copied from Glint so Hearth TV builds on its own:

- Repository: `theSiegs/glint`
- Branch: `glint/panelstart`, at `38a1f3840713dc6dc9e22e40fabea0661edab4a8`
- Last change to `esphome-proxy/` there: `30fc2d6f5319a8262c98bd915f03457f15c15824`
- Design: `docs/design/esphome-proxy.md` on that branch

`core/` (`:esphome-proxy-core`, plain JVM, with its tests) and `android/` (`:esphome-proxy-android`) are Glint's
sources unchanged, except:

- Build files: Groovy `build.gradle` without Glint's version catalog, and the projects named `:esphome-proxy-core` and
  `:esphome-proxy-android` (`android/settings.gradle`); compile SDK 36 and min SDK 28 as the app's.
- `BleScanner.SERVICES` (the screen-off filters) also has 0xFEF3 and 0xFCF1 (Google: Android phones and watches),
  0xFD44 (Apple) and 0xFCB2 (Detecting Unwanted Location Trackers). Google's Find Hub trackers advertise under 0xFEAA
  (frame 0x40/0x41), already listed, as are Fast Pair (0xFE2C) and Microsoft (company id 6) in `MAKERS`.

To take a newer Glint version: copy both folders over these, keep the two changes above (or send the filter change
to Glint first), and run `./gradlew :esphome-proxy-core:test :app:testDebugUnitTest`. The core's opt-in interop test
runs a real aioesphomeapi client when `ESPHOME_PROXY_PYTHON` names a Python that has it.
