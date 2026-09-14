# No-RC .NET implementation brief

Read [DECISIONS.md](../DECISIONS.md) first. Implement in this worktree only.

## Goal and completion boundary

Make ownership and borrowing readable as separate concerns: native resources have one owning handle; managed edges keep sources reachable; a borrow ledger enforces Rust access rules without keeping native resources alive through reference counting. A .NET method is rejected when its returned value retains a borrow from a `manually_disposable` opaque source.

The work is complete when templates, generator, generated C# for feature tests and examples, relevant docs, and authored tests agree on this contract and compile. Do not report tests as passing: execution was not requested. Keep a short implementation report with exact checks and unverified limits.

## Research: verified baseline

These observations are from `main` at `6610ee15`, not the experimental SafeHandle branch. Recheck line numbers after edits.

- [The baseline handle](../tool/templates/dotnet/RustHandle.cs.jinja) has `_refCount`, `Retain`, `DependencyToken`, and `Decrement`. Edges are `object[]` and cleanup disposes every `IDisposable` edge indiscriminately. Replace that lifetime ownership model, not just the counter names.
- [The opaque template](../tool/templates/dotnet/opaque.impl.cs.jinja) owns cleanup in the public wrapper/finalizer, passes pointers through `AsFFI`, and emits `DiplomatRetainDependency`. Move finalization responsibility and dependencies to the owning handle. A dependent must retain the handle, not force the public wrapper to survive.
- [The method generator](../tool/src/dotnet/gen/method.rs) uses the common borrowing visitor to select source edges and creates opaque/span/error outputs. Audit receiver and named parameters, optional sources, multiple/duplicate sources, success/error returns, and properties. Retained source validity must be transitive.
- The new source restriction belongs after that shared edge analysis: inspect the resolved HIR type for the receiver and every named parameter, then reject only retained `OpaqueParam` edges whose source is `manually_disposable`. Temporary borrows and independent results remain valid.
- [Borrowed span](../tool/templates/dotnet/DiplomatBorrowedSpan.cs.jinja) currently dereferences a raw pointer in `Clone` and `WithSpan` without source-validity checks. It deliberately does not expose a naked span property. Preserve that boundary; validate before raw access and keep storage alive through callbacks, including exceptional exits.
- [The bridge macro](../macro/src/lib.rs), `gen_bridge`, generates each opaque destructor as an `extern "C"` function taking `Box<T>`. [AST module collection](../core/src/ast/modules.rs) only uses inherent impls; current HIR does not record custom `Drop` impls. An empty generated `impl Drop for Opaque` lets Rust reject competing manual impls even outside the bridge. This is an intentional policy implementation, not a placeholder.
- [Existing lifetime fixtures](../feature_tests/src/lifetimes.rs) have out-of-bridge opaque `Drop` impls and tests expecting deferred source release. They need deliberate migration. Counter-only owned field helpers can observe destruction without accessing borrowed parents. Do not move a forbidden parent-reading destructor into a field and call that safe.
- [Thread policy](../book/src/safety.md#L56-L64) permits per-backend conventions. Do not import the SafeHandle branch's concurrent Dispose safety promise.
- [Rust destruction rules](https://doc.rust-lang.org/reference/destructors.html#r-destructors.operation) include field teardown even without custom outer `Drop`. Record the no-parent-access rule as a binding-author precondition; no complete static field-safety analysis is requested.

## Reference implementation: borrow checks only

Read `D:\diplomat` at `98b1ed09` for reference, without editing it. Relevant files:

- `tool/templates/dotnet/RustHandle.cs.jinja`: `BorrowLedger`, shared/exclusive state, version and scope invalidation.
- `tool/templates/dotnet/BorrowLease.cs.jinja`: scoped release and versioned source references. Its `DangerousAddRef`/`DangerousRelease` ownership semantics are NOT the target design.
- `tool/src/dotnet/gen/method.rs`, `method_body.cs.jinja`, `property.cs.jinja`: mutability-aware receiver/parameter guards and result construction.
- `feature_tests/dotnet/Tests/RuntimeBorrowSafetyTests.cs`: useful access-rule cases; concurrent tests are not acceptance criteria for this worktree.

Port concepts selectively. Do not cherry-pick that branch or copy its runtime and keep the unwanted ownership model under a new name.

## Implementation slices

### 1. Single-owner handle and source edges

- Prefer a small handle interface for validity checks and source edges, and one generic resource owner for pointer/destructor. Names should express ownership, borrowed access, and version checks directly.
- Owned handle disposal/finalization runs its own destructor at most once, then releases owned lifetime resources such as pins. Non-owning views never destroy their source pointer.
- A source edge holds an ordinary managed reference. Releasing a child edge releases borrow bookkeeping if needed, never disposes the source owner. Distinguish owned pin resources from borrowed source references explicitly.
- Public wrapper disposal closes that wrapper's own resource. The generator does not produce retained-borrow edges from a manually disposable source, so source disposal is not a public parent-invalidation contract.
- A live child keeps its source handle reachable for GC even if the source wrapper is collected. No native resource reference counting may be added to achieve this.
- Retain existing normal-call GC lifetime protections. Removing application-thread guarantees does not make GC/finalizer liveness optional. Use strong managed references through the entire native call/callback with `finally`/`GC.KeepAlive` as needed.

### 2. Mutability independent of ownership

- Keep shared/exclusive access rules and mutation versions. Shared views revalidate sources; owned borrowing values and exclusive views must not permit mutation that violates their retained Rust reference. Reuse established distinctions rather than treating every edge the same.
- A manually disposable source may still use temporary `&self`, `&mut self`, and opaque parameter borrows when the return does not retain them. A manually disposable result may borrow from a non-disposable source.
- A same-thread callback can call Dispose or attempt mutation while `WithSpan` is active. Keep that case separate from cross-thread safety. Reject unsafe reentrant disposal before changing state rather than releasing a pointer still exposed to the callback. A transient active-call guard is not ownership RC and must not defer destruction until child collection.
- Ordinary finalizer bookkeeping still occurs on the runtime's finalizer thread. Keep any atomics needed for exactly-once cleanup or finalizer interactions, without advertising general thread safety or recreating a native refcount.
- Acquire all parameter/source guards inside a cleanup scope; a later invalid parameter must release guards already acquired. Optional/duplicate edges and transitive views must not poison counters after errors or disposal.

### 3. Enforce the top-level Drop restriction

- Emit an empty `impl Drop` for each opaque in the bridge macro, using the same cfg/lifetime handling as its destructor. Rust coherence should reject any manual impl for that opaque, whether inside or outside the bridge. Write compile-fail coverage and check macro output.
- This policy is global to opaque types in this experiment because the macro has no selected target backend. Document the intentional compatibility change. Update only affected fixtures/examples and macro snapshots, not unrelated backend code.
- Owned fields may still have ordinary destructors. They must obey the no-access-to-borrowed-parent precondition. Counter guards for tests must not contain borrowed parent pointers.
- Generated Drop also prevents some field moves/Copy impls; inspect and resolve concrete compilation failures without broad API redesign. If necessary, use a narrow existing conversion/access pattern and document the compatibility limit.

### 4. Generator and docs

- Edit templates and generator sources first, then regenerate .NET feature/example output with this worktree's tool. Do not hand-edit generated C# as the only implementation.
- The diagnostic must name the method, source parameter/receiver, and source type, and suggest an independent result or removing `manually_disposable`.
- Preserve unsupported-type diagnostics, including borrowed references nested in unsupported structs. Do not add new struct-lifetime support as a side project.
- Preserve explicit copying versus zero-copy semantics and optional/fallible rollback. Oversized span construction must reject invalid lengths before dereference and clean up acquired resources.
- Replace deferred-native-release documentation with the new disposal and caller-synchronization contract. Explain the field teardown precondition honestly. Keep comments short and about why code exists.

## Tests to implement (do not execute)

Use the generated public interface for lifetime behavior. Use generator assertions/compile-fail tests only for generation policy, not as a substitute for runtime tests.

1. Generator rejection: cover receiver and named sources, optional and keyword-named parameters, lifetime bounds, multiple sources, borrowed opaque/slice returns, owned borrowing children, and owned success/error/optional paths.
2. Generator acceptance: keep temporary `&self`/`&mut self` and opaque parameter calls, primitive/value outputs, independent owned outputs, and a manually disposable result borrowing from a non-disposable source.
3. GC: a live child retains an undisposed source handle after its public wrapper dies; once all wrappers/children die, both native resources clean up. Do not demand parent/child finalizer order.
4. Pin: legal non-disposable sources keep their input pin alive through a dependent, and dependent cleanup or finalization releases it. Observe weak buffer collection only from a `NoInlining` helper.
5. Mutability: shared/exclusive conflicts, retained exclusive views, mutation versions, and same-thread span callback guards remain covered. Do not turn the source restriction into a universal `opaque_mut` ban.
6. Disposal: keep explicit `IDisposable` coverage for independent resources and legal disposable results; do not assert parent disposal with a retained child.
7. Macro: custom opaque Drop rejected inside and outside bridge; lifetimes/cfg handled; owned field destructor allowed. Reuse existing testing libraries or add a small established compile-test harness only if needed.
8. Existing incompatible RC expectations: rewrite the asserted contract rather than disabling failing tests. Do not weaken unrelated tests.

## Validation and isolation

- Use PowerShell commands serially, with this worktree as cwd. Worktree-local build output; do not reuse another worktree's native fixture.
- Generation: `cargo make gen-dotnet-feature` and `cargo make gen-dotnet-example` (confirm task names). The repo generator may build its executable as part of generation.
- Rust static checks: focused `cargo check` including tests for touched packages, plus `cargo fmt --all -- --check` and focused clippy where feasible. Do not execute `cargo test` or `dotnet test`.
- Compile the .NET feature project for its configured net8.0 and net48 targets without running test methods. Treat existing unrelated diagnostics separately. Do not claim this is runtime validation.
- Avoid loading test DLLs into persistent PowerShell: it locks output files. Verify final diff, generated/template agreement, and `git diff --check`. Report authored tests as NOT RUN.
- Keep the implementation simple. No dependency-injection framework, generalized collector, reverse child registry, graph-cycle detector, production telemetry, broad formatting churn, commits, pushes, or external review agents.
