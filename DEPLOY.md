# Swipe Reader: get the APK onto your tablet

You do not need Android Studio. GitHub builds the APK for you, for free, and gives you a download link.

What you need: a free GitHub account, and `git` on your Mac. Check git by opening Terminal and running:

```
git --version
```

If it is missing, macOS offers to install it. Accept, wait, then run the command again.

## Step 1: Unzip the project

In Terminal:

```
cd ~/Downloads
unzip swipe-reader.zip
cd swipe-reader
ls -a
```

You should see `app`, `.github`, `debug.keystore`, `build.gradle.kts` and more. The `.github` folder is hidden in Finder, which is why we use Terminal and not drag and drop.

## Step 2: Create an empty repo on GitHub

1. Go to https://github.com/new while signed in as Awosdot.
2. Repository name: `swipe-reader`
3. Choose **Public** (needed so the tablet can download the APK without signing in).
4. Leave every checkbox empty: no README, no .gitignore, no license.
5. Click **Create repository**.

The project holds no secrets. The signing key in it is a throwaway key for this one app.

## Step 3: Push the project

Still in the `swipe-reader` folder in Terminal:

```
git init -b main
git add .
git commit -m "Swipe Reader first version"
git remote add origin https://github.com/Awosdot/swipe-reader.git
git push -u origin main
```

If Terminal asks for a password, GitHub no longer accepts your account password there. Easier route: run `gh auth login` (if `gh` is installed), choose GitHub.com, HTTPS, browser login, then run the last command again.

## Step 4: Wait for the build

1. Open https://github.com/Awosdot/swipe-reader/actions
2. Click the newest run named **Build APK**.
3. Wait for a green tick. The first build takes about 5 to 8 minutes.

If it shows a red cross, click the run, open the failed step, copy the last 30 lines of the error and paste them to Claude. This first build is also the first time the code is compiled, so expect that a fix may be needed.

## Step 5: Download on the tablet

On the Lenovo tablet, open Chrome and go to:

```
https://github.com/Awosdot/swipe-reader/releases/download/latest/swipe-reader.apk
```

Tap the downloaded file when Chrome offers it.

## Step 6: Install

1. If Android says "For your security, your tablet is not allowed to install unknown apps from this source", tap **Settings** and switch on **Allow from this source** for Chrome. Go back.
2. Tap **Install**.
3. If Play Protect warns about an unknown app, tap **More details** then **Install anyway**.
4. Tap **Open**.

## Step 7: First run

1. Tap **Allow** on the storage permission. This happens once.
2. The app opens straight onto your newest unread PDF from `Telegram/Telegram Files` (it also checks `Telegram/Telegram Documents`).
3. Swipe up to read. Swipe sideways to browse other PDFs.
4. At the last page a "Up next" card appears. Swipe up on it to open the next unread PDF.

## Daily use

Download a PDF in Telegram as normal, never tap Save to Files, then open Swipe Reader. It is already there. If the app is open while the download finishes, the new PDF appears by itself within a few seconds.

## Updating the app later

After any code change:

```
cd ~/Downloads/swipe-reader
git add .
git commit -m "describe the change"
git push
```

Wait for the green tick, then download and install the APK again. It installs over the old version and keeps your reading progress, because every build uses the same signing key.

## If something does not work

- **PDF not appearing:** open the Files app and confirm the PDF is inside `Telegram/Telegram Files`. If it is in a different folder, tell Claude the exact path.
- **Permission screen keeps showing:** tap **Allow access** again. If nothing pops up, it opens app settings: go to Permissions, then enable Storage.
- **Release link gives 404:** the build has not finished or failed. Check the Actions tab.
