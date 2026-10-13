# 🚀 Samvad - Premium Real-Time Messaging App

संवाद - Meaningful conversations, securely connected.

A **PRODUCTION-READY** real-time messaging application built with Flutter and Node.js, featuring secure authentication, real-time messaging, and high-end modern chat features.

## ✨ Features

### 🔐 **Secure Authentication**
- ✅ **Firebase Phone Authentication**
- ✅ **Global Country Code Support**
- ✅ **Terms & Privacy Compliance**
- ✅ **Resend OTP** with Smart Timer
- ✅ **Automatic OTP Retrieval**
- ✅ **JWT-based Secure Session** Management

### 💬 **Elite Messaging Experience**
- ✅ **Real-Time Engine** via Socket.IO
- ✅ **Typing Indicators** & Presence Awareness
- ✅ **Advanced Message Status** (Sent/Delivered/Seen)
- ✅ **Rich Chat History** with Optimized Loading
- ✅ **Smart Notification Badges**

### 📱 **Rich Media & Sharing**
- ✅ **HD Image Sharing** (Camera + Gallery)
- ✅ **Crystal Clear Video Messages**
- ✅ **High-Quality Voice Notes**
- ✅ **Universal File Attachments** (PDF, DOC, etc.)
- ✅ **Premium Emoji Engine** with Search
- ✅ **Instant Media Previews**

### 🔔 **Push Notifications**
- ✅ **Firebase Cloud Messaging** (FCM) 
- ✅ **Intelligent Background Handling**
- ✅ **Seamless Foreground Alerts**
- ✅ **Direct Action Notifications**
- ✅ **Dynamic Badge Updates**

### 👥 **Intelligent Contact Management**
- ✅ **Native Device Contact Sync**
- ✅ **Granular Permission Handling**
- ✅ **Blazing Fast Contact Search**
- ✅ **Dynamic Profile Management**
- ✅ **Accurate Last Seen** Logic

### 🎨 **Premium Design Language**
- ✅ **Material 3 Engine**
- ✅ **Elite Dark Theme** (Optimized for OLED)
- ✅ **Glassmorphism & Depth Effects**
- ✅ **Fluid 60FPS Animations**
- ✅ **High-Density Layouts**
- ✅ **Custom Premium Branding**

## 🏗️ **Architecture**

### **Backend (Node.js)**
- **Express.js** Enterprise API
- **MongoDB** Cluster Integration
- **Socket.IO** Binary Transport
- **JWT** Encryption
- **Firebase Admin SDK**
- **Cloud Storage** Integration
- **Military-Grade Security** Headers

### **Frontend (Flutter)**
- **Flutter 3.x** Stable
- **Riverpod** Modern State Management
- **Socket.IO Client** (Persistent)
- **Local Persistence** (AES Encrypted)
- **Native Bridge** for Media & Permissions

## 🚀 **Deployment Guide**

### **1. Backend Environment**

```bash
# Update .env with production credentials:
MONGODB_URI=your_production_mongodb_uri
JWT_SECRET=your_long_secure_random_secret
FIREBASE_PROJECT_ID=samvad-app
TWILIO_ACCOUNT_SID=your_twilio_sid
TWILIO_AUTH_TOKEN=your_twilio_token
```

### **2. Firebase Production Setup**

1. **Authentication** → Enable Phone Sign-in.
2. **Cloud Messaging** → Upload APNs (for iOS) and Server Key.
3. **Android**: Download `google-services.json` to `android/app/`.
4. **iOS**: Download `GoogleService-Info.plist` to `ios/Runner/`.

### **3. Mobile App Build**

```bash
# Clean project
flutter clean

# Get dependencies
flutter pub get

# Generate Release APK
flutter build apk --release --obfuscate --split-debug-info=debug_info

# Generate App Bundle (Play Store)
flutter build appbundle --release
```

## 🔌 **API Integration**

The app is pre-configured to point to `https://api.samvad.app`. All endpoints are secured via JWT and Rate-Limiting.

### **Key Events**
- `send_message`: Encrypted payload delivery
- `typing`: Real-time state synchronization
- `mark_messages_seen`: Read-receipt updates

## 🔐 **Security Standard**

- **SSL/TLS Termination** for all traffic
- **JWT Expiry & Refresh** mechanisms
- **XSS & NoSQL Injection** prevention
- **Encrypted Media Storage**
- **OWASP Mobile Top 10** Compliance

---
 
**Made with ❤️ in India | Samvad - The Future of Premium Messaging**
