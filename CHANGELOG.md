# Changelog

All notable changes to this project are documented in this file.

## [1.2.0] - Unreleased

### Fixed
- **Redstone upgrades no longer destroy a lens' telephoto end** (`maxFocalLength` was reset to the wide end on any lens given redstone after a glass upgrade).
- **Closing the settings screen no longer resets the viewfinder HUD mode**; the screen now merges its fields into the current settings instead of rebuilding them from defaults.
- **Failed pixel reads abort the capture** instead of saving the previous shot's leftover buffer.
- Settings screen writes go to the live main-hand stack (stale `ItemStack` reference could silently drop all adjustments).
- Noise textures rebuilt every frame at 4K+ low GUI scale (≈128 MB/frame): rebuild detection now uses the untruncated window size; old textures are freed on rebuild.
- 8K capture buffers (~800 MB off-heap) are released after each shot instead of being held forever.
- Key presses made while a screen was open no longer fire all at once after closing it (shutter/mode/param keys are drained every tick).
- FOV takeover and camera mode are cleared when leaving a world (FOV no longer stuck in the main menu / next world).
- Hardcoded projection far plane (512) replaced with the live perspective matrix's real near/far planes — depth-to-distance conversion now matches the render distance.
- Left-click shutter no longer punches/breaks blocks: attack events are suppressed while the viewfinder is up.
- `[` `]` `←` `→` are real rebindable keybindings now (previously raw polling that broke on non-QWERTY layouts).
- Metering raycasts throttled (configurable, default every 3 ticks instead of every tick) and now account for rain/thunder.
- Saved file names report the real megapixels (`camera_33mp_…` instead of the wrong `20mp`); disk space is checked before writing a ~400 MB TIFF.
- Settings panel adapts to small windows / GUI scale 4; arrow keys no longer swallow F1/F3; empty-area clicks keep the scroll target.
- Shutter values outside the standard table (e.g. cinema 1/48) snap to the nearest stop instead of jumping to 4000/–3000.
- Scene colour temperature is continuous across dawn (no more 5500 K→4000 K jump within one tick).
- Cave detection uses sky light instead of altitude; EV preview is symmetric up to ±5 stops; capture white balance uses CCT-derived per-channel gains shared with the preview.
- Dark areas keep a read-noise floor at high ISO; bokeh disc sampling scales with blur radius (8→32 samples).
- HUD strings resolved at tick rate; springs advance with real frame delta (smooth above 20 fps); vignette texture pre-warmed at startup.
- Server broadcasts the lens-install animation; GeckoLib synced-animatable registered exactly once.
- Custom camera `shutter` sound replaced with a registered vanilla click (the old id was never registered and always played the fallback sound).
- Dead code removed: unreachable `WorldRenderEvents.END` fallback capture path and its malformed `writeRgb16` TIFF (XResolution 0/0), unused `drawSingleBar`.

### Changed
- **Classic viewfinder style (Canon EOS inspired)**, selectable alongside the existing Neon HUD via `uiStyle` in the config / Mod Menu: OVF black surround (original release layout), green seven-segment digits, mode letter A+/P/Tv/Av/M, Canon-format shutter (`1/80`, `0"5`, `2"`), `F`-number, ISO badge, ±3 EV scale with 1/3-stop ticks and spring-smoothed pointer, AWB/Kelvin white balance, centre AF box with focus-confirm pulse. The right-click parameter screen was re-laid-out to match (mode letter / shutter / F / ISO top row, EV scale, boxed toggles).
- Camera model scaled up ~18% in-world (inventory icon unchanged).
- Data storage migrated from opaque `CUSTOM_DATA` NBT to typed data components (`camera-mod:lens_spec`, `camera-mod:camera_data`) with Codec-based (de)serialisation; old worlds migrate automatically and keep working.
- Lens upgrade: materials count by stack size with per-type caps (glass ≤ 4, redstone ≤ 3, iron ingot ≤ 4, iron block ≤ 2); redstone ≥ 2 unlocks autofocus **and** zoom; crafted lens preserves custom names/other components.
- Recipe book unlocks via advancements for all six recipes; unused `#camera-mod:cmos` tag removed.
- Sneak right-click with an empty offhand removes the installed lens and returns it.
- CMOS sensors gained rarity (UNCOMMON/RARE) and are fireproof.
- Package structure unified under `com.example.camera.*`; pinyin class names renamed (`Jishen`→`CameraBodyItem`, `Jingtou`→`LensItem`, `Chuanganqi`→`SensorItem`); `CameraFovState` moved to the client source set; template mixins and `fabric.mod.json` stubs removed.
- Full English (`en_us`) translation added; all hardcoded Chinese messages/tooltips/HUD strings moved to translation keys.
- Config file `config/camera-mod.json` (`hideVanillaHud`, `meteringIntervalTicks`, `settleFrames`, `mmPerBlock`, `irisHdrTarget`, `filenamePrefix`) plus a Mod Menu settings screen; JVM system properties still override when explicitly set.
- `InGameHudMixin` honours `hideVanillaHud`; crosshair injection is soft (`require = 0`).
- TIFF gains a `Software` tag.
- Build defaults to official Maven/Gradle sources; CN mirrors moved to an opt-in script (`scripts/enable-cn-mirrors.sh`); loom pinned to 1.17.21 (was a floating SNAPSHOT); `fabric-api` dependency given a version floor.

### Developer
- **Automatic versioning**: `gradle.properties` pins only `mod_version_base`; the real version is derived from git — tag `v1.2.0` builds as `1.2.0`, ordinary commits as `<base>-dev.<commitCount>+<shortHash>`.
- **DH+Iris combo matrix verified in dev**: Iris 1.8.8 / Sodium 0.6.9 / DH 3.3.3 / Complementary Reimagined — all four iris×dh on/off combos pass (HDR from colortex0, exports + screenshots per combo). Iris toggled via the official IrisApi.v0, DH via `quickEnableRendering`; dev-only JiJ library jars (jcpp/glsl-transformer/antlr4) are shipped to the dev classpath since Loom does not expand nested jars of `files()` deps. Auto-test now saves & quits on completion (with force-exit fallback for DH's slow database shutdown); `-PwithIrisDev` accepts a bare flag.
- **Unattended auto-test harness** (`-Dcameramod.autoTest=<outDir|true>` or `-PautoTest`): creates/reuses a UUID-named test world, waits for fps/tps/mspt stability, screenshots hand-held style + Neon/Classic HUDs, exports parameter-preset TIFFs across Iris/DH on-off combos (DH detected from mixin/log lines with parse-progress extraction), writes `run/auto-test/{shots,exports,metrics.csv,report.json}` and wipes them on next startup.
- **Commit-to-release pipeline**: push to `main`/`master` builds and refreshes the rolling *Dev Snapshot* prerelease; tag `vX.Y.Z` publishes a stable release (jar named by the tag). CI checkouts use full history for real dev versions.
- CI: Java 21 (matches the mod), Gradle caching, concurrency cancellation.
- README gained a CN local-build guide (opt-in BMCLAPI mirrors for loom, Gradle distribution download workarounds) and a mechanics overview; `CHANGELOG.md` added; TODO/PROGRESS rewritten to match actual behaviour.

## [1.1.0-beta]

- Viewfinder HUD rewrite: borderless top status / left mode list / bottom parameter rail, spring animations, five scene modes (Auto/Photo/Portrait/Night/Cinema), `[`/`]` parameter switching.
- Snapmatica-style thin-lens CoC (with Airy-disk diffraction), ISO noise model, async 8K capture pipeline (no more server kick during saves).
- Lumin F / Lumin H bodies with GeckoLib models, first-person animations and lens bone visibility.

## [1.0.0-beta]

- Initial release: 8K 32-bit float TIFF capture with EXIF, thin-lens depth of field on the saved photo, aperture/focal-length crafting upgrades, evaluative/center/partial/spot metering, auto exposure with EV compensation.
