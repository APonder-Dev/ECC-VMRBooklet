# Contributing

## Versioning

Releases follow `1.0.x`: the `1.0` line stays put for this Minecraft
version, and `x` bumps for every fix or small update. A jump to `1.1`+ (or a
new minor line) is reserved for a Minecraft version bump or a genuinely
bigger feature pass.

The version lives in `gradle.properties` (`version=`) - bump it there before
tagging a release.

## Releasing

Releases are automated (`.github/workflows/release.yml`) and publish
whatever jar `./gradlew build` produces to GitHub Releases - never done by
hand.

1. Bump `version=` in `gradle.properties`.
2. Add a `## [x.y.z]` section to `CHANGELOG.md` (above the previous version's
   section) describing what changed - this becomes the top of the release
   description; GitHub's own auto-generated commit list is appended below it
   automatically.
3. Commit both.
4. Tag and push: `git tag v1.0.1 && git push origin v1.0.1` - the tag must
   exactly match `gradle.properties`' version (prefixed with `v`), or the
   workflow fails before building anything.

The workflow then builds, verifies the tag/version match, and creates the
GitHub release with the jar attached.

## Bug reports / QA / feature requests

Use the issue templates - they ask for the mod version and Minecraft/Fabric
versions, which is almost always the first thing needed to reproduce
anything.

## Building

```
./gradlew build
```

Requires JDK 25. Output jar lands in `build/libs/`.
