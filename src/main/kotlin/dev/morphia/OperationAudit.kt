package dev.morphia

import org.asciidoctor.Asciidoctor.Factory
import org.jsoup.Jsoup
import java.io.File
import java.lang.String.format
import java.net.URL
import java.util.*


val asciidoctor = Factory.create()

class OperationAudit {
    fun check(url: String, cssSelector: String, file: String): OperationAudit {

        val operators = Jsoup.parse(URL(url), 30000)
            .select(cssSelector)
            .distinctBy { it.text() }
            .map { it.text() }
        if (operators.size == 0) {
            throw IllegalStateException("No operators found for $url.")
        }
        val properties = Properties()
        properties.load(this::class.java.getResourceAsStream(file))
        val property = properties.get(operators[0])
        var document = """= $url

.${file}
|===
|Operator Name|Implementation
"""
        for (operator in operators) {
            document += """
            |${operator}
            |""".trimIndent()
            properties.get(operator)?.let {
                document += it
            }
            document += "\n"
        }

        document += "|==="

        try {
            File("target/temp.html").writeText(asciidoctor.convert(document, mapOf()))
        } catch (e: Exception) {
            document.lines()
                .forEachIndexed<String> { index, line ->
                    println(format("%3d %s", index, line))
                }
            File("target/doc.adoc").writeText(document)
        }

        return this
    }

}


fun main(args: Array<String>) {
    OperationAudit()
        .check(
            "https://docs.mongodb.com/manual/reference/operator/query/", ".xref.mongodb-query",
            "/query-operators.properties"
        )
//        .check("https://docs.mongodb.com/manual/meta/aggregation-quick-reference", ".xref.mongodb-pipeline",
//            "/query-operators.properties")
}

