// FIR_IDENTICAL
// !DIAGNOSTICS: -UNUSED_PARAMETER
// ISSUE: KT-41176
// WITH_RUNTIME

internal class ArrayMapImpl<T : Any> {
    private var data = arrayOfNulls<Any>(10)

    fun entries(): List<Entry<T>> {
        @Suppress("UNCHECKED_CAST")
        return data.mapIndexedNotNull { index, value ->
            if (value != null) Entry(index, value as T) else null
        }
    }

    data class Entry<T>(override val key: Int, override val value: T) : Map.Entry<Int, T>
}
