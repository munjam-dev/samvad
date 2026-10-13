@echo off
echo 🚀 Building Zingify APK - Simple Method
echo =====================================

echo 🧹 Cleaning Flutter cache...
flutter clean

echo 📦 Getting dependencies...
flutter pub get

echo 🔨 Building APK (this may take 5-10 minutes)...
flutter build apk --release --no-shrink

echo 📱 Checking for APK...
if exist "build\app\outputs\flutter-apk\app-release.apk" (
    echo ✅ SUCCESS! APK found at:
    echo 📁 build\app\outputs\flutter-apk\app-release.apk
    echo 📊 APK Size:
    dir "build\app\outputs\flutter-apk\app-release.apk" | find "app-release.apk"
    echo 🎉 Your Zingify APK is ready!
    echo 📱 Copy this file to your Android device and install
) else (
    echo ❌ APK build failed
    echo 🔧 Try these solutions:
    echo    1. Use GitHub Actions: https://github.com/munjam-dev/zingify-mobile/actions
    echo    2. Use Codemagic: https://codemagic.io
    echo    3. Check Android Studio setup
    echo    4. Update Flutter: flutter upgrade
)

echo 🌐 Opening build directory...
if exist "build\app\outputs\flutter-apk" (
    explorer "build\app\outputs\flutter-apk"
)

echo 🎯 Build process completed!
pause
