# WirelessSignalMeter Interface

The app is a field measurement tool. Keep live readings easy to compare, actions
predictable, and diagnostic details available without filling every screen with
explanations.

## Implemented In This Pass

- Tools and settings share Miuix `ArrowPreference` and `BasicComponent` rows:
  theme colors, aligned icons, and a single click target per action.
- Tools are grouped into field tests and diagnostics. All five existing tools
  remain available; descriptive cards no longer push actions below the fold.
- Device capabilities open from one settings row instead of displaying every
  capability inline. No capability information has been removed.
- About contains the current version, a manual GitHub release check, the
  repository issue tracker, and open source acknowledgments.
- Acknowledgments use the existing compact title bar and retain independent
  source and license links.
- Settings and acknowledgments use a centered content width of at most 600 dp.
  Rows grow with text rather than clipping large system fonts.
- Appearance now offers three independent interface styles: Classic cards,
  Simple list, and Compact readings. They change signal and carrier metrics,
  parameter rows, settings, tools, and acknowledgment spacing without changing
  the selected color palette.
- Android Telephony and DIAG share the same signal metric and parameter
  components. Classic cards place primary readings in three columns when there
  is room; narrow screens and large fonts use rows. Simple list uses labeled
  rows, and Compact readings uses closer spacing with monospaced values.
- The shared operator card uses the same Miuix card as the signal sections.
  Operator, radio mode, cell name, and carrier aggregation occupy separate rows.
- Primary signal readings and carrier measurements no longer nest metric cards
  inside section cards. Long parameter values wrap rather than being truncated.
- Neighbor tables and signal trends follow the same section style and spacing
  in Android Telephony and DIAG. The chart and table render directly on their
  section background.
- Floating navigation and liquid glass both default to off. Standard
  navigation highlights the current tab and animates its label into place.
  Floating navigation uses the original labeled bar and draggable indicator. Glass requires floating
  navigation and runtime shader support.
  Existing explicit preferences remain stored.
- The appearance preview follows the selected style and navigation mode.
  Changes propagate through `NsTheme`, including the Android signal page's
  separate Compose view, without remounting its cellular fragment.
- Display mode uses rounded segments and stacks when space is limited.
- The default palette uses the Miuix neutral background and blue accent.
  Wallpaper colors are opt-in. Wallpaper and custom shades use Miuix's theme controller. Palette choices
  expand in the page and apply without an activity restart.
- Settings feedback and shared confirmation dialogs use Miuix window dialogs.
  Map and speed test panels use Miuix bottom sheets, and signal, signaling and
  replay menus use its window popups. The network lock page uses Miuix tabs.
  Page and tab transitions use the Miuix spring animation.

## Next Screen Improvements

| Screen | Proposed Change | Verification Needed |
| --- | --- | --- |
| Signaling | Keep filters and search together; reserve the body for messages and event details. Keep follow and clear in the existing overflow menu. | Long messages, rapid updates, follow behavior, and event selection. |
| Band lock | Group mode, bands, and cell locking separately; keep the current operation and restore action easy to locate. | Samsung restart/reconnect, unsupported devices, and restore verification. |
| Test tasks | Keep current task controls above task history; show recording settings on demand. | Active, completed, interrupted, and replayed tasks. |

These later changes should preserve collection, recording, locking, and replay
behavior. A visual refresh must not reintroduce startup probes, stale data, or
task-dependent live collection.

## Layout Rules

- Map Miuix theme colors into Material and legacy views until their migration.
- Use the existing Material icon set within Miuix components.
- Use minimum row heights, wrapping text, and fixed icon/action widths.
- Keep long state messages in the row body rather than the trailing action.
- Avoid explanatory badges, nested cards, large decorative headings, and
  repeated text that does not help interpret a measurement.
- Keep every interactive target at least 48 dp, including secondary links.
- Preserve light, dark, AMOLED, and dynamic color settings.

## References

- [Miuix 0.9.5-rc01](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.5-rc01)
- [awesome-design-md](https://github.com/VoltAgent/awesome-design-md): use its
  Linear, IBM, and SAP entries as references for hierarchy, data alignment, and
  operation grouping. Their website branding and marketing layouts are not
  templates for this Android app.
- Reviewed source entries: [Linear](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/linear.app/DESIGN.md),
  [IBM](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/ibm/DESIGN.md),
  and [SAP](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/sap/DESIGN.md).

## Device Review

Check tools, settings, capability details, acknowledgments, and update dialogs
at a 360 dp width, with a large system font, and in light and dark themes.
Check update success, no published release, offline access, rate limiting, and
opening external links. See [SECURE-UPDATES.md](SECURE-UPDATES.md) for update
delivery behavior.

For each interface style, also review LTE, NR SA, NSA, dual SIM, missing readings,
multiple carriers, and long parameter values. Toggle floating navigation in
gesture and three-button navigation modes, including landscape. Check that the
last signal row remains reachable, collection continues, and saved style and
effect preferences survive an app restart.
