# ComposeDeskKit (Nucleus)

A multi-module Gradle plugin and runtime library toolkit for shipping production-ready JVM desktop applications on macOS, Windows, and Linux.

## Project Structure

- `core-runtime` - Executable type detection, single instance, deep links, platform detection
- `aot-runtime` - AOT cache mode detection for JDK 25+ (Project Leyden)
- `updater-runtime` - Auto-update engine (GitHub/S3), SHA-512 verification, progress tracking
- `darkmode-detector` - Reactive OS dark mode detection via JNI
- `system-color` - Reactive system accent color and high contrast detection via JNI
- `energy-manager` - Energy efficiency & screen-awake APIs
- `native-ssl` / `native-http` / `native-http-okhttp` / `native-http-ktor` - OS trust store integration
- `linux-hidpi` - Native HiDPI scale detection on Linux
- `graalvm-runtime` - GraalVM native-image bootstrap
- `decorated-window-core` / `-jbr` / `-jni` / `-jewel` / `-material2` / `-material3` - Custom window decorations
- `plugin-build/plugin` - Gradle plugin for packaging & distribution
- `example` / `jewel-sample` / `sample-cmp` - Sample applications

## Build & Run

```bash
./gradlew run                                    # Run example app
./gradlew packageDistributionForCurrentOS        # Package for current OS
./gradlew packageReleaseDistributionForCurrentOS # Release build with ProGuard
./gradlew preMerge                               # Full CI verification
./gradlew reformatAll                            # Format all code
```

## Key Technologies

- Kotlin 2.0+ with Compose Desktop
- JNA/JNA-Platform for native interop (JNI bridges for macOS, Windows, Linux)
- JBR (JetBrains Runtime) API
- Gradle 8+ with version catalog (`gradle/libs.versions.toml`)
- Detekt + KtLint for code quality

## Development Notes

- Target: JDK 17+ runtime, JDK 25+ recommended for AOT
- JNI code: be careful with macOS ARC/retain and weak references (recent crash fixes in PRs #97–#101)
- Native modules use platform-specific JNI implementations — test on each OS
- Plugin is published via included build in `plugin-build/`
- Version catalog is the source of truth for all dependency versions
