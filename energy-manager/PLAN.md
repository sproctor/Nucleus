# Light Efficiency Mode — Implementation Plan

## Goal

Add `enableLightEfficiencyMode()` / `disableLightEfficiencyMode()` to provide a softer
alternative to the current (aggressive) efficiency mode. The light mode deprioritizes
CPU scheduling without throttling I/O or network.

## Per-platform behavior

| Platform | Light mode                          | Full mode (existing, unchanged)                  |
|----------|-------------------------------------|--------------------------------------------------|
| macOS    | `task_policy_set(TIER_5)` only      | `PRIO_DARWIN_BG` + `task_policy_set(TIER_5)`     |
| Windows  | EcoQoS only (no IDLE_PRIORITY)      | EcoQoS + `IDLE_PRIORITY_CLASS`                   |
| Linux    | `nice +10`                          | `nice +19` + ioprio IDLE + timer slack 100ms     |

## Scope

All three platforms (macOS, Windows, Linux) are implemented.

## TODO

- [x] 1. Add `enableLightEfficiencyMode()` / `disableLightEfficiencyMode()` to `PlatformEnergyManager` interface (default = unsupported)
- [x] 2. Add `nativeEnableLightEfficiencyMode()` / `nativeDisableLightEfficiencyMode()` to `NativeMacOsEnergyBridge`
- [x] 3. Implement native C functions in `nucleus_energy_manager.c` (macOS):
  - `nativeEnableLightEfficiencyMode`: `task_policy_set(TIER_5)` without `PRIO_DARWIN_BG`
  - `nativeDisableLightEfficiencyMode`: reset tiers to `UNSPECIFIED`
- [x] 4. Wire up in `MacOsEnergyManager`
- [x] 5. Expose in `EnergyManager` public API
- [x] 6. Add `withLightEfficiencyMode` suspend helper (mirrors `withEfficiencyMode`)
- [x] 7. Rebuild macOS native library (`build.sh`)
- [x] 8. Update docs (`docs/runtime/energy-manager.md`)
- [x] 9. Update example app to use light mode on focus-loss and full mode on minimize
