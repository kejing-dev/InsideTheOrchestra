# Inside the Orchestra (走进乐团)

A 23-piece orchestra (realistic Tripo musicians) takes its real seats, 1:1, on your living-room floor and plays
Grieg's *In the Hall of the Mountain King*. Stay where you are and point to listen: look at a player (eye gaze,
head direction as fallback) or aim the controller ray at them and their part comes forward (+6 dB against the rest);
pinch / pull the trigger to hear only that part, with the family's instrument card. PICO OS 6, Kotlin, PICO Spatial
SDK 6.1.9, one Mixed Stage (Full Space, passthrough), package `io.embodit.insideorchestra`, v0.1.0.

Design package (binding): `../spatial-app-factory/apps/inside-orchestra/design-package.json` (v1.5, archetype D,
STAGE_MIXED, single panel with popup, states S0 - S19). Skeleton: `../bianzhong` (Chime Bells: Stage start, plane
tracker, wall snap, ghost placing, thumbstick turn, panel pool, lazy follow, debug receiver); eye gaze from
`../rehearsal-hall`; GLB template + clone from `../life-size-creatures`.

Read with the plugin guidance (`../PICO-SPATIAL-AGENTIC-TOOLS.AGENTS.md`): SpatialUI + PicoTheme only, no Material.

## v0.1.5 (2026-11-05) — read this first; the sections below describe v0.1.0–v0.1.4

- **One system window** (`ui/window/`): Planar `WindowContainer` "OrchestraPanel" 640 × 440 dp, system TabBar pages
  演奏 / 曲目 / 声部 / 乐器 / 设置 (exit app at the bottom, confirmed on the same page). Moving / closing is the system's
  handle dot. `WindowCtl` opens it only when the head is level (`LevelGate`), "叫回面前 / Bring here" = close → open after
  ON_DESTROY. The Stage keeps one chip ("打开面板" / "平视前方"). All world panels, bars, card, snackbar, the drag handle and
  `PlaneColliders` are gone; an app-owned invisible floor slab catches the placing pinch.
- **Starts on the podium**: head valid 1 s + a floor → `HeadFrame.podiumAt` (user on the podium centre, players ahead).
  "回到指挥台" on Play and Settings slides the orchestra back. [Move orchestra] still opens the marker placing flow.
- **Six pieces** (`assets/pieces/index.json`, `pieces/<id>/piece.json|parts.bin`, `audio/<id>/<stem>.ogg`), rendered with
  the GeneralUser GS SoundFont: `python tools/make_music.py <GeneralUser-GS.sf2> [qa dir] [ids]` (pip: numpy scipy
  soundfile tinysoundfont matplotlib). Arrangements in `tools/repertoire.py`. No Blender needed any more.
- **Volume**: master + 4 sections, detents 低 35 / 中 70 / 高 100 %, mute toggles; master default 高 (`masterVol5`).
- **Players' scale**: `Orchestra.modelHeight/modelScale` (bassist 1.9 → 1.3 m), one calibration node per instance.
- New debug keys: `piece=<id|0-5>`, `page=play|pieces|sections|learn|settings`, `podium=1`, `bring=1`, `exitask=1|0`;
  `menu=1` = settings page, `recall=1` = bring here; `sidedrag` removed.
- Emulator: `adb screencap` tears over the window (factory snippet §7.5); its audio output ignores mixer-group volume.

## Build

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --offline :app:testDebugUnitTest :app:assembleDebug
```

Assets are generated, not hand-made (re-run when the inputs change):

```
blender --background --python tools/prep_models.py -- <factory>/apps/inside-orchestra/assets-src app/src/main/assets/models <check dir>
python -I tools/make_music.py <wav dir>                 # arrangement -> 10 WAV stems + assets/parts.bin + assets/piece.json
blender --background --python tools/encode_ogg.py -- <wav dir> app/src/main/assets/audio
python -I tools/subset_cjk_font.py                      # Noto Sans SC subset to the Chinese copy
python -I tools/make_icon.py <factory>/apps/inside-orchestra/icon.png
```

- `prep_models.py`: the 14 raw Tripo GLBs (1.0 unit tall) scaled to real height (seated 1.25 m incl. chair, double
  bass 1.9 m, standing percussion 1.6 m), turned so every model faces glTF +Z (`FRONT` table, found with
  `tools/yaw_check.py` multi-angle renders), maps 2K -> 1K JPEG. 5.1 MB for all 14.
- `make_music.py`: the theme written once (64 eighths), each pass = which parts, octave, loudness, bpm; synthesized
  with numpy (bowed / plucked strings, reeds, brass, timpani, bass drum, cymbals) + a small room reverb; stems
  normalised to the same loudness. Pass starts 0 / 5.7 / 28.6 / 50.4 / 70.4 / 88.9 / 105.7 / 121.2 / 135.3 /
  148.3 / 160.3 s, duration 171.7 s. Envelopes come from the note lists (not the mix), 20 Hz, bit 7 = pizzicato.

## Structure

```
app/src/main/java/io/embodit/insideorchestra/
  Main.kt                       DefaultStage { OrchTheme { OrchScreen() } }
  domain/model/Orchestra.kt     Family / Part (15) / Stem (10, source positions) / Seat (23, design table) / Cards /
                                NearListen (S8 rule + debug stand sites)
  domain/model/Piece.kt         piece.json + parts.bin (envelopes, passes, captions, rests)
  domain/usecase/               OrchPose + PlacementUseCase (anchor = podium front, 0.6 m clearance, snap),
                                WallSnapUseCase (merged walls, 4.8 x 4.0 m frames), HeadFrame, PieceClock
  platform/                     PlaneTracker, HeadTracker, EyeTracker (+ EyePermissionActivity), ControllerInput
                                (ray, trigger, stick x / y), audio/OrchestraAudio (10 x ObjectAudio + mixer groups)
  ui/orch/OrchViewModel.kt      the state machine, the piece clock tick, captions / coach, debug hooks
  ui/orch/scene/OrchScene.kt    the ECS frame system: ghost, pointing + focus, stick orbit / push, drag, pull / home,
                                near listening, rings / sway / solo dimming / D14 fade, zones, name tag, card + leader,
                                audio mix and clamps, the CLOCK log line
  ui/orch/scene/OrchestraView   orchestra root: podium, zones, 23 seats (holder -> Tripo clone, ring, tap box), sources
  ui/orch/components/           OrchStage (SpatialView + pool), Panels (control / placing), Overlays (menu, card, snack), Copy
app/src/debug/                  DebugCommandReceiver + DebugCommands
app/src/test/                   OrchestraTest (layout, sources, facing, stand sites, clearance, clock, assets)
```

Audio layers (design `audio`): component volume = podium balance (recomputed at place / play / size from the real ear
height), mixer group = 0.7 master x mute x focus (1 / 0.5) or solo (1 / 0.08), eased 300 ms; controller volume =
close-up clamp (level distance < 0.5 m). Sync: every 1 s against the median of the 10 positions; 20 - 80 ms off ->
speed 0.99 / 1.01; > 80 ms -> seek at the stem's next rest. The app's piece clock drives everything visual and
leans towards the audio median by at most 20 ms/s (`CLOCK clock=audio|app lat=...`).

Pointing source (design `input.source`): the controller when it moved in the last 5 s, else the eyes
(`EyeTrackingProvider`), else the head direction. Hit test is the app's own ray vs. each musician's box (the system
hover is not reported). Taps (pinch, trigger, mouse) come from `detectSpatialTapGesture` on the musician / podium /
zone / plane colliders; drags on the podium or a floor zone move the orchestra (`detectSpatialDragGesture`).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.insideorchestra.DEBUG -p io.embodit.insideorchestra --es cmd 'place=auto play=1,pos=95 freeze=1'"
DEV=<serial> tools/qa_shot.sh S7-cello 'place=auto' 'solo=VCa play=1,pos=20 freeze=1'
DEV=<serial> tools/launch_check.sh 60
```

Keys (design `debugHooks`, see `DebugCommands.kt`): `state=S0|S1|S2|S13|S14|S17`, `place=auto|wall`, `near=1`,
`floor_timeout=<s>`, `wallmin=<m>`, `play=1|0`, `pause=1`, `pos=<s>`, `done=1`,
`solo=str|ww|br|perc|<stem>|<seat>|none`, `item=<instrument>`, `hover=<seat>|0`, `pull=<seat>`, `orbit=<deg>`,
`push=<m>`, `home=1`, `mute=0|1`, `stand=<stem>|podium`, `scale=1|2`, `lang=zh|en|system`, `captions=0|1`,
`caption=<pass>`, `coach=1`, `coach_reset=1`, `audiofail=1`, `retry=1`, `freeze=0|1`, `menu=0|1`, `replace=1`,
`turn45=1`, `exit=1`; queries `dump=1`, `hmd=1`, `bounds=1`, `planes=1`, `park=1`; `ui=<probe name>` presses a
panel control. Log tag `Orchestra`: `STATE Sxx overlays=...` on every change, `CLOCK ...` every second, plus
`FOCUS`, `SOLO`, `NEAR`, `PLACE`, `MOVE`, `PULL`, `CARD`, `AUDIO`, `MODEL`, `TAP`, `DRAG`, `SNAP`, `WALLS`, `VROOM`.

Screenshot list (design `stateModel.qaCombos`):

| combo | commands |
| --- | --- |
| S0 | cold start, first 2 s (or `state=S0 freeze=1`) |
| S1 | `state=S1` |
| S2 | `state=S2` (ghost on the pointer floor point; pivot = podium front) |
| S2 too close | `state=S2 near=1` |
| S2a | `place=wall freeze=1` |
| S17 | `state=S17` |
| S13 | `floor_timeout=1` on a cold start, or `state=S13`; + `menu=1` for the steppers |
| S14 | `state=S14` |
| S3 | `place=auto` |
| S4 | `place=auto play=1,pos=95 freeze=1`; coach: `coach=1` |
| S5 | `place=auto play=1,pos=75` then `pause=1` |
| S6 | `place=auto hover=VCa play=1,pos=20 freeze=1` (eye-hand mode and controller mode) |
| S7 cello | `place=auto solo=VCa play=1,pos=20 freeze=1` |
| S7 brass | `place=auto solo=br play=1,pos=125 freeze=1` |
| S7 + S5 | `solo=VCa pause=1` |
| S8 cello | `place=auto pull=VCa play=1,pos=20` then `freeze=1` after 1 s |
| S8 podium | `place=auto stand=podium` |
| S19 | `place=auto orbit=60`; then `home=1` |
| S9 | `place=auto done=1 freeze=1` |
| S10 | `menu=1` |
| S11 | `place=auto play=1,pos=7 freeze=1`; `play=1,pos=149 freeze=1` |
| S12 | `scale=2 place=auto` |
| S15 | `place=auto audiofail=1` |
| S16 | `lang=en place=auto solo=ww item=oboe play=1,pos=55 freeze=1` |
| S18 | Home key while playing / placing, come back |
| mute | `mute=1` |

Emulator notes: plane detection takes 20 - 45 s on the emulator room, so a cold start usually goes to the virtual room
after 5 s (S13); real planes then replace the virtual floor. Eye tracking is not available on the emulator (pointing
falls back to the head). Audio from the emulator is not part of QA; the timeline runs on the app clock.
