package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.jboss.forge.roaster.Roaster
import org.jboss.forge.roaster.model.JavaType
import org.jboss.forge.roaster.model.MethodHolder
import org.jboss.forge.roaster.model.source.MethodSource
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.text.NumberFormat


class OperationAudit(var methods: Map<String, List<MethodSource<*>>>) {
    companion object {
        fun parse(vararg pkgNames: String, taglet: String): OperationAudit {
            val file = File(System.getenv("MORPHIA_SRC"), "src/main/java").absoluteFile
            println("Scanning $file for sources")
            val methods = file.walkBottomUp()
                .filter { it.extension == "java" }
                .map { Roaster.parse(JavaType::class.java, it) }
                .filter { it.getPackage() in pkgNames }
                .filterIsInstance<MethodHolder<*>>()
                .map { it.getMethods() }
                .flatten()
                .filterIsInstance<MethodSource<*>>()
                .filter { it.getJavaDoc().tagNames.contains(taglet) }
                .groupBy { it.getJavaDoc().getTags(taglet)[0].value.substringAfter(" ") }

            return OperationAudit(methods)
        }

    }

    fun audit(
        name: String,
        url: String,
        cssSelector: String,
        filter: List<String> = listOf()
    ) {
        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
            .filter { it !in filter }
            .sorted()
        if (operators.isEmpty()) {
            throw IllegalStateException("No operators found for $url.")
        }
        val map = operators.map {
            it to methods[it]?.let { impls(it) }
        }

        val remaining = map.filter { it.second == null }
        val done = map.filter { it.second != null }

        val percent = NumberFormat.getPercentInstance().format(1.0 * done.size / operators.size)
        var document = """
            = $url
            
            .${name}
            [cols="e,a"]
            |===
            |Operator Name ($percent of ${operators.size} complete. ${remaining.size} remain)|Implementation
            """.trimIndent()

        document += writeImpls(remaining, document)
        document += writeImpls(done, document)

        document += "\n|==="

        val asciidoctor = Factory.create()

        File("target/${name}.html").writeText(asciidoctor.convert(document, mapOf()))
        File("target/${name}.adoc").writeText(document)
    }

    private fun writeImpls(operators: List<Pair<String, String?>>, document: String): String {
        var document1 = ""
        for (operator in operators) {
            document1 += """
                    
                |${operator.first}
                |${operator.second  ?: ""}
                
                """.trimIndent()

        }
        return document1
    }

    private fun impls(list: List<MethodSource<*>>): String {
        val impls = list
            .map { method ->
                method.removeJavaDoc()
                method.removeAllAnnotations()
                val signature = method.toString().substringBefore("{").trim()
                ". ${signature} [_${method.getOrigin().getName()}_]"
            }
            .joinToString("\n")
        return impls
    }
}

fun main() {
    OperationAudit
        .parse("dev.morphia.query.experimental.filters", taglet = "@query.filter")
        .audit("query-expressions", "https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query",
            listOf())

    OperationAudit
        .parse("dev.morphia.aggregation.experimental", taglet = "@aggregation.expression")
        .audit("aggregation-pipeline", "https://docs.mongodb.com/manual/meta/aggregation-quick-reference",
            ".xref.mongodb-pipeline", listOf("\$listSessions", "\$listLocalSessions"))

    OperationAudit
        .parse("dev.morphia.aggregation.experimental.expressions", taglet = "@aggregation.expression")
        .audit("aggregation-expressions", "https://docs.mongodb.com/manual/reference/operator/aggregation/index.html",
            ".xref.mongodb", listOf("\$addFields", "\$group", "\$project", "\$set"))
}
