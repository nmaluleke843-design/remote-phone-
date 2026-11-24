# RemotePhone

Control your own Android phone from a browser on your computer (or another phone).

```
Android app  ──(screen frames)──►  relay server  ──►  browser viewer
             ◄──(taps/swipes/keys)─              ◄──
```

- **android/**: the app (Kotlin). Streams the screen with MediaProjection and performs taps, swipes, Back/Home/Recents and text entry through an Accessibility Service.
- **server/**: Node.js relay plus the web controller page.

## Safety by design

- Nothing is shared until you tap **Start sharing** and approve Android's own screen-capture prompt.
- Control only works after you enable the accessibility service yourself in Settings.
- A persistent notification with a **Stop** button shows the whole time.
- Each session uses a fresh random 8-digit code shown only on the phone; wrong guesses are rate limited.
- Frames are only sent while a viewer is connected.

## 1. Run the server

```bash
cd server
npm install
npm start          # http://localhost:8080
```

For use outside your home network, host it somewhere with HTTPS (Render, Railway, Fly.io, or a VPS with Caddy). The phone then uses `wss://your-domain`. Plain `ws://` is only for testing on your own Wi-Fi, for example `ws://192.168.1.20:8080`.

## 2. Build the app

1. Open the `android/` folder in Android Studio (it creates the Gradle wrapper for you).
2. Run it on your phone (USB debugging) or **Build > Build APK(s)** and install the APK.

## 3. Use it

1. On the phone: enter the server URL, tap **Enable control (Accessibility)** and turn on *RemotePhone control*.
   - On Android 13+, if it is greyed out: App info > ⋮ > *Allow restricted settings*, then try again.
2. Tap **Start sharing**, approve the prompt. An 8-digit code appears.
3. On the computer open the server URL, type the code, click **Connect**.
4. Click = tap, click-and-drag = swipe, mouse wheel = scroll, side buttons for Back/Home/Recents, text box for typing into the focused field.

## Limits

- Android only. iOS does not allow remote touch control by third-party apps.
- Text entry needs a focused text field; some secure screens (banking, password fields, DRM video) show black in screen capture by design.
- Taps are single-finger; pinch and multi-touch are not implemented.
- Android stops screen capture if the app is force-closed; start sharing again.

## Ideas for next steps

Audio forwarding, file transfer, clipboard sync, WebRTC for lower latency (replace the relay with peer-to-peer), a login so only your account can start a session.
