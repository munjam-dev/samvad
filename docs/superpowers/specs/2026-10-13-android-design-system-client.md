# Samvad Android Client — Design System and Client Roadmap (A-series)

Date: 2026-10-13
Status: Spec revision 4, **draft awaiting owner approval**. Extends `2026-10-09-foundation-auth-design.md` (rev 3.1); it changes no approved backend text except the roadmap pointer in §1 of that spec.
Visual reference: the design canvas "Samvad Design System" (private claude.ai artifact; it shows the foundations, the brand directions and the core screens in light and dark).

## 0. Why this revision exists

The owner issued the Android design and product directive ("Botanical Modernism") and so started the client phase that rev 3 had deferred. Decisions the owner made while this spec was planned:

| Decision | Choice |
|---|---|
| Sequencing | Design now → implement **B1** (backend) → **A1** (Android foundation). No screen ships against a backend sub-project that doesn't exist yet. |
| Location | New `android-native/` Gradle project, application ID `com.samvad.messenger`. The root Gradle project (`app/`, AI Studio "Sanraksha" vault, `com.aistudio.sanraksha.vault`), Flutter, `supabase/` and `backend/` stay untouched, as rev 3 §4.3 requires. |
| Tokens | Owner palette adjusted only where it fails WCAG AA (§3.2). |

## 1. Brand

- Name **Samvad**, Indian-language identity **संवाद**. Personality: warm, intelligent, calm, expressive, trustworthy, premium.
- Concept: **Botanical Modernism**, natural warmth with the precision of a digital product. Real conversations and user media provide most of the visual richness. No promotional banners, no imitation of another messenger's layouts, logo or illustrations.

### 1.1 Brand mark

Three directions are on the design canvas:

| Direction | Idea | Assessment |
|---|---|---|
| **B · Twin leaves (recommended)** | Two leaves leaning toward each other, offset like speech marks. Green speaks, champagne answers, and the overlap is the conversation. | Distinctive, botanical, and reads as dialogue without drawing a bubble or a telephone. Works in monochrome (one leaf filled, one outlined) and at 16dp. |
| A · Leaf bubble | Speech bubble carrying a leaf vein | Close to category conventions; the vein is lost below 24dp |
| C · Dialogue blocks | Two bubbles meeting corner to corner | Very legible but less botanical |

**Owner choice required.** The chosen mark is delivered as Android vector drawables: an adaptive-icon foreground inside the 66dp safe zone, an ivory (`#F8F5EC`) background layer, a monochrome layer for themed icons, the Android 12 SplashScreen icon, a compact in-app mark, the wordmark "Samvad" (Inter Bold, −2% tracking), and a bilingual lockup with संवाद (Noto Sans Devanagari Medium).

## 2. Architecture of the design system

- Module `android-native/core/designsystem` is the single source of truth. Screens use only `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `MaterialTheme.shapes` and the `SamvadTheme.colors / spacing / elevation` extension (CompositionLocals). **No hex literal outside `core/designsystem`** is allowed; a lint check (detekt rule or a unit test that scans sources) enforces it.
- Theme mode is stored in DataStore: `SYSTEM` (default), `LIGHT`, `DARK`, `AMOLED`. Dynamic color (Material You) is **off**, because brand consistency comes first. It may become an opt-in later.
- Reusable components live in `core/designsystem/component`. Feature modules compose them and never restyle them.

## 3. Tokens

### 3.1 Colour roles

| Token (M3 role / Samvad extension) | Light | Dark | AMOLED |
|---|---|---|---|
| background | `#F8F5EC` | `#101914` | `#000000` |
| surface | `#FFFFFF` | `#17221B` | `#0B120E` |
| surfaceContainer (secondary surface) | `#F1F4EC` | `#1E2B22` | `#141E17` |
| surfaceContainerHigh (elevated) | `#FFFFFF` | `#1E2B22` | `#17221B` |
| primary | `#167B52` | `#75D6A0` | `#75D6A0` |
| onPrimary | `#FFFFFF` | `#101914` | `#000000` |
| primaryStrong (pressed) | `#105C3D` | `#B5E8C7` | `#B5E8C7` |
| primaryContainer (pale sage) | `#D9E9D7` | `#234A35` | `#1B3B2A` |
| onPrimaryContainer | `#105C3D` | `#B5E8C7` | `#B5E8C7` |
| secondaryContainer (pale mint) | `#E8F4E9` | `#1E3A2A` | `#16301F` |
| tertiaryContainer (champagne peach) | `#FBE7D7` | `#4A3326` | `#3A281D` |
| onTertiaryContainer | `#6E4424` | `#F2B98F` | `#F2B98F` |
| accentPeach (decorative fill only) | `#F2C6A5` | `#F2B98F` | `#F2B98F` |
| onSurface (text) | `#202821` | `#F4F6F1` | `#F4F6F1` |
| onSurfaceVariant (secondary text) | **`#56685D`** | `#B0BDB2` | `#B0BDB2` |
| outline (inputs, focus) | `#7D8C80` | `#5E6E62` | `#5E6E62` |
| outlineVariant (divider) | `#E6E8DF` | `#2B3A30` | `#1F2A23` |
| success / presenceOnline | `#18794E` | `#75D6A0` | `#75D6A0` |
| warning | `#98601B` | `#E8B66B` | `#E8B66B` |
| error | `#B42332` | `#FF9AA2` | `#FF9AA2` |
| bubbleIncoming / border | `#FFFFFF` / `#E6E8DF` | `#1E2B22` / `#2B3A30` | `#141E17` / `#1F2A23` |
| bubbleOutgoing | `#D9E9D7` | `#234A35` | `#1B3B2A` |
| bubbleOutgoingMeta | `#3F5A47` | `#B5E8C7` | `#B5E8C7` |
| statusRingUnread / Seen | primary / outline | primary / outline | primary / outline |

The dark values `#E8B66B` and `#FF9AA2`, and the AMOLED column, are new proposals. Spot checks pass: warning 9.68 and error 8.88 on the dark background, secondary text 10.77 on AMOLED black, and dark outline 3.32 or more. The A1 contrast test re-verifies every pair.

### 3.2 Contrast audit (WCAG 2.x, computed)

| Pair | Ratio | Result |
|---|---|---|
| Text on background / sage / peach | 13.9 / 12.0 / 12.6 | AAA |
| Owner secondary `#64766B` on background / surface 2 / sage | 4.43 / 4.35 / 3.82 | **fails AA → replaced by `#56685D`** (5.45 / 5.35 / 4.69) |
| White on primary (buttons) | 5.26 | AA |
| Primary on background / mint (links, icons) | 4.83 / 4.65 | AA |
| Peach accent `#F2C6A5` on white | 1.57 | **decorative fill only**, never text or icons |
| Warning / error on background | 4.79 / 5.97 | AA |
| Outline `#7D8C80` on white / background | 3.54 / 3.25 | ≥3:1 for UI components |
| Outgoing meta `#3F5A47` on sage | 6.0 | AA |
| onTertiaryContainer on champagne | 6.96 | AA |
| Dark: text / secondary / primary on background | 16.5 / 9.2 / 10.2 | AAA |
| Dark: text on outgoing bubble `#234A35` | 9.18 | AAA |
| Dark: onPrimary on primary | 10.2 | AAA |

Dividers (1.13 light, 1.49 dark) are decorative only. Nothing that conveys state may depend on them.

### 3.3 Typography

Inter (SIL OFL 1.1, bundled as a variable font in `res/font`, with its licence file next to it) for Latin. Indic scripts fall back to the system's Noto families. Every size is in `sp` and verified at 200% font scale.

| Role | M3 style | Size / line / weight |
|---|---|---|
| Page title | headlineMedium | 32 / 40 / 700 |
| Conversation title | titleLarge | 22 / 28 / 600 |
| Contact / list name | titleMedium | 17 / 24 / 600 |
| Message body | bodyLarge | 16 / 22 / 400 |
| Preview, empty-state guidance | bodyMedium | 14 / 20 / 400 |
| Section heading | labelMedium | 12 / 16 / 600, +4% tracking |
| Buttons, navigation | labelLarge | 15 / 20 / 600 |
| Timestamps, metadata | labelSmall | 12 / 16 / 500 |

Long names wrap to two lines in headers and profile screens and ellipsize to one line in lists.

### 3.4 Spacing, shape, elevation

- Spacing scale (dp): `xxs 2, xs 4, s 8, m 12, l 16, xl 24, xxl 32, xxxl 48`. Screen gutter 16–20dp; list row 72dp minimum.
- Shapes: chip 8, button 12, field 12, card 16, chat bubble 20 with a 6dp tail corner, bottom sheet 28 (top), FAB 16, avatar circle, group avatar rounded square 14.
- Elevation: tonal first (surfaceContainer levels). Shadows are reserved for the FAB, sheets and dialogs, and call controls. Gradients are reserved for splash/onboarding, call background and profile header. No glassmorphism or blur.

### 3.5 Iconography and touch

Material Symbols Rounded (outline) as the single icon family, with a 24dp glyph (20dp inline) inside a ≥48dp touch target. Every icon-only control has a string-resource `contentDescription`. Emoji appear only in user content, never in UI chrome.

### 3.6 Motion

M3 emphasized easing, 150–300ms. Navigation uses shared-axis or fade-through. Press feedback uses ripple plus a scale of at most 0.97. The unread badge animates count changes; the status ring cross-fades from unread to seen. Recording pulses only while recording. Skeletons show only during the load window, with no endless shimmer. When `Settings.Global.ANIMATOR_DURATION_SCALE == 0`, all non-essential animation is off. Lazy lists use stable keys, and message enter animation runs only for new items at the bottom.

## 4. Component inventory (`core/designsystem/component`)

`SamvadButton` (primary, tonal, outline, text; loading and disabled states), `SamvadTextField`, `PhoneNumberField` (country picker plus libphonenumber formatting), `OtpField` (6 cells, SMS autofill, paste), `SamvadTopBar`, `SamvadNavigationBar`, `Avatar` (initials fallback, image via Coil, presence dot, group shape), `StatusRing`, `UnreadBadge`, `FilterChipRow`, `ConversationRow`, `MessageBubble` (incoming/outgoing, reply quote, reactions, link preview, media, document, voice, delivery state), `DateSeparator`, `Composer`, `AttachmentSheet`, `SettingsRow` / `SettingsGroup`, `EmptyState`, `ErrorState` (with retry), `OfflineBanner`, `SkeletonRow`, `BrandMark` / `Wordmark`.

Every component has light and dark previews and a screenshot test.

## 5. Screen catalogue and required states

Every network-dependent screen implements **loading, content, empty, error (with retry) and offline** states. A screen whose backend sub-project isn't built yet is not shipped. It must never show placeholder data.

| Screen | Sub-project | Backend dependency | Notes |
|---|---|---|---|
| Splash, onboarding (≤3 pages, skip) | A1 | none | first-launch versus returning-user routing; no permission requests during onboarding |
| Phone number, OTP, consent, profile setup | A1 | B1 | client contract rev 3 §8 applies in full |
| Settings: profile, account, appearance, language, privacy info, sign out (this device / everywhere), delete account | A1 | B1 | each row does something real or is not shown |
| Chats home, conversation, message info, media viewer, search in chat | A2 | B2 | paging, offline queue, send/sent/delivered/read/failed/retry |
| Contacts, new chat, invite, groups, group info, block/report/mute | A3 | B3, B6 (discovery) | permission explanation before the contacts request; phone numbers are never shown to unrelated users |
| Media and document attachments, status/stories (text, media, audience) | A4 | B4, B7 | |
| Presence, typing, push notifications and deep links, notification settings | A5 | B5 | |
| Voice/video calls (incoming, outgoing, active, video) | A6 | B6 (TURN, call authorization) | WebRTC; no static mock may be presented as working |
| Communities and channels, saved/pinned/scheduled messages, global search | A7 | B7 | member counts only from real data |

The design canvas covers onboarding, phone, OTP, chats home, conversation and settings in light, plus chats and conversation in dark. Screens for later sub-projects are designed at the start of each one.

## 6. Accessibility and localization

- TalkBack: a merged semantics node per conversation row ("Ananya Krishnamurthy, 2 unread, last message …, 10:42, pinned"). Bubbles announce sender, time and delivery state. The OTP cells form one logical field.
- Contrast is tested by a unit test over every declared foreground/background pair in all three schemes (≥4.5:1 for text, ≥3:1 for UI components).
- All strings live in `strings.xml`, with plurals for counts. English ships first, and the layout is ready for Hindi and other Indian languages (no text in images, no fixed-width labels, RTL-safe `start`/`end`).
- Font scale is checked at 100% and 200%. Nothing clips, and touch targets stay ≥48dp.

## 7. Privacy, trust and retention

- No E2EE wording anywhere until it is implemented and verified (rev 3 §2).
- Blocking, reporting, muting, sign-out-everywhere and account deletion can each be reached within two taps of Settings.
- No engagement dark patterns: no notification nagging, no fake activity, no invented community statistics.
- Analytics and Crashlytics stay off until consent (rev 3 §8).

## 8. Testing (A-series)

- JVM: the contrast test, the "no hex outside designsystem" check, ViewModel tests with fakes, and Roborazzi screenshot tests for each component and screen in light and dark at 100% and 200% font scale.
- Instrumented and Compose UI tests for navigation and auth flow states.
- Emulator Suite integration: phone sign-in with a test number against the Auth emulator (`demo-samvad`), followed by the profile and device writes.
- Items that need a live project or device (real SMS, Play Integrity, App Check enforcement, push, calls) are reported as "setup still required".

## 9. Toolchain additions

The Android SDK (platform 36, build-tools) is needed to build `android-native/`. Installing it changes the machine and therefore needs the owner's OK, as rev 3 §5 already requires for the JDK and Node. JDK 21 runs Gradle. AGP, Kotlin, Compose BoM, Hilt, Room, DataStore, Navigation, Firebase BoM and libphonenumber versions are pinned together in the A1 plan.

## 10. Open questions for the owner

1. Which brand-mark direction: B (recommended), A or C?
2. Should Inter be bundled (adds about 300 KB), or should the system font be used? Inter is recommended for the brand.
3. Approve the AMOLED and dark warning/error proposals, subject to the contrast test?
4. Terms of Service and Privacy Policy URLs (also listed in rev 3 §11.10).
