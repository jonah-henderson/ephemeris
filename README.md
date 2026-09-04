# Ephemeris

Dimensions made after the server has started, on **Fabric and NeoForge from one codebase** — and the seams
to decide what they look like once they exist.

An ephemeris is a table of where the sky's bodies will be. These worlds do not outlast the save.

Minecraft **26.1.2**. MIT.

## What it does

```kotlin
val level = RuntimeLevels.open(
    server,
    Identifier.fromNamespaceAndPath("yourmod", "somewhere"),
    RuntimeLevelConfig(dimensionType, generator, seed),
)
```

That is the whole of creating one. It is idempotent, so re-opening a level after a restart is the same call
that made it — there is no "create" that fails on the second boot and no "load" that fails on the first.
`RuntimeLevels.delete` closes one and discards its saved chunks, fenced so it can only ever reach a
dimension folder inside the world it was given.

**Nothing persists these for you.** Vanilla does not write them to `level.dat` and will not rebuild them at
boot, and that is the right place for the decision: whatever decided a level should exist knows how to
describe it. Re-opening is `open` again with the same id.

### How it works, and why that matters

Vanilla already builds levels exactly this way — just earlier. `MinecraftServer` constructs every
non-overworld level from `(executor, storageSource, DerivedLevelData(…), dimension, stem)` and puts it in
its `levels` map. Ephemeris does the same thing later. The cost is **four access-widener lines and no
Mixin at all** for the level itself.

The `LevelStem` registry is deliberately untouched, and that is the finding the whole approach rests on:
`ServerLevel`'s constructor takes a stem *directly*, and every vanilla reader of `Registries.LEVEL_STEM` is
a startup or world-creation path — the boot loop, the datapack loader, the world-select screen, the
optimise-world tool. Nothing reads it during play. So a runtime level needs no registry surgery, and is
invisible to exactly the machinery that has no business creating it.

## Attachment points

```kotlin
RuntimeLevelEvents.whenOpened { level -> … }   // however it came to exist, including a replay on boot
RuntimeLevelEvents.whenClosing { level -> … }  // while it is still usable
```

Registered once, called for every runtime level. This exists because using a library that only *returns*
the level means anything that has to know later gets wired by hand at every call site — which works until
there are three of them.

`LevelWeather.source { level -> … }` gives a level a weather schedule of its own. 26.1 keeps one
`WeatherData` on the server and every dimension computes from it; hand a level its own and vanilla does the
rest — the cycle, the timers, `isRainingAt`, mob spawning, snow and ice, `/weather`, and **the client needs
nothing at all**.

## Appearance

Three rungs, and the convenient road is the same road as the escape hatch.

**Say what a level looks like** and every client that needs to know is told, now and on every future join:

```kotlin
LevelAppearance.give(level, LevelLook(SkySpec.drawn(suns = 2, moons = 0, …), air, corners))
```

There is deliberately no separate "send" — sending *is* what changing means, so there is no call to forget
and no way for a client to disagree with the server about a place. Delivery is eager by default and should
stay that way for almost everyone; `lazily()` exists for the thousands-of-levels case, and comes with a
watchdog that names any route which forgot to call `expecting` rather than letting the wrong sky be silent.

`SkyRules`, carried in the same object, decides two things vanilla never had to answer. **When is it day**
in a sky with several suns — while any is up, while one named one is, or leave vanilla's clock alone and let
the visuals disagree. And **how the horizon is painted** when more than one sun is near it: each paints its
own band at its own bearing in its own colour, added, shared out, or nearest only. Under `auto`, whatever
day and night settle to reaches hostile spawning, phantoms, sleeping, daylight sensors and the block
lighting, because 26.1 derives all of them from one number.

**A level can repaint what grows in it** — `Look.grass`, `Look.foliage`, `Look.dryFoliage`, on the level as
a whole or on any one biome through `corners`. Vanilla resolves those three off the biome, so the obvious
route is to write a colour onto `minecraft:forest` — and that repaints every forest in every world,
including the overworld's. These are answered *per level* instead and the biome registry is never touched,
which is the same reasoning that keeps a runtime dimension out of `LevelStem`.

Grass keeps the biome's own `grass_color_modifier` where that modifier reads the colour it is handed — a
dark forest stays darkened — and skips it where it does not, because vanilla's swamp modifier ignores its
base outright and would throw your colour away. Leaves are answered by wrapping the tint *source* rather than
by hooking a renderer — Fabric's Indigo brings its own and a renderer hook is a bet on which is installed —
because vanilla's leaves are three different things: oak and its kin ask the biome, spruce and birch
carry a *constant* that never does, and cherry and pale oak carry no tint source at all. All of them follow
a level that repaints its foliage. **Azalea does not and cannot** — its model is built on `cube_all`, whose
faces carry no `tintindex`, so no tint is ever multiplied into them however it is arrived at. The colour is baked into the chunk mesh
when a section compiles, so changing one after the fact means dropping what was baked — `LevelLooks.whenTold`
fires for that, and `GroundTints.forget` is what the built-in client hangs off it. Water is deliberately not
here: it is a fluid tint and a different problem.

`Orbit.liftDegrees` carries a body off its great circle so it can stop setting at all. A great circle
centred on the observer is half above the horizon by construction, so nothing else can produce a midnight
sun; lay a path flat and lift it, and the sun circles at exactly that height all day.

Cloud decks are cut from a **texture**, read the way vanilla reads its own `clouds.png` — a grid of
12-block cells, cloud wherever a pixel is opaque. So the silhouette is vanilla's, at any height you like,
and a deck wanting thinner or thicker cover supplies a different picture rather than asking for a number.
`CloudDeck.solid(...)` is the other kind: an unbroken ceiling, with the roil as its only relief.

**The texture decides where there is cloud; the roil decides the tone of the cloud that is there.** Two
separate things, and worth keeping separate — one moves the clouds, the other stirs their surface.

A body **adds** to the sky or **covers** it — `Blending`. Vanilla adds both its sun and its moon, because
its celestial sprites carry no alpha at all: they are indexed colour with no `tRNS`, and vanilla's moon has
a night-sky gradient painted around it. Ephemeris crops that away — a moon sprite is 32 by 32 with the moon
in the middle 8 by 8 — so a body can cover, which is how a moon eclipses a sun, something vanilla cannot do.

The crop is **vanilla's moon only**. Its sun is left whole, because what surrounds *it* is not sky but its
own corona; and a texture of yours is left whole because it is presumed drawn as you want it seen.
`SpriteCuts.register(shape, pipeline)` is there for one that needs a rule of its own.

**Keep the vocabulary, bring your own transport** — `LevelLooks.remember(dimension, look)` on the client,
filled from your own packet, config, or rule.

**Or draw it yourself.** `LevelRendering.sky { moment -> … }`, `.clouds { … }`, `.horizon { … }`,
`.environment { … }`. A
renderer decides for itself whether it applies, because the interesting cases are dynamic and a
registration keyed by dimension cannot express them. Return `false` and the next renderer is asked; if none
claims it, vanilla draws its own.

This rung exists because 26.1 closed every other one: `DimensionSpecialEffects` is gone, Fabric API dropped
`DimensionRenderingRegistry`, and a dimension type still cannot carry appearance to the client. Every mod
that wants a sky of its own writes the same two Mixins, and two mods that do fight over the same seam.

### Rendering a level that is not the player's

Portals, mirrors, camera feeds and preview panels all draw a level into a target that is not the window,
and **nothing in the client renderer says which level a frame is for**:
`SkyRenderer.renderSunMoonAndStars` takes angles and no level, `LevelRenderer.renderLevel` reaches
`Minecraft.getMainRenderTarget()` for itself, and `gameRenderer.mainCamera` is the player's wherever you
read it. Code hanging off those seams asks `Minecraft.getInstance()` and is right only while there is one
of everything — so a second level breaks all of it at once, and *silently*, drawing the player's sky into
somebody else's window.

Declare the missing context and everything downstream follows it:

```kotlin
OffscreenLevelRender.drawing(previewLevel, onto = panelTarget, from = orbitCamera) {
    previewRenderer.renderLevel(…)
}
```

Renderers registered above never need it — a `SkyMoment` already carries its `level` and its `target`, so
read those rather than reaching for `Minecraft` and your renderer works off-screen for free. The scope is
for what cannot be reached that way: deciding which level a moment is *for*, giving a shared singleton
canvas somewhere to draw, and answering where the eye is.

Render thread only, and it nests. **One known gap:** block colours are asked for during section
compilation, on a worker thread, so a leaf in an off-screen level is tinted for the player's level instead.
That wants the level threaded through section compilation, which is vanilla's to give and does not.

## Using it

Ephemeris is **a mod, not a plain library**, on both loaders — because on NeoForge a nested library jar
lives outside the game module layer and cannot see the Kotlin standard library that Kotlin For Forge
provides. Declare it as a dependency; do not bundle it.

Written in Kotlin, callable from Java (`RuntimeLevels.INSTANCE`, and the `fun interface`s are SAM types).

## Notes

`notes/day-and-night.md` is how a sky decides the day, and the record of everything built into one.
`notes/renderer-compatibility.md` is an open audit: which of the client hooks stand on a seam every
renderer shares and which are bets on vanilla's own, after Fabric's Indigo cost three walks on a leaf.

## Building

Java 25 (`.sdkmanrc` pins `25.0.4-tem`). Always the wrapper.

```bash
./gradlew build          # both loaders
./gradlew :common:test   # the checks
```
