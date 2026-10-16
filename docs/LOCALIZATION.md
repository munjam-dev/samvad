# Localization

Languages in the app: **English, Hindi (hi), Telugu (te), Tamil (ta)**. The picker (`core/.../profile/Languages.kt`,
Settings → Language, and the screen after profile setup) lists exactly these. Keep it in step with
`app/src/main/res/xml/locales_config.xml`.

## Status: complete, but not yet reviewed by native speakers

Every string in `values/` has a translation in `values-hi/`, `values-te/` and `values-ta/`, checked by script (same keys,
same placeholders, plural categories present, well-formed XML) and by `lintDebug` (no MissingTranslation,
ExtraTranslation or format-mismatch). **The wording was written by an AI model and has not been reviewed by a native
speaker.** Before a public release each language needs a native-speaker pass, especially the lists below.

Do not add a language to the picker until all of its strings exist and its screens have been checked on a device.

## Rules for new strings

- Add the English string, and the same key in every `values-<lang>/` file, in the same change. A new feature's strings
  go in its own `strings_<feature>.xml` (plus the same file name in each language folder).
- Keep placeholders exactly (`%1$s`, `%2$d`); reorder with positional indexes when grammar needs it.
- Escape `\'`, `\"`, `&amp;`; keep `\n`.
- No claim of end-to-end encryption in any language until it is implemented and verified.
- Keep `app_name`, `brand_devanagari`, `cd_samvad_mark` and `home_footer_tag` untranslated.

## Typography for Indic scripts

Latin headings use negative letter spacing. Any letter spacing breaks the joined shapes of Devanagari, Telugu and Tamil
syllables (the Devanagari wordmark wrapped onto two lines before this was fixed). `SamvadTheme` therefore removes letter
spacing whenever the app language is not English (`Typography.withoutTracking()`), and the always-Devanagari wordmark on
the first onboarding page uses `TextStyle.noTracking()`.

## Please have a native speaker check

Hindi: the Hindi wording is plain and conversational; check the longer privacy and two-step verification texts.

Telugu: "మోమెంట్స్" for Moments (alternatives "క్షణాలు", "స్టేటస్"); "పరిచయం" for About; disappearing messages
("మాయమయ్యే సందేశాలు" may read too literally); two-step verification ("టూ-స్టెప్ వెరిఫికేషన్" vs "రెండంచెల ధృవీకరణ");
calm mode; the scam-warning reasons; colour and theme names. Zero-width non-joiners (U+200C) are used after loanwords
that end in a virama, so check they render correctly on real devices.

Tamil: "தருணம்" for Moments; "நட்சத்திரமிடு" for Star; "திட்டமிடு" for Schedule; "ஃபார்வர்டு" for Forward; the typing
indicator wording; colour names; the formal tone of the scam-warning reasons; and length at 200% font scale for
`phone_consent`, the `settings_*_body` strings and the `onboarding_*` strings (Tamil runs long).

## Not yet translated

Strings from features still being merged (Personal Vault, weak-network media) need `values-hi`, `values-te`, `values-ta`
files when those branches land. Right-to-left languages (Urdu) are not offered; they need layout testing first.
