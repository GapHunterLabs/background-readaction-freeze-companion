<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Background ReadAction Freeze Companion Changelog

## [Unreleased]

## [0.3.0]

### Added

- Kotlin support: a second inspection reports Kotlin code that runs on a
  background thread and reaches a blocking read action. It follows calls
  into functions of the same file and works the same in K1 and K2 mode.
- New blocking calls, verified against the current IntelliJ Platform
  source: `ReadAction.computeBlocking()`/`runBlocking()`,
  `runReadActionBlocking {}`, the top-level `runReadAction {}` and
  `DumbService#runReadActionInSmartMode(...)`.
- New entry point: a method or function annotated
  `@RequiresBackgroundThread`.
- Each warning names the exact blocking call and quotes the reason that
  applies to it. `computeBlocking()` and `Application#runReadAction(...)`
  are never described as deprecated, because they are not.
- A description page for the inspection in **Settings | Editor |
  Inspections**, which showed "Under construction".

### Fixed

- `ReadAction.computeCancellable()` is no longer reported. It gives way to
  a pending write action (it throws `CannotReadException`), so it does not
  block the write lock. Earlier versions reported it by mistake.

### Changed

- The rating prompt's local counter keeps one-way fingerprints of findings
  instead of their file paths, and deletes the list that earlier versions
  kept.
- `PRIVACY.md` describes the values the plugin keeps in the IDE's local
  settings.

## [0.2.2]

### Fixed

- Review/star CTA now links to this plugin's own Marketplace
  reviews page instead of the vendor's generic plugin list.

## [0.2.1]

### Fixed

- The interprocedural fixed-point reachability computation (whole-project
  Tarjan-SCC + per-file scan) now checks for cancellation
  (`ProgressManager.checkCanceled()`) once per file and once per
  fixed-point iteration -- a large real project could previously block
  the read action uncancellably while the user kept typing. The same
  kind of gap this plugin's own analysis is built to catch, found
  missing in this plugin's own engine during an internal review.

## [0.2.0]

### Added

- S4: `BackgroundTaskUtil#executeOnPooledThread(...)` as a recognized
  background-thread entry point, found via manual validation against
  real `intellij-community` code (`plugins/hg4idea`) -- the original
  3-entry list proved incomplete against the first real sample checked.

## [0.1.0]

### Added

- Flags a `Task.Backgroundable.run()` override, an
  `Application#executeOnPooledThread(...)` task, or a task submitted to
  `AppExecutorUtil`'s pooled executor that can reach a blocking,
  non-cancellable read-lock call (`ReadAction.compute()`/`run()`/
  `computeCancellable()`, or `Application#runReadAction(...)`) -- either
  directly or through a chain of helper methods, named in the message.
- Distinguishes formally deprecated sinks from mechanically identical but
  not-yet-deprecated ones, rather than over-claiming.
- Downgrades, rather than suppresses, a hit where the same method also
  checks for cancellation explicitly.

[Unreleased]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.3.0...HEAD
[0.3.0]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.2.2...0.3.0
[0.2.2]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/commits/0.1.0
