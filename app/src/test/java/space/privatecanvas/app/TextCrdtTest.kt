package space.privatecanvas.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCrdtTest {
    @Test
    fun localInsertionStaysAtTheRequestedCursorPosition() {
        val initial = initialTextAtoms("block", "ab")
        val first = applyLocalTextChange(initial, "aXb", "device")
        val second = applyLocalTextChange(first.atoms, "aXYb", "device")

        assertEquals("aXb", renderTextAtoms(first.atoms))
        assertEquals("aXYb", renderTextAtoms(second.atoms))
        assertTrue(first.delta.inserts.single().id.startsWith("0000000000001-"))
    }

    @Test
    fun concurrentInsertsConvergeWithoutDroppingEitherSide() {
        val initial = initialTextAtoms("block", "ab")
        val fromA = applyLocalTextChange(initial, "aXb", "device-a")
        val fromB = applyLocalTextChange(initial, "aYb", "device-b")

        val aThenB = applyRemoteTextDelta(applyRemoteTextDelta(initial, fromA.delta), fromB.delta)
        val bThenA = applyRemoteTextDelta(applyRemoteTextDelta(initial, fromB.delta), fromA.delta)

        assertEquals(renderTextAtoms(aThenB), renderTextAtoms(bThenA))
        assertTrue(renderTextAtoms(aThenB).contains('X'))
        assertTrue(renderTextAtoms(aThenB).contains('Y'))
        assertEquals(4, renderTextAtoms(aThenB).length)
    }

    @Test
    fun concurrentDeleteAndInsertRemainReachable() {
        val initial = initialTextAtoms("block", "ab")
        val deletion = applyLocalTextChange(initial, "b", "device-a")
        val insertion = applyLocalTextChange(initial, "aXb", "device-b")
        val merged = applyRemoteTextDelta(applyRemoteTextDelta(initial, deletion.delta), insertion.delta)

        assertFalse(renderTextAtoms(merged).contains('a'))
        assertTrue(renderTextAtoms(merged).contains('X'))
        assertTrue(renderTextAtoms(merged).contains('b'))
    }

    @Test
    fun emojiIsOneCrdtAtom() {
        val initial = initialTextAtoms("block", "A🌿B")
        assertEquals(3, visibleTextAtoms(initial).size)
        val removed = applyLocalTextChange(initial, "AB", "device")
        assertEquals(1, removed.delta.deletes.size)
        assertEquals("AB", renderTextAtoms(removed.atoms))
    }
}
