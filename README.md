# Plexus

Plex client for Google TV and Android TV, built with Kotlin and Jetpack Compose for TV.

Plexus plays your Plex library with a fast, remote-friendly interface. It can also connect to Radarr and Sonarr, so you can find new movies and shows and add them from the couch.

---

## Features

### Browsing
- **Home**: Continue Watching, On Deck, Recently Added and your other Plex hubs. You can reorder rows, hide them, or change each row's card style.
- **Libraries**: the Recommended, Browse, Collections and Categories tabs for each library. Rows are customisable here too.
- **Title pages**:
  - Movies get a backdrop, logo, ratings, cast, reviews, collections and related titles.
  - Shows get an overview page with a season poster row.
  - The episode page has season tabs and a sliding episode row.
- **Cast**: select an actor to see everything they're in on your server.
- **Search**: an on-screen keyboard, with results updating as you type. Recent searches are kept.
- **Hold OK** on any card for more options:
  - Play, mark watched or unwatched
  - Go to Show
  - Remove from Continue Watching
  - Update metadata (owner only)
  - Customise the row

### Playback
- Media3/ExoPlayer: direct play where possible, otherwise Plex transcoding. Audio passthrough (AC3, E-AC3, DTS, TrueHD) where your TV supports it.
- **Subtitles**:
  - Embedded or external tracks, with size, colour and background options.
  - Defaults include "Always if embedded".
  - You can pick a track on the show page before playing, and your choice is remembered per show.
- **Player menu** (press Down): quality, audio track, subtitles, speed, and skip intro/credits settings.
- **Skip Intro and Skip Credits**: as a button, or automatically.
- **Up Next**: shows when the credits start, with a countdown.
- **Seek preview thumbnails**: off by default. They use Plex's video preview thumbnails.
- **Voice and remote control**: "Hey Google, pause" and media keys work through Android's media session.
- **Back**: hides the player bar first, then leaves the video.

### Discover (owner only by default)
- **Sources**:
  - **Plex**: uses your Plex account. Nothing to set up.
  - **TMDB**: needs a free key, and adds genre, "Coming soon" and "Out digitally this week" rows.
- **Filters and search**: All, Movies and TV Shows (applied as soon as you highlight them), plus a search.
- **See more**: at the end of each row, opens it as a full grid.
- **Card tags**:
  - Titles you already own show **In library** and open straight in Plex.
  - Titles Radarr or Sonarr already track show **Monitored**.
- **Release corner tag**: green means out digitally, amber means in cinemas, blue means coming soon. It uses your TV's region.
- **Downloading row**: Radarr and Sonarr's download queues, with progress and time left.
- **More like this**: a row at the bottom of movie and show pages.

### Radarr and Sonarr (optional, owner only by default)
- **Adding titles**: from Discover, from Search, or from "More like this". Shows let you choose all seasons, future episodes, the first season, or the latest season.
- **Checked adds**: "Added" only appears once Radarr or Sonarr confirms it. A search starts straight away. **Search again** re-runs one later.
- **Episodes you don't have yet**: shown at the end of the latest season's row.
  - Upcoming: "Episode 6 · Coming this Wednesday".
  - Aired but missing: tagged Downloading %, Queued, Not downloaded, Not monitored, or Download problem.
  - Search again, or Monitor and search, are one press away.
- **Sharing**: Settings → Server → "Let everyone on this TV use Discover and downloads" opens all of this to every profile.

### Profiles and accounts
- Plex Home profiles, with PIN support. You can add and remove managed profiles, and hide profiles from the picker.
- An owner's or admin's tools (update metadata, preview thumbnail settings, Discover and downloads) only appear for the owner.

### Personalisation
- **Look**: themes; five side bar styles with a live preview; text size; card size (80% by default); and app icon variants (dark, light, transparent, with or without text).
- **Minimal look**: hide row titles, episode text, watched badges and progress bars.
- **Spoilers**: blur unwatched episode images and hide their descriptions.
- **Side bar**: you can hide it (Left still opens it), and choose whether it appears on title pages.
- **Other**: navigation sounds, and a splash screen on cold start only.

### Updates
- Plexus checks this repository's GitHub Releases for a newer version and installs it in-app, after you confirm. See [Releasing an update](#releasing-an-update).

---

## Requirements

- A Google TV or Android TV device running Android 10 or newer. Developed on a Google TV Streamer.
- A Plex Media Server and a Plex account.
- To build: Android Studio (recent stable), with JDK 17 or newer bundled.
- Optional:
  - Radarr v3+ and Sonarr v3/v4 on your network.
  - A free TMDB API key.

---

## Installing on a TV

### From Android Studio
1. On the TV, go to **Settings → System → About** and press **Android TV OS build** seven times to turn on developer options.
2. Turn on **USB debugging** or **Wireless debugging** under Developer options.
3. Select the TV in Android Studio's device menu and press **Run**.

### From an APK
1. Build a signed APK: **Build → Generate Signed App Bundle / APK → APK → release**. Only the `.apk` file is needed.
2. Then either:
   - install over the network with `adb connect <tv-ip>` and then `adb install -r plexus.apk`, or
   - put the APK on the TV with an app such as *Downloader* or *Send Files to TV*, and allow that app to install unknown apps.

---

## First-time setup

1. **Sign in**: open Plexus and go to **plex.tv/link** on your phone or computer. Enter the code shown on the TV.
2. **Choose a profile**: enter a PIN if the profile has one.
3. **Choose a server**: Plexus picks your server automatically if you only have one. Switch servers anytime in Settings → Server.

### Optional: Discover and recommendations
Go to **Settings → Server → Discover and recommendations → Recommendations from**:
- **Plex**: nothing to set up.
- **TMDB**:
  1. Create a free account at themoviedb.org.
  2. Go to Settings → API and request a key. The application URL can be any site you own.
  3. Enter either the API key or the Read Access Token in Plexus.

### Optional: Radarr and Sonarr
In **Settings → Server → Adding movies and shows**, for each app:
1. **IP address**: filled in automatically if Radarr or Sonarr runs on the same PC as Plex.
2. **Port**: 7878 for Radarr and 8989 for Sonarr unless you changed it.
3. **API key**: in Radarr or Sonarr, under **Settings → General → Security**.
4. **Quality and folder**: once the status shows *Connected*, choose the quality profile and root folder for new titles.

Tip: the **Google TV** app on your phone has a remote with a keyboard, and you can paste API keys into Plexus from it.

Radarr and Sonarr are reached over your home network with plain `http://`. The app allows cleartext traffic for this.

---

## Troubleshooting

- **The release build crashes on start with an `IllegalAccessError`**: don't turn on `packageScope` in the `optimization { }` block.
- **"Activity class … does not exist" when running**: set the run configuration to launch `MainActivity` (see [Building](#building)).
- **Video fails to play on the emulator**: the emulator often can't decode real video formats. Test on a real TV.
- **Radarr or Sonarr says "Can't connect"**:
  - Check the IP address and port.
  - Check the PC's firewall allows the port.
  - If Radarr/Sonarr uses a URL base (for example `/radarr`), include it in the address.
- **A title was added but nothing downloads**: that's on the Radarr/Sonarr side. Check its indexers (Settings → Indexers, System → Status) and use **Interactive Search** on the title.
- **An update won't install ("signed with a different key")**: that copy was installed with another key, such as Android Studio's debug key. Uninstall Plexus once and install the new version.
- **Discover on the Plex source shows an error**: Plex's discovery service isn't officially documented. Filter Logcat by `PlexDiscover` to see what it returned, or switch to TMDB.

Logcat tags used by the app: `PlexDiscover` (Plex discovery) and `Arr` (Radarr and Sonarr requests).

---

*Plexus isn't affiliated with or endorsed by Plex, Inc. This product uses the TMDB API but is not endorsed or certified by TMDB.*
