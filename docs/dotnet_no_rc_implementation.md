# .NET No-RC Implementation Report

## Scope

- Branch: `experiment/dotnet-no-rc`
- Base: `main` at `6610ee15c22ef6fbb2d70ea2c4444e0d6ff9f820`
- Worktree: `D:\diplomat-dotnet-no-rc`
- The requested `GPT-5.6 Luna Max` label was unavailable; this pass used the available Luna agent. No other agent or external AI was delegated in this pass.

## Implemented

- Replaced native lifetime reference counting with one `RustHandle<T>` owner, managed source-handle edges, borrow ledgers, mutation versions, and active-operation guards.
- Added scoped opaque receiver and parameter leases with rollback-safe `try/finally` cleanup. Returned opaque and span values move only the leases they own.
- Made borrowed opaque views validate source mutation and edge state. Shared views use versioned edges; exclusive views hold and release the source borrow.
- Rejected .NET methods whose success, error, or optional return edges retain a borrow from a `manually_disposable` receiver or opaque parameter. The diagnostic names the method, source, and source type and suggests an independent result or removing the attribute.
- Made borrowed spans validate source edges before `WithSpan` and `Clone`, reject same-thread reentrant source mutation or disposal during callbacks, and release edges through `Dispose` or finalization.
- Generated an empty `Drop` implementation for every bridge opaque, including its lifetimes and `cfg` attributes.
- Replaced manual opaque `Drop` fixtures with owned `DropRecord` fields. The pinned fixture stores its checksum before crossing the binding boundary.
- Regenerated feature and example .NET bindings.
- Migrated retained-borrow sources to finalizer-only surfaces and removed parent-`Dispose` acceptance tests. Added generator rejection/acceptance coverage and kept public tests for exclusive release, mutation invalidation, span reentrancy, exception cleanup, source-handle reachability, pin cleanup, and eventual finalizer cleanup.
- Isolated process-global lifetime probes in one non-parallelized collection. The shared test helper drains pending finalizers before every counter reset, and no-inline helpers return only weak references for later GC observations. The duplicate pin-disposal cases are consolidated, and the eventual-cleanup test has an accurate name.
- Added positive generator cases for `&mut self` and temporary `&mut` opaque parameters, plus rejection cases for owned-borrowing `Result` success and accessor getter returns. Positive cases assert generated method surfaces. No binding regeneration was needed because this follow-up changed only authored tests, generator unit-test inputs, and a non-doc fixture comment.
- Added two trybuild compile-fail cases for manual opaque `Drop` inside and outside a bridge. The harness compiles; it was not executed, so `.stderr` outputs were not generated.
- Updated the .NET backend book section, generator comments, templates, plan, and report for the retained-borrow restriction.

## Verified Call Chains

- Retained-borrow rejection: `tool/src/lib.rs::gen` -> `tool/src/dotnet::run` -> `tool/src/dotnet/gen/mod.rs::ItemGenContext::render_all_types` -> `prepare_type` -> `build_members` -> `tool/src/dotnet/gen/method.rs::build_method_info` -> `borrowed_output_keep_alive_edges` -> `output_keep_alive_edges` -> `reject_manually_disposable_borrows`; `ErrorStore::take_all` then prevents file emission.
- Legal borrowed view: `feature_tests/dotnet/Generated/OpaqueThinVec.cs` `First` -> `Handle.Lease` -> `RustHandle.Lease`/`AcquireOperation` -> raw FFI call -> lease release. The managed edge keeps the non-disposable source handle reachable; no native retain counter is involved.
- Span access: `feature_tests/dotnet/Generated/DiplomatBorrowedSpan.cs:64` `WithSpan` -> `feature_tests/dotnet/Generated/DiplomatBorrowedSpan.cs:70` `AcquireLeases` -> `feature_tests/dotnet/Generated/RustHandle.cs:70` dependency operation leases -> `feature_tests/dotnet/Generated/BorrowLease.cs:114` version check -> `feature_tests/dotnet/Generated/DiplomatBorrowedSpan.cs:73` callback -> `feature_tests/dotnet/Generated/DiplomatBorrowedSpan.cs:75` release in `finally`.
- Macro policy: `macro/src/lib.rs:488` `gen_bridge` -> `macro/src/lib.rs:631` generated opaque `Drop`; owned fixture observation uses `feature_tests/src/lifetimes.rs:1` `DropRecord` and `feature_tests/src/lifetimes.rs:41` its field destructor.

## Final validation

Rust commands used `CARGO_TARGET_DIR=D:\diplomat-dotnet-no-rc\target`.

| Command | Status |
| --- | --- |
| `cargo make gen-dotnet-feature` | 0 |
| `cargo make gen-dotnet-example` | 0 |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net8.0` | 0; warning for absent native DLL |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net48` | 0; warning for absent native DLL |
| `cargo fmt --all -- --check` | 0 |
| `cargo check --manifest-path tool/Cargo.toml --tests` | 0 |
| `cargo check --manifest-path feature_tests/Cargo.toml --tests` | 0; existing deprecated-fixture warning |
| `cargo clippy --manifest-path tool/Cargo.toml --tests` | 0 |
| `cargo clippy --manifest-path feature_tests/Cargo.toml --tests` | 0; existing deprecated-fixture warning |
| `git diff --check` | 0 |
| VS Code diagnostics for requested Rust/C# paths | no errors |

### Bounded follow-up validation (2026-09-14)

The follow-up used `CARGO_TARGET_DIR=D:\diplomat-dotnet-no-rc\target` and compile/check commands only.

| Command | Status |
| --- | --- |
| `cargo fmt --all -- --check` | 0 |
| `cargo check -p diplomat-tool --tests` | 0; Windows incremental-directory cleanup warning only |
| `cargo clippy -p diplomat-tool --tests` | 0; Windows incremental-directory cleanup warning only |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net8.0` | 0; warning for absent native DLL |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net48` | 0; warning for absent native DLL |
| `git diff --check` | 0 |
| VS Code diagnostics for the five touched paths | no errors |

### Final cleanup validation (2026-09-14)

After the final assertion and token-indentation cleanup, the compile and check commands above were rerun successfully. The builds retained the missing-native-DLL warning, and Cargo retained only Windows incremental-directory cleanup warnings. No generation or test runner was used.

No generation command was needed for this follow-up. The authored generator cases and C# tests were compiled only; no test runner was invoked.

The first generation attempt correctly failed on two remaining disposable-source fixtures; after migrating them, both final generation commands exited 0. Earlier C# compile failures were stale authored calls found and repaired during migration. The final C# builds only checked compilation; they did not load a native DLL.

## Tests Not Run

- No `cargo test`, `dotnet test`, trybuild execution, or runtime/e2e test was run.
- The authored generator and public C# tests were compiled only; their methods were not executed.
- The compile-fail harness contains 2 UI cases and was only compiled through `cargo check -p diplomat --tests`.

## Preconditions and Limits

- Callers must synchronize calls and disposal across dependency chains. The branch does not promise cross-thread call/dispose safety.
- Opaque field destructors must not access borrowed parent storage. The generated outer `Drop` does not statically enforce that field-level rule.
- Generation rejects retained-borrow edges from manually disposable sources. This rule does not solve all GC, pin, or finalization cycles.
- Runtime behavior remains unverified until the native feature library is built and the authored tests are explicitly run.
- The trybuild `.stderr` baselines still need to be produced by a later authorized test run.

## Review fixes (2026-09-14, second pass)

Rulings for this pass are in `DECISIONS.md` ("Owned borrowing children follow the read-view rule").

### Changed

- **Owned children of a shared borrow are read views.** `LifetimeEdges` now turns a shared
  source lease into a versioned edge for `WrapperKind.Owned` as well (`IsReadView`), and
  `IBorrowLease` exposes `Kind`. A `Box<T<'a>>` returned from `&'a self` no longer blocks the
  source; the next `&mut` call on the source invalidates it. Values born from an exclusive
  borrow (`&'a mut T` views and owned children of `&'a mut self`/`&'a mut` parameters) keep
  the borrow until released.
- **Generator rule for exclusive-origin returns.** `exclusive_borrow_sources` and
  `reject_undisposable_exclusive_borrows` in `tool/src/dotnet/gen/method.rs` reject a returned
  opaque (Ok or Err arm) that keeps an exclusive borrow unless it is `manually_disposable`,
  so `Dispose()` can end the borrow deterministically.
- **Fixtures.** Removed `OpaqueThinVec::borrow_mut` (it would have required a borrowed-from
  type to be disposable). Added `ExclusiveSource`, `ExclusiveView`, and `ExclusiveWriter`.
- **Tests.** `RuntimeBorrowSafetyTests` no longer waits for GC to release a borrow: it asserts
  read-view invalidation for `Iter()` and `Dispose()`-scoped blocking for exclusive returns.
  `GcRaceTests` has its GC-pressure reproducer back. Three generator tests that still expected
  a wrapper finalizer (`~Plain()`, `~FinalizerOnly()`, `~Manual()`) now assert the opposite.
  Six generator tests cover the new rule and the runtime read-view conversion.
- **Templates.** The duplicate disposed pre-check in opaque methods and properties is gone; the
  `Handle` getter is the single check. `Dispose()` documents the same-thread active-operation
  throw. The unread `is_opaque` template field was removed.
- **Docs.** `book/src/backends/dotnet.md` describes read views versus exclusive-origin values,
  the `manually_disposable` requirement, GC-timed unpin for borrowed-from pinning types, and
  the `Dispose()` throw. `book/src/types/opaque.md` gained a "Destructors" section covering the
  generated `impl Drop` and its consequences (E0119, E0184, E0509).

### Deliberately unchanged

- Base commit `6610ee15`; moving onto current upstream `main` needs a commit and rebase.
- Rule 4 (pinning returns must be disposable) is not restored; it would reject IronVNC's
  `SecurityListV37::decode` + `get_failure` pair under the disposable-source rule.
- The bridge-macro empty `impl Drop` stays, documented in the opaque chapter. It affects every
  backend and needs its own upstream discussion; it should not ship inside a .NET-only change.

### Validation (compile/check only)

Rust commands used `CARGO_TARGET_DIR=D:\diplomat-dotnet-no-rc\target`.

| Command | Status |
| --- | --- |
| `cargo make gen-dotnet-feature` | 0 |
| `cargo make gen-dotnet-example` | 0 |
| `cargo fmt --all -- --check` | 0 |
| `cargo check -p diplomat-tool --tests` | 0; no warnings |
| `cargo check -p diplomat-feature-tests` | 0; pre-existing deprecated-fixture warning |
| `cargo clippy -p diplomat-tool --tests` | 0; no warnings |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net8.0` | 0; absent native DLL warning only |
| `dotnet build feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net48` | 0; absent native DLL warning only |
| `git diff --check` | 0 |

### Test run (2026-09-14, after the review fixes)

| Command | Result |
| --- | --- |
| `TRYBUILD=overwrite cargo test -p diplomat --test opaque_drop` | 1 passed; wrote `macro/tests/ui/*.stderr` |
| `cargo test -p diplomat` | 16 + 1 passed |
| `cargo test -p diplomat-tool` | 105 passed after fixing ten stale generator tests (`AsFFI()` call shape, `Handle` getter counted as a property getter, explicit edge array in the error path, `&mut` fixtures missing `opaque_mut`, an `r#type` parameter HIR cannot lower) |
| `cargo build -p diplomat-feature-tests` | 0 |
| `dotnet test feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net8.0` | 120 passed |
| `dotnet test feature_tests/dotnet/Somelib.FeatureTests.csproj -c Debug --no-restore -f net48` | 120 passed |
