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
