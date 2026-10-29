# Changelog

All notable changes to VMR Booklet are documented here, one section per
release. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Each section heading must be `## [x.y.z]` exactly (matching the git tag
`vx.y.z`) - the release workflow extracts the section between two `## [`
headings and uses it as that release's description.

## [Unreleased]

- (nothing yet)

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
