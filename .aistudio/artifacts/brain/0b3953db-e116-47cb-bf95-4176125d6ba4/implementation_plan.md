# Sanraksha (संरक्षा) — Personal Document Vault

A privacy-first, offline-first native Android document vault that empowers users to securely store, categorize, preview, and track expiry dates for essential personal records without external cloud dependencies.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and user experience preferences have been confirmed and incorporated into this blueprint:

- **Security & App Lock**: Optional in Settings (unlocked by default on first launch for immediate onboarding and document import; biometric/PIN lock can be enabled anytime in Settings).
- **Home Dashboard Carousel**: Auto-advances across 5 promotional and feature highlights every 5 seconds, automatically pausing on touch gestures or interaction.
- **Initial Vault State**: Clean initial state featuring 7 default categories (Identity, Education, Banking, Insurance, Medical, Travel, Other) and an inviting empty state guiding the user to import their first document.
- **Hardware-Backed Encryption**: Documents are encrypted using Android Keystore-backed AES-256-GCM before writing to app-private storage (`context.filesDir/vault`), ensuring zero plaintext exposure.
- **Zero Cloud/ITSM Overhead**: All ITSM components from the Pega Blueprint are completely omitted; the app runs 100% locally with Room SQLite and native Android notifications.

---

### 1. Overview & Core Concept

- **What It Does**: Sanraksha provides a secure, encrypted mobile vault to import PDF and image documents, tag them with metadata and categories, inspect and preview them safely, share/export on demand, and receive proactive local notifications before documents expire.
- **Target Audience / Personas**:
  - *Students*: Safeguarding degree certificates, mark sheets, identity proofs, and test score cards.
  - *Working Professionals*: Managing employment contracts, tax forms, insurance policies, and vehicle registrations.
  - *Family Organizers*: Tracking household utilities, property papers, health records, and family IDs.
- **Key Value**: True privacy and offline autonomy. No account creation required, no analytics or external server uploads, and military-grade hardware key protection.

---

### 2. User Experience & Visual Design

#### Key User Flows

1. **Splash & Onboarding Walkthrough**:
   - Modern 3-step carousel highlighting document organization, local hardware encryption, and smart expiry alerts.
   - Quick action to "Get Started" or "Skip" directly into the dashboard.

2. **Home Dashboard**:
   - **Header**: Sanraksha wordmark (संरक्षा), shield crest, notification status icon, and quick settings shortcut.
   - **Top Banner Carousel**: 5 rich slides with headlines, subtitle descriptions, 3D visual assets, animated indicator dots, and direct action CTAs (*Add Document*, *Explore Security*, *Set Reminder*, *Import Document*, *Explore Sanraksha*).
   - **Vault Statistics Bar**: Real-time counter of total documents, documents expiring soon, and active categories.
   - **Quick Category Grid**: Identity, Education, Banking, Insurance, Medical, Travel, Other with distinct iconography and item badges.
   - **Recent Documents**: Quick access cards with file type pills, category badges, and relative timestamps.
   - **Upcoming Expiry Reminders**: Alert cards with urgency badges (Overdue, 7 Days, 30 Days).
   - **Empty State**: Friendly illustration and "Add your first document" call to action when the vault is empty.

3. **Document Library**:
   - Search bar with instant real-time query filtering.
   - Horizontal filter chips: All, PDFs, Images, and Category pills.
   - Sort controls (Date Added, Title, Expiry Date).
   - Grid / List view toggle.
   - Document cards with context actions: View details, Export/Share, Edit metadata, and Delete.

4. **Add & Import Document Experience**:
   - Real Android System File Picker (`ActivityResultContracts.OpenDocument` / `GetContent` / `PickVisualMedia`).
   - Metadata input form: Document Title, Category selector, Optional Notes/Description, Optional Expiry Date picker.
   - Configurable reminder checkboxes: 30 days before, 7 days before, 1 day before expiry.
   - File validation (MIME types, file size check, duplicate detection).
   - Encryption indicator showing Keystore AES-GCM encryption in progress.

5. **Document Detail & Secure In-App Preview**:
   - Full-bleed viewer: High-fidelity image viewer and native Android `PdfRenderer` page-by-page renderer.
   - Secure temporary decryption in memory/isolated cache; automatic cache purge on exit.
   - Action bar for Share/Export via secure `FileProvider`, Edit Metadata, or Delete.

6. **Expiry Reminders Center**:
   - Dedicated tab listing all scheduled reminders chronologically.
   - Color-coded urgency indicators: Expired (Red), Within 7 days (Amber), Within 30 days (Blue).
   - Direct shortcut to renew, update expiry date, or dismiss reminder.

7. **Settings, Security & Privacy**:
   - Biometric Authentication & PIN fallback toggle.
   - Auto-lock timeout options (Immediately, 1 min, 5 min, 15 min).
   - Notification permissions and reminder alert settings.
   - Theme toggle (System default, Light, Dark).
   - Data Export and "Wipe All Data" with double-confirmation dialog.
   - Privacy Policy and offline safety explanation.

#### Visual Identity & Theme Tokens

- **Brand Aesthetic**: Minimalist, clean fintech/privacy feel. Crisp white surfaces, deep navy typography, and electric blue accents.
- **Color Palette**:
  - `Background`: `#FFFFFF` (Light) / `#101114` (Dark)
  - `Surface`: `#F7F9FC` (Light) / `#1A1D21` (Dark)
  - `Surface Variant`: `#EFF6FF` (Soft blue surface) / `#1E293B` (Dark)
  - `Primary Blue`: `#2563EB` (Electric Blue)
  - `Deep Blue`: `#1D4ED8`
  - `Text Primary`: `#101114` (Light) / `#F8FAFC` (Dark)
  - `Text Secondary`: `#626B78` (Light) / `#94A3B8` (Dark)
  - `Card Border`: `#E7EBF2` (Light) / `#2D3748` (Dark)
  - `Status Success`: `#15803D`
  - `Status Warning`: `#B45309`
  - `Status Error`: `#DC2626`
- **Typography & Geometry**:
  - M3 Type scale with bold headers and readable metadata captions.
  - 18–24 dp corner radius for cards and modal sheets; 14–16 dp for buttons and text inputs.
  - Minimum 48 dp touch targets for full accessibility.

---

### 3. Key Product Decisions & Trade-Offs

- **Android Keystore AES-256-GCM**:
  - *Chosen Approach*: Generate a hardware-backed SecretKey in the Android Keystore (`AndroidKeyStore`). Encrypt file streams with AES/GCM/NoPadding, embedding the 12-byte initialization vector (IV) in the file header.
  - *Why*: Guarantees data at rest is secure even if the device's storage is inspected. Document bytes are never stored in Room.
  - *Alternatives Considered*: Plain storage (insecure) or third-party proprietary crypto (unnecessary complexity).
- **Room SQLite for Metadata & Reactive Flows**:
  - *Chosen Approach*: Room Database with Kotlin Coroutines and reactive `Flow` queries for instant UI updates when documents or reminders change.
  - *Why*: Battle-tested, zero network dependency, instant query performance.
- **Native Android Notification Scheduling**:
  - *Chosen Approach*: `AlarmManager` exact alarms with `BroadcastReceiver` and `NotificationManagerCompat` channels (`Sanraksha Expiry Reminders`).
  - *Why*: Functions 100% offline without requiring external push notification servers.
- **Isolated Decrypted Previewing**:
  - *Chosen Approach*: Decrypt directly to ephemeral memory stream or temporary cache file that is immediately deleted when leaving the preview screen.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Sanraksha UI Layer                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────────────┐  │
│  │ Home Dashboard  │  │ Document Library │  │ Add / Import Document │  │
│  │ (Top Carousel)  │  │ (Search/Filters) │  │ (File Picker Flow)    │  │
│  └────────┬────────┘  └────────┬─────────┘  └───────────┬───────────┘  │
│           │                    │                        │              │
│  ┌────────┴────────┐  ┌────────┴─────────┐  ┌───────────┴───────────┐  │
│  │ Document Detail │  │ Expiry Reminders │  │  Settings & Security  │  │
│  │ (PDF/Img Viewer)│  │ (Chronological)  │  │  (Biometrics / PIN)   │  │
│  └────────┬────────┘  └────────┬─────────┘  └───────────┬───────────┘  │
└───────────┼────────────────────┼────────────────────────┼──────────────┘
            │                    │                        │
┌───────────▼────────────────────▼────────────────────────▼──────────────┐
│                     Sanraksha ViewModels Layer                         │
│   (VaultViewModel, LibraryViewModel, ReminderViewModel, SettingsVM)     │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼──────────────────────────────────────┐
│                        Repository & Storage Layer                      │
│  ┌───────────────────────────┐     ┌────────────────────────────────┐  │
│  │ DocumentVaultRepository   │     │ CryptoVaultManager             │  │
│  │ - Reactive Room Flow      │     │ - Android Keystore (AES-GCM)   │  │
│  │ - Expiry calculation      │     │ - Encrypted file I/O streams   │  │
│  └─────────────┬─────────────┘     └───────────────┬────────────────┘  │
└────────────────┼───────────────────────────────────┼───────────────────┘
                 │                                   │
┌────────────────▼─────────────┐     ┌───────────────▼────────────────┐
│      Room Local Database     │     │   App-Private Storage (Vault)  │
│  - documents table           │     │   filesDir/vault/*.enc         │
│  - categories table          │     └────────────────────────────────┘
│  - reminders table           │     ┌────────────────────────────────┐
│  - preferences table         │     │ Android AlarmManager / Notifs  │
└──────────────────────────────┘     └────────────────────────────────┘
```

#### Database Entities & Schema

1. **DocumentEntity**:
   - `id`: String (UUID Primary Key)
   - `title`: String
   - `categoryId`: String (Foreign Key to Category)
   - `internalFileName`: String (UUID-based encrypted filename in app storage)
   - `originalFileName`: String
   - `mimeType`: String (`application/pdf`, `image/jpeg`, `image/png`, etc.)
   - `fileSizeBytes`: Long
   - `createdAt`: Long
   - `updatedAt`: Long
   - `expiryDate`: Long? (Null if no expiry)
   - `encryptionVersion`: Int (1 = AES-256-GCM)
   - `notes`: String?

2. **CategoryEntity**:
   - `id`: String (Primary Key)
   - `name`: String
   - `iconName`: String
   - `isDefault`: Boolean
   - `createdAt`: Long

3. **ReminderEntity**:
   - `id`: String (UUID Primary Key)
   - `documentId`: String (Foreign Key)
   - `expiryDate`: Long
   - `reminderDaysBefore`: Int (30, 7, or 1)
   - `scheduledTimestamp`: Long
   - `isTriggered`: Boolean
   - `notificationId`: Int

4. **VaultPreferences**:
   - `isAppLockEnabled`: Boolean (Default false)
   - `pinHash`: String?
   - `biometricsEnabled`: Boolean
   - `autoLockMinutes`: Int
   - `themeMode`: String ("system", "light", "dark")
   - `notificationsEnabled`: Boolean
   - `onboardingCompleted`: Boolean

---

### Implementation Phases

- **Phase 1: Project Foundations & Theme**: Update `metadata.json`, `strings.xml`, theme colors, typography, design tokens, and Keystore crypto manager.
- **Phase 2: Database & Core Models**: Room entities, DAOs, Database, and pre-population of default categories.
- **Phase 3: File Storage & Encryption Engine**: Keystore key generator, AES-GCM stream encryption/decryption, safe file import, and FileProvider export.
- **Phase 4: UI Screen Implementation**:
  - Splash & Onboarding walkthrough.
  - Home Dashboard with top promotional banner carousel, stats, category shortcuts, recent documents, and reminders.
  - Document Library with search, filter chips, and grid/list views.
  - Add Document form with file picker, metadata, and expiry configuration.
  - Document Details & Secure In-App Viewer (PDF rendering + Image display).
  - Reminders Screen.
  - Settings & Security screen (PIN/Biometric lock toggle, theme, export, wipe vault).
- **Phase 5: Notification Reminders & Permissions**: Android `AlarmManager` receiver and notification channels.
- **Phase 6: Verification & Compilation**: Build app via `compile_applet`, run unit tests, and verify smooth navigation and end-to-end functionality.
