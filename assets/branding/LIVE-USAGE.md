# Live Expository branding

The approved [canonical square SVG](expository-v1-icon-square.svg) is now used in the website's visible header and its SVG favicon. The existing EXPO​SITORY wordmark and subtitle remain, with the icon marked as decorative for screen readers. The self-hosted image is permitted by the site's existing Content Security Policy.

The native Android Transfer application now declares `@drawable/expository_launcher_icon` as its launcher and round icon. This Android VectorDrawable is a platform-native translation of the same square v1 composition (pastel storage tiers, one disk tilt, the white glass and three parallel horizontal-only pastel echoes) and requires no runtime graphics dependency.

The SVG files in this directory remain the canonical design sources. If the v1 design changes, update the Android VectorDrawable accordingly. Android launchers may apply device-specific masks to icons, so physical-device screenshots remain a separate visual acceptance gate.

These edits change branding presentation only. They do not expand permissions, modify transfer operations, or change authentication or repository browsing behaviour.
