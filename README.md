# ⚡ TaskbarDock

> A modern, gesture-driven dynamic taskbar dock and freeform multitasking manager for Android phones.

**TaskbarDock** brings desktop- and tablet-style floating taskbar multitasking to any standard Android smartphone without forcing system-wide DPI changes (600dp tablet mode) or breaking your phone's UI scaling.

---

## ✨ Features

### 🚀 Dynamic Taskbar Dock
- **Gesture Triggered:** Summon the dock seamlessly from any app via a quick swipe or hold on your navigation bar.
- **Customizable Pinned Apps:** Pin your favorite applications for instant, one-tap access from anywhere.
- **Auto-Hide & Persistent Modes:** Choose between an auto-hiding transient dock or an always-visible taskbar.
- **Optimized Sensor Zone:** Compact 30 dp bottom sensor zone engineered to trigger reliably without interfering with system navigation.

### 🪟 True Freeform Multitasking
- **Shizuku Shell Powered:** Launches apps into true Android Freeform (`WINDOWING_MODE_FREEFORM`) without root.
- **Direct Floating Shortcut:** Tap an app to launch in standard fullscreen; long-press any app icon in the dock or drawer to open it directly in a **Floating Window** (zero popup menus required).

### 🫧 Minimized Floating Instance Bubbles
- **Never Lose Your Floating Window:** When a background fullscreen app takes focus, a floating icon bubble instantly hovers in the foreground.
- **Multi-App Bubble Stack:** Tracks multiple floating apps simultaneously in a vertical floating stack.
- **Free 2D Dragging:** Move the bubble anywhere on your screen — release it anywhere with no forced edge-snapping.
- **Instant Session Restoration:** Tapping a bubble brings the *exact previous running instance* back to the front without reloading or duplicating tasks (`FLAG_ACTIVITY_REORDER_TO_FRONT`).
- **Double-Tap Dismissal:** Double-tap any bubble to quickly dismiss and close that instance.

### 🕒 Recents in All Applications Drawer
- Built-in **Recent Apps** section at the top of the All Applications drawer.
- Tap a recent app to resume it in fullscreen, or long-press to open it directly in a floating window.

### 🔋 Always Run in Background
- Built-in prompt to ignore battery optimizations so OEM task killers (Motorola, Samsung, Xiaomi, etc.) never kill background dock services.

---

## 🛠️ Architecture & Tech Stack

- **Language:** Kotlin
- **UI:** ViewBinding, Material 3 Components, Android WindowManager (`TYPE_APPLICATION_OVERLAY`)
- **Shell & Multi-Window:** [Shizuku API](https://shizuku.rikka.app/) (`am start --windowingMode 5`, `cmd activity task`)
- **Touch & Drag Physics:** Custom `OnTouchListener` with touch slop discrimination and 2D free-drag coordinates
- **Concurrency:** Kotlin Coroutines (`SupervisorJob`, `Dispatchers.IO`)

---

## 📋 Prerequisites & Setup

1. **Overlay Permission:** Grant `Display over other apps` (`SYSTEM_ALERT_WINDOW`).
2. **Shizuku (Recommended for Freeform):**
   - Install and start [Shizuku](https://shizuku.rikka.app/) (via wireless debugging or root).
   - Grant permission when prompted in the app.
3. **Unrestricted Background Activity:**
   - Allow the app to ignore battery optimizations so the service stays active in the background.
4. **Developer Options (Recommended for Freeform):**
   - Enable **"Enable freeform windows"**
   - Enable **"Force activities to be resizable"**

---

## 📦 Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/TaskbarDock.git
