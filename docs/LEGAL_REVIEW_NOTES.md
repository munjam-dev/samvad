# Legal review notes (items for a lawyer to confirm)

Written 2026-10-21 alongside the update of the legal pages. The public pages contain only what the code and design
documents show. These items are deliberately not asserted publicly.

1. **LiveKit as processor.** The pages call LiveKit Cloud a processor. No data processing agreement is recorded in
   the repository. Confirm one is signed, and confirm the LiveKit region used (the pages say "region chosen for the project"; if media can leave India, section 7 of the privacy policy and DPDP cross-border wording should name it).
2. **Call records after account deletion.** The code ends live calls but keeps call records as the other party's history. Confirm this is acceptable under the DPDP Act erasure right, or change the code.
3. **Moments after account deletion and at expiry.** Moments expire at 24 hours (server-set), but the code reviewed does not show account deletion explicitly deleting a user's Moments or their media files. The pages say "expire after 24 hours" only. Confirm the media files are actually deleted at expiry.
4. **Appeals retention.** Appeals are deleted with the account; no fixed retention period is stated. Decide one.
5. **Safety report retention (90 days) and server logs (30 days)** were already in the policy; confirm they match actual configuration.
6. **Linked devices.** Sign-out is by marker and push message, not a true token revocation. The policy says "next time it opens the app or receives a notification". Confirm this wording is adequate.
7. **Cloud storage region.** The Service Providers page says Cloud Storage uses the "region chosen for the project". Confirm it is Mumbai before saying so.
8. **Disappearing messages deletion.** The pages say messages are removed after the period. Confirm the server also deletes attached files, and from all copies.
9. **Effective date.** The `updated:` front-matter date was set to 2026-10-21. Confirm the date you want shown, and whether a re-consent prompt is needed for existing users (new data practices: calls, Moments, LiveKit).
10. **Consent for LiveKit sub-processing and cross-border transfer** under section 16 DPDP Act, and any restrictions notified.
11. **Open-source / AGPL gate** (libsignal) is unaffected by these pages, but the app must not ship libsignal until counsel confirms (see `docs/security/E2EE_DECISION.md`). No page claims E2EE.
12. **USE_BIOMETRIC** is not declared in the app's own manifest; confirm in the merged manifest before Play submission (affects the permission table).
13. **Play content rating and target audience** (18+) to confirm with the owner.
14. **Hindi, Telugu and Tamil translations** of any legal text are not provided; the in-app wording was AI-written and not reviewed by native speakers (`docs/LOCALIZATION.md`). Decide whether legal pages need translation.
15. **Business details** (operator name, address, grievance officer, emails) were left exactly as in `website/src/config/legal.ts`. No registration number is shown; confirm whether Indian law or Play requires one to be displayed.
16. **Community Guidelines / Child Safety pages** were not edited. Consider adding Moments and calls to the scope of prohibited content and reporting.
17. **Personal Vault ID copies.** The vault is local and never uploaded, but users may store ID copies there. Confirm no extra notice is needed.

## Update 2026-10-26: end-to-end encryption, AGPL and licences

The privacy policy, terms, security, law-enforcement and service-provider pages, the website FAQ and home text,
`docs/PLAY_DATA_SAFETY.md` and `docs/RELEASE_CHECKLIST.md` now describe the app as it is in code: one-to-one chats can be
end-to-end encrypted (libsignal) when both people have keys; existing chats, groups, polls, Moments, calls and meetings
are not; reports from encrypted chats are metadata only unless the reporter attaches messages. The public pages say
nothing about the items below.

### TODO-OWNER (not verified in code, or needs your decision; none of these is asserted on the public pages)

1. **Release flag.** Release builds have `E2EE_ENABLED=false` unless built with `-Psamvad.e2ee=true`. The public text is
   true only for a build with the flag on. Build the release that way, or hold the website update until you do.
2. **External cryptography review** of the E2EE (see `docs/security/E2EE_DESIGN.md`) is not done. The security page says
   so. Decide whether to publish before it is done.
3. **AGPL.** Lawyer's written confirmation of publishing the app source under AGPL-3.0 and of the server code sharing the
   repository. Confirm the repository `https://github.com/munjam-dev/samvad` is public when the app links to it.
4. **On-device translation with ML Kit** was requested to be described, but no ML Kit translation dependency exists in the
   code (only the barcode scanner, via `play-services-code-scanner`). It is not mentioned in the public text or the
   licences list. Add it when the feature exists.
5. **Scam Shield.** Verified only as a local "new contact" banner (`ScamPreferences`). The policy says no more than that.
   Confirm if the feature is meant to do more.
6. **Vault backup to Google Drive.** The code is present and reachable from the Vault screen; it needs the Android OAuth
   client from `docs/VAULT_BACKUP_SETUP.md` to work, otherwise it fails as "not configured". I could not verify that it is
   off in V1, so the policy describes it as optional and off by default. If it is hidden or disabled in V1, remove
   sections 3.14 (backup paragraph), 6.2 and the Drive rows in the policy, the deletion page and the service-providers page.
7. **Data location.** Functions are `asia-south1` (verified, `firebase/functions/src/config.ts`). Firestore and Storage
   regions and the Realtime Database region (Singapore, `asia-southeast1`, as in `docs/OWNER_SETUP.md`) could not be
   verified from the repo. Realtime Database is not available in Mumbai, so the policy keeps "Singapore" for presence. Confirm
   the Storage bucket and Firestore are Mumbai.
8. **GIPHY.** The server sends only the search words, rating `g` and paging to GIPHY (`lib/gifs.ts`). Check GIPHY's
   current terms and privacy policy, and whether an API agreement or attribution is needed.
9. **Analytics and crash reporting.** None in the app (no analytics, Crashlytics or Performance dependency). Server logs
   are 30 days (unverified configuration, see item 5 above). Confirm that Firebase Console settings (Google Analytics
   linking, Crashlytics, data sharing) are off.
10. **Account deletion** removes the account, profile, devices, tokens, scheduled messages and avatars (`deleteAccount.ts`).
    The deletion code reviewed does not clearly remove the user's end-to-end encryption prekeys, or Moments and their media.
    Confirm this, or change the code.
11. **Children.** The policy and Terms say 18+. The app does not ask for a date of birth (not found in the code); the age
    limit rests on the agreement screen. Confirm that is acceptable for Play's target-audience form.
12. **Grievance and contact details** come from `website/src/config/legal.ts` and are real-looking values (Arlo Technologies,
    Hyderabad address, Durgam Vincent, vincent@arlotechnologies.in, samvad@, contact@). Confirm each is correct and
    monitored; no `TO CONFIRM` placeholder remains. Confirm whether a registration number must be shown.
13. **Update 2026-10-26 dates.** `updated:` was set to 2026-10-26 on the edited pages. Decide whether existing users must
    be asked to consent again (new data practices: encryption keys, GIF search).
14. **Open-source licence list.** `NOTICE` and the in-app "Source code and licences" screen list libraries from
    `android-native/gradle/libs.versions.toml`. Check licences of transitive dependencies (LiveKit bundles WebRTC) with a
    licence scanner before release.
15. **Hindi, Telugu and Tamil** wording of the new privacy strings was written by an AI and has not been reviewed by a
    native speaker (see `docs/LOCALIZATION.md`).
