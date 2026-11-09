package dev.yourstream.ui.screens

import java.lang.reflect.Proxy

/**
 * An interface implementation answering only what a test needs: [answer] gets the method name
 * and arguments (suspend functions may return their value directly). Anything else fails loudly.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T> stub(crossinline answer: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, m, args ->
        when (m.name) {
            "toString" -> "stub ${T::class.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> answer(m.name, args ?: emptyArray())
        }
    } as T
