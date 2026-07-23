# Native libraries

**`libbass.so` is already bundled** for all four ABIs
(`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) — nothing to add for
BASS core.

**Still needed: `libbassmidi.so`** (the BASSMIDI add-on's native
library — see `app/libs/README.md` for where to get it), placed
alongside `libbass.so` in the matching folder:

```
app/src/main/jniLibs/armeabi-v7a/libbass.so       ✅ already here
app/src/main/jniLibs/armeabi-v7a/libbassmidi.so   ← add this
app/src/main/jniLibs/arm64-v8a/libbass.so         ✅ already here
app/src/main/jniLibs/arm64-v8a/libbassmidi.so     ← add this
app/src/main/jniLibs/x86/libbass.so               ✅ already here
app/src/main/jniLibs/x86/libbassmidi.so           ← add this
app/src/main/jniLibs/x86_64/libbass.so            ✅ already here
app/src/main/jniLibs/x86_64/libbassmidi.so        ← add this
```

Most real Android devices only need `arm64-v8a` (and `armeabi-v7a` for
older devices) — `x86`/`x86_64` are mainly for emulators, so it's fine
to skip `libbassmidi.so` for those if you don't need emulator testing.

See `app/libs/README.md` for the full checklist and licensing note.
