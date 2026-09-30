# Contributing to Dejavu

Thank you for helping make Dejavu better. This guide explains how to report problems, suggest ideas, contribute
designs and send code.

## Issues

[Open an issue](https://github.com/by-architect/Dejavu/issues/new/choose) and choose a template. Each template starts
the title with a prefix and adds a label, so issues are easy to sort:

| Template                | Title starts with   | Label         |
| ----------------------- | ------------------- | ------------- |
| Bug report              | `[BUG]`             | `bug`         |
| Feature request         | `[FEATURE REQUEST]` | `enhancement` |
| Design, icons or themes | `[DESIGN]`          | `design`      |
| Sponsorship             | `[SPONSOR]`         | `sponsorship` |

Keep the prefix and write a short, clear title after it, for example `[BUG] Pinned tabs disappear after a restart` or
`[FEATURE REQUEST] Reorder essentials by dragging`.

Before you open an issue, search the existing ones. If yours is already there, add a thumbs up reaction to it instead
of opening a new one; reactions help us see what matters most.

- Security problems must not go into public issues. See [SECURITY.md](SECURITY.md).
- If a website is broken in Firefox for Android too, the problem is in Firefox's engine. Please report it to Mozilla
  at [webcompat.com](https://webcompat.com/).

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change, one topic per pull request.
3. Build Dejavu and try your change on a phone or an emulator.
4. Open a pull request against `main`, fill in the template, and link the issue it solves with `Closes #123`.

Start the title of the pull request with the kind of change:

| Title starts with | Use it for                      |
| ----------------- | ------------------------------- |
| `[FIX]`           | A bug fix                       |
| `[FEATURE]`       | A new feature or an improvement |
| `[DESIGN]`        | Design, icons or themes         |
| `[TRANSLATION]`   | New or corrected translations   |
| `[DOCS]`          | Documentation                   |

For a bigger change, open an issue first, so we can agree on the idea before you spend time on it.

## Design, icon packs and themes

Design help is especially welcome, and you do not need to write code for it. Open a `[DESIGN]` issue with images,
SVG files or a link to your mockups, or send a pull request if you know your way around the files below.

- **Icons and icon packs:** Dejavu draws its icons in code, on a 24 unit grid with round 1.75 unit strokes, in
  `mobile/android/fenix/tools/dejavu_icons/icons.py`. `generate_icons.py` in the same folder turns them into the
  Android icons that replace Firefox's icons of the same names.
- **App icon and store graphics:** `generate_launcher.py` draws the launcher icon, the splash screen logo and the logo
  inside the app. `generate_store_assets.py` draws the Google Play icon and feature graphic.
- **Container icons:** the `dejavu_container_*.xml` files in `mobile/android/fenix/app/src/main/res/drawable`.
- **Colors and themes:** the app's colors are in `mobile/android/fenix/app/src/main/res/values/dejavu_colors.xml`,
  and the gradients offered for workspaces are in
  `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/dejavu/home/WorkspaceLook.kt`.
- **Screens and flows:** mockups of a screen or of a better way to do something are a great start for a discussion.

Only share artwork that you made or have the right to share.

## Translations

Firefox's own texts are translated by Mozilla's community. Dejavu's own texts are in
`mobile/android/fenix/app/src/main/res/values/dejavu_strings.xml` in English, and in the `dejavu_strings.xml` file of
each `values-<language>` folder next to it. To correct a translation, edit the file of your language and send a pull
request titled `[TRANSLATION] ...`.

## Build Dejavu

Dejavu is a fork of Mozilla's Firefox repository, so the repository is large. You need Linux or macOS, and a phone or
emulator with Android 8.0 or newer.

1. Clone your fork. A shallow clone is much faster:

   ```sh
   git clone --depth 1 https://github.com/<your-name>/Dejavu.git
   cd Dejavu
   ```

2. Install the build tools. When asked, choose **GeckoView/Firefox for Android Artifact Mode**, which downloads a
   prebuilt browser engine so that you only build the app:

   ```sh
   ./mach bootstrap
   ```

3. Create a file named `mozconfig` at the root of the repository:

   ```sh
   ac_add_options --enable-project=mobile/android
   ac_add_options --target=aarch64-linux-android
   ac_add_options --enable-artifact-builds
   mk_add_options MOZ_OBJDIR=./objdir-frontend
   ```

   For an x86_64 emulator, use `--target=x86_64-linux-android`.

4. Prepare the build once, which downloads the engine, then build the app:

   ```sh
   ./mach build
   ./mach gradle :fenix:assembleDebug
   ```

5. Install it on a connected phone:

   ```sh
   adb install -r objdir-frontend/gradle/build/mobile/android/fenix/app/outputs/apk/debug/fenix-arm64-v8a-debug.apk
   ```

The debug build is a separate app from the one on Google Play, so both can be installed at once. Mozilla's
[Firefox for Android documentation](https://firefox-source-docs.mozilla.org/mobile/android/fenix.html) has more
details about the build.

Run Dejavu's unit tests with:

```sh
./mach gradle :fenix:testDebugUnitTest --tests 'org.mozilla.fenix.dejavu.*'
```

## Code

- Dejavu's code is in the `org.mozilla.fenix.dejavu` package, in
  `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/dejavu`, and its tests are in the same package under
  `src/test`. Its resources are the `dejavu_` files in `mobile/android/fenix/app/src/main/res`.
- Keep changes to Firefox's own files small, and mark them with a `Dejavu:` comment. This keeps updating Dejavu to
  newer Firefox versions easy.
- Kotlin code follows Firefox's style: it is formatted with ktfmt, with 4 space indents and lines of up to 120
  characters.

## License

Dejavu is licensed under the [Mozilla Public License 2.0](LICENSE). By contributing code, designs, icons or
translations, you agree that your contribution is licensed under the same license.

Please be kind and respectful to everyone, as described in the [Code of Conduct](CODE_OF_CONDUCT.md).
