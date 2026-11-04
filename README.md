# Braid

**Real-time collaborative notes for Android, with no server.** Your devices find each other on the local network, open an end-to-end encrypted channel, and merge every keystroke with a CRDT. Works fully offline, and every edit converges.

```
  Phone A ─┐                               ┌─ Phone B
           │   mDNS discovery (_braid._tcp) │
           └──── Noise_NNpsk0 over TCP ─────┘
             (X25519 · ChaCha20-Poly1305)
                         │
             version-vector delta sync
                         │
            RGA text CRDT + LWW + OR-Set
                         │
             Room op log (event-sourced)
```

Braid has no account, no cloud and no Firebase. Two phones on the same Wi-Fi edit the same note at the same time and see each other's cursors. Take one phone offline, edit on both, reconnect, and everything merges with no conflict dialogs and no lost text.

---

## Highlights

| | |
|---|---|
| **Character-level CRDT** | A hand-written RGA (Replicated Growable Array) with run-length blocks and run extension: typing 20 000 characters produces **one** internal block. Interleaving-free concurrent inserts. Surrogate-pair safe. |
| **Proven convergence** | A property-based fuzz test runs 2–4 replicas through 150 seeded scenarios × 400 random actions each, with partitions, partial gossip and binary round-trips, and asserts identical state. |
| **Spec-exact crypto** | Noise Protocol `NNpsk0_25519_ChaChaPoly_SHA256`, implemented in ~250 lines and **verified byte-for-byte against the official cacophony test vectors**. Gives forward secrecy, mutual authentication through the workspace key, and binds each session to one workspace. |
| **Serverless mesh** | NSD/mDNS discovery, a coordination-free "smaller id dials" rule with asymmetric-visibility fallback, exponential backoff with jitter, and transitive relay (A↔B↔C syncs A↔C). |
| **Exact delta sync** | Per-replica gap-free sequence numbers plus version vectors: a reconnecting peer receives *exactly* the ops it lacks, in one round trip. |
| **Collaboration-aware undo** | Undo reverts *your* last change, not a collaborator's, and still works after the surrounding text has moved. |
| **Time travel** | Scrub through every operation in a note's life, see who did what and when, and restore any version (as a forward edit, so history is never rewritten). |
| **Live cursors & authorship** | Remote carets and selections anchored to CRDT ids (never indices), plus a "blame" view that colors text by author. |
| **Ghost collaborator** | A second replica *inside the app*, connected through the real handshake, encryption and protocol, so the whole collaboration experience can be evaluated with one phone. |

## Try it

**With one phone:** create a workspace → tap the face icon (top bar) → open or create a note. The Ghost joins, types next to you, makes typos, fixes them, and tags notes. Type in the same spot and watch it converge. Open the history screen to scrub through both of your edits.

**With two or more devices on the same Wi-Fi:** create a workspace on device A → *Devices & sync* → *Show QR code*. On device B, choose *Scan QR* (or open the shared `braid://` link). The devices connect within seconds.

> Emulators: two emulators on one host are behind separate NATs and can't see each other's mDNS. Use real devices, or one device plus the Ghost.

## Build

Requirements: JDK 17+ (CI uses 21) and the Android SDK (API 36). The Gradle wrapper (8.14.3) downloads everything else.

```bash
./gradlew assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease           # R8-minified, ~4 MB (debug-signed for convenience)
./gradlew test                      # all 62 unit tests (JVM + Robolectric)
./gradlew :core:crdt:test           # just the CRDT and its fuzzer (~10 s)
./gradlew :app:lintDebug            # lint, warnings as errors
```

If `./gradlew` fails with `Permission denied` (exit code 126), the executable bit was lost on checkout or upload. Fix it once and commit:

```bash
chmod +x gradlew
git update-index --chmod=+x gradlew
```

`.gitattributes` keeps `gradlew` on LF line endings so it also runs after a Windows checkout.

### Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request (pinned `ubuntu-24.04`, Temurin 21, `gradle/actions/setup-gradle@v5`, all actions on Node 24):

1. Makes sure the wrapper is executable, then prints the toolchain (`./gradlew --version`).
2. `:core:crdt:test :core:sync:test`: pure-JVM CRDT, crypto and protocol tests. Fastest, so they run first.
3. `:core:data:testDebugUnitTest`: Robolectric + real SQLite.
4. `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`: app tests, lint (warnings as errors), and the R8 release build.
5. If something fails, uploads test and lint reports. On success, uploads the release APK.

The build compiles with **zero compiler warnings** and **zero lint issues**.

## Architecture

```
app/            Compose UI, Hilt DI, navigation, workspace lifecycle, Ghost peer, QR (CameraX + ZXing)
core/network/   Android LAN transport: NsdManager discovery, TCP server/dial, multicast lock
core/data/      Room op log, WorkspaceEngine (event-sourced replica + group commit),
                CollaborativeText (text-field ⇄ CRDT binding), Keystore-sealed prefs
core/sync/      Pure Kotlin: Noise handshake, SecureChannel, wire protocol, SyncSession, PeerMesh, invites
core/crdt/      Pure Kotlin: HLC, version vectors, RGA text, LWW register, OR-Set, undo, timeline, codec
build-logic/    Gradle convention plugins (one place for SDK levels, JVM target, compiler flags)
```

Dependencies point strictly downward. `core:crdt` and `core:sync` are **plain Kotlin/JVM modules with no Android dependency**: their 47 tests run in seconds without an emulator, and the same code could power a desktop or server peer.

### Key decisions (short version)

- **CRDT over OT:** OT needs a central server to order operations, and a mesh of phones has none.
- **RGA over Yjs/Automerge bindings:** no mature, maintained Kotlin binding exists. Writing the CRDT in Kotlin keeps the APK small, avoids JNI/WASM, and makes every line testable.
- **Event sourcing:** the op log is the only persisted truth. State is a pure function of it, so there is no "current text" column that can disagree with history after a crash.
- **CRDT state on the main thread:** the text field and the CRDT have to agree at the instant a keystroke is diffed. Confining both to one thread rules out the race by construction, with zero locks (the same model Yjs uses in browsers). Heavy work (replay, I/O, large remote batches) stays off that thread.
- **Noise over TLS:** TLS-PSK isn't available on Android's platform stack, certificates make no sense for ad-hoc peers, and Noise has no negotiation, so there's nothing to downgrade.
- **NSD over Nearby Connections:** no Google Play Services dependency, and it interoperates with any mDNS stack.

The full reasoning, including alternatives and trade-offs for every layer, is in the design document that accompanies this repository.

## Testing

| Suite | What it proves |
|---|---|
| `ConvergenceFuzzTest` | Strong eventual consistency under random concurrency, partitions and reordering; replay and codec equivalence |
| `RgaTextTest` | No interleaving, deletes vs. concurrent inserts, emoji safety, causal-gap detection, run extension rules |
| `NoiseVectorTest` | Byte-exact match with the cacophony vectors; wrong key, wrong workspace and tampering are all rejected |
| `SyncSessionTest` | History + live streaming, auth failure leaks nothing, transitive relay, reconnect delta, 6 000-op fragmented transfer |
| `PeerMeshTest` | 4-device full mesh with exactly one connection per pair; asymmetric discovery fallback |
| `CollaborativeTextTest` | Typing against a stale text field never deletes remote text (randomized) |
| `UndoAndTimelineTest` | Undo is per-user and position-independent; randomized undo/redo of whole histories; history replays every version |
| `WorkspaceEngineTest` | Group commit (12 keystrokes → 1 transaction), only durable ops served, restart equivalence, parking of out-of-order ops, retry after disk-write failure |
| `RoomOpLogTest` | Real SQLite via Robolectric: idempotent append, exact delta query, paged load |
| `PerformanceTest` | 16 µs per keystroke; 20 000-op fragmented document replays in ~2 s on the JVM |

## Security model, in one paragraph

The invite (QR or link) carries a random 256-bit workspace key, and **the invite is the membership credential**. Every connection runs a Noise NNpsk0 handshake: a device without the key can't complete it, can't read anything, and learns nothing about the workspace. Fresh ephemeral X25519 keys give forward secrecy. The key is stored sealed with a non-exportable AES-GCM key in the Android Keystore. App data is excluded from cloud backup, and the QR screen sets `FLAG_SECURE`. Removing a single member isn't possible without rotating the key; that limit, and its fix, are covered in the design document.

## Limitations

- Plain text only (no rich text). The RGA is the foundation a rich-text CRDT (e.g. Peritext) would build on.
- Sync runs while the app is in the foreground. Background sync (a foreground service or WorkManager-triggered sessions) is a deliberate scope cut.
- Tombstones aren't garbage-collected yet. The design document describes a safe causal-stability GC.
- LAN only. A relay transport would plug into the existing `MeshTransport` interface.

## License

MIT
