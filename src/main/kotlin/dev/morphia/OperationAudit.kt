package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.jboss.forge.roaster.Roaster
import org.jboss.forge.roaster.model.JavaType
import org.jboss.forge.roaster.model.MethodHolder
import org.jboss.forge.roaster.model.source.MethodSource
import org.jsoup.Jsoup
import java.io.File
import java.net.URL


class OperationAudit(var methods: Map<String, List<MethodSource<*>>>) {
    companion object {
        fun parse(vararg pkgNames: String): OperationAudit {
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
                .filter { it.getJavaDoc().tagNames.contains("@mongodb.driver.manual") }
                .groupBy { it.getJavaDoc().getTags("@mongodb.driver.manual")[0].value.substringAfter(" ") }

            return OperationAudit(methods)
        }

    }

    fun audit(name: String, url: String, cssSelector: String) {
        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
            .sorted()
        if (operators.isEmpty()) {
            throw IllegalStateException("No operators found for $url.")
        }
        var document = """
            = $url
            
            .${name}
            [cols="e,a"]
            |===
            |Operator Name|Implementation
            """.trimIndent()
        for (operator in operators) {
            document += """
            |${operator}
            |""".trimIndent()
            methods[operator]?.map { method ->
                val name = method.getName()
                val className = method.getOrigin().getQualifiedName()
                method.removeJavaDoc()
                method.removeAllAnnotations()
                method.setBody("")
                document += ". ${method.toString().substringBefore("{")}\n"
            }
            document += "\n"
        }

        document += "\n|==="

        val asciidoctor = Factory.create()

        File("target/${name}.html").writeText(asciidoctor.convert(document, mapOf()))
        File("target/${name}.adoc").writeText(document)
    }
}

fun main() {
    OperationAudit
        .parse(/*"dev.morphia.aggregation", */"dev.morphia.aggregation.experimental")
        .audit(
            "aggregation-pipeline", "https://docs.mongodb.com/manual/meta/aggregation-quick-reference",
            ".xref.mongodb-pipeline"
        )

    OperationAudit
        .parse(/*"dev.morphia.aggregation", */"dev.morphia.aggregation.experimental.expressions")
        .audit(
            "aggregation-expressions", "https://docs.mongodb.com/manual/meta/aggregation-quick-reference",
            ".xref.mongodb-expression"
        )

//    OperationAudit
//        .parse(/*"dev.morphia.aggregation", */"dev.morphia.aggregation.experimental.expressions")
//        .audit("https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query",
//            "query-expressions"))
}
