Non-paid sponsor:
--
![showcase](https://cdn.modrinth.com/data/cached_images/c492ccf06e399d32da83829fcdb591655fcea2d0_0.webp)
[My discord admin's resource pack!](https://packsmc.com/pack/partisans-pvp-overlay-c7dc10be)
--
## Record-able
---
---
---
A lightweight screen recording mod for Fabric that lets you capture, clip, and relive your gameplay. Supports Minecraft 1.20 through 26.1.2+ across three build variants.

what does it do?

Record-able captures video and audio directly from your Minecraft session. No external software needed, no performance tanks. Uses on-demand FFmpeg downloads instead of bundling binaries.

## Key features!
---
- Hardware acceleration support (NVENC, AMF, QuickSync)
- Software encoding with automatic ultrafast preset for smooth queue performance
- Performance Preset slider to dial in the speed-vs-quality tradeoff
- Four container formats (MP4, MKV, MOV, WebM)
- Customizable video quality and bitrate settings
- Audio capture from game with microsecond-precision A/V sync
- Microphone & Push-to-Talk (PTT) capture with a draggable mic overlay indicator
- Noise suppression on microphone input (FFmpeg afftdn adaptive denoiser)
- Separate audio tracks (game vs. mic) and audio chapter markers
- Simple Voice Chat integration (optional): captures proximity voice chat into recordings
- Max file size limits with auto-stop
- Customizable recording overlay (5 positions, scalable from 50% to 200%)
- Built-in watermark editor
- Redesigned settings UI with scrollable sections and storage management
- Built-in video collection browser with in-game video player
- Crash recovery: unfinished recordings from a previous crash are detected on next launch and can be remuxed into a clean, playable file
- Deferred Capture: record at low FPS during gameplay, render a smooth video offline afterward
- Mod compatibility checker: warns at startup if Flashback or ReplayMod are detected

### Auto-Clip System
---
Record-able can automatically save clips when something noteworthy happens:

- **Player death** - captures your final moments (and whatever got you)
- **Dimension change** - portal transitions, End entries, etc.
- **Achievements** - clips the moment you unlock an advancement
- **Kills** - detects melee attacks, projectiles (arrows, tridents), and specialized/modded weapons via a damage-scan fallback
- **Kill Montages** - a rolling buffer stitches together a pre-roll, the kill event, and a post-roll into one clip

Clips are organized in a dedicated "Clips" collection UI with subfolder organization, a draggable scrollbar, and a Protect/Unlock feature to keep your best moments safe.

### Deferred Capture
---
Deferred Render mode lets you capture frames at a low rate (5 to 30 FPS) to keep gameplay smooth, then render the final video offline with a configurable output FPS (30, 60, 120) and optional motion-compensated interpolation. Sessions that are not rendered immediately are saved to a Pending Renders queue so you can batch-process them later.

### Replay Buffer
---
Save the last 30 to 120 seconds of gameplay at any time. The buffer is disk-backed so even long-duration or high-resolution sessions will not exhaust memory.

## How does FFmpeg work?
---
First launch will prompt you to download FFmpeg. The mod handles this automatically from trusted mirrors (gyan.dev, johnvansickle.com, evermeet.cx) with SHA-256/MD5 integrity verification. One-time setup, then you're good to go.

## System requirements (because ofc)
---
Fabric Loader

Legacy (Minecraft 1.20 - 1.20.4): Java 17

Sandwich (Minecraft 1.20.5 - 1.21.11): Java 21

Modern (Minecraft 26.1 - 26.1.2+): Java 25

FFmpeg (downloaded on first use when prompted)

## Controls
---
Start/stop recording with a keybind (configurable)

Push-to-Talk keybind for mic capture (configurable)

Access settings, video collection, and clips from mod menu

Overlay shows recording status and duration in real-time

Additional bindable hotkeys: save replay buffer, add bookmark, open recording settings, open pending renders, toggle censor overlay, cancel recording, rename recording.

## Technical details for the power-users!
---
Uses OpenAL loopback for audio capture with microsecond-precision timestamp tracking to prevent A/V drift. Video encoder runs async to avoid frame drops. Mic input is captured on a separate track with its own gain control. Supports custom FFmpeg parameters if you want full control.

Video uploads use SNI pinning and trusted-chain fallback for reliable TLS connections across all platforms, including HTTPS-inspecting proxies and mobile JVMs.

## Compatibility
---
Record-able includes a compatibility bridge for coexistence with Replay Mod and Flashback. Auto-recording is suppressed during replay playback, and a replayYieldAudioDevice config flag manages OpenAL loopback device conflicts so the mods can share the audio pipeline.

A built-in mod compatibility checker warns you at startup if a known conflicting mod is detected, so you know before a recording starts.

That said, running multiple recording mods side by side can still cause visual or audio quirks, so your mileage may vary. (Shout-out to each respective mod's authors)

Also: high quality, high FPS, high resolution, and long recording times eat up a lot of Memory, CPU, and disk (so better be careful!)

## What's new in V1-0.10
---
- Crash recovery: unfinished recordings from crashes are detected on next launch and can be remuxed
- Deferred Capture mode: record at low FPS, render smooth video offline with optional interpolation
- Pending Renders queue: manage and batch-render deferred sessions
- In-game video player: watch recordings without leaving Minecraft
- Mod compatibility checker: startup warnings for Flashback, ReplayMod, and BetterShields
- Projectile kills (arrows, tridents) now correctly trigger auto-clips and kill montages
- Replay buffer is now disk-backed, eliminating out-of-memory crashes on long sessions
- New bindable hotkeys: "Open Recording Settings" and "Open Pending Renders"
- Fixed hard crash on MC 1.21.11 when a modded screen called applyBlur() twice in one frame
- Fixed mic start-sync jitter (524 to 923ms variance) caused by imprecise timing reference
- Fixed duplicate kill-clip triggers on projectile hits
- Fixed non-monotonic DTS warnings from some DirectShow mic drivers causing audio desync

# Disclaimer:
---
For complete clarity, this project was and is AI assisted.
