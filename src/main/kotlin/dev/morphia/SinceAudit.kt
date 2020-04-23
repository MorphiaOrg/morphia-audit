package dev.morphia

import dev.morphia.model.MorphiaClass
import dev.morphia.model.MorphiaMethod
import dev.morphia.model.State
import dev.morphia.model.State.ABSENT
import dev.morphia.model.State.DEPRECATED
import dev.morphia.model.State.PRESENT
import dev.morphia.model.Version
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_CODE
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_DEPRECATED
import org.objectweb.asm.Opcodes.ACC_PROTECTED
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.net.URL
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

fun main() {
    SinceAudit().run()
}

class SinceAudit() {
    val classHistory = ConcurrentHashMap<String, MorphiaClass>()
    val methodHistory = ConcurrentHashMap<String, MorphiaMethod>()
    val classes = javaClass.getResourceAsStream("/audit-classes.txt")
        .bufferedReader()
        .readLines()
        .filterNot { it.startsWith("#") }
    val reports = LinkedHashMap<String, (PrintWriter) -> Boolean>()

    fun run() {
        Version.values().forEach { version ->
            version.download().also {
                it.entries().iterator()
                    .forEach { entry ->
                        if (entry.name.endsWith("class") && !entry.name.endsWith("module-info.class")) {
                            val classNode = ClassNode().also { node ->
                                ClassReader(it.getInputStream(entry)).accept(node, SKIP_CODE)
                            }

                            if (classNode.className() in classes) {
                                val morphiaClass = classHistory.computeIfAbsent(classNode.fqcn()) { _ ->
                                    MorphiaClass(classNode.packageName(), classNode.className())
                                }
                                morphiaClass.versions[version] =
                                    if (classNode.access.isDeprecated()) DEPRECATED else PRESENT

                                classNode.methods.forEach { m ->
                                    if (m.access.isNotPrivate() && m.access.isNotSynthetic()) {
                                        val method =
                                            methodHistory.computeIfAbsent("${morphiaClass.fqcn()}#${m.descriptor()}") { _ ->
                                                MorphiaMethod(morphiaClass.pkgName, morphiaClass.name, m.descriptor())
                                            }
                                        method.versions[version] = if (m.access.isDeprecated()) DEPRECATED else PRESENT
                                    }
                                }
                            }
                        }
                    }
            }
        }

        validate()

        if (reports.isNotEmpty()) {
            val writer = PrintWriter(FileWriter("target/violations.txt"))
            try {
                var failure = false
                reports.values.forEach { failure = it(writer) || failure }
                if (failure) throw IllegalStateException("Violations found")
            } finally {
                writer.flush()
                writer.close()
            }
        }
    }

    private fun validate() {
        reportMissingNondeprecatedMethods(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        reportNewDeprecatedMethods(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        reportNewDeprecatedClasses(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        reportNewMethods(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        reportNewClasses(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
    }

    private fun reportMissingNondeprecatedMethods(newer: Version, older: Version) {
        val list = methodHistory.values
            .filter { it.versions[newer] == ABSENT && it.versions[older] == PRESENT }
            .filter { classHistory["${it.pkgName}.${it.className}"]?.versions?.get(older) == PRESENT }
            .sortedBy { it.fullyQualified() }

        val filtered = list
            .filter { it.returnType() != "Lcom/mongodb/WriteResult;" }  // outdated return type
            .filter { !it.name.startsWith("merge(Ljava/lang/Object;") }  // issue 959
            .filter { !it.name.startsWith("save(Ljava/lang/Iterable;") }  // return type changed from Iterable<Key> to List<T>
            .filter { !it.name.startsWith("save(Ljava/lang/Object;") }  // return type changed from Key to T
            .filter { !it.name.startsWith("update(Ldev/morphia/query/Query;Ldev/morphia/query/UpdateOperations;") }  // outdated return type
            .filter { !it.name.startsWith("update(Ldev/morphia/query/Query;Ldev/morphia/query/UpdateOperations;") }  // outdated return type
            .filter { it.fullyQualified() != "dev.morphia.DeleteOptions#copy()Ldev/morphia/DeleteOptions;" }  // internal method
            .filter { it.fullyQualified() != "dev.morphia.DeleteOptions#getCollation()Lcom/mongodb/client/model/Collation;" }  // no getters
            .filter { !it.fullyQualified().startsWith("dev.morphia.FindAndModifyOptions#get") }  // no getters
            .filter { !it.fullyQualified().startsWith("dev.morphia.FindAndModifyOptions#is") }  // no getters
            .filter { it.fullyQualified() != "dev.morphia.InsertOptions#copy()Ldev/morphia/InsertOptions;" }  // internal method
            .filter { it.fullyQualified() != "dev.morphia.UpdateOptions#copy()Ldev/morphia/UpdateOptions;" }  // internal method
            .filter { movedToParent(it.fullyQualified()) }
            .filter { dbObjectMigration(it.fullyQualified()) }

        reportMethods(
            "Methods missing in ${newer} that weren't deprecated in ${older}".format(newer, older),
            older, newer, filtered
        )
    }

    private fun dbObjectMigration(name: String): Boolean {
        return name !in listOf(
            "dev.morphia.query.FindOptions#getHint()Lcom/mongodb/DBObject;",
            "dev.morphia.query.FindOptions#getMax()Lcom/mongodb/DBObject;",
            "dev.morphia.query.FindOptions#getMin()Lcom/mongodb/DBObject;",
            "dev.morphia.query.FindOptions#getSort()Lcom/mongodb/DBObject;",
            "dev.morphia.UpdateOptions#isUpsert()Z"
        )
    }
    private fun movedToParent(name: String): Boolean {
        return name !in listOf(
            "dev.morphia.DeleteOptions#getWriteConcern()Lcom/mongodb/WriteConcern;",
            "dev.morphia.UpdateOptions#getWriteConcern()Lcom/mongodb/WriteConcern;",
            "dev.morphia.query.CountOptions#getCollation()Lcom/mongodb/client/model/Collation;",
            "dev.morphia.query.CountOptions#getHint()Ljava/lang/String;",
            "dev.morphia.query.CountOptions#getLimit()I",
            "dev.morphia.query.CountOptions#getReadConcern()Lcom/mongodb/ReadConcern;",
            "dev.morphia.query.CountOptions#getReadPreference()Lcom/mongodb/ReadPreference;",
            "dev.morphia.query.CountOptions#getSkip()I",
            "dev.morphia.FindAndModifyOptions#bypassDocumentValidation(Ljava/lang/Boolean;)Ldev/morphia/FindAndModifyOptions;",
            "dev.morphia.FindAndModifyOptions#collation(Lcom/mongodb/client/model/Collation;)Ldev/morphia/FindAndModifyOptions;",
            "dev.morphia.FindAndModifyOptions#maxTime(JLjava/util/concurrent/TimeUnit;)Ldev/morphia/FindAndModifyOptions;",
            "dev.morphia.FindAndModifyOptions#upsert(Z)Ldev/morphia/FindAndModifyOptions;",
            "dev.morphia.FindAndModifyOptions#writeConcern(Lcom/mongodb/WriteConcern;)Ldev/morphia/FindAndModifyOptions;",
            "dev.morphia.UpdateOptions#getBypassDocumentValidation()Ljava/lang/Boolean;",
            "dev.morphia.UpdateOptions#getCollation()Lcom/mongodb/client/model/Collation;",
            "dev.morphia.UpdateOptions#isUpsert()Z"
        )
    }

    private fun reportNewDeprecatedMethods(newer: Version, older: Version) {
        reportMethods(
            "New deprecated methods in ${newer}", older, newer, newMethods(newer, older, DEPRECATED, ABSENT),
            false
        );
    }

    private fun reportNewDeprecatedClasses(newer: Version, older: Version) {
        reportClasses(
            "New deprecated classes in ${newer}", older, newer, newClasses(newer, older, DEPRECATED, ABSENT),
            false
        );
    }

    private fun reportNewClasses(newer: Version, older: Version) {
        reportClasses(
            "New classes in ${newer}", older, newer, newClasses(newer, older, PRESENT, ABSENT),
            false
        );
    }

    private fun reportNewMethods(newer: Version, older: Version) {
        reportMethods(
            "New methods in ${newer}", older, newer, newMethods(newer, older, PRESENT, ABSENT),
            false
        );
    }

    private fun newMethods(newer: Version, older: Version, newState: State, oldState: State): List<MorphiaMethod> {
        return methodHistory.values
            .filter { it.versions[newer] == newState && it.versions[older] == oldState }
            .sortedBy { it.fullyQualified() }
    }

    private fun newClasses(newer: Version, older: Version, newState: State, oldState: State): List<MorphiaClass> {
        return classHistory.values
            .filter { it.versions[newer] == newState && it.versions[older] == oldState }
            .sortedBy { it.fqcn() }
    }

    private fun reportMethods(
        title: String, older: Version, newer: Version, list: List<MorphiaMethod>,
        failureCase: Boolean = true
    ) {
        if (list.isNotEmpty()) reports[title] = { writer ->
            write(writer, "${title}: ${list.size}")
            val versions = "%-10s %-10s"
            write(writer, versions.format("older", "newer"))
            list.forEach {
                val states = versions.format(it.versions[older], it.versions[newer])
                write(writer, "$states ${it.pkgName}.${it.className}#${it.name}")
            }
            failureCase
        }
    }

    private fun reportClasses(
        title: String, older: Version, newer: Version, list: List<MorphiaClass>,
        failureCase: Boolean = true
    ) {
        if (list.isNotEmpty()) reports[title] = { writer ->
            write(writer, "${title}: ${list.size}")
            val versions = "%-10s %-10s"
            write(writer, versions.format("older", "newer"))
            list.forEach {
                val states = versions.format(it.versions[older], it.versions[newer])
                write(writer, "$states ${it.fqcn()}")
            }
            failureCase
        }
    }

    private fun write(writer: PrintWriter, line: String) {
        println(line)
        writer.println(line)
    }

}

fun URL.download(jar: File): JarFile {
    if (!jar.exists()) {
        jar.parentFile.mkdirs()
        FileOutputStream(jar).write(readBytes())
    }
    return JarFile(jar)
}

fun ClassNode.className(): String = this.name
    .substringAfterLast("/")
    .substringBeforeLast(".")
    .replace("/", ".")

fun ClassNode.packageName(): String = this.name
    .substringAfter("classes/")
    .substringBeforeLast("/")
    .replace("/", ".")

fun ClassNode.fqcn() = "${packageName()}.${className()}"

fun MethodNode.descriptor() = name + desc

fun Int.isNotPrivate(): Boolean = isPublic() || isProtected()

fun Int.isNotSynthetic(): Boolean = !matches(ACC_BRIDGE) && !matches(ACC_SYNTHETIC)

fun Int.isDeprecated() = matches(ACC_DEPRECATED)

fun Int.isProtected() = matches(ACC_PROTECTED)

fun Int.isPublic() = matches(ACC_PUBLIC)

fun Int.matches(mask: Int) = (this and mask) == mask

fun MorphiaMethod.returnType(): String = name.substringAfterLast(")")

fun Int.accDecode(): List<String> {
    val decode: MutableList<String> = ArrayList()
    val values: MutableMap<String, Int> = LinkedHashMap()
    try {
        for (f in Opcodes::class.java.declaredFields) {
            if (f.name.startsWith("ACC_")) {
                values[f.name] = f.getInt(Opcodes::class.java)
            }
        }
    } catch (e: IllegalAccessException) {
        throw RuntimeException(e.message, e)
    }
    for ((key, value) in values) {
        if (this.matches(value)) {
            decode.add(key)
        }
    }
    return decode
}

