package dev.diplomattest.somelib;
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

internal interface FinalizerOrderSourceLib: Library {
    fun FinalizerOrderSource_destroy(handle: Pointer)
    fun FinalizerOrderSource_create(): Pointer
    fun FinalizerOrderSource_make_dependent(handle: Pointer): Pointer
    fun FinalizerOrderSource_reset_probe(): Unit
    fun FinalizerOrderSource_source_drops(): FFIUint64
    fun FinalizerOrderSource_dependent_drops(): FFIUint64
    fun FinalizerOrderSource_bad_order_drops(): FFIUint64
}

class FinalizerOrderSource internal constructor (
    internal val handle: Pointer,
    // These ensure that anything that is borrowed is kept alive and not cleaned
    // up by the garbage collector.
    internal val selfEdges: List<Any>,
    internal var owned: Boolean,
)  {

    init {
        if (this.owned) {
            this.registerCleaner()
        }
    }

    private class FinalizerOrderSourceCleaner(val handle: Pointer, val lib: FinalizerOrderSourceLib) : Runnable {
        override fun run() {
            lib.FinalizerOrderSource_destroy(handle)
        }
    }
    private fun registerCleaner() {
        CLEANER.register(this, FinalizerOrderSource.FinalizerOrderSourceCleaner(handle, FinalizerOrderSource.lib));
    }

    companion object {
        internal val libClass: Class<FinalizerOrderSourceLib> = FinalizerOrderSourceLib::class.java
        internal val lib: FinalizerOrderSourceLib = Native.load("diplomat_feature_tests", libClass)
        @JvmStatic
        
        fun create(): FinalizerOrderSource {
            
            val returnVal = lib.FinalizerOrderSource_create();
            val selfEdges: List<Any> = listOf()
            val handle = returnVal 
            val returnOpaque = FinalizerOrderSource(handle, selfEdges, true)
            return returnOpaque
        }
        @JvmStatic
        
        fun resetProbe(): Unit {
            
            val returnVal = lib.FinalizerOrderSource_reset_probe();
            
        }
        @JvmStatic
        
        fun sourceDrops(): ULong {
            
            val returnVal = lib.FinalizerOrderSource_source_drops();
            return (returnVal.toULong())
        }
        @JvmStatic
        
        fun dependentDrops(): ULong {
            
            val returnVal = lib.FinalizerOrderSource_dependent_drops();
            return (returnVal.toULong())
        }
        @JvmStatic
        
        fun badOrderDrops(): ULong {
            
            val returnVal = lib.FinalizerOrderSource_bad_order_drops();
            return (returnVal.toULong())
        }
    }
    
    fun makeDependent(): FinalizerOrderDependent {
        // This lifetime edge depends on lifetimes: 'a
        val aEdges: MutableList<Any> = mutableListOf(this);
        
        val returnVal = lib.FinalizerOrderSource_make_dependent(handle);
        val selfEdges: List<Any> = listOf()
        val handle = returnVal 
        val returnOpaque = FinalizerOrderDependent(handle, selfEdges, aEdges, true)
        return returnOpaque
    }

}