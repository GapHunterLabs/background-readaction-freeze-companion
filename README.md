# Background ReadAction Freeze Companion

Flags a background-thread entry point in your plugin's own code that can
reach a blocking, non-cancellable read-lock call -- the pattern JetBrains'
own Platform team has publicly named as a real, recurring cause of IDE
freezes, with no static tooling to catch it today.

## Why it exists

Not the usual "a paid competitor's users are complaining" story this
catalog is built around -- this one is motivated directly by JetBrains'
own Platform Blog:

- ["Investigating IntelliJ Platform UI Freezes"](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)
  (Sept 2025): detecting a freeze today is manual, post-mortem thread-dump
  analysis, after it already happened in a real user's IDE.
- ["UI Freezes and the Dangers of Non-Cancellable Read Actions in
  Background Threads"](https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/)
  (March 2026): *"many reports actually show problems in plugins that
  contain that single erroneous pattern"* -- a background thread calling
  `ReadAction.compute()`/`ReadAction.run()`/`Application#runReadAction()`,
  none of them cancellable, blocking the write lock and freezing the UI.
  The post gives a real code example and names a real plugin
  ("Package Checker") affected by it. It states plainly that there's no
  static tool for this today.

Verified independently (not just taken on the blog's word): neither the
official Plugin Verifier (bytecode compatibility, not locking semantics)
nor any of the 8 new Kotlin coroutine inspections shipped in IDEA
2025.2-2026.1 cover this pattern.

## Why built this way

Interprocedural, not just "grep for `ReadAction.compute`": a background
entry point rarely calls the sink directly, it delegates to a helper a
few calls deep. This plugin reuses the same real, from-scratch Tarjan's
SCC algorithm and whole-project fixed-point summary computation already
proven in this catalog's Log Injection Companion and Interprocedural
Resource Leak Companion, applied to a simpler reachability lattice (one
summary per method, not per parameter).

## Stated honestly -- v0.1 scope

- **Java PSI only.** No Kotlin coroutines (`Dispatchers.Default`/`IO`) --
  this catalog's interprocedural machinery has never been extended to
  Kotlin PSI. A real, declared limitation, not a silent gap.
- **Three background entry points, a closed list**: a `Task.Backgroundable`
  `run(ProgressIndicator)` override, a task passed to
  `Application#executeOnPooledThread(...)`, or a task submitted to
  `AppExecutorUtil`'s pooled executor (one hop of variable indirection
  resolved, no deeper alias tracking). Not `com.intellij.util.Alarm`,
  not reflection/extension-point invocation, not a custom `ExecutorService`.
- **Two sink tiers, verified against the real `intellij-community` source**,
  not assumed: Tier 1 (`ReadAction.compute()`/`run()`/`computeCancellable()`)
  is formally `@Deprecated`. Tier 2 (`Application#runReadAction(...)`) is
  **not** formally deprecated -- its own Javadoc says "Avoid using this
  method directly in applied/plugins code", but this plugin never calls
  it "deprecated". Getting this distinction right matters more here than
  in any other plugin in this catalog: the intended audience includes the
  people who wrote the real source.
- **No CHA, no points-to.** `call.resolveMethod()` resolves a virtual call
  to one candidate -- same caveat already accepted catalog-wide.
- **`checkCanceled()` downgrades, never suppresses.** A hit where the same
  method also calls `ProgressManager`/`ProgressIndicatorProvider`/
  `ProgressIndicator#checkCanceled()` is reported at lower severity, not
  hidden -- the write lock can still block until the next poll.
- A project with more than 3,000 analyzable methods skips analysis
  entirely rather than risk pathological cost.

## Usage

Install the plugin, open a Java-based IntelliJ plugin project. Any
`Task.Backgroundable.run()` override, `executeOnPooledThread(...)` task,
or `AppExecutorUtil`-submitted task that reaches a non-cancellable
ReadAction call is flagged inline, with the real call chain named in the
message.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us at
**gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
