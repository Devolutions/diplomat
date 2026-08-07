// Finalizer-order probe for the JS (wasm) backend.
// Run: node --expose-gc repro/finalizer-order.mjs
//
// Creates source/dependent pairs where the dependent's Rust Drop reads
// through its borrow of the source. Drops all managed refs, forces GC,
// and yields so FinalizationRegistry cleanup callbacks run. The Rust
// fixture counts how many dependents dropped AFTER their source
// (badOrderDrops) — each one is a native use-after-free read.

import { FinalizerOrderSource } from "../api/index.mjs";

if (typeof global.gc !== "function") {
    console.error("run with --expose-gc");
    process.exit(2);
}

const BATCHES = 200;
const PAIRS_PER_BATCH = 500;

const asNum = (v) => (typeof v === "bigint" ? Number(v) : v);
const probe = () => ({
    sourceDrops: asNum(FinalizerOrderSource.sourceDrops()),
    dependentDrops: asNum(FinalizerOrderSource.dependentDrops()),
    badOrderDrops: asNum(FinalizerOrderSource.badOrderDrops()),
});

FinalizerOrderSource.resetProbe();

function churn() {
    for (let i = 0; i < PAIRS_PER_BATCH; i++) {
        const source = FinalizerOrderSource.create();
        const dep = source.makeDependent();
        if (!dep.readsSource()) {
            throw new Error("fixture wiring broken");
        }
    }
}

for (let batch = 0; batch < BATCHES; batch++) {
    churn();
    global.gc();
    await new Promise((res) => setTimeout(res, 0));
}

// Drain: keep collecting until the counters stop moving.
let last = -1;
for (let i = 0; i < 50; i++) {
    global.gc();
    await new Promise((res) => setTimeout(res, 10));
    const { sourceDrops, dependentDrops } = probe();
    const now = sourceDrops + dependentDrops;
    if (now === last && now > 0) break;
    last = now;
}

const result = probe();
result.totalPairs = BATCHES * PAIRS_PER_BATCH;
console.log(JSON.stringify(result));
process.exit(result.badOrderDrops > 0 ? 1 : 0);
