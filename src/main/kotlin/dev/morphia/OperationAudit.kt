package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.jboss.forge.roaster.Roaster
import org.jboss.forge.roaster.model.JavaType
import org.jboss.forge.roaster.model.MethodHolder
import org.jboss.forge.roaster.model.source.MethodSource
import org.jsoup.Jsoup
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.util.Properties


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

    fun audit(url: String, cssSelector: String, file: File) {
        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
            .sorted()
        if (operators.isEmpty()) {
            throw IllegalStateException("No operators found for $url.")
        }
        var document = """= $url

.${file}
[cols="e,a"]
|===
|Operator Name|Implementation
"""
        for (operator in operators) {
            document += """
            |${operator}
            |""".trimIndent()
            methods[operator]?.map { method ->
                val name = method.getName()
                val className = method.getOrigin().getQualifiedName()
                method.removeJavaDoc()
                document += ". ${method}\n"
            }
            document += "\n"
        }

        document += "\n|==="

        val asciidoctor = Factory.create()

        File("target/${file.extension("html")}").writeText(asciidoctor.convert(document, mapOf()))
        File("target/${file.extension("adoc")}").writeText(document)
    }

    private fun File.extension(extension: String) = this.nameWithoutExtension + ".$extension"
}

fun main() {
    OperationAudit
        .parse(/*"dev.morphia.aggregation", */"dev.morphia.aggregation.experimental")
        .audit("https://docs.mongodb.com/manual/meta/aggregation-quick-reference", ".xref.mongodb-pipeline",
            File("src/main/resources/aggregation-pipeline.properties"))

//        .audit("https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query",
//            File("src/main/resources/query.properties"))
//        .audit("https://docs.mongodb.com/manual/meta/aggregation-quick-reference", ".xref.mongodb-expression",
//            File("src/main/resources/aggregation-expressions.properties"))
}
