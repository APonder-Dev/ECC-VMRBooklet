# Changelog

All notable changes to VMR Booklet are documented here, one section per
release. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Each section heading must be `## [x.y.z]` exactly (matching the git tag
`vx.y.z`) - the release workflow extracts the section between two `## [`
headings and uses it as that release's description.

## [1.0.3]

### Added

- A "Show: Unfinished" option in the Show cycle, between Completed and Partial - everything still standing between you and 100% (Partial + Undiscovered together), without having to flip between those two separately.
- Right-click the Show button to open a dropdown and jump straight to any filter, same as the Category row.
- `/vbooklet missing notes` - same list as `/vbooklet missing`, but with each item's acquisition note attached, so it's an actual to-do list instead of just names.
- `/vbooklet trades` - cross-references everything you're still missing against every imported friend's own progress and lists who already has what, instead of having to compare friend tabs by hand.
- `/vbooklet need <item>` - walks an item's recipe chain (skipping anything you already have enough of, and stopping at items with their own acquisition note instead of looping through a fake compress/decompress recipe) to build a shopping list of the raw materials still standing between you and it.
- `/vbooklet goal <item>` / `/vbooklet goal` / `/vbooklet goal clear` - a small, self-picked subset of items to track as its own progress line in the booklet header, separate from the general Favorites list.
- A "T" button next to ?/%/R showing a day-by-day completion timeline - how many items you finished on each day, newest first. The activity log this reads from now keeps 500 entries instead of 50, so there's enough history for it to actually be worth graphing.
- A "N" button showing a live, expandable tree of what's still needed to craft the selected item - the same recipe-chain walk `/vbooklet need` already did, but as something you can leave open and click through node by node (expand/collapse, jump straight to a leaf) instead of a one-shot clipboard dump.
- An "F" button showing which of your imported friends already have each of your missing items - a live version of `/vbooklet trades`.
- `/vbooklet missing bycategory` - same list as `/vbooklet missing`, grouped under a header line per category instead of one flat list.
- Named goal lists: `/vbooklet goal lists` shows every list you have (with the active one marked), `/vbooklet goal list <name>` switches to one (creating it empty if it's new). Everything that already worked on "your goal list" (`/vbooklet goal <item>`, `/vbooklet goal`, `/vbooklet goal clear`, and the header progress line) now applies to whichever list is active. An existing single-list `goals.json` from before this migrates automatically into a list named "default" the first time it's read - nothing pinned gets lost.
- Search shortcuts inside the booklet: `/` or Ctrl+F jumps straight to the search box without clicking it, plus an optional rebindable "Focus Booklet Search" key (unbound by default) under Controls.
- A day-streak counter in the header, next to the session-completion count, once it's at least 2 days - entirely derived from the existing activity log, no new save data needed.
- Hover tooltips on every header and toolbar button (?, %, R, T, N, F, Show, Sort, Category, Next unfinished, Notify), and placeholder text in the empty search box.
- Mod Menu settings now have toggles for the header's streak and goal-progress parts, alongside the existing scan-notification toggle.
- Every piece of text the mod shows - the booklet screen, overlays, toasts, chat feedback, and the settings screen - now comes from the language file (`assets/vmrbooklet/lang/en_us.json`), so the mod can be translated. Category names reuse Minecraft's own creative-tab names, so they already appear in your game language.
- A "Booklet: 3/5" (or "✓ Booklet") line on every booklet item's tooltip, anywhere in the game - inventories, chests, trades, recipe views - with "· Goal" added when it's on your active goal list. Toggleable in Mod Menu settings.
- Hover any item in your inventory, a chest, a shulker box, or the creative tabs and press the booklet key (B) to open the booklet with that item selected. Typing "b" into a search or rename field still just types.
- Optional gold tint on container slots holding an item you still need (Mod Menu settings, off by default) - sorting loot shows at a glance what's worth keeping.
- `/vbooklet ready` - lists the booklet items you're carrying that you still need, and which of them you have enough of to finish right now.
- An optional on-screen goal tracker HUD: your active goal list with counts, unfinished items first, in whichever corner you pick (Mod Menu settings, off by default).
- Search filters in the booklet's search box: `@category`, `#release`, `!done` / `!todo` / `!partial` / `!new`, and `>50%` / `<50%`, combinable with each other and with plain name words.
- `/vbooklet note <item> <text>` / `here` / `clear` - your own per-item notes and saved locations (coordinates + dimension), shown under the item's details.
- A pace estimate in the % overlay: your last 7 days' completion rate and roughly how many days that puts you from 100%.
- A colorblind color option (Mod Menu settings): blue/orange/gray instead of green/yellow/gray on bars, list rows, slot highlights and the HUD, plus ✓ and ~ marks on list rows.
- Single-key overlay toggles in the booklet: H (help), S (stats), R (recent), T (timeline), N (needed), F (friends), while the search box isn't focused.
- A session summary toast ("+12 items this session - 5 day streak") when you close the booklet or leave a server, only when something new was completed since the last one. Toggleable in Mod Menu settings.
- Custom item lists: an `items.json` in `config/vmrbooklet/` replaces the bundled list (only `id` is required per entry), and `/vbooklet reload` re-reads it. A broken file falls back to the bundled list with a chat warning. Share and backup codes are refused while a custom list is active, since they encode positions in the bundled list.
- `/vbooklet card` - saves a shareable PNG progress card (overall %, streak, top categories) to `config/vmrbooklet/cards/`, with a clickable file name in chat.

### Changed

- Opening an overlay now always starts it scrolled to the top; previously each overlay kept its own scroll position for as long as the booklet stayed open.
- Long item names in the list, the detail header, the ingredient-filter banner, and the Recent overlay now truncate with "..." instead of running under the counts beside them.
- The Redstone category now uses Minecraft's own creative-tab name, "Redstone Blocks".
- The header counts, day streak, goal progress, stats, timeline, needed tree, and friend comparison are now computed at most once a second (and immediately after any progress change) instead of on every frame.
- The Mod Menu settings screen is now two columns, to fit the new tooltip, highlight, HUD, HUD corner, session summary and colorblind options; its columns shrink on narrow windows instead of running off-screen.
- The search box now accepts up to 96 characters (was 48), to leave room for combined filters.
- Internal: the source is reorganized into `commands/`, `data/`, `sync/`, `scanning/` and `gui/` packages, and the booklet screen is split into smaller classes (overlays, dropdowns, input handling, shared drawing helpers). Nothing players will notice, but it makes contributions much easier.

### Fixed

- Resin Clump now has an acquisition note - the booklet only showed its crafting recipe before (which just unpacks a Block of Resin, not a real source), with no mention of attacking a creaking at night, breaking a creaking heart, or woodland mansion chest loot.
- Found the same class of bug across 8 more items that only ever showed a "fake" compress/decompress recipe with no real acquisition note: Raw Iron/Copper/Gold, Stone, Cobblestone, Deepslate, Cobbled Deepslate, Slime Ball, and Wheat all now explain their actual real-world source (mining the right ore/block with the right tool, mob drops, farming, etc).
- Torchflower Seeds and Pitcher Pod's notes made it sound like a sniffer's dig might come up empty - it never does, it's always one or the other, split exactly 50/50. Pitcher Pod also had no note at all before this.
- Glow Lichen and Hanging Roots notes implied any tool gets you a drop - only shears actually collect either one, any other tool (even Silk Touch) destroys them for nothing.
- Azalea/Flowering Azalea Leaves notes said breaking them could drop a "sapling" - there's no such item for either; it's the azalea/flowering azalea item itself (or a stick) at a real 5% chance, not a vague "small chance." A leftover from the 1.0.2 copy-paste across all leaf types that didn't account for these two.
- Orange/Red/Yellow Poplar Leaves notes all said breaking them "drops nothing of interest" - that directly contradicted Poplar Sapling's own note, which (correctly) said it comes from poplar leaves. All three variants really do have a chance at a sapling or stick, like every other leaf type.
- Pressing B while the booklet was open didn't close it, because Minecraft only delivers keybind presses while no screen is open. The booklet screen now handles B itself, closing whenever the search box isn't focused. The booklet key also has a proper name and its own "VMR Booklet" category in Controls.
- Clicking a row in the Recent overlay changed the selection but left the overlay covering it, so nothing seemed to happen. The overlay now closes and the list scrolls to the item.
- Every config file (progress, favorites, activity, friends, settings, unknown-item log) is now written to a temporary file and moved into place, so a crash mid-save can no longer leave an empty file that loads as "no progress".
- Restoring a backup or doing a first /upload logged every item it filled in as "completed today", pushing all real history out of the activity log behind the Recent list. Changes that complete more than 20 items at once are now treated as an import and kept out of the activity log and session counter. The log is also saved once per change instead of once per item, and saves from different threads can no longer interleave.
- After dying or disconnecting with the booklet open, B could stop opening it until you ran /vbooklet. The booklet now always clears itself when another screen replaces it.
- The header's session line could draw over the "Last synced" text on narrower windows (e.g. 1366x768 at GUI scale 3). It now tightens its spacing, then truncates with "...".
- Escape closed the whole booklet even when an overlay or dropdown was open; it now closes that first.
- Home/End, Page Up/Page Down, and the A-Z letter rail all measured "how many rows are visible" against the list's raw height instead of the space actually left once the ingredient-filter banner is showing - while that banner was up, all four could land the scroll position a row off from where they should have.
- `BookletData` silently let a later item overwrite an earlier one if two ever shared the same display name once lowercased (no two currently do, but nothing would have warned if a future sheet update introduced one) - now logs a warning so a real collision doesn't go unnoticed.
- The full-panel overlays (help, stats, recent) didn't fully cover the detail panel: its text and bars stayed visible through the overlay. The list and detail panel are now not drawn at all while an overlay is open.
- Pearlescent/Verdant Froglight still had warm/cold frog types swapped (Pearlescent is warm-frog, Verdant is cold-frog) - a 1.0.2 pass already claimed to fix this exact swap, but got it backwards instead.
- Sniffer Egg's note only said "breeding two sniffers" with no mention of torchflower seeds being required, or the other real source - brushing suspicious sand in warm ocean ruins (~6.7% chance).
- Poisonous Potato's note only mentioned the potato-crop chance (and didn't give the number) - it's also found in shipwreck supply chests (~42% chance, 2-6 at once), and the crop chance is 2% specifically.
- Nudging an item's own Amount with the +/- buttons could deselect it entirely and close its detail panel, if that change walked it out of whatever Show filter was active (most noticeably: a required-1 item goes straight from Undiscovered to Completed on a single +click, vanishing from a "Show: Partial" or "Show: Undiscovered" view with no indication of what happened). The item stays selected now regardless of whether the active filter still matches it.
- The help overlay (the ? button) and the recently-completed overlay (the R button) used to just get cut off if their content didn't fit - the help overlay silently clipped past the bottom, and the recent overlay fell back to a flat "+N more not shown" with no way to actually see them. Both now scroll with the mouse wheel instead, same as the %-button stats overlay already did - this matters most at a high GUI scale, where a lot less fits on screen at once.
- Picking a category from its right-click dropdown didn't update the Category button's own label - it kept showing whatever it last said until the next left-click cycle.
- The item detail panel (recipe + "used in" + acquisition note) used to just get silently clipped if its content ran taller than the panel, with no way to see what got cut off - it now scrolls with the mouse wheel. The icon/name/progress header above it stays fixed in place.
- Large fern and tall grass acquisition notes wrongly implied bonemeal/natural growth gets you the item - breaking either (even with Silk Touch) never drops the plant itself, only shears-given short-grass/fern pairs or a small seed chance. The real survival source is village house chest loot (savanna for tall grass, taiga for large fern).
- Rabbit's Foot note only mentioned the rabbit kill drop - it's also obtainable from foxes, a tamed cat's post-sleep gift, husks riding camel husks, and Cleric villager trades.
- Goat Horn note said any goat ram, including goat-on-goat, drops one - it's actually only from ramming a specific set of blocks (stone, coal/copper/iron/emerald ore, logs, packed ice - not any ore block, gold/diamond/lapis/redstone included), plus a 50% chance from pillager outpost chests (regular variants only, never a screaming-goat sound).
- Copper Golem Statue's note had the freeze condition backwards - it said an *unoxidized* golem freezes into a statue, when it's actually the opposite: only a *fully oxidized* one does, at ~0.58% chance per tick. Also clarified that reviving one takes repeated axe hits to fully de-oxidize first, not a single hit.
- Armadillo Scute's note implied you need a brush to get one - armadillos actually shed a scute on their own every 5-10 minutes with no interaction at all; brushing just collects one on demand instead of waiting.
- Diamond Horse Armor's note was a vague "chests in villages, dungeons, etc." - named all 8 actual structures (ancient cities, dungeons, desert pyramids, nether fortresses, end cities, jungle temples, strongholds, village weaponsmith) like its Iron/Golden siblings already did.
- Enchanted Book's note buried its actual primary source (enchanting a plain Book at an enchanting table, same as enchanting gear) under confusing "by-product trades" wording that made it sound uncraftable entirely.
- Amethyst Cluster's note told you how to get Amethyst Shards instead of the cluster itself - mining it with a plain pickaxe gives shards, you actually need Silk Touch to keep the cluster block as an item.
- Pointed Dripstone's note said it needed a pickaxe - any tool works, a pickaxe is just faster.
- Moss Block's bonemeal note had the mechanic backwards - you bonemeal the moss block itself to spread it outward, not the stone/dirt block you want converted.
- Found one more of the circular-recipe gaps from earlier: the Netherite Upgrade Smithing Template had no acquisition note at all, only its duplication recipe (which needs a template to make more templates) - now explains the real bastion remnant source (guaranteed treasure chest, plus 10% in other bastion chests).
- The 5 new Village Map notes (Savanna/Desert/Plains/Snowy/Taiga) implied any cartographer could eventually roll any of them - which one a cartographer can offer is actually fixed by their OWN home biome, so each note now names the specific home biome(s) to look for.
- Music Disc Otherside's note wrongly said it's a creeper+skeleton kill drop like most other discs - it isn't, at all; it's chest loot only (monster rooms, stronghold altars, ancient cities). Mellohi and Wait's notes dropped a Bedrock-only buried-treasure mention that doesn't apply to this (Java-only) mod. The other half-dozen creeper-drop discs (11, Blocks, Chirp, Far, Mall, Stal, Strad, Ward) had a generic "also sometimes found in structure chest loot" line that isn't true for any of them - only 13 and Cat actually have a second (chest-loot) source.
- Totem of Undying's note said evokers "rarely" drop it - that's backwards, it's a guaranteed 100% drop, every time.
- Shulker Shell's note called its 50% drop chance "rare."
- Wither Skeleton Skull's note was missing its actual drop chance (2.5%, higher with Looting) and didn't mention a charged creeper explosion kill guarantees the skull instead.
- Creeper/Piglin/Skeleton/Zombie Head notes all said these "rarely" drop from a charged creeper explosion kill - that kill always drops the head (the only catch: if the blast kills several mobs at once, only one head drops total, from a random one of them).
- Nautilus Shell's note only mentioned fishing and a vague "rare" drowned kill - it's actually about whether a drowned spawned already holding one (~3% do, and those always drop it on death), plus a wandering trader sell and a fishing-treasure chance.
- Trident's note didn't mention only ~6.25% of drowned spawn holding one in the first place (that one then has an 8.5% drop chance on kill), or the trial chamber vault/reward chest source.
- Globe Banner Pattern's note wrongly said it could also be found in structure loot - it's Master-level Cartographer trade only, nothing else.
- Torchflower Seeds' note wrongly claimed a village/trail ruins chest-loot source that doesn't exist - the only real source is a sniffer finishing a dig (50% chance per dig).
- Piglin Banner Pattern and Bottle o' Enchanting notes were missing their actual drop/trade chances.
- Breeze Rod's note said "dropped" like a chance-based drop - breezes always drop 1-2 rods on death, guaranteed.
- Disc Fragment (for Music Disc 5)'s note didn't say how many fragments (9) are needed, or the ancient city chest's actual chance (~30%).
- The 4 Trail Ruins armor trim templates (Host, Raiser, Shaper, Wayfinder) wrongly said to brush "suspicious sand/gravel" - trail ruins only ever generate suspicious gravel, never sand.
- Eye Armor Trim Template's note was missing that a stronghold's library chest always has one (100%) - only the altar chest is a coin-flip-ish 10% chance.
- Vex Armor Trim Template's note didn't mention only the woodland mansion's random-loot chests can roll it - its fixed decorative/furniture chests never do.
- All 18 armor trim templates now state their real find chance where one applies (most were missing a number entirely).
- Rabbit's Foot's note listed "Cleric villagers buy them for emeralds" alongside its real acquisition sources - that's backwards, the Cleric buys feet FROM you, it's not a way to get one.
- Ocean Monument Map's note didn't say which cartographer level or price (Journeyman, 13 emeralds + a compass).
- Jungle Pyramid Map and Swamp Hut Map notes were missing the apprentice-level cartographer "Village Map" trade entirely - same mechanic as the other 5 village maps, just home-biome-gated to jungle/swamp cartographers respectively.
- Bee Nest's note said plains/forest/flower forest and didn't mention flowers are irrelevant to where nests generate - it also completely missed meadows, which guarantee one on every eligible tree (every other biome's chance is well under 5%).
- Honeycomb's note didn't say the nest/hive has to be completely full (honey level 5) first, that shearing it gives 3 at once, or that a dispenser can shear it without angering the bees at all.
- Turtle Egg's note didn't mention breeding needs seagrass, or that a lay is 1-4 eggs at once.
- Pale Hanging Moss's note implied any tool gets you a drop - only shears or Silk Touch actually drop the block, any other tool gets nothing. It and Open/Closed Eyeblossom were also missing their wandering trader sources.
- Pale Oak Sapling's note didn't give its actual drop chance (5%, higher with Fortune) or mention the wandering trader sale.

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
