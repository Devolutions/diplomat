package dev.diplomattest.somelib

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// Probe: the generated aEdges keep the source wrapper REACHABLE while the
// dependent is reachable, but once both are garbage nothing orders their
// Cleaner actions. The Rust fixture counts dependents whose Drop ran after
// their source's Drop (each such drop reads through a freed borrow).
class FinalizerOrderTest {

    private fun churn(pairs: Int) {
        for (i in 0 until pairs) {
            val source = FinalizerOrderSource.create()
            val dep = source.makeDependent()
            check(dep.readsSource())
        }
    }

    @Test
    fun generatedEdgesDoNotOrderNativeDrops() {
        FinalizerOrderSource.resetProbe()
        val batches = 200
        val pairsPerBatch = 500

        for (b in 0 until batches) {
            churn(pairsPerBatch)
            System.gc()
            Thread.sleep(1)
        }

        // Drain: keep collecting until the counters stop moving.
        var last = -1L
        for (i in 0 until 100) {
            System.gc()
            Thread.sleep(20)
            val now = (FinalizerOrderSource.sourceDrops() + FinalizerOrderSource.dependentDrops()).toLong()
            if (now == last && now > 0) break
            last = now
        }

        val sources = FinalizerOrderSource.sourceDrops()
        val deps = FinalizerOrderSource.dependentDrops()
        val bad = FinalizerOrderSource.badOrderDrops()
        println("FINALIZER_ORDER_PROBE sourceDrops=$sources dependentDrops=$deps badOrderDrops=$bad totalPairs=${batches * pairsPerBatch}")

        assertTrue(
            bad > 0.toULong(),
            "expected at least one dependent Drop to run after its source Drop; " +
                "sourceDrops=$sources dependentDrops=$deps badOrderDrops=$bad"
        )
    }
}
