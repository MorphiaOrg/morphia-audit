package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.jsoup.Jsoup
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.util.Properties


class OperationAudit {
    fun check(url: String, cssSelector: String, file: File): OperationAudit {
        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
            .sorted()
        if (operators.isEmpty()) {
            throw IllegalStateException("No operators found for $url.")
        }
        val properties = Properties()
        properties.load(FileInputStream(file))
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
            properties[operator]?.let {
                it as String
                for (s in it.split(";")) {
                document += ". $s\n"
                }
            }
            document += "\n"
        }

        document += "\n|==="

//        File("target/${file.extension("html")}").writeText(asciidoctor.convert(document, mapOf()))
        File("target/${file.extension("adoc")}").writeText(document)

        return this
    }

    private fun File.extension(extension: String) = this.nameWithoutExtension + ".$extension"
}

fun main() {
    OperationAudit()
        .check(
            "https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query",
            File("src/main/resources/query.properties")
        )
        .check(
            "https://docs.mongodb.com/manual/meta/aggregation-quick-reference", ".xref.mongodb-pipeline",
            File("src/main/resources/aggregation.properties")
        )
}
