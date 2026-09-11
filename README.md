# ⚡ TaskbarDock

> A modern, gesture-driven dynamic taskbar dock and freeform multitasking manager for Android phones.

**TaskbarDock** brings desktop- and tablet-style floating taskbar multitasking to any standard Android smartphone without forcing system-wide DPI changes (600dp tablet mode) or breaking your phone's UI scaling.

---

## ✨ Features

### 🚀 Dynamic Taskbar Dock
- **Gesture Triggered:** Summon the dock via an upward swipe, down-and-up gesture, or hold on your navigation bar.
- **Zero Touch Interference:** Integrates with Android's native `AccessibilityService` to listen for gestures directly without blocking screen touches.
- **Customizable Pinned Apps:** Pin your favorite apps for instant access from anywhere.
- **Persistent / Transient Modes:** Choose between an auto-hiding dock or an always-visible taskbar.

### 🪟 True Freeform & Multitasking
- **Shizuku Shell Powered:** Launches apps into true Android Freeform (`WINDOWING_MODE_FREEFORM`) without root.
- **Direct Launch Shortcut:** Long-press any dock icon to launch directly into a floating window, or tap for standard fullscreen.
- **Split Screen Support:** Optional split-screen launcher menu toggle.

### 🫧 Minimized Floating Instance Bubbles
- **Never Lose Your Floating Window:** When a background fullscreen app covers an active floating window, a floating icon bubble instantly hovers in the foreground.
- **Multi-App Bubble Stack:** Tracks multiple floating apps simultaneously in a vertical floating pill.
- **Free 2D Dragging:** Move the bubble anywhere on the screen — no forced snapping.
- **Instant Session Restoration:** Tapping a bubble brings the *exact previous running instance* back to the front without reloading or duplicating tasks (`FLAG_ACTIVITY_REORDER_TO_FRONT`).
- **Double-Tap Dismissal:** Double-tap any bubble to quickly close that instance.

### 🕒 Recents in All Applications Drawer
- Built-in **Recent Apps** strip at the top of the All Applications drawer.
- Open recent apps in fullscreen with a tap, or directly into a floating window with a long-press.

### 🔋 Battery Optimization Exemption
- Built-in prompt to ignore battery optimizations so OEM task killers (Motorola, Samsung, Xiaomi, etc.) don't terminate background overlay services.

---

## 🛠️ Architecture & Tech Stack

- **Language:** Kotlin
- **UI:** ViewBinding, Material 3 Components, Android WindowManager (`TYPE_APPLICATION_OVERLAY`)
- **Shell & Multi-Window:** [Shizuku API](https://shizuku.rikka.app/) (`am start --windowingMode 5`, `cmd activity task`)
- **Gesture Detection:** `AccessibilityService` (`typeAllMask`, `flagRetrieveInteractiveWindows`) + Optional overlay touch sensor fallback
- **Concurrency:** Kotlin Coroutines (`SupervisorJob`, `Dispatchers.IO`)

---

## 📋 Prerequisites & Setup

1. **Overlay Permission:** Grant `Display over other apps` (`SYSTEM_ALERT_WINDOW`).
2. **Accessibility Service:** Enable `Taskbar Dynamic Gesture Service` in Android Accessibility Settings for native gesture detection.
3. **Shizuku (Recommended for Freeform):**
   - Install and start [Shizuku](https://shizuku.rikka.app/) (via wireless debugging or root).
   - Grant permission when prompted in the app.
4. **Developer Options (If using Freeform on older AOSP builds):**
   - Enable **"Enable freeform windows"**
   - Enable **"Force activities to be resizable"**

---

## 📦 Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/TaskbarDock.git
   ```
2. Open the project in **Android Studio** (Hedgehog / Iguana / Ladybug or newer).
3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
