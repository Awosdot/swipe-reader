# Glide apps: install and rebuild guide

Three separate PDF readers for Telegram downloads. Each has its own app id, name and icon, so they install next to your older Swipe Reader, Swipe Shelf and Flow Reader and never replace them. Everything is offline. None of them has internet permission and there are no ads.

| App | What it is | Icon | Download link |
| --- | --- | --- | --- |
| Glide Reader | Simple list of PDFs, one at a time | violet | https://github.com/Awosdot/swipe-reader/releases/download/glide-reader/glide-reader.apk |
| Glide Shelf | Shelf that groups episodes by series, with reading stats | green | https://github.com/Awosdot/swipe-reader/releases/download/glide-shelf/glide-shelf.apk |
| Glide Flow | Shelf plus one continuous scroll across episodes, tap lower screen to scroll | magenta | https://github.com/Awosdot/flow-reader/releases/download/glide-flow/glide-flow.apk |

## 1. Install on the tablet

1. Open the download link of the app on the tablet in Chrome.
2. Tap the downloaded file `glide-....apk` (Chrome shows it at the bottom, or open the Files app, then Downloads).
3. If Android says "For your security, your phone is not allowed to install unknown apps from this source", tap Settings, switch on Allow from this source, press Back, then tap Install.
4. Open the app. When it asks for file access, tap Allow. If the shelf is empty, tap Choose folder and pick the folder that holds your PDFs. For Telegram X this is `Android/data/org.thunderdog.challegram/files/documents`.

## 2. What is new in all three readers

- Auto scroll: the round play button on the right edge. Tap it to start or pause. While it runs the screen stays awake. Holding a finger on the page pauses it. It stops by itself at the end.
- Speed: tap the play button once more to reveal the speed buttons. Minus is slower, plus is faster (6 levels). The buttons hide again after 4 seconds, and your speed is remembered.
- Side slider: drag the thin bar on the right edge up or down to move through the pages. A label shows where you are.
- Jump to end: the button with the down arrow, shown next to the speed buttons. In Glide Flow it goes to the end of the current episode, and pressing it again goes to the end of the next one.
- Tap the middle of the page in any reader to reveal the buttons.

## 3. Only in Glide Shelf and Glide Flow

- Reading time: tap Stats on the shelf. It shows an estimate of minutes read today, in total, and the daily average. The estimate counts the time between your touches and scrolls, and long pauses are capped, so treat it as a close guess.
- Mark a whole series as read: long press a series card, then choose Mark all as read. Choose Mark all as unread to undo it. The same button is also in the episode list under All episodes.
- Glide Flow only: tap the lower 30 percent of the screen to scroll down about one screen, tap the top 20 percent to scroll up.

## 4. Rebuild after a change (command line)

The code lives in two GitHub repos under Awosdot. The `swipe-reader` repo holds two apps in the folders `reader` and `shelf`. The `flow-reader` repo holds Glide Flow.

```
git clone https://github.com/Awosdot/swipe-reader.git
cd swipe-reader
```

Edit the files, for example `shelf/app/src/main/java/com/awoo/glideshelf/MainActivity.kt`, then:

```
git add .
git commit -m "Describe your change"
git push origin main
```

Open the repo on GitHub, click the Actions tab, and wait for a green tick (about 4 minutes). The release link in the table above then serves the new APK. Install it over the old one, since the signing key is kept between builds, so your reading progress stays.

For Glide Flow use `git clone https://github.com/Awosdot/flow-reader.git` and the same three commands.

## 5. Rebuild without the command line

1. Go to the repo page on GitHub and open the folder that holds the file you want to change.
2. Click the file, click the pencil icon, edit, then click Commit changes.
3. Click the Actions tab and wait for the green tick.

## 6. Start from nothing (new GitHub repo)

1. Go to https://github.com/new, type a repo name, keep Public, click Create repository.
2. Click uploading an existing file. Drag in the contents of one project folder (for example everything inside `flow`). Make sure the hidden `.github` folder goes in too. Click Commit changes.
3. The build starts by itself. When it is green, open the repo, click Releases on the right, and download the `.apk`.

## 7. If something goes wrong

- Build shows a red cross: open Actions, click the failed run, click the red step, and copy the error text. Send it to Claude.
- App says no PDFs found: tap Choose folder and pick the folder that holds the PDFs.
- Install says "App not installed": uninstall any earlier build of the same Glide app first, then install again.
