---
title: Service Providers
summary: The companies that process personal data on Samvad's behalf, what they do, and where.
updated: 2026-10-26
order: 11
group: privacy
---

> **In short.** Samvad runs on Google Cloud and Firebase, uses LiveKit Cloud to carry call and meeting audio and video, and GIPHY for GIF and sticker search. These providers process data only on our instructions, to host and deliver the service. We do not use any advertising or analytics providers.

## 1. Why we use service providers

Running a reliable, secure messenger needs data centres, SMS delivery and push notifications. We use trusted providers for these, under contracts that require them to process personal data only on our instructions and to protect it. Under the Digital Personal Data Protection Act, 2023 they are our **Data Processors**; we remain responsible for your personal data.

## 2. Our service providers

| Provider | Service | Purpose | Personal data involved | Location |
|---|---|---|---|---|
| Google LLC and affiliates (Firebase Authentication) | Phone sign-in | Send one-time sign-in codes by SMS and manage sign-in sessions | Phone number, sign-in records | Global (SMS routing) |
| Google LLC and affiliates (Cloud Firestore) | Main database | Store accounts, profiles, chats, messages, groups, starred and scheduled messages, reports | Account, profile and message data | Mumbai, India |
| Google LLC and affiliates (Firebase Realtime Database) | Presence | Online status, last seen and typing | Presence data | Singapore |
| Google LLC and affiliates (Cloud Storage for Firebase) | File storage | Store photos, voice messages, files and profile photos | Media you send | Google Cloud region chosen for the project |
| Google LLC and affiliates (Cloud Functions, Cloud Run, Cloud Scheduler) | Server code | Group management, two-step verification, reports, scheduled sending, account deletion | Data needed for each operation | Mumbai, India |
| Google LLC and affiliates (Firebase Cloud Messaging) | Push notifications | Deliver message notifications to your phone | Push token, notification data | Global |
| Google LLC and affiliates (Firebase App Check, Play Integrity) | App integrity | Confirm requests come from the genuine app on a genuine device | Device integrity signals | Global |
| Google LLC and affiliates (Firebase Hosting) | Website hosting | Serve this website | Standard server logs | Global (content delivery network) |
| LiveKit, Inc. (LiveKit Cloud) | Call and meeting media | Relay the audio and video of voice calls, video calls and group meetings. Encrypted in transit; not end-to-end encrypted; not recorded or stored by Samvad | Call audio and video, the access token for the call | Region chosen for the project |
| GIPHY | GIF and sticker search | Find GIFs and stickers. Your search words reach GIPHY through our server, without your name, phone number or account identifier. A phone that shows a GIF loads it from GIPHY, which can see that phone's IP address | Search words; IP address of the phone that loads a GIF | Global |
| Google LLC (Google Drive, only if you turn on Vault backup) | Your own Google account | Store an encrypted copy of your vault in the hidden app-data folder of your own Drive. This is your account with Google, not a service we run for you; we never receive the copy or the key | An encrypted archive that only you can open | Global |
| Google LLC (Google Play) | App distribution | Distribute and update the app | As described in Google's own privacy policy | Global |

## 3. Providers we do not use

We do **not** use advertising networks, analytics or attribution SDKs, crash-reporting services that collect personal data, data brokers, or AI services that process your messages.

## 4. Changes

We will update this page before adding a new service provider that processes personal data, and, where the change materially affects how your data is used, tell you in the app.

## 5. More information

- [Privacy Policy](/legal/privacy-policy)
- Google Cloud's data processing terms: [cloud.google.com/terms/data-processing-addendum](https://cloud.google.com/terms/data-processing-addendum)
- Firebase privacy and security: [firebase.google.com/support/privacy](https://firebase.google.com/support/privacy)
