// KT-72862: No function found for symbol
// IGNORE_NATIVE: cacheMode=STATIC_USE_HEADERS_EVERYWHERE
// MODULE: lib
// FILE: Outer.kt
class Outer {
    private fun privateMethod() = "OK"
    class Nested{
        internal inline fun internalInlineMethod() = Outer().privateMethod()
    }
}

// MODULE: main()(lib)
// FILE: main.kt
fun box(): String {
    return Outer.Nested().internalInlineMethod()
}
