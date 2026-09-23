# Privacy Policy for AndroidAirPlay

**Last updated**: September 23, 2026

This Privacy Policy describes how **AndroidAirPlay** ("the Application", "we", "us", or "our") handles user information.

---

### 1. Overview and Core Principle

AndroidAirPlay is designed with privacy as a foundational principle. **We do not collect, store, sell, transmit, or share any personal information, voice recordings, or usage analytics.** 

All audio streaming functionality operates strictly on your local area network (LAN / Wi-Fi) directly between your Android device and your selected AirPlay-compatible receiver (e.g. Apple HomePod).

---

### 2. Information We Do NOT Collect

- **No Personal Identifiable Information (PII)**: We do not collect names, email addresses, phone numbers, contacts, device identifiers (IMEI, Android ID), or locations.
- **No Audio Recordings**: The application does NOT record sounds from your microphone, and never records, stores, or uploads any captured audio to any remote server or cloud infrastructure.
- **No Analytics / Telemetry**: The application contains no tracking SDKs, advertising networks, or third-party behavioral analytics tools.
- **No Account Required**: The application does not require any user registration or authentication.

---

### 3. Permissions and How They Are Used

AndroidAirPlay requests certain permissions strictly for its core functionality:

1. **`android.permission.RECORD_AUDIO`**:
   - **Purpose**: Required by the Android OS `AudioPlaybackCapture` API (Android 10+) to allow the app to capture media audio playing from other applications on your device (e.g., music players or video apps).
   - **Usage**: Audio frames are processed entirely in device volatile memory (RAM), encoded into local RTP audio packets, and sent over your local Wi-Fi directly to your speaker. No audio is ever written to persistent storage or uploaded to the internet.
   - **Microphone**: The application does NOT use or listen to the device microphone.

2. **`android.permission.FOREGROUND_SERVICE` (Media Projection & Playback)**:
   - **Purpose**: Allows continuous background audio streaming while your screen is locked or while multitasking in other apps.
   - **Usage**: Shows an active playback control notification so you can see the connection status and stop streaming at any time.

3. **`android.permission.ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`**:
   - **Purpose**: Used to verify Wi-Fi connectivity and bind streaming sockets to the local Wi-Fi interface (allowing local playback even if a VPN is running).

4. **`android.permission.CHANGE_WIFI_MULTICAST_STATE`**:
   - **Purpose**: Enables Multicast DNS (mDNS) discovery to automatically locate HomePod and AirPlay receivers advertising on your local network.

5. **`android.permission.POST_NOTIFICATIONS`**:
   - **Purpose**: Required on Android 13+ to display the foreground playback control notification with the "Stop" button.

---

### 4. Local Data Storage

The application stores minimal user preferences locally on your device via Android `SharedPreferences`:
- Preferred audio latency buffer setting (e.g., 250ms, 500ms, 1000ms).
- Speaker muting preference (whether to mute phone speakers during streaming).
- List of discovered speaker names and local IP addresses (for instant reconnect without scanning).

This data never leaves your device and is erased automatically if you clear the app data or uninstall the application.

---

### 5. Third-Party Services

AndroidAirPlay does not integrate with any third-party tracking, advertising, or cloud analytics services.

---

### 6. Children's Privacy

The application does not target or knowingly collect any information from children under the age of 13.

---

### 7. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Any changes will be reflected with a revised "Last updated" date in this document.

---

### 8. Contact

If you have any questions or feedback regarding this Privacy Policy, please contact the developer via:
- **Email**: `admin@isklv.ru`
- **GitHub**: [https://github.com/isklv/homepod-airplay](https://github.com/isklv/homepod-airplay)
