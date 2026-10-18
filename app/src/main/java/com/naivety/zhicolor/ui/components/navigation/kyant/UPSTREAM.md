# Kyant liquid bottom tabs

Official source: Kyant0/AndroidLiquidGlass, tag 1.0.6, commit `896a94a3ade1cc1a940b92365f942a34971fecda`.
Copyright 2025 Kyant. Licensed under Apache License 2.0; the unmodified license is included in `LICENSE`.

The five Kotlin sources originate from the official `catalog` components and utilities. `UPSTREAM.json` records their original SHA-256 values, adapted SHA-256 values and changes. The complete project manifest is `docs/upstream/kyant-ui.json`.

The original lens, vibrancy, blur, invisible content-sampling layer, combined backdrop, highlights, shadows, Capsule shape, press scales, velocity deformation and spring specifications remain in the component chain. The default panel remains 64 dp with a 56 dp indicator.

Project adaptations supply Miuix colors, original project icons and four routes in `AppNavigationBar.kt`. The adapted component handles external page synchronization without echoing callbacks, restores the current page when a drag cancels, resets geometry-dependent gesture state on size/density/layout-direction changes, exposes tab selection semantics, and cancels stale release jobs after a fresh press. Large font settings may increase the panel height while keeping the same insets and proportions.

The route adapter dispatches every valid tab selection, including the currently displayed page, so the host can cancel an unfinished transition and honor the latest selection. External pager updates do not interrupt an active indicator drag. A completed drag submits its target to the host; cancellation restores the host's current page without emitting a selection callback. Queued programmatic indicator animations also yield if a new drag has started before they execute.

The app declares `io.github.kyant0:backdrop:1.0.6` and `io.github.kyant0:shapes:1.2.0` directly. The latter provides the official `com.kyant.shapes.Capsule` API used by these examples; Backdrop's runtime-transitive dependency alone does not expose it to the app's compile classpath.
