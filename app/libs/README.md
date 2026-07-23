# BASSMIDI Java library goes here (BASS core is already bundled)

**BASS core is already fully integrated** — the real `BASS.java` source
from un4seen is bundled directly at
`app/src/main/java/com/un4seen/bass/BASS.java`, and the real
`libbass.so` binaries are bundled per-ABI in `app/src/main/jniLibs/`.
No jar needed for BASS core; nothing to add for it.

**Still needed: the BASSMIDI add-on**, which is what actually creates a
playable stream from a MIDI file and loads SoundFonts (.sf2). BASS core
alone can't do MIDI synthesis — it needs this add-on. To add it:

1. Download the **BASSMIDI** package for Android from
   https://www.un4seen.com/ (a separate download from BASS core — look
   for the BASSMIDI add-on, matching your BASS version).
2. If it includes Java source (`BASSMIDI.java`), copy it to
   `app/src/main/java/com/un4seen/bass/BASSMIDI.java` — same treatment
   as `BASS.java`. If it only ships a compiled `bassmidi.jar`, drop
   that jar in this folder (`app/libs/`) instead — either way,
   `BassMidiAudioEngine.java` picks it up automatically (it currently
   reaches BASSMIDI reflectively specifically so it doesn't matter
   which form you have).
3. Copy the matching `libbassmidi.so` into
   `app/src/main/jniLibs/<abi>/` — see the README there.
4. Rebuild. Check logcat (`adb logcat -s KaraokeAudioEngine`) — it
   logs which engine actually got used for each song, and will show
   `"BASSMIDI (native)"` once this step is done correctly.

**Licensing:** BASS is free for non-commercial/freeware use. Commercial
or shareware distribution requires a paid license from un4seen — check
https://www.un4seen.com/bass.html for current terms before shipping.
