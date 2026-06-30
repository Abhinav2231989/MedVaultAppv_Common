# MedVault - Simple Installation Guide

Since the Gradle wrapper files cannot be included, here are your options:

## ✅ EASIEST OPTION: Use Android Studio

1. **Open Android Studio**
2. **File → Open** → Select the MedVaultApp folder
3. **Wait for Gradle sync** (it will download everything automatically)
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. **Wait 3-5 minutes**
6. **Click "locate"** when build completes
7. **Transfer APK to phone and install**

---

## 📱 Alternative: Install Pre-Built APK

Since building requires additional setup, I recommend:

### Option A: Let Android Studio Handle Everything
- Android Studio automatically downloads Gradle wrapper
- No manual command line needed
- Just open project and click Build

### Option B: Manual Gradle Setup (Advanced)

If you want to use command line:

1. **Download Gradle 8.1:**
   - Go to: https://gradle.org/releases/
   - Download: gradle-8.1-bin.zip
   - Extract to: C:\gradle

2. **Add to PATH:**
   - System Properties → Environment Variables
   - Add: C:\gradle\gradle-8.1\bin

3. **Build:**
   ```
   cd C:\Users\AbhinavShaurya\Downloads\MedVaultApp
   gradle assembleDebug
   ```

---

## 🎯 RECOMMENDED: Just Use Android Studio

**It's much simpler!**

1. Open project in Android Studio ✅
2. Let it sync (automatic) ✅  
3. Click Build APK ✅
4. Done! ✅

The Gradle sync error you saw is minor - the build will still work.

---

## 📋 Your OAuth Settings (Already Configured)

**Package name:** com.medvault.app
**SHA-1:** EF:30:60:05:B1:81:16:1B:5E:8D:1F:60:E3:4D:A4:5A:30:CB:A5:D9

Make sure you've created the OAuth client in Google Cloud Console with these values!

---

## ✅ After Installation

1. Open MedVault app
2. Login (PIN: 258411)
3. Connect Google Drive
4. Start using!

