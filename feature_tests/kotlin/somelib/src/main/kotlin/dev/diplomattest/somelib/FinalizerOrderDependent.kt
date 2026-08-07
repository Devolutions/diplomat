package dev.diplomattest.somelib;
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

internal interface FinalizerOrderDependentLib: Library {
    fun FinalizerOrderDependent_destroy(handle: Pointer)
    fun FinalizerOrderDependent_reads_source(handle: Pointer): Byte
}

class FinalizerOrderDependent internal constructor (
    internal val handle: Pointer,
    // These ensure that anything that is borrowed is kept alive and not cleaned
    // up by the garbage collector.
    internal val selfEdges: List<Any>,
    internal val aEdges: List<Any?>,
    internal var owned: Boolean,
)  {

    init {
        if (this.owned) {
            this.registerCleaner()
        }
    }

    private class FinalizerOrderDependentCleaner(val handle: Pointer, val lib: FinalizerOrderDependentLib) : Runnable {
        override fun run() {
            lib.FinalizerOrderDependent_destroy(handle)
        }
    }
    private fun registerCleaner() {
        CLEANER.register(this, FinalizerOrderDependent.FinalizerOrderDependentCleaner(handle, FinalizerOrderDependent.lib));
    }

    companion object {
        internal val libClass: Class<FinalizerOrderDependentLib> = FinalizerOrderDependentLib::class.java
        internal val lib: FinalizerOrderDependentLib = Native.load("diplomat_feature_tests", libClass)
    }
    
    fun readsSource(): Boolean {
        
        val returnVal = lib.FinalizerOrderDependent_reads_source(handle);
        return (returnVal > 0)
    }

}