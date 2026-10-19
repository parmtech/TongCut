package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class ProjectCatalogTest {
    private val first = ProjectCard("first", "A", 3, 100)
    private val second = ProjectCard("second", "B", 2, 200)

    @Test fun sortedUpdatedProjectsAndSafeSelection() {
        val cat = ProjectCatalog().upsert(first).upsert(second).select("first")
        assertEquals(listOf("second", "first"), cat.projects.map { it.id })
        assertEquals("first", cat.activeId)
        val newCat = cat.upsert(first.copy(title = "Renamed", updatedMs = 300))
        assertEquals("first", newCat.projects[0].id)
        assertEquals("Renamed", newCat.projects[0].title)
        assertEquals("first", newCat.activeId)
    }

    @Test fun removingActiveProjectSelectsRemaining() {
        val cat = ProjectCatalog().upsert(first).upsert(second).select("first")
        val removed = cat.remove("first")
        assertEquals("second", removed.activeId)
        assertEquals(1, removed.projects.size)
        assertNull(removed.remove("second").activeId)
    }

    @Test fun jsonCatalogRoundTripPreservesOrder() {
        val before = ProjectCatalog().upsert(first).upsert(second).select("first")
        val after = ProjectCatalogCodec.decode(ProjectCatalogCodec.encode(before))
        assertEquals(before, after)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateProjectsRejected() {
        ProjectCatalog(listOf(first, first), "first")
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptMissingActiveProjectRejected() {
        ProjectCatalog(listOf(first), "missing")
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptProjectPathRejected() {
        val raw = """{"schema":1,"active":"../outside","projects":[{"id":"../outside","title":"Bad","clips":1,"updated":100}]}"""
        ProjectCatalogCodec.decode(raw)
    }
}
