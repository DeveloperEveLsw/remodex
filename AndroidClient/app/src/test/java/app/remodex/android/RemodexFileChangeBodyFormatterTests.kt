package app.remodex.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class RemodexFileChangeBodyFormatterTests {
    @Test
    fun renderUnifiedDiffBodySplitsPerFileLikeIos() {
        val body = RemodexFileChangeBodyFormatter.renderUnifiedDiffBody(
            diff = """
            diff --git a/src/A.kt b/src/A.kt
            index 1111111..2222222 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1 +1 @@
            -old
            +new
            diff --git a/src/B.kt b/src/B.kt
            new file mode 100644
            --- /dev/null
            +++ b/src/B.kt
            @@ -0,0 +1 @@
            +hello
            """.trimIndent(),
            status = "completed",
        )

        assertTrue(body.contains("Status: completed"))
        assertTrue(body.contains("Path: src/A.kt"))
        assertTrue(body.contains("Path: src/B.kt"))
        assertTrue(body.contains("```diff"))
        assertTrue(body.contains("\n\n---\n\n"))
    }

    @Test
    fun decodeFileChangeItemBodyBuildsIosStyleSectionsFromChanges() {
        val itemObject = JsonObject(
            mapOf(
                "status" to JsonPrimitive("inProgress"),
                "changes" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "path" to JsonPrimitive("src/Main.kt"),
                                "kind" to JsonPrimitive("update"),
                                "additions" to JsonPrimitive(2),
                                "deletions" to JsonPrimitive(1),
                                "diff" to JsonPrimitive(
                                    """
                                    diff --git a/src/Main.kt b/src/Main.kt
                                    @@ -1 +1,2 @@
                                    -old
                                    +new
                                    +more
                                    """.trimIndent(),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val body = RemodexFileChangeBodyFormatter.decodeFileChangeItemBody(itemObject)

        assertTrue(body.contains("Status: inProgress"))
        assertTrue(body.contains("Path: src/Main.kt"))
        assertTrue(body.contains("Kind: update"))
        assertTrue(body.contains("Totals: +2 -1"))
        assertTrue(body.contains("```diff"))
    }
}
