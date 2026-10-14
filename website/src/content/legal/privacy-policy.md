---
title: Privacy Policy
summary: What Samvad collects, why, where it is stored, how long we keep it, and your rights under Indian law.
updated: 2026-10-14
order: 1
group: privacy
---

> **In short.** We collect what a messenger needs to work: your phone number, your profile, and the messages and files you send. We do not show ads, sell your data, or use advertising or analytics trackers. Messages are encrypted while they travel and while stored, but **they are not yet end-to-end encrypted**, which means our systems could technically access them. We are building end-to-end encryption and will update this policy and tell you in the app when it is live. You can delete your account at any time.

## 1. Who we are and how this policy applies

Samvad is a messaging app provided by **{{OPERATOR}}**, {{ADDRESS}} ("Samvad", "we", "our", "us").

This policy explains how we process personal data when you use the Samvad Android app, our servers, and this website (together, the "Services"). For the purposes of the **Digital Personal Data Protection Act, 2023** ("DPDP Act") and the rules made under it, we are the **Data Fiduciary** for the personal data described here, and you are the **Data Principal**. This policy is also our privacy policy under rule 4 of the Information Technology (Reasonable Security Practices and Procedures and Sensitive Personal Data or Information) Rules, 2011, and rule 3(1)(a) of the Information Technology (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021.

When this policy says "personal data", it means any information about you that identifies you or can be used with other information to identify you.

## 2. Your consent

Before you create an account, the app shows you this policy and our [Terms of Service](/legal/terms-of-service) and asks you to agree. By agreeing, you give consent to the processing described below, for the purposes listed. Your consent is:

- **specific** — given for the purposes in section 5, not for anything else;
- **free** — Samvad will not ask you to consent to anything that is not necessary for the feature you use;
- **withdrawable** — you can withdraw it at any time, as easily as you gave it (see section 11).

Where the DPDP Act lets us process data without consent for a "legitimate use" (for example, to comply with a law or a court order), we say so in section 5.

## 3. Personal data we collect

### 3.1 Account data

- **Mobile phone number.** This is how you sign in. To verify it, our service provider Google (Firebase Authentication) sends a one-time code to your phone by SMS.
- **Agreement record.** The date and version of the Terms and this policy you agreed to.

### 3.2 Profile data

- **Display name**, optional **about** text and optional **profile photo**.
- These are visible to people you chat with and to members of groups you are in. Your profile photo is hidden from people you have blocked.
- Profile photos are checked and re-encoded on our servers, which removes hidden information such as the camera model and location.

### 3.3 Contacts

Only if you allow it, Samvad reads the **phone numbers** in your address book and checks them against Samvad accounts so you can see which of your contacts use Samvad.

- The numbers are sent over an encrypted connection, compared, and **not stored** on our servers.
- Contact **names** are read and used only on your phone, to show people under the names you saved. They are never uploaded.
- The number of lookups is limited per day to prevent misuse.
- If you do not allow contacts access, you can still start chats by entering a phone number.

### 3.4 Messages and content

- The **text messages, photos, voice messages, documents, polls and poll votes, reactions, replies, edits and deletions** you send, with the time sent and delivery and read status.
- This content is stored on our servers so it can be delivered to the people you send it to and synced to your devices.
- Photos are resized and re-encoded on your phone before upload, which removes hidden information such as location.
- Files are limited to 16 MB and to safe file types; app installation files (APKs) cannot be sent.

### 3.5 Starred and scheduled messages

- **Starred messages:** a reference to each message you star (not a copy of the message), so your stars sync across devices.
- **Scheduled messages:** the text, the chat and the time you choose. Our servers send the message at that time, even if your phone is off. After it is sent, cancelled or fails, we keep the record for 7 days so you can see what happened.

### 3.6 Groups

Group name, description, photo, members, admins, when each member joined, and invite codes. Group events (for example, "Asha added Ravi") are visible to the group's members. Members can only read messages sent after they joined.

### 3.7 Presence and activity

- Whether you are **online**, when you were **last seen**, and whether you are **typing** in a chat.
- You can hide your last seen in Settings. If you hide it, you also cannot see other people's.
- If you turn **read receipts** off, others will not see when you have read their messages, and you will not see theirs.

### 3.8 Device and technical data

- A random identifier we create for each installation, the app version, and the Android version.
- A **push notification token** so we can deliver notifications to your phone.
- **App integrity signals.** We use Google Play Integrity through Firebase App Check to confirm that requests come from the genuine Samvad app on a genuine device.
- **Server logs.** Our servers keep technical logs to run and secure the service. They are designed **not** to contain message content or phone numbers; they hold request identifiers, counts and outcomes. An automated check blocks any release that would log phone numbers or tokens.

### 3.9 Security data

- If you turn on **two-step verification**, we store your PIN only as a salted, one-way hash (scrypt), never the PIN itself, together with failed-attempt counts and lockout times.
- **App lock** and **screenshot blocking** work on your phone. Your fingerprint, face or screen-lock data never leaves your phone and is never sent to us.

### 3.10 Safety reports and blocks

- If you **report** someone, the report includes the reported account and the **last 5 messages** of that chat, so our safety team can review it.
- If you **block** someone, we store that, so they cannot message you or see your presence and profile photo.

### 3.11 Data that stays on your phone

Chat themes, wallpaper photos you choose, names from your contacts, app lock settings and other preferences stay on your phone and are not uploaded. Chats you export are files you create and control.

### 3.12 This website

This website does not use advertising or analytics cookies. See our [Cookie Policy](/legal/cookie-policy). Our hosting provider keeps standard server logs (such as IP address and pages requested) for security, for a short period.

## 4. What we do not do

- We do **not** sell, rent or trade your personal data.
- We do **not** show advertising, and we do **not** use advertising, analytics or tracking SDKs in the app.
- We do **not** collect your precise or approximate location.
- We do **not** read your contacts' names on our servers or build profiles of people who are not Samvad users.
- We do **not** use your messages to train artificial intelligence models.
- We do **not** knowingly process the personal data of children (see section 12).

## 5. How we use personal data, and on what basis

| Purpose | Personal data used | Basis |
|---|---|---|
| Create, verify and secure your account | Phone number, verification, two-step PIN hash, device data | Consent |
| Deliver messages and keep them in sync across your devices | Messages, content, starred and scheduled messages, group data | Consent |
| Send notifications | Push token, sender name and (unless you turn previews off) a message preview | Consent |
| Show which of your contacts use Samvad | Contact phone numbers (not stored) | Consent, given through the contacts permission |
| Show presence, typing and read status | Presence and activity data | Consent; you can limit it in Settings |
| Keep Samvad safe; prevent spam, fraud and abuse; act on reports | Reports, blocks, rate-limit counters, logs, app integrity signals | Consent, and legitimate uses permitted by law |
| Respond to your requests and complaints | Your request and the data needed to answer it | Consent |
| Comply with law, court orders and lawful government requests | Only the data required | Legitimate use under section 7 of the DPDP Act |
| Respond to a medical emergency or threat to life, or a disaster | Only the data required | Legitimate use under section 7 of the DPDP Act |

We will not use your personal data for a new purpose without telling you and, where required, asking for your consent again.

## 6. Who can see or receive your personal data

### 6.1 People you communicate with

- People you chat with see your profile, the messages you send them, your presence (unless hidden) and read status (unless turned off).
- Group members see your profile and the messages you send in that group, from the time they joined.
- Anyone you send a message to can copy, forward, screenshot or export it. Please share carefully.

### 6.2 Our service providers

We use **Google Cloud and Firebase** (Google LLC and its affiliates) to host our databases, files and servers, to send sign-in codes by SMS, to deliver push notifications, and to check app integrity. Google processes this data only on our behalf, under its data processing terms. The full list is on our [Service Providers](/legal/service-providers) page.

### 6.3 Authorities

We disclose personal data when required by Indian law, a court order, or a valid request from an authorised government agency, for example under section 69 of the Information Technology Act, 2000 and the rules made under it, and when the law requires us to report child sexual abuse material. Our [Law Enforcement Guidelines](/legal/law-enforcement-guidelines) explain how we handle these requests.

### 6.4 Business changes

If Samvad is merged, acquired, or its assets are transferred, your personal data may move to the new owner, who must continue to protect it as this policy describes. We will tell you before that happens.

We do not share personal data with anyone else.

## 7. Where your data is stored

- Our main database, files and servers are in Google Cloud's **Mumbai, India** region.
- Online status and typing information is kept in Google Cloud's **Singapore** region.
- Our service providers may process data in other countries where they operate to provide their services (for example, to route SMS or notifications).

Any transfer of personal data outside India is made in line with section 16 of the DPDP Act and any restrictions notified by the Government of India.

## 8. How long we keep personal data

| Personal data | How long |
|---|---|
| Account, profile, devices, starred messages, server-side settings | Until you delete your account |
| Messages and files | Until deleted for everyone by the sender, or until the account that holds the chat is deleted. Messages you sent stay in other people's chats after you delete your account, because they are part of their conversation history |
| Scheduled messages | Until sent, cancelled or failed, then 7 days; deleted at once when you delete your account |
| Contact phone numbers checked for matches | Not stored |
| Safety reports | 90 days, unless the law requires us to keep them longer |
| Two-step verification data | Until you turn it off or delete your account |
| Server logs | Up to 30 days |
| Record that an account was deleted | Kept, without any profile or messages, so a deleted account cannot be misused |

We may keep personal data longer when the law requires it, for example to comply with a court order or preserve evidence in an investigation. When we no longer need personal data, we delete it or make it anonymous.

## 9. How we protect personal data

We use reasonable security practices in line with the Information Technology Act, 2000 and the SPDI Rules, 2011, including:

- encryption of all connections (TLS) and of data at rest;
- strict access rules on our servers that check every request, so only the people in a chat can read it;
- app integrity checks that block requests not coming from the genuine app;
- limits on sign-in attempts, contact lookups, reports and PIN guesses;
- optional two-step verification, app lock, screenshot blocking and private notifications;
- logging designed to exclude message content and phone numbers;
- limiting staff access to personal data to what is needed, and keeping a record of that access.

**Messages are not yet end-to-end encrypted.** We will update this policy and tell you in the app when end-to-end encryption is available.

No system is perfectly secure. If a personal data breach happens, we will inform the **Data Protection Board of India** and each affected user, as the DPDP Act and its rules require, and tell you what happened and what you can do. To report a security problem, see our [Security and Responsible Disclosure](/legal/security) page.

## 10. Your choices

- **Contacts:** turn the permission on or off in Android settings.
- **Last seen and online:** hide it in Settings → Privacy.
- **Read receipts:** turn them off in Settings → Privacy.
- **Notifications and previews:** turn previews off in Settings → Notifications.
- **Blocking:** block anyone from their chat menu.
- **Your profile:** edit or remove your name, about and photo at any time.

## 11. Your rights

Under the DPDP Act you have the right to:

1. **Access** — get a summary of the personal data we process about you, the processing we do, and the identities of the Data Fiduciaries and Data Processors we have shared it with, with a description of what was shared.
2. **Correction and erasure** — correct, complete or update inaccurate or incomplete data, and erase data that is no longer needed. You can change your profile in the app at any time and delete your account from Settings.
3. **Withdraw consent** — at any time. Withdrawing consent does not affect processing that happened before. We will stop processing and delete the data unless the law requires us to keep it. Because a messenger cannot work without your phone number and messages, withdrawing consent for these means deleting your account.
4. **Grievance redressal** — complain to our Grievance Officer and, if you are not satisfied, to the Data Protection Board of India. See [Grievance Redressal](/legal/grievance-redressal).
5. **Nominate** — nominate another person to exercise these rights for you if you die or become unable to.

**How to make a request.** Email [{{GRIEVANCE_EMAIL}}](mailto:{{GRIEVANCE_EMAIL}}) with your request and your registered phone number. To protect your account, we will ask you to confirm the request from that number. We respond within the time set by the DPDP Act and its rules, and at the latest within 30 days. Requests are free.

You also have duties under section 15 of the DPDP Act: for example, not to impersonate anyone, not to suppress material information, and not to file false or frivolous complaints.

## 12. Children

Samvad is only for people **aged 18 and over**. We do not knowingly process the personal data of children (people under 18 under the DPDP Act), and we do not track, monitor or target advertising at children. If we learn that a child has created an account, we will delete it. If you believe a child is using Samvad, please contact us at [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}}). Our [Child Safety Standards](/legal/child-safety-standards) explain how we act against child sexual abuse and exploitation.

## 13. App permissions

| Permission | Why | If you say no |
|---|---|---|
| Contacts | Find which contacts use Samvad, and show saved names | You can still start chats by phone number |
| Notifications | Tell you about new messages | You will not get notifications |
| Microphone | Record voice messages, only while you record | You cannot send voice messages |
| Photos and files | Chosen by you through Android's own picker; Samvad sees only what you choose | No permission needed |
| Fingerprint, face or screen lock | Optional app lock, handled by Android; nothing is sent to us | App lock stays off |
| Internet | Send and receive messages | Samvad cannot work |

## 14. Grievance Officer

In line with the Information Technology Act, 2000, the IT Rules 2021, the SPDI Rules 2011 and the DPDP Act:

**{{GRIEVANCE_NAME}}**, Grievance Officer
{{OPERATOR}}, {{ADDRESS}}
Email: [{{GRIEVANCE_EMAIL}}](mailto:{{GRIEVANCE_EMAIL}})

We acknowledge complaints within **24 hours** and resolve them within **15 days**. Complaints about content that exposes a person's private areas, shows them in full or partial nudity or in a sexual act, or impersonates them (including artificially morphed images) are acted on within **24 hours**. Full details are on our [Grievance Redressal](/legal/grievance-redressal) page.

## 15. Changes to this policy

We will update this policy when Samvad or the law changes. For important changes — for example, when end-to-end encryption becomes available, or if we start collecting a new kind of personal data — we will tell you in the app before the change takes effect and, where the law requires, ask for your consent again. The "Effective" date at the top shows the current version. Earlier versions are available on request.

## 16. Contact us

- Questions about this policy: [{{CONTACT_EMAIL}}](mailto:{{CONTACT_EMAIL}})
- Requests about your personal data and complaints: [{{GRIEVANCE_EMAIL}}](mailto:{{GRIEVANCE_EMAIL}})
- Post: {{OPERATOR}}, {{ADDRESS}}
