# PRD: Samvad Website and Legal Centre

Status: approved scope (owner answers 1A 2A 3A 4A, 2026-10-14) · code in `website/`

## 1. Introduction

Samvad needs a public website that explains the app and hosts every legal and policy document the app,
Google Play and Indian law require. The owner supplied a licensed NextSaaS "App Development" template
(Next.js 16, React 19, Tailwind 4, GSAP). We turn it into Samvad's site, keep its UI/UX, remove the
agency-only pages, and add a Legal Centre whose pages the app links to.

## 2. Goals

- One site at `https://samvad-messenger.web.app` (Firebase Hosting, static export), free to run.
- Every link in the app (Terms, Privacy, Delete account, Child safety, Help) opens a real page here.
- Legal documents are complete, accurate to how Samvad actually works, and readable on a phone.
- No dead links, no template placeholder text, no agency pages, no login/signup.

## 3. User stories

### US-001: Site in the repo, static export
**Description:** As the owner, I want the website versioned with the app and deployable to Firebase.

**Acceptance Criteria:**
- [ ] Template copied to `website/` without `node_modules`/build output; licence assumed held by owner
- [ ] `next.config.ts` uses `output: 'export'` and unoptimised images; `npm run build` writes `website/out`
- [ ] `firebase.json` hosting serves `website/out` with clean URLs and security headers
- [ ] Lint and type check pass

### US-002: Legal Centre hub
**Description:** As a visitor, I want one page listing every policy so I can find the right one.

**Acceptance Criteria:**
- [ ] `/legal` lists all documents as cards (title, one-line summary, last updated), grouped: Your
      privacy · Using Samvad · Safety · For authorities and researchers
- [ ] Shows who operates Samvad and how to reach the Grievance Officer
- [ ] Verify in browser using dev-browser skill (light and dark, 375 px and 1280 px)

### US-003: Document viewer
**Description:** As a reader, I want long documents to be easy to navigate, print and cite.

**Acceptance Criteria:**
- [ ] `/legal/[slug]` renders Markdown from `website/src/content/legal/*.md` (front matter: title,
      summary, updated, order, group)
- [ ] Sticky table of contents built from the document's headings (collapsible on phones), heading
      anchors, "Effective" date, "Print or save as PDF", related documents, contact box
- [ ] Business details come from one config file and appear identically in every document
- [ ] Verify in browser using dev-browser skill

### US-004: Full legal documents
**Description:** As a user, regulator or app store reviewer, I want complete and accurate policies.

**Acceptance Criteria:**
- [ ] Privacy Policy (DPDP Act 2023, IT Act 2000, SPDI Rules 2011, Play data safety)
- [ ] Terms of Service (IT Rules 2021 user agreement requirements, Consumer Protection Act 2019)
- [ ] Community Guidelines
- [ ] Account and Data Deletion (Google Play web deletion requirement)
- [ ] Child Safety Standards (Google Play CSAE requirement, POCSO Act 2012)
- [ ] Grievance Redressal (IT Rules 2021 rule 3(2), DPDP Act section 13)
- [ ] Cookie Policy (website)
- [ ] Law Enforcement Guidelines
- [ ] Security and Responsible Disclosure
- [ ] Intellectual Property and Takedown Policy (Copyright Act 1957, Trade Marks Act 1999, IT Rules)
- [ ] Service Providers (sub-processors)
- [ ] Every statement matches the app's real behaviour (no end-to-end encryption claim, no ads, etc.)

### US-005: App links and short URLs
**Description:** As an app user, I want the links in the app to open the right document.

**Acceptance Criteria:**
- [ ] `/privacy`, `/terms`, `/delete-account`, `/child-safety`, `/community-guidelines`, `/grievance`
      redirect (301) to the matching `/legal/...` page
- [ ] The app's Terms and Privacy links keep working

### US-006: Rebrand the rest of the site
**Description:** As a visitor, I want the site to be about Samvad only.

**Acceptance Criteria:**
- [ ] Kept and rewritten: Home, Features, Security, Download, FAQ, Support/Contact, Changelog, Brand kit
- [ ] Removed: agency pages (services, pricing, case studies, careers, affiliates, referral,
      whitepapers, blog, team, press, testimonials, customers, use cases, glossary, tutorial,
      process, manifesto, success stories, integration, analytics, refund and affiliate policies,
      GDPR, login, signup)
- [ ] Navbar and footer list only real pages; "Get the app" goes to Download, which shows "Coming soon
      to Google Play"
- [ ] Verify in browser using dev-browser skill

## 4. Functional requirements

- FR-1: The site must build as a static export served by Firebase Hosting on `samvad-messenger`.
- FR-2: Each legal document must live in one Markdown file and render through one shared viewer.
- FR-3: Operator name, address, Grievance Officer and contact emails must come from one config file.
- FR-4: The legal short URLs must redirect to their documents.
- FR-5: No page may contain template text, sample people, fake metrics or links to removed pages.
- FR-6: The site must not set tracking cookies or load analytics.

## 5. Non-goals

- A blog, careers or press section; user accounts on the website; web messaging
- Translations of the legal documents (English is the governing version for now)
- Legal advice: documents must be reviewed by a lawyer before launch

## 6. Open items (owner)

- Business name, address, Grievance Officer name, grievance / contact / child-safety emails
- Lawyer review before publishing on Google Play
