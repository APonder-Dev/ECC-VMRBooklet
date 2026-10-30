# Changelog

All notable changes to VMR Booklet are documented here, one section per
release. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Each section heading must be `## [x.y.z]` exactly (matching the git tag
`vx.y.z`) - the release workflow extracts the section between two `## [`
headings and uses it as that release's description.

## [Unreleased]

- (nothing yet)

## [1.0.2]

### Added

- A "Category" filter button, next to Show/Sort, that narrows the list to one of Minecraft's own creative-inventory groupings (Building Blocks, Natural Blocks, Redstone, Tools & Utilities, Combat, Food & Drinks, Ingredients, Spawn Eggs, etc.). Left-click cycles through them one at a time; right-click opens a dropdown to jump straight to any of them.
- ~126 acquisition notes (all 87 spawn eggs, plus items like barrier, the command blocks, debug stick, spawner, trial spawner, structure/jigsaw/test blocks, vault, and several rare head/trim drops) now document VMR's rare crate as a real survival source, alongside each item's existing normal-mode explanation. Phantom Membrane's note also now explains the crate is the *only* real source, since phantom spawning is disabled on this server.

### Changed

- Pottery sherd acquisition notes now name each sherd's actual source structure and drop chance (desert pyramid, desert well, warm/cold ocean ruins, trail ruins, or trial chambers vault pots) instead of one generic "could be any of these" note shared by all 23.

### Fixed

- Colored shulker box acquisition notes said dyeing one takes 8 dye - it only takes 1.
- Blue/brown egg acquisition notes wrongly attributed egg color to a chicken's "feather color variant" - it's actually determined by the biome a chicken spawned in (cold biomes lay blue, warm biomes lay brown), inherited from one parent on breeding.
- Grass block, podzol, mycelium, and crimson/warped nylium notes didn't mention Silk Touch is required to pick them up as themselves (otherwise they drop dirt/netherrack).
- All 18 ore block acquisition notes (surface and deepslate) now correctly explain Silk Touch keeps the ore block itself, the right pickaxe tier drops the raw resource instead, and a too-weak tool drops nothing at all.
- All 14 leaf-type acquisition notes now correctly explain Silk Touch or shears are required to keep the leaves block itself - breaking them any other way only has a small chance at a sapling/stick/apple/propagule, never the leaves.
- Chorus flower's note now covers its full drop behavior: Silk Touch, explosions, and the full list of projectiles that knock it loose.
- Sculk's note now covers ancient city treasure chests, the hoe speed bonus, and that only Silk Touch keeps the block (anything else just gives experience).
- The 7 infested stone/cobblestone/deepslate variants now correctly note they're VMR rare-crate exclusive, not naturally mineable on the server.
- Copper oxidation notes (24 entries) now include the 4-block spacing tip for fastest oxidation.
- The base Copper Golem Statue's note described the wrong oxidation stage (a copy-paste of the fully-oxidized one's wording).
- Grammar: "the copper bars oxidizes" -> "oxidize", on all 3 oxidation-stage entries.
- The B key could steal keystrokes from an unrelated open screen's own text field (the real Creative Inventory's search box, a sign, an anvil, chat) instead of only opening/closing the booklet.
- Several hardening fixes to the new Category filter found during a follow-up deep QA pass: a possible UI freeze if creative-tab data wasn't ready yet, the category cache never clearing on disconnect/server-switch, and no protection against another mod's own creative-tab code throwing an exception.
- Trial chamber vault loot notes (armor trims, banner patterns, music discs, the Heavy Core, Ominous Bottle) now correctly distinguish regular vaults from ominous vaults, with drop chances. Music Disc Relic was wrongly listed as ominous vault loot - it's actually from brushing suspicious gravel at trail ruins. Music Disc Creator (Music Box) was wrongly listed as vault loot - it's from breaking decorated pots.
- Ominous Bottle now also notes it's a guaranteed drop from pillager raid captains, not just vault RNG.
- Several more acquisition notes corrected after a second deep QA pass: Dragon's Breath (removed a fake "end crystal beam" source), Echo Shard (was wrongly described as mined from "sculk veins" - it's ancient city chest loot), Trial Key/Ominous Trial Key (the spawner awards these directly, not the mobs), Pearlescent/Verdant Froglight (warm/cold frog types were swapped), Reinforced Deepslate/Sculk Shrieker (removed references to nonexistent "ancient city vaults" - vaults are a trial chambers-only feature), Spawner/Trial Spawner (corrected - neither is obtainable in survival, not even with Silk Touch), and Enchanted Golden Apple/Horse Armor (filled in several missing valid loot sources).
- A sheet-import header-detection bug that could, on an unusually laid-out sheet, let a later data row silently override the real "Item"/"Obtained" column positions.
- BookletServerTracker now resets on disconnect instead of possibly misattributing an unrelated server's chat messages to VMR.
- Two minor scroll-clamp inconsistencies (ingredient-filter banner height, stats overlay row count) that could show the list stopping one row early/late in edge cases.
- Vault's acquisition note still said it could be mined with Silk Touch - vaults are actually unbreakable in survival, a leftover mistake missed by the earlier trial-chambers QA pass.

### Removed

- The temporary Numpad0 debug-dump tool used during development to read real item ids out of the crate preview screen.

## [1.0.1]

### Added

- Keyboard list navigation: Page Up/Page Down jump a full panel of items, Home/End jump to the top/bottom of the list (in addition to the existing Up/Down).

### Fixed

- Left-click and right-click on a list row were swapped - left-click now opens an item's details, right-click favorites it.
- The recipe panel's title text could clip the panel border on some items.
- The copy button didn't work (clipboard access was broken in Minecraft's JVM) and now copies the item's display name instead of its registry id.
- `/vbooklet` could flash the booklet open and immediately close it if run while chat was still closing.
- A handful of thread-safety, resource-leak, and progress-clamping issues found during a full codebase review.

## [1.0.0]

- Initial release.
