# Spell Check

Nucleus does not ship a spell-checking module. The ecosystem already has a native cross-platform solution for Kotlin.

## Recommended: PlatformSpellCheckerKt

[**PlatformSpellCheckerKt**](https://github.com/Wavesonics/PlatformSpellCheckerKt) by [@Wavesonics](https://github.com/Wavesonics) — a Kotlin Multiplatform wrapper over the native OS spell-check engines.

```kotlin
dependencies {
    implementation("com.darkrockstudios:platform-spellchecker:<version>")
}
```

```kotlin
val checker = PlatformSpellChecker()

val misspellings = checker.check("This is a sentance with errors")
// → [Misspelling(word = "sentance", suggestions = ["sentence", ...])]
```

### What it covers

- macOS — `NSSpellChecker` (the same engine as TextEdit and the system text fields)
- Windows — Windows Spell Check API (`ISpellChecker`, available since Windows 8)
- Linux — `enchant` / `hunspell`
- Suggestions, language detection, and custom dictionary support

### Why Nucleus doesn't ship this

Spell checking is a deep, locale-heavy domain that benefits from a focused, well-tested library. PlatformSpellCheckerKt already wraps the right native engines and has no JVM-specific limitations that Nucleus would need to work around.
