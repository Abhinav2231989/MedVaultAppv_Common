# MedVault - Quick Setup Guide

## 🎯 Goal
Enable multiple users to share patient records via Google Drive without cloud servers.

## ⏱️ Setup Time
- First device: 15 minutes
- Additional devices: 5 minutes each

---

## 📝 Step-by-Step Setup

### Part 1: Google Cloud Setup (Do Once)

#### 1.1 Create Google Cloud Project
```
1. Go to: https://console.cloud.google.com/
2. Click "Select Project" → "New Project"
3. Project name: MedVault
4. Click "Create"
5. Wait 30 seconds for project creation
```

#### 1.2 Enable Google Drive API
```
1. In Cloud Console, click "☰" menu
2. Go to: APIs & Services → Library
3. Search: "Google Drive API"
4. Click on it → Click "Enable"
5. Wait for confirmation
```

#### 1.3 Configure OAuth
```
1. Go to: APIs & Services → Credentials
2. Click "Configure Consent Screen"
3. Select: "External" (or "Internal" if you have Google Workspace)
4. Fill in:
   - App name: MedVault
   - User support email: your-email@gmail.com
   - Developer email: your-email@gmail.com
5. Click "Save and Continue"
6. Scopes → Click "Add or Remove Scopes"
7. Search: "drive.file"
8. Check: "../auth/drive.file"
9. Click "Update" → "Save and Continue"
10. Test users → Add your email
11. Click "Save and Continue"
```

#### 1.4 Create Android OAuth Credentials
```
1. Go to: APIs & Services → Credentials
2. Click "Create Credentials" → "OAuth client ID"
3. Application type: "Android"
4. Name: MedVault Android Client

5. Get SHA-1 fingerprint:
   For development (debug keystore):
   
   On Windows:
   keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   
   On Mac/Linux:
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   
   Copy the SHA-1 value (looks like: A1:B2:C3:...)

6. Paste SHA-1 fingerprint
7. Package name: com.medvault.app
8. Click "Create"
9. Note: You DON'T need to download anything for this setup
```

---

### Part 2: Build Android App

#### 2.1 Using Android Studio (Recommended)
```
1. Extract MedVaultApp.tar.gz
2. Open Android Studio
3. File → Open → Select MedVaultApp folder
4. Wait for Gradle sync (2-5 minutes)
5. Connect Android device via USB
6. Enable USB Debugging on device:
   - Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back → Developer Options
   - Enable "USB Debugging"
7. Click green "Run" button ▶
8. Select your device
9. Wait for installation (1-2 minutes)
```

#### 2.2 Using Command Line
```bash
cd MedVaultApp
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

### Part 3: First Device Setup

#### 3.1 Launch App
```
1. Open MedVault app on device
2. Enter name: (any name)
3. Enter PIN: 258411
4. Click "Login"
```

#### 3.2 Connect Google Drive
```
1. Popup: "Connect to Google Drive for multi-user sync?"
2. Click "Yes"
3. Choose Google account
4. Click "Allow" to grant permissions:
   ✓ See and download Drive files
   ✓ Create new Drive files
5. Wait for "Google Drive connected" message
6. Sync indicator turns GREEN
```

#### 3.3 Add First Record
```
1. Click "+ Add Patient Record"
2. Fill in:
   - Patient Name: Test Patient
   - Date of Visit: Today's date
   - Diagnosis: Test diagnosis
   - Medicines: Test medicine
3. Click "Save Record"
4. See success message
5. Notice record appears in table
6. Status shows "Synced to Google Drive"
```

---

### Part 4: Additional Devices Setup

#### 4.1 Install App
```
1. Transfer APK to new device
2. Install APK
3. Open MedVault
```

#### 4.2 Login and Connect
```
1. Enter name: (any name)
2. Enter PIN: 258411
3. Click "Login"
4. Connect Google Drive (same steps as Part 3.2)
5. Use SAME Google account (shared account)
```

#### 4.3 Verify Sync
```
1. Wait 5 seconds
2. Click "☁️ Sync Now"
3. Test Patient record should appear!
4. Add a new record on Device 2
5. Go to Device 1 → Click "Sync Now"
6. New record appears on Device 1!
```

---

## ✅ Verification Checklist

After setup, verify each device has:
- [ ] Green sync indicator
- [ ] "Synced: just now" status
- [ ] All patient records visible
- [ ] Can add new records
- [ ] New records appear on other devices after sync

---

## 🔧 Common Issues & Solutions

### Issue 1: "OAuth client not found"
**Solution:**
```
1. Double-check package name: com.medvault.app
2. Verify SHA-1 fingerprint matches your keystore
3. Wait 5-10 minutes after creating OAuth client
```

### Issue 2: "Permission denied" when connecting Drive
**Solution:**
```
1. Go to Google Cloud Console
2. OAuth consent screen → Add your email to "Test users"
3. Try connecting again
```

### Issue 3: "Sync failed"
**Solution:**
```
1. Check internet connection
2. Open Google Drive app → Check storage (need free space)
3. Logout and login again
4. Reconnect Google Drive
```

### Issue 4: Different data on devices
**Solution:**
```
1. Both devices click "Sync Now"
2. Wait for green indicator
3. Records should match
```

---

## 🎓 Understanding the System

### How Data Flows
```
Device 1                Google Drive              Device 2
   |                         |                        |
   |-- Save Record --------->|                        |
   |                         |                        |
   |                         |<---- Sync Request -----|
   |                         |                        |
   |                         |----- Send Record ----->|
   |                         |                        |
```

### Sync Timing
- **Immediate**: After saving/deleting a record
- **Background**: Every 5 minutes automatically
- **Manual**: Click "Sync Now" button anytime

### What Gets Synced
- ✅ All patient records
- ✅ Patient ID mappings
- ✅ Timestamps for conflict resolution
- ❌ User login info (stays local)
- ❌ App settings (stays local)

---

## 📊 Capacity Limits

| Item | Limit |
|------|-------|
| Devices | Unlimited |
| Records | ~50,000 (within 15GB) |
| File Size | Limited by Drive quota |
| Users | Unlimited |
| Free Storage | 15GB |

---

## 🔐 Security Recommendations

1. **Change Default PIN**
   - Edit medical-history.html
   - Change: `const CORRECT_PIN = '258411';`
   - Rebuild app

2. **Use Strong Google Account**
   - Enable 2-Step Verification
   - Strong password
   - Regular password changes

3. **Limit Folder Access**
   - Share only with trusted team
   - Review permissions regularly
   - Remove access when staff leaves

4. **For HIPAA Compliance**
   - Use Google Workspace (paid)
   - Sign Business Associate Agreement
   - Enable audit logging

---

## 📱 Multi-Device Scenarios

### Scenario 1: Clinic with 3 Tablets
```
Setup: 
- 1 shared Google account
- MedVault folder shared with that account
- All 3 tablets login with same Google account

Result:
- All tablets see same data instantly
- Any tablet can add/edit records
- Perfect for clinic reception, exam rooms
```

### Scenario 2: Doctor + Assistants
```
Setup:
- Each person has own Google account
- Doctor creates MedVault folder
- Doctor shares folder with assistants (Editor access)
- Each person connects their own Google account

Result:
- Everyone sees same patient data
- Personal Google Drive access maintained
- Better audit trail (who made changes)
```

### Scenario 3: Multiple Clinics
```
Setup:
- Create separate MedVault folders per clinic
- Each clinic team shares their folder
- Use different app instances or data separation

Result:
- Clinic A data separate from Clinic B
- Each clinic team only sees their data
```

---

## 🚀 Quick Start Commands

```bash
# Extract and build
tar -xzf MedVaultApp.tar.gz
cd MedVaultApp
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# View logs (debugging)
adb logcat | grep MedVault
```

---

## 📞 Emergency Recovery

### Lost all data?
```
1. Check Google Drive → MedVault folder
2. File should be there: medvault_db.json
3. Click "Sync Now" in app
4. Data restored!
```

### Need to start fresh?
```
1. Delete medvault_db.json from Google Drive
2. Clear app data on all devices
3. Start adding records again
```

---

## ✨ You're All Set!

Your MedVault system is now:
- ✅ Multi-user enabled
- ✅ Cloud synced (via Google Drive)
- ✅ Working offline
- ✅ Automatically backing up
- ✅ Accessible from multiple devices

Start adding patient records and they'll automatically sync across all devices!

---

**Need help? Review the troubleshooting section or README.md**
