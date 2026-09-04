package dev.gaphunter.backgroundreadactionfreezecompanion.detect

import com.intellij.openapi.progress.ProgressManager

/**
 * Real Tarjan's Strongly Connected Components algorithm -- same
 * from-scratch, iterative (not recursive, so a deep real call graph
 * can never overflow the stack) implementation as
 * `interprocedural-resource-leak-companion`'s own copy, duplicated
 * here per this catalog's "no shared library between plugin repos"
 * convention. [compute] returns each SCC in an order where every SCC a
 * given SCC calls INTO already appears EARLIER -- callees before
 * callers.
 *
 * Calls [ProgressManager.checkCanceled] once per traversal step so a
 * large real call graph can't block the read action uncancellably --
 * same discipline as `interface-resource-close-divergence-companion`'s
 * copy of this same class.
 */
class TarjanSccComputer<T>(private val graph: Map<T, List<T>>) {

    private var indexCounter = 0
    private val indices = HashMap<T, Int>()
    private val lowlink = HashMap<T, Int>()
    private val onStack = HashSet<T>()
    private val stack = ArrayDeque<T>()
    private val sccs = mutableListOf<List<T>>()

    private class Frame<T>(val node: T, var childIndex: Int = 0)

    fun compute(): List<List<T>> {
        for (node in graph.keys) {
            if (node !in indices) strongConnectIterative(node)
        }
        return sccs
    }

    private fun strongConnectIterative(start: T) {
        val callStack = ArrayDeque<Frame<T>>()
        callStack.addLast(beginNode(start))

        while (callStack.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val frame = callStack.last()
            val v = frame.node
            val neighbors = graph[v].orEmpty()

            if (frame.childIndex < neighbors.size) {
                val w = neighbors[frame.childIndex]
                frame.childIndex++
                when {
                    w !in indices -> callStack.addLast(beginNode(w))
                    w in onStack -> lowlink[v] = minOf(lowlink.getValue(v), indices.getValue(w))
                }
            } else {
                if (lowlink.getValue(v) == indices.getValue(v)) {
                    val component = mutableListOf<T>()
                    while (true) {
                        val w = stack.removeLast()
                        onStack -= w
                        component += w
                        if (w == v) break
                    }
                    sccs += component
                }
                callStack.removeLast()
                if (callStack.isNotEmpty()) {
                    val parent = callStack.last().node
                    lowlink[parent] = minOf(lowlink.getValue(parent), lowlink.getValue(v))
                }
            }
        }
    }

    private fun beginNode(v: T): Frame<T> {
        indices[v] = indexCounter
        lowlink[v] = indexCounter
        indexCounter++
        stack.addLast(v)
        onStack += v
        return Frame(v)
    }
}
