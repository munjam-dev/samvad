# Accessibility and UX audit (static)

Scope: `ui/call`, `ui/vault`, `ui/community`, `ui/privacy`, `ui/groups`, `ui/media`, `ui/search`, `ui/status` and the
`ui/chat/*Views.kt` files. Method: reading and searching the Compose source for the checks below, against
`.ux-profile.md` (48 dp targets, string-resource labels, theme tokens only, 200% font scale, `Role` on custom
radio and checkbox rows). Nothing was run on a device, so this is a source audit, not a TalkBack test.

## Fixed

| Where | Problem | Fix |
|---|---|---|
| `search/GlobalSearchScreen.kt` | Clear-query icon was an 18 dp `clickable` icon (target far below 48 dp) with the wrong label (`cd_cancel_upload`, "cancel upload") | Now an `IconButton` (48 dp target) with the new `a11y_clear_search` label |
| `community/AnnouncementsScreen.kt` | Reaction summary was a bare `clickable` text with no role, no label and a tiny target | `Role.Button`, click label `a11y_react_thumbs_up`, `minimumInteractiveComponentSize()` |
| `community/CreateCommunityScreen.kt` | Group picker rows were plain `clickable` rows with a checkbox that had no semantics of its own, so TalkBack could not announce checked state | `toggleable(role = Checkbox)` on the row |
| `status/MomentComposer.kt` | Text-style colour swatches were 36 dp and used `clickable(role = RadioButton)` without selected state | 48 dp `selectable(role = RadioButton)` target around the 36 dp circle, content description kept |
| `status/MomentComposer.kt` | Audience dialog: radio rows used `clickable` (no selected state), people rows used `clickable` around a checkbox | `selectable(role = RadioButton)` and `toggleable(role = Checkbox)` |
| `chat/DisappearingViews.kt` | Timer options were `clickable(role = RadioButton)` with no selected state | `selectable(selected, role = RadioButton)` |
| `groups/GroupInfoScreen.kt` | Mute row was a `clickable` row holding a switch with no semantics | `toggleable(role = Switch)` on the row |
| `status/StatusViews.kt` | "Viewers" pill in the moment viewer had no role, no label and a target under 48 dp | `Role.Button`, click label `a11y_see_viewers`, minimum 48 dp target |
| `media/SharedMediaScreen.kt` | Photo and video grid cells were unlabeled `clickable` boxes | `Role.Button` with click label `a11y_open_media` |
| `community/CommunityScreen.kt`, `groups/GroupInfoScreen.kt`, `community/CreateCommunityScreen.kt` | Character counters were built from a literal `"${n} / ${max}"` in code | New `a11y_character_count` string, so numerals and order are localisable |

New strings are in `res/values*/strings_a11y.xml` (English, Hindi, Telugu, Tamil; every key in every locale).

## Checked and found fine

- Icon-only controls: every `IconButton` in the audited files already passes a string-resource content description.
  The only `contentDescription = null` uses are decorative icons that sit beside visible text (`PinnedViews.kt:66`,
  `ScamViews.kt:170`).
- Existing custom radio, checkbox and switch rows with correct `Role` and state: `call/MeetingViews.kt`,
  `privacy/AppealScreen.kt`, `privacy/PrivacySettingsScreen.kt`, `community/AddCommunityMembersScreen.kt`,
  `groups/NewGroupScreen.kt`, `chat/ForwardViews.kt`, `chat/GroupViews.kt` (poll options and the multi-answer switch),
  `status/PhotoEditor.kt` (colour swatch is a 48 dp target around 32 dp).
- Literal user-visible strings: none found in the audited files apart from the three counters above.
- Fixed `height`/`size` values: all remaining fixed heights are spacers, dividers, progress bars, waveforms, avatars and
  icon backgrounds. No fixed-height container holds wrapping text. `defaultMinSize`/`heightIn(min = 48.dp)` is used for
  text rows, so they grow at 200% font scale.

## Not changed on purpose (needs a decision)

- `Color.White` and `Color.Black` are used in the full-screen media viewers, the moment viewer, the video overlay and
  the call screen. They sit over photos, video or the call gradient where the theme surface colours would be wrong, and
  `.ux-profile.md` allows gradients on the call background. They are not theme tokens, so if you want a token for
  "content over media" it is a design-system change (`SamvadTheme.colors.onMedia` or similar), not a quick edit here.
- Colour-contrast values: the open AAA token work in `.ux-profile.md` (primary button 5.26:1 and others) is unchanged.
- The moment text-style swatch description is the style key (for example the name from `StatusRules.STYLES`), which is
  not localised. Giving each style a translated name needs a design call on the names.

## Needs a human check on a device

Run each with TalkBack on, then again at 200% font scale (Settings, Display, Font size and Display size) and at
the smallest and largest display size, in English and in one Indic language:

1. Call screens (`ui/call`): incoming, outgoing, in-call controls, meeting join, Calls tab empty and populated states.
   Check that control buttons are announced with their state (muted, speaker) and that the layout does not clip.
2. Vault (`ui/vault`): lock gate, empty state, item list, item viewer, add flow, biometric prompt focus order.
3. Communities and announcements (`ui/community`): create flow with the group picker, members list with the admin
   menu, announcement reactions, character counters.
4. Privacy (`ui/privacy`): settings radios and switches, appeal form, linked devices.
5. Groups (`ui/groups`): group info mute switch, events screen date and time pickers, new group member picker.
6. Media and search (`ui/media`, `ui/search`): shared media grid (the cells are labelled generically, not by file),
   global search results order, clear button, expanded results.
7. Moments (`ui/status`): composer modes, style swatches, audience dialog, viewer overlay, photo editor, reply field
   with the keyboard open at 200% font.
8. Chat views (`ui/chat/*Views.kt`): contact card, scheduled messages, poll voting, voice-note player, transfer
   progress controls, scam warning, safety numbers.
9. Switch Access / keyboard: focus indicator is visible on every custom `clickable` row (the profile requires a
   prominent focus ring; this was not changed).
10. Contrast at AAA (7:1) for the secondary text tokens listed as open in `.ux-profile.md`.
11. Reduced motion: set animation scale to 0 and confirm empty-state pop-in, moment progress and call animations stop.

The three Compose smoke tests added in `app/src/androidTest/.../NoBackendScreensUiTest.kt` compile but have not been
run; run them with `./gradlew :app:connectedDebugAndroidTest` on an emulator.
