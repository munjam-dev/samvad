---
title: Security and Responsible Disclosure
summary: How we protect Samvad, how to report a vulnerability safely, and what we promise researchers who do.
updated: 2026-10-14
order: 9
group: authorities
---

> **Found a security issue?** Email [{{SECURITY_EMAIL}}](mailto:{{SECURITY_EMAIL}}). We acknowledge reports within 3 working days, keep you updated, and will not take legal action against good-faith research that follows this policy.

## 1. How we protect Samvad

- **Encryption** of all connections (TLS) and of stored data.
- **Deny-by-default access rules** on our databases and file storage, so every request is checked and only the people in a chat can read it. These rules are covered by automated tests that run on every change.
- **App integrity checks** (Google Play Integrity through Firebase App Check) on server functions.
- **Rate limits** on sign-in codes, contact lookups, reports, profile photo changes and two-step PIN guesses, with increasing lockouts.
- **Two-step verification** (PIN stored only as a salted scrypt hash), **app lock**, **screenshot blocking** and **private notifications** for users.
- **Privacy-preserving logs** that exclude message content and phone numbers, enforced by an automated check.
- **Least-privilege access** for our team and service accounts.

Messages are **not yet end-to-end encrypted**; end-to-end encryption is on our roadmap.

## 2. Reporting a vulnerability

Email [{{SECURITY_EMAIL}}](mailto:{{SECURITY_EMAIL}}) with:

- a description of the issue and its impact;
- the steps to reproduce it (app version, Android version, and any proof-of-concept);
- your name or handle, if you would like credit.

Please report to us privately first, and give us reasonable time to fix the issue before telling anyone else. We aim to fix critical issues within 30 days.

## 3. Scope

**In scope:** the Samvad Android app, Samvad's Firebase backend (security rules, Cloud Functions), and this website.

**Out of scope:** issues in Google's own platforms (report those to Google), denial-of-service attacks, social engineering or phishing of our team or users, physical attacks, spam, reports from automated scanners without a demonstrated impact, missing best-practice headers without impact, and issues that need a rooted or compromised device.

## 4. Rules of engagement

To stay protected by this policy, please:

- only test with accounts you own, or with the explicit permission of the account owner;
- never access, change or delete other users' data; if you accidentally access it, stop, do not keep or share it, and tell us;
- not degrade the service for others, or use automated tools that send large volumes of requests;
- not attempt to bypass rate limits at scale, send spam, or test with real people's phone numbers;
- not demand payment in exchange for not disclosing an issue.

## 5. Our promise

If you follow this policy, we will:

- treat your research as authorised and not pursue legal action against you for it;
- acknowledge your report within **3 working days** and keep you informed of our progress;
- credit you publicly, if you wish, once the issue is fixed.

We do not currently run a paid bug bounty programme.

## 6. Reporting abuse instead

To report abuse, spam, scams or harmful content (rather than a technical vulnerability), use **Report** in the app or see [Grievance Redressal](/legal/grievance-redressal).
