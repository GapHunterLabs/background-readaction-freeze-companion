<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Background ReadAction Freeze Companion Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/compare/0.1.0...HEAD
[0.1.0]: https://github.com/GapHunterLabs/background-readaction-freeze-companion/commits/0.1.0
