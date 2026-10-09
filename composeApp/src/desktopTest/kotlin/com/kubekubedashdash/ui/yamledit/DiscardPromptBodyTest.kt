package com.kubekubedashdash.ui.yamledit

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.yamledit.session.DirtyEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the "unsaved changes" dialog says, as plain text: the count, one bullet per editor, what closing means. */
class DiscardPromptBodyTest {

    private fun edit(label: String, context: String = "cluster-a") = DirtyEdit(windowId = 1, clusterSessionId = SessionId("tab-1"), label = label, context = context)

    @Test
    fun `one editor is named with its cluster`() {
        assertEquals(
            "1 editor has unsaved changes:\n• ConfigMap example-ns/demo-cm (cluster-a)\n\nIt will close without applying.",
            discardPromptBody(listOf(edit("ConfigMap example-ns/demo-cm"))),
        )
    }

    @Test
    fun `several editors get one bullet each`() {
        assertEquals(
            "2 editors have unsaved changes:\n• ConfigMap example-ns/demo-cm (cluster-a)\n• Deployment example-ns/web (cluster-b)\n\nThey will close without applying.",
            discardPromptBody(listOf(edit("ConfigMap example-ns/demo-cm"), edit("Deployment example-ns/web", "cluster-b"))),
        )
    }

    @Test
    fun `an Apply YAML label that already names its cluster is not given it twice`() {
        val body = discardPromptBody(listOf(edit("Apply YAML (cluster-a)")))

        assertTrue("• Apply YAML (cluster-a)\n" in body, body)
        assertFalse("(cluster-a) (cluster-a)" in body, body)
    }

    @Test
    fun `a long list is cut and the rest summed up`() {
        val edits = (1..11).map { edit("ConfigMap example-ns/demo-cm-$it") }

        val lines = discardPromptBody(edits).lines()

        assertEquals("11 editors have unsaved changes:", lines.first())
        assertEquals(8, lines.count { it.startsWith("• ConfigMap") })
        assertTrue("• … and 3 more" in lines, lines.toString())
        assertEquals("They will close without applying.", lines.last())
    }
}
