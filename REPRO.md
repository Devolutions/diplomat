# Kotlin and JS finalizer-order repro

This branch ports the .NET finalizer-order fixture
(`experiment/dotnet-finalizer-edge-repro`, commit `095cbca6`) to the Kotlin
and JS backends. The backends themselves are unchanged.

It adds:

* `feature_tests/src/finalizer_order.rs` — the same Rust owned-borrowing
  fixture, gated `#[diplomat::attr(not(any(dotnet, kotlin, js)), disable)]`.
  The dependent's Rust `Drop` reads through its borrow of the source and the
  fixture counts dependents that dropped **after** their source
  (`bad_order_drops`). Each such drop is a native read through freed memory.
* regenerated Kotlin and JS bindings for the fixture;
* `feature_tests/js/repro/finalizer-order.mjs` — Node probe;
* `feature_tests/kotlin/somelib/src/test/kotlin/.../FinalizerOrderTest.kt` —
  JUnit probe.

Both generated wrappers carry the normal strong edge from dependent to source
(`aEdges = [this]` in JS, `aEdges = mutableListOf(this)` in Kotlin). Neither
probe ever calls any destructor explicitly — there is none to call.

## JS (Node 24.16.0 / V8, wasm32-unknown-unknown)

```sh
cargo make gen-js-feature
cargo make build-feature-wasm
cp target/wasm32-unknown-unknown/debug/diplomat_feature_tests.wasm feature_tests/js/api/
cd feature_tests/js
node --expose-gc repro/finalizer-order.mjs
```

Three runs, 100,000 pairs each:

```text
{"sourceDrops":100000,"dependentDrops":100000,"badOrderDrops":47000}
{"sourceDrops":100000,"dependentDrops":100000,"badOrderDrops":49000}
{"sourceDrops":100000,"dependentDrops":100000,"badOrderDrops":50000}
```

About half of all dependents ran their Rust `Drop` after their source was
already destroyed. No crash is possible in this configuration: wasm linear
memory is never unmapped, so the use-after-free reads recycled allocator
memory silently.

## Kotlin (JDK 21 Temurin, Gradle 8.10, JNA, Windows x64)

```sh
cargo make gen-kotlin-feature
cargo make build-feature-jvm
cp target/debug/diplomat_feature_tests.dll feature_tests/kotlin/somelib/
cd feature_tests/kotlin/somelib
gradle test --tests "dev.diplomattest.somelib.FinalizerOrderTest"
```

Three runs, 100,000 pairs each:

```text
FINALIZER_ORDER_PROBE sourceDrops=100000 dependentDrops=100000 badOrderDrops=656
FINALIZER_ORDER_PROBE sourceDrops=100000 dependentDrops=100000 badOrderDrops=805
FINALIZER_ORDER_PROBE sourceDrops=100000 dependentDrops=100000 badOrderDrops=615
```

Roughly 0.7% of dependents ran their Rust `Drop` after their source. The
reads went through freed native heap; the run did not happen to crash, but
the memory is genuinely freed (same class of UAF the .NET repro crashed on
with 0xC0000005).

## Conclusion

The finalizer-ordering hole demonstrated for .NET is not .NET-specific. The
generated edges make the source unreachable-only-after the dependent, but no
GC host orders the two native destructor calls once both are garbage:

| Backend | Cleanup mechanism | Bad-order drops per 100k (3 runs) |
| --- | --- | ---: |
| .NET | finalizer | crash (0xC0000005) |
| JS / V8 | per-type `FinalizationRegistry` | 47,000 / 49,000 / 50,000 |
| Kotlin / JVM | shared `java.lang.ref.Cleaner` | 656 / 805 / 615 |

The rate differs enormously by runtime, but the violation is reproducible on
all three. Only fixtures whose dependent `Drop` actually reads the borrow are
affected; the hazard is invisible for types with trivial drops.
