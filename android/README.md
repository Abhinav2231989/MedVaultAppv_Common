# MedVault - Multi-User Medical Records with Google Drive Sync

Complete Android application for managing medical records with **multi-user cloud synchronization** via Google Drive.

## 🌟 Key Features

✅ **Multi-User Shared Database** - All users see the same data via Google Drive  
✅ **Offline-First** - Works 100% offline, syncs when online  
✅ **Auto-Sync** - Automatic background synchronization  
✅ **Manual Sync** - "Sync Now" button for instant updates  
✅ **Conflict Resolution** - Smart merging of simultaneous edits  
✅ **No Cloud Servers** - Uses Google Drive you control  
✅ **Secure** - Data encrypted in transit via HTTPS  
✅ **Free** - 15GB Google Drive storage included  

## 📱 Architecture

```
┌──────────────────┐         ┌──────────────────┐         ┌──────────────────┐
│   Device 1       │         │  Google Drive    │         │   Device 2       │
│                  │         │                  │         │                  │
│  Local SQLite ──→│─ Sync ─→│  Shared Folder  │←─ Sync ─│←── Local SQLite  │
│  (Offline DB)    │         │  "MedVault"     │         │  (Offline DB)    │
│                  │         │                  │         │                  │
│  Works without   │         │  medvault_db    │         │  Works without   │
│  internet        │         │  .json          │         │  internet        │
└──────────────────┘         └──────────────────┘         └──────────────────┘
```

## 🚀 Setup Instructions

### Step 1: Google Cloud Console Setup (One-Time)

1. **Go to Google Cloud Console**
   - Visit: https://console.cloud.google.com/
   - Sign in with your Google account

2. **Create New Project**
   - Click "Select a project" → "New Project"
   - Name: `MedVault`
   - Click "Create"

3. **Enable Google Drive API**
   - Go to: APIs & Services → Library
   - Search for "Google Drive API"
   - Click "Enable"

4. **Create OAuth 2.0 Credentials**
   - Go to: APIs & Services → Credentials
   - Click "Create Credentials" → "OAuth client ID"
   - Application type: "Android"
   - Name: `MedVault Android`
   
5. **Configure OAuth Consent Screen**
   - Go to: OAuth consent screen
   - User Type: "Internal" (if using Google Workspace) or "External"
   - App name: `MedVault`
   - User support email: Your email
   - Scopes: Add `../auth/drive.file`
   - Save

6. **Get SHA-1 Fingerprint**
   ```bash
   # For debug keystore (development)
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   
   # For release keystore (production)
   keytool -list -v -keystore /path/to/your/keystore -alias your-alias
   ```
   - Copy the SHA-1 fingerprint

7. **Add SHA-1 to OAuth Client**
   - Paste SHA-1 fingerprint
   - Package name: `com.medvault.app`
   - Click "Create"

8. **Download OAuth Client JSON**
   - Download the `google-services.json` file
   - Place it in: `MedVaultApp/app/` directory

### Step 2: Build the Android App

#### Option A: Android Studio (Recommended)

1. **Open Project**
   - Extract `MedVaultApp.tar.gz`
   - Open Android Studio
   - File → Open → Select `MedVaultApp` folder

2. **Add OAuth Credentials**
   - Place `google-services.json` in `app/` directory

3. **Build APK**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - APK location: `app/build/outputs/apk/debug/app-debug.apk`

4. **Install on Device**
   - Connect Android device via USB
   - Enable USB Debugging
   - Click "Run" in Android Studio

#### Option B: Command Line

```bash
# Extract project
tar -xzf MedVaultApp.tar.gz
cd MedVaultApp

# Add google-services.json to app/ directory

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

### Step 3: First-Time Setup on Each Device

1. **Install the App**
   - Transfer APK to device
   - Install APK
   - Open MedVault

2. **Login with PIN**
   - Enter any full name
   - PIN: `258411`

3. **Connect Google Drive**
   - Prompt: "Connect to Google Drive for multi-user sync?"
   - Click "Yes"
   - Select Google account
   - Grant permissions:
     - ✓ See and download Drive files
     - ✓ Create new Drive files

4. **Wait for Sync**
   - Status indicator shows: "Google Drive connected"
   - App downloads existing data (if any)

5. **You're Ready!**
   - Add patient records
   - Auto-syncs to Google Drive
   - Other devices see changes automatically

## 📊 How Multi-User Sync Works

### Automatic Sync

- **On Save**: Uploads to Drive within 0.5 seconds
- **On Delete**: Syncs deletion immediately
- **Background**: Checks for updates every 5 minutes
- **On Login**: Downloads latest data

### Manual Sync

Click the **"☁️ Sync Now"** button anytime to:
1. Download latest data from Google Drive
2. Merge with local changes
3. Upload merged data back

### Conflict Resolution

If two users edit the **same record** simultaneously:
- App keeps the **newer version** based on timestamp
- No data is lost
- Last edit wins

### Sync Status Indicators

| Icon | Status | Meaning |
|------|--------|---------|
| 🟢 Green | Synced | Data is up-to-date |
| 🟡 Yellow | Syncing | Upload/download in progress |
| 🔴 Red | Offline | Not connected to Drive |

## 🔐 Security & Privacy

### Data Encryption
- ✅ HTTPS encryption during transfer
- ✅ Google Drive encryption at rest
- ✅ OAuth 2.0 authentication

### Access Control
1. **PIN Protection**: 6-digit PIN (default: 258411)
2. **Google Account**: Only authorized users can access
3. **Shared Folder**: Control who has access via Drive

### HIPAA Compliance (Optional)
For healthcare use:
- Use **Google Workspace** (paid)
- Enable **Business Associate Agreement (BAA)**
- Turn on **2-Step Verification**

## 📂 Google Drive Folder Structure

```
Google Drive/
└── MedVault/                    (Shared folder)
    └── medvault_db.json         (Database file)
```

### Sharing the Folder

**Method 1: Share via Google Drive Web**
1. Go to drive.google.com
2. Find "MedVault" folder
3. Right-click → Share
4. Add team members' emails
5. Permission: "Editor"
6. Click "Send"

**Method 2: Share via Link**
1. Right-click folder → "Get link"
2. Change to "Anyone with the link"
3. Role: "Editor"
4. Copy and share link

## 💾 Database Schema

### Stored Data Structure

```json
{
  "records": [
    {
      "id": 1707456789,
      "patientName": "John Doe",
      "patientId": "MED-87654321",
      "dateOfVisit": "2026-02-08",
      "diagnosis": "Hypertension - Blood pressure elevated",
      "medicines": "Lisinopril 10mg (once daily)",
      "timestamp": "2026-02-08T10:30:00.000Z"
    }
  ],
  "patientMap": [
    {
      "name": "John Doe",
      "id": "MED-87654321"
    }
  ],
  "lastSync": "2026-02-08T10:35:00.000Z"
}
```

## 🔧 Troubleshooting

### "Not connected to Google Drive"
1. Check internet connection
2. Go to Settings → Apps → MedVault → Permissions
3. Ensure "Storage" permission granted
4. Restart app and click "Sync Now"

### "Sync failed"
1. Check Google Drive storage (15GB free)
2. Verify internet connection
3. Re-authenticate: Logout → Login → Connect Drive

### "No data showing up"
1. Click "Sync Now" button
2. Wait 5-10 seconds
3. Check sync status indicator

### Two devices show different data
1. Both devices click "Sync Now"
2. Wait for green "Synced" indicator
3. Reload app

## 🎯 Usage Workflow

### Adding Records (Multi-User)

**User A (Doctor):**
1. Opens app
2. Clicks "+ Add Patient Record"
3. Enters patient details
4. Saves → Auto-uploads to Drive

**User B (Nurse):**
1. Opens app (sees sync indicator)
2. New record appears automatically
3. Or clicks "Sync Now" to force update

### Searching Records

1. Enter exact patient name or ID in search box
2. Click "Search"
3. View all records for that patient
4. Click "Show All" to see last 15 days

## 📱 Features Overview

| Feature | Description |
|---------|-------------|
| **Offline Mode** | Works without internet, syncs later |
| **Auto Patient IDs** | Format: MED-12345678 |
| **Last 15 Days View** | Default shows recent records |
| **Search** | By exact name or patient ID |
| **Multi-Device** | 2-100+ devices can share data |
| **Auto Backup** | Google Drive automatic backups |
| **Free Storage** | 15GB included with Google account |

## 🔄 Migration & Backup

### Export Data
1. Open Google Drive
2. Download `MedVault/medvault_db.json`
3. Save backup copy

### Import Data
1. Upload `medvault_db.json` to MedVault folder
2. Click "Sync Now" in app
3. Data appears automatically

## 📞 Support & Updates

### Changing the PIN
Edit `medical-history.html`:
```javascript
const CORRECT_PIN = '258411'; // Change to your PIN
```

### Adding More Users
1. Share Google Drive folder with their email
2. They install app
3. Login → Connect Google Drive
4. Done!

### Removing Users
1. Go to Google Drive
2. Right-click MedVault folder → Share
3. Remove user access

## ⚙️ Advanced Configuration

### Change Sync Interval
In `medical-history.html`:
```javascript
// Auto-sync every 5 minutes (300000ms)
setInterval(() => {
  if (GoogleDriveSync.isSignedIn()) {
    GoogleDriveSync.downloadData();
  }
}, 300000); // Change this value
```

### Disable Auto-Sync
Comment out the setInterval code above to use manual sync only.

## 📊 Comparison

| Feature | Before (Local Only) | Now (Google Drive) |
|---------|--------------------|--------------------|
| Multi-User | ❌ No | ✅ Yes |
| Data Sharing | ❌ Manual export | ✅ Automatic |
| Backup | ❌ Manual | ✅ Automatic |
| Sync | ❌ None | ✅ Real-time |
| Cost | Free | Free (15GB) |
| Internet | Not needed | Only for sync |

## 🎓 How It Works (Technical)

1. **Local Storage**: Each device has SQLite database
2. **Google Drive API**: Uploads JSON to shared folder
3. **Merge Logic**: Combines local + remote data
4. **Timestamp Check**: Keeps newer version on conflicts
5. **Background Service**: Syncs every 5 minutes

## 📋 Minimum Requirements

- **Android**: 7.0 (Nougat) or higher (API 24+)
- **Storage**: 50 MB app + data
- **RAM**: 1 GB minimum
- **Internet**: Only needed for syncing
- **Google Account**: Free Gmail account

## 🏥 Perfect For

- ✅ Small clinics (2-10 staff)
- ✅ Family doctors (shared with assistants)
- ✅ Home healthcare teams
- ✅ Medical students (shared study notes)
- ✅ Research teams (patient data collection)

## 🚀 Next Steps

1. Extract `MedVaultApp.tar.gz`
2. Follow "Step 1: Google Cloud Console Setup"
3. Build APK
4. Install on all devices
5. Share Google Drive folder
6. Start using!

---

**Built with ❤️ for better healthcare collaboration**

## 📄 License

Free to use for medical record management.

## 🆘 Need Help?

Check troubleshooting section or review the setup steps again.
Test sync