package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.eclipse.jgit.api.Git
import org.jboss.forge.roaster.Roaster
import org.jboss.forge.roaster.model.JavaType
import org.jboss.forge.roaster.model.MethodHolder
import org.jboss.forge.roaster.model.source.MethodSource
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.text.NumberFormat

private val morphiaGit = File("/tmp/morphia-audit")

class OperationAudit(var methods: Map<String, List<MethodSource<*>>>) {
    companion object {
        fun parse(pkgName: String, taglet: String): OperationAudit {
            val file = File(morphiaGit, "core/src/main/java/${pkgName.replace(".", "/")}").absoluteFile
            if(!file.exists()) {
                throw IllegalArgumentException("$file does not exist.")
            }
            println("Scanning $file for ${taglet}")
            val methods = file.walkBottomUp()
                .filter { it.extension == "java" }
                .map { Roaster.parse(JavaType::class.java, it) }
                .filterIsInstance<MethodHolder<*>>()
                .map { it.getMethods() }
                .flatten()
                .filterIsInstance<MethodSource<*>>()
                .filter { it.getJavaDoc().tagNames.contains(taglet) }
                .groupBy { it.getJavaDoc().getTags(taglet)[0].value.substringAfter(" ") }

            return OperationAudit(methods)
        }

    }

    fun audit(name: String, url: String, cssSelector: String, filter: List<String> = listOf()): Int {
        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
            .filter { it !in filter  && it.startsWith('$')}
            .sorted()
        if (operators.isEmpty()) {
            throw IllegalStateException("No operators found for $url.")
        }
        val map = operators.map {
            it to methods[it]?.let { list -> impls(list) }
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

        document += writeImpls(remaining)
        document += writeImpls(done)

        document += "\n|==="

        val asciidoctor = Factory.create()

        File("target/${name}.html").writeText(asciidoctor.convert(document, mapOf()))
        File("target/${name}.adoc").writeText(document)

        asciidoctor.shutdown()
        return remaining.size
    }

    private fun writeImpls(operators: List<Pair<String, String?>>): String {
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
        return list.joinToString("\n") { method ->
            method.removeJavaDoc()
            method.removeAllAnnotations()
            val signature = method.toString().substringBefore("{").trim()
            ". ${signature} [_${method.getOrigin().getName()}_]"
        }
    }
}


fun main() {
    val git = if (!morphiaGit.exists()) {
        Git.cloneRepository()
            .setURI("https://github.com/MorphiaOrg/morphia")
            .setDirectory(morphiaGit)
            .setCloneAllBranches(false)
            .call()
    } else {
        Git.open(morphiaGit).apply {
            pull().call()
        }
    }
    git.close()

    val remainingUpdates = OperationAudit
        .parse("dev.morphia.query.experimental.updates", taglet = "@update.operator")
        .audit("update-operators", "https://docs.mongodb.com/manual/reference/operator/update/", ".xref.mongodb-update",
        listOf("$", "$[]", "$[<identifier>]", "\$position", "\$slice", "\$sort"))

    val remainingFilters = OperationAudit
        .parse("dev.morphia.query.experimental.filters", taglet = "@query.filter")
        .audit("query-filters", "https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query")

    val remainingStages = OperationAudit
        .parse("dev.morphia.aggregation.experimental", taglet = "@aggregation.expression")
        .audit("aggregation-pipeline", "https://docs.mongodb.com/manual/meta/aggregation-quick-reference",
            ".xref.mongodb-pipeline", listOf("\$listSessions", "\$listLocalSessions"))

    val remainingExpressions = OperationAudit
        .parse("dev.morphia.aggregation.experimental.expressions", taglet = "@aggregation.expression")
        .audit("aggregation-expressions", "https://docs.mongodb.com/manual/reference/operator/aggregation/index.html",
            ".xref.mongodb", listOf("\$addFields", "\$group", "\$project", "\$set"))

    if(remainingExpressions + remainingFilters + remainingUpdates + remainingStages > 0) {
        throw Exception("""
            |Audit found missing items
            |    remaining expressions: ${remainingExpressions}
            |    remaining filters: ${remainingFilters}
            |    remaining updates: ${remainingUpdates}
            |    remaining stages: ${remainingStages}
            """.trimMargin("|"))
    }
}
