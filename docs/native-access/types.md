# Supported Types

## Type Mapping

| Type | As param | As return | As property | Notes |
|------|----------|-----------|-------------|-------|
| `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short` | ✅ | ✅ | ✅ | Direct pass-through |
| `String` | ✅ | ✅ | ✅ | UTF-8 output-buffer pattern |
| `ByteArray` | ✅ | ✅ | — | Pointer + size; suspend, callbacks, DC fields, collections |
| `enum class` | ✅ | ✅ | ✅ | Ordinal mapping |
| `data class` | ✅ | ✅ | ✅ | Fields: primitives, String, ByteArray, Enum, Object, nested DC, List, Set, Map |
| `Object` (class instances) | ✅ | ✅ | ✅ | Opaque `StableRef` handle, lifecycle tracked |
| Nested classes | ✅ | ✅ | ✅ | Exported as `Outer_Inner`, up to 3+ nesting levels |
| `T?` (nullable) | ✅ | ✅ | ✅ | Sentinel-based null encoding |
| `List<T>`, `Set<T>` | ✅ | ✅ | ✅ | All element types incl. DataClass, ByteArray, nested collections |
| `Map<K, V>` | ✅ | ✅ | ✅ | Parallel key + value arrays |
| `List<List<T>>` | ✅ | ✅ | — | Nested collections via StableRef handles |
| `(T) -> R` (lambda) | ✅ | ✅ | — | FFM upcall/downcall stubs; nullable `((T) -> R)?` supported |
| `suspend fun` | — | ✅ | — | All return types: primitives, String, ByteArray, DataClass, List, Set, Map |
| `Flow<T>` | — | ✅ | — | All element types: primitives, String, ByteArray, DataClass, List, Set, Map |

## Declarations

| Feature | Notes |
|---------|-------|
| Classes | `StableRef` lifecycle, `AutoCloseable` on JVM |
| Open / abstract classes | `open class Shape` → JVM `open class Shape`, hierarchy mirrored |
| Inheritance | `class Circle : Shape` → JVM `class Circle : Shape(handle)`, multi-level (3+) |
| Interfaces | `interface Measurable` → JVM `interface Measurable`, multi-interface impl |
| Sealed classes | `sealed class Result` → JVM `sealed class`, subclass ordinal bridges |
| Extension functions | `fun Shape.displayName()` → real Kotlin extension on JVM proxy |
| Constructor `val`/`var` params | Exposed as properties with getters (and setters for `var`) |
| Companion objects | Static methods and properties on JVM proxy |
| Top-level functions | Grouped into a singleton `object` on JVM |

## Not yet supported

| Feature | Notes |
|---------|-------|
| Generics (`class Box<T>`) | Use concrete types or collections |
| Interface / sealed class as return type | Methods must return the concrete type |
| Operator overloading, infix functions | Use named methods |
| `ByteArray` in collections / data class fields / callback params | Use `List<Int>` or Base64 String |
| Subclassing from JVM | Subclass on native side instead |
| CInterop types in public API (`CPointer`, etc.) | Wrap behind a clean Kotlin API |
