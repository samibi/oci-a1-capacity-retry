# StayAwake

StayAwake is a watch app for Samsung Galaxy Watch 4 and newer (any Wear OS 3+
watch with a heart-rate sensor) that tries to catch you falling asleep when
you don't want to — for example while working a night shift, studying, or
keeping watch — and wakes you up with a loud vibrating alarm.

It is **not** a medical device and it is **not** a sleep tracker. It is a
simple safety-net alarm.

## How detection works (plain language)

While a monitoring session is running, the watch keeps an eye on two things:

1. **Your heart rate.** The app watches your heart rate for the first
   several minutes of a session to learn your normal "awake" resting rate
   (the baseline). After that, if your heart rate drops noticeably below
   that baseline and *stays* down for a minute or two, that's one sign you
   might be dozing off.
2. **Wrist movement.** Using the watch's motion sensor, the app checks
   whether your wrist has been essentially still for a couple of minutes.
   People who are awake fidget, adjust, and move; people who fall asleep
   generally don't.

The alarm only fires when **both** signs are true at once — heart rate has
been low for a while *and* your wrist has been still for a while. Either
signal alone is not enough, which cuts down on false alarms from just sitting
calmly (like reading) or just briefly setting your arm down.

As a backstop, the app also listens to Android's built-in Health Services
"user asleep" signal (a passive sleep-detection signal the watch's own
sensors provide) and will treat that as an alarm trigger too, in case it
notices something the heart-rate/stillness check misses.

You choose how twitchy this detector is with a **sensitivity** setting — see
below.

## Features

- **Manual sessions** — tap "Start monitoring" any time and tap "Stop" to
  end it.
- **Scheduled windows** — set up recurring time windows (e.g. "Mon–Fri,
  02:00–04:00") and the app starts and stops monitoring automatically, even
  if the app isn't open, using Android's alarm scheduler.
- **Sensitivity presets** — Low, Medium, or High, trading off "catches drowsiness
  earlier" against "fewer false alarms while calmly awake."
- **Live readout on the watch face** — while a session is running, the Home
  screen shows your current heart rate, your learned baseline, how many
  seconds your wrist has been still, and how many seconds your heart rate
  has been low — so you can see exactly what the detector is seeing.
- **Full-screen alarm** — when triggered, the screen turns on (even over the
  lock screen), the watch vibrates strongly, and a red "WAKE UP!" screen is
  shown. The vibration repeats in a loop until you tap **"I'm awake"**.
  Dismissing the alarm does **not** stop monitoring — the session keeps
  running afterward so it can catch you again if you doze off a second time.

## Battery expectation

Expect roughly **2–4% battery per hour** of active monitoring, since the
watch keeps the heart-rate sensor and motion sensor running continuously
during a session. Plan charging around that if you're using it for long
overnight or multi-hour sessions.

## Project structure

The app is a single Wear OS module written in Kotlin with Jetpack Compose
for the UI. Code is organized by responsibility: `monitor/` holds the
sleep-detection logic and the foreground service that keeps it running,
`schedule/` holds the scheduled-window logic (alarms, boot restore), `data/`
holds saved settings, `ui/` holds the on-watch screens, and `alarm/` holds
the full-screen wake-up alarm activity.

```
watch-app/
├── app/src/main/java/com/samibi/stayawake/
│   ├── StayAwakeApp.kt        # app-wide setup (notification channels)
│   ├── alarm/                 # full-screen "WAKE UP!" alarm activity
│   ├── data/                  # saved settings (sensitivity, schedule windows)
│   ├── monitor/                # sleep-onset detection + background service
│   ├── schedule/               # scheduled time windows, alarms, boot restore
│   └── ui/                     # Home, Settings, Schedule screens
└── app/src/main/AndroidManifest.xml
```

## Installing it on your watch

See **[INSTALL.md](./INSTALL.md)** for a complete, beginner-friendly,
step-by-step guide to getting the app onto your watch (no phone app or
Play Store needed).

## Safety disclaimer

**StayAwake is a best-effort aid, not a guarantee.** It uses simple sensor
heuristics (heart rate and wrist stillness) that can miss you falling
asleep, fire later than you'd like, or occasionally trigger falsely. Sensor
accuracy also varies by watch fit, skin tone, movement, and individual
physiology.

**Never rely on this app while driving, operating machinery, or in any
other situation where falling asleep could put you or others in danger.**
If you feel drowsy, pull over and rest, or hand off the task to someone
else — do not treat this app as a substitute for that judgment. It is meant
for low-stakes situations (like not wanting to nap during a study session),
not for safety-critical ones.
