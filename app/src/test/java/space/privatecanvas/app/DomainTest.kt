package space.privatecanvas.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DomainTest {
    @Test
    fun contactInitialsUseAtMostTwoWords() {
        assertEquals("AL", TrustedContact("1", "Avery Lee").initials)
        assertEquals("A", TrustedContact("2", "Avery").initials)
        assertEquals("?", TrustedContact("3", "  ").initials)
        assertEquals("Avery", normalizeDisplayName("\u0000\u200B"))
    }

    @Test
    fun pinnedContactsMoveToTopWithoutReorderingPeers() {
        val contacts = listOf(
            TrustedContact("1", "One"),
            TrustedContact("2", "Two", pinned = true),
            TrustedContact("3", "Three"),
            TrustedContact("4", "Four", pinned = true),
        )

        assertEquals(listOf("2", "4", "1", "3"), orderedContacts(contacts).map { it.id })
    }

    @Test
    fun contactSortModesKeepPinnedContactsFirst() {
        val contacts = listOf(
            TrustedContact("1", "Zulu", pairedAtEpochMs = 10),
            TrustedContact("2", "Beta", pairedAtEpochMs = 20, pinned = true),
            TrustedContact("3", "Alpha", pairedAtEpochMs = 30),
        )

        assertEquals(listOf("2", "3", "1"), orderedContacts(contacts, ContactSortMode.Name).map { it.id })
        assertEquals(listOf("2", "3", "1"), orderedContacts(contacts, ContactSortMode.RecentlyPaired).map { it.id })
    }

    @Test
    fun largeTextReservesMoreAutomaticBlockHeight() {
        val block = SharedTextBlock("1", "A line that wraps across the available block width", 0f, 0f, 140f)

        assertEquals(true, estimatedBlockHeight(block, 1.18f) > estimatedBlockHeight(block, .9f))
    }

    @Test
    fun mergePreservesTargetIdentityAndParagraphBoundary() {
        val source = SharedTextBlock("source", "after dinner 🍲", 40f, 200f, 250f)
        val target = SharedTextBlock("target", "Walk by the river 🌿", 100f, 80f, 180f)

        val merged = mergeSharedBlocks(source, target)

        assertEquals("target", merged.id)
        assertEquals("Walk by the river 🌿\nafter dinner 🍲", merged.text)
        assertEquals(250f, merged.width)
        assertEquals(100f, merged.x)
        assertEquals(80f, merged.y)
    }

    @Test
    fun mergeDoesNotCreateEmptyParagraphs() {
        val source = SharedTextBlock("source", "", 0f, 0f)
        val target = SharedTextBlock("target", "Shared thought", 0f, 0f)

        val merged = mergeSharedBlocks(source, target)

        assertEquals("Shared thought", merged.text)
        assertFalse(merged.text.contains("\n"))
    }

    @Test
    fun newBlockFindsFreePositionInsteadOfStacking() {
        val occupied = SharedTextBlock("existing", "Existing thought", 62f, 180f, 230f)

        val position = findAvailableBlockPosition(62f, 180f, 230f, listOf(occupied))

        assertFalse(position.x == 62f && position.y == 180f)
        assertFalse(position.x == 62f || position.y == 180f)
    }

    @Test
    fun handDrawnFrameRequiresAnEnclosedArea() {
        assertFalse(isUsefulFrame(listOf(CanvasPoint(0f, 0f), CanvasPoint(100f, 100f), CanvasPoint(200f, 200f))))
        assertEquals(
            true,
            isUsefulFrame(
                listOf(CanvasPoint(0f, 0f), CanvasPoint(160f, 0f), CanvasPoint(160f, 120f), CanvasPoint(0f, 120f)),
            ),
        )
    }

    @Test
    fun handDrawnFrameCanBeSelectedInsideOrNearItsEdge() {
        val frame = HandDrawnFrame(
            "frame",
            listOf(CanvasPoint(0f, 0f), CanvasPoint(200f, 0f), CanvasPoint(200f, 160f), CanvasPoint(0f, 160f)),
        )

        assertEquals(true, frame.containsOrTouches(CanvasPoint(100f, 80f)))
        assertEquals(true, frame.containsOrTouches(CanvasPoint(208f, 80f)))
        assertFalse(frame.containsOrTouches(CanvasPoint(260f, 220f)))
    }

    @Test
    fun remoteDeleteOnlyRecoversDivergedLocalText() {
        val deletedSnapshot = SharedTextBlock("block", "shared", 0f, 0f)

        assertFalse(hasRemoteDeleteConflict(deletedSnapshot, deletedSnapshot))
        assertEquals(
            true,
            hasRemoteDeleteConflict(deletedSnapshot.copy(text = "shared + offline edit"), deletedSnapshot),
        )
        assertFalse(hasRemoteDeleteConflict(deletedSnapshot.copy(text = ""), deletedSnapshot))
    }

}
