// Preferences appended to mobile/android/app/geckoview-prefs.js by the F-Droid recipe, so that the
// Google Play build keeps these features and the F-Droid build does not.
//
// F-Droid's inclusion policy does not allow an app to download executable code unless the user
// clearly chooses it. Gecko fetches two such binaries on demand: Cisco's OpenH264 decoder, through
// the Gecko Media Plugin manager, and Google's Widevine CDM, through Encrypted Media Extensions.
// Neither is free software and neither asks first, so both are turned off here. Fennec F-Droid does
// the same in its own prebuild.
//
// What this costs: sites that only offer H.264 through OpenH264 lose video, and DRM-protected
// streaming services do not play. Gecko's own codecs are unaffected.

// Do not use Encrypted Media Extensions, which would fetch the Widevine CDM.
pref("media.eme.enabled", false);

// Do not use Gecko Media Plugins at all.
pref("media.gmp-provider.enabled", false);

// Leave the plugin manager with nowhere to download from.
pref("media.gmp-manager.url.override", "data:text/plain,");

// Ignore OpenH264 even if a copy is already on the device.
pref("media.gmp-gmpopenh264.enabled", false);
