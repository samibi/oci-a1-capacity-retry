# Installing StayAwake on your watch

This guide walks you through getting StayAwake onto a Samsung Galaxy Watch 4
(or newer Wear OS watch) from scratch. You do **not** need a phone app or the
Play Store — the app installs straight onto the watch over Wi-Fi from your
computer. No programming knowledge needed; just follow the steps in order.

It takes about 15–20 minutes the first time.

---

## a. Get the APK

The watch app is built automatically whenever the code changes, by a robot
(GitHub Actions) that packages it into a file called `app-debug.apk`. You
need to download that file.

1. Open the project's GitHub page in a browser and click the **Actions** tab.
2. In the left sidebar, click the **"Build watch app"** workflow.
3. Click the most recent run that has a green checkmark (✓) next to it —
   that means it finished successfully.
4. Scroll down to the **Artifacts** section at the bottom of that run's page.
5. Click **"stayawake-apk"** to download it. It downloads as a `.zip` file.
6. Unzip that file (double-click it, or right-click → "Extract All"). Inside
   you'll find **`app-debug.apk`** — this is the file you'll install.

Keep note of where you unzipped it; you'll need to reach it from a terminal
later.

---

## b. Turn on developer mode on the watch

Your watch needs "developer options" turned on before it will accept an app
installed this way.

1. On the watch, open **Settings → About watch → Software info**.
2. Tap **"Software version"** (or the build number, depending on watch
   software) **5 times in a row**. You'll see a message saying developer
   mode is now on.
3. Go back to **Settings**, and you should now see a new **Developer
   options** menu. Open it.
4. Turn on **"ADB debugging"**.
5. Turn on **"Wireless debugging"**.

Two important notes:

- Your watch and your computer must be connected to the **same Wi-Fi
  network**. (Not a guest network that isolates devices from each other.)
- Inside the Wireless debugging screen, if you see a toggle for something
  like **"automatic Wi-Fi"** or "connect automatically," **turn it off** —
  it can interfere with pairing.

---

## c. Install `adb` on your computer

`adb` ("Android Debug Bridge") is the small tool that lets your computer
talk to the watch. It comes as part of "platform-tools."

1. Download platform-tools for your operating system from:
   https://developer.android.com/tools/releases/platform-tools
2. Unzip the downloaded file. You'll get a folder named `platform-tools`
   containing (among other things) an `adb` (or `adb.exe` on Windows) file.
3. Open a terminal (Command Prompt/PowerShell on Windows, Terminal on
   Mac/Linux) **inside that `platform-tools` folder**. On most systems you
   can right-click the folder and choose an "Open Terminal here" option, or
   open a terminal and `cd` into the folder's path.

You'll run all the `adb` commands below from this folder.

---

## d. Pair and install

Now connect your computer to the watch wirelessly and install the app.

1. On the watch, open **Settings → Developer options → Wireless debugging**,
   then tap **"Pair new device."** This shows you a 6-digit pairing code and
   an address that looks like `IP:PORT` (e.g. `192.168.1.42:37251`).
2. On your computer, in the terminal, run (replacing with your watch's
   actual address):

   ```
   ./adb pair 192.168.1.42:37251
   ```

   It will ask for the pairing code shown on the watch — type it in and
   press Enter.
3. Go back to the **main** Wireless debugging screen on the watch (not the
   "Pair new device" screen). It shows a **different** `IP:PORT` pair used
   for the actual connection. Use that one to connect:

   ```
   ./adb connect 192.168.1.42:41893
   ```
4. Check the connection worked:

   ```
   ./adb devices
   ```

   You should see your watch listed with the word `device` next to it (not
   `offline` or `unauthorized`).
5. Install the app (point this at wherever you unzipped `app-debug.apk`):

   ```
   ./adb install app-debug.apk
   ```

   After a few seconds you should see `Success`. StayAwake will now appear
   in your watch's app list.

> **Windows users:** drop the `./` and just run `adb pair ...`, `adb connect
> ...`, `adb devices`, `adb install ...`.

### Common problems

- **"Pairing code expired" or pairing just fails.** Close the "Pair new
  device" dialog on the watch and reopen it to get a fresh code, then try
  the `adb pair` command again right away.
- **Device shows as `offline` in `adb devices`.** On the watch, turn
  Wireless debugging off and back on, then run `adb connect IP:PORT` again
  with the current address shown on the watch (it can change).
- **Watch pops up "Allow debugging from this computer?"** Tap **"Always
  allow from this computer"** so you don't have to approve it every time.

---

## e. First run on the watch

1. Open the **StayAwake** app on the watch.
2. Tap **"Start monitoring."**
3. The watch will ask for permissions — grant them:
   - **Body sensors** — **required**. Without this, the app can't read your
     heart rate at all, so monitoring can't work.
   - **Activity recognition** and **Notifications** — recommended. These
     let the app show its status notification and use motion data properly.
4. After starting, if you see a chip labeled **"Allow background sensing,"**
   tap it and choose **"Allow all the time"** (or **"While using the
   app"** if that's what's offered). This matters because, without it,
   Android may stop reading your heart rate as soon as the watch screen
   turns off — which would defeat the whole point of an overnight or
   hands-off monitoring session.
5. If you plan to use **Schedule** windows (Settings → Schedule): if a chip
   labeled **"Allow exact alarms"** appears there, tap it and allow it. This
   lets scheduled sessions start and stop at the exact time you set instead
   of possibly being a few minutes late.

---

## f. Test it

1. Start a monitoring session and check the Home screen. Within about **30
   seconds** you should see a live number next to **"HR:"** — that confirms
   the heart-rate sensor is working.
2. To see the full alarm without waiting for a real night's sleep:
   - Go to **Settings** and set sensitivity to **High** (this reacts
     fastest).
   - Start a session and wait for the "Baseline" number to appear (this
     takes a few minutes as the app learns your resting heart rate).
   - Then sit **completely still and relax** for a few more minutes. If your
     heart rate settles down enough and your wrist stays still long enough,
     the alarm will fire — even though you're not actually asleep, this is
     a good way to confirm everything's wired up correctly.
   - Otherwise, simply let it run and it will catch you next time you
     actually doze off.
3. When the alarm fires: the screen turns bright red with **"WAKE UP!"** and
   the watch vibrates strongly in a **repeating loop**. It keeps vibrating
   until you tap **"I'm awake."** Tapping it stops the vibration and closes
   the alarm screen — but monitoring **keeps running** in the background
   afterward, so it can alert you again if you doze off a second time.

---

## g. Tuning guide

If the alarm behavior doesn't feel right, adjust **sensitivity** in
Settings:

- **Alarm fires too late, or not at all** → switch to **High**.
- **False alarms while you're relaxed but still awake** (e.g. reading
  quietly) → switch to **Low**.
- **Medium** is the balanced default.

What the numbers on the Home screen mean while a session is running:

- **HR** — your current heart rate reading, in beats per minute.
- **Baseline** — your learned "normal awake" resting heart rate, calculated
  from the first several minutes of the session.
- **Still for** — how many seconds your wrist has been essentially
  motionless.
- **Low HR for** — how many seconds your heart rate has been sitting
  meaningfully below your baseline.

The alarm only triggers once **both** "Still for" and "Low HR for" have been
building up long enough at the same time — stillness alone or a low reading
alone won't trigger it. Roughly, at each sensitivity level:

| Sensitivity | Heart rate must drop | ...and stay low for | Wrist must stay still for |
|---|---|---|---|
| High | ~8% below baseline | ~1 minute | ~1.5 minutes |
| Medium | ~10% below baseline | ~1.25 minutes | ~2 minutes |
| Low | ~13% below baseline | ~2 minutes | ~3 minutes |

---

## h. Updating later

You don't need a phone app for any of this — installation is watch-only.

To install a newer version later, repeat step (a) to get the latest
`app-debug.apk`, then from the same `platform-tools` terminal (reconnecting
with `adb connect IP:PORT` first if needed) run:

```
./adb install -r app-debug.apk
```

The `-r` flag replaces the existing install and keeps your saved settings.
