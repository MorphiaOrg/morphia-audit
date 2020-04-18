package dev.morphia

import dev.morphia.model.MorphiaClass
import dev.morphia.model.MorphiaMethod
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
    val reports = mutableMapOf<String, () -> Unit>()

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
                                val morphiaClass = record(classNode)
                                morphiaClass.versions[version] =
                                    if (classNode.access.isDeprecated()) DEPRECATED else PRESENT

                                classNode.methods.forEach { m ->
                                    if (m.access.isNotPrivate() && m.access.isNotSynthetic()) {
                                        val method = record(morphiaClass, m)
                                        method.versions[version] = if (m.access.isDeprecated()) DEPRECATED else PRESENT
                                    }
                                }
                            }
                        }
                    }
            }
        }

        validate()

        if(reports.isNotEmpty()) {
            reports.values.forEach { it() }
            throw IllegalStateException("Violations found")
        }
    }

    private fun validate() {
        missingNondeprecatedMethods(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        newDeprecatedMethods(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
        newDeprecatedClasses(Version.v2_0_0_SNAPSHOT, Version.v1_6_0_SNAPSHOT)
    }

    private fun newDeprecatedMethods(newer: Version, older: Version) {
        val list = methodHistory.values
            .filter { m ->
                m.versions[newer] == DEPRECATED && m.versions[older] == ABSENT
            }
            .sortedBy { it.name }

        reportMethods("New deprecated methods in ${newer}", older, newer, list);
    }

    private fun newDeprecatedClasses(newer: Version, older: Version) {
        val list = classHistory.values
            .filter { c ->
                c.versions[newer] == DEPRECATED && c.versions[older] == ABSENT
            }
            .sortedBy { it.name }
        reportClasses("New deprecated classes in ${newer}", older, newer, list);
    }

    private fun missingNondeprecatedMethods(newer: Version, older: Version) {
        val list = methodHistory.values
            .filter { m ->
                m.versions[newer] == ABSENT && m.versions[older] == PRESENT
            }
            .filter { classHistory["${it.pkgName}.${it.className}"]?.versions?.get(older) == PRESENT }
            .sortedBy { it.name }

        reportMethods("Methods missing in ${newer} that weren't deprecated in ${older}".format(newer, older),
            older, newer, list
        )
    }

    private fun reportMethods(title: String, older: Version, newer: Version, list: List<MorphiaMethod>) {

        if (list.isNotEmpty()) reports[title] = {
            println("${title}: ${list.size}")
            val versions = "%-10s %-10s"
            println(versions.format("older", "newer"))
            list.forEach {
                val states = versions.format(it.versions[older], it.versions[newer])
                println("$states ${it.pkgName}.${it.className}#${it.name}")
            }
        }
    }

    private fun reportClasses(title: String, older: Version, newer: Version, list: List<MorphiaClass>) {
        if (list.isNotEmpty()) reports[title] = {
            println("${title}: ${list.size}")
            val versions = "%-10s %-10s"
            list.forEach {
                val states = versions.format(it.versions[older], it.versions[newer])
                println("$states ${it.fqcn()}")
            }
        }
    }

    private fun record(classNode: ClassNode): MorphiaClass {
        return classHistory.computeIfAbsent("${classNode.packageName()}.${classNode.className()}") { _ ->
            MorphiaClass(classNode.packageName(), classNode.className())
        }
    }

    private fun record(parent: MorphiaClass, node: MethodNode): MorphiaMethod {
        val descriptor = node.name + node.desc

        return methodHistory.computeIfAbsent("${parent.fqcn()}#${descriptor}") { _ ->
            MorphiaMethod(parent.pkgName, parent.name, descriptor)
        }
    }
}

fun URL.download(jar: File): JarFile {
    if (!jar.exists()) {
        FileOutputStream(jar).write(readBytes())
    }
    return JarFile(jar)
}

fun ClassNode.className(): String {
    return this.name
        .substringAfterLast("/")
        .substringBeforeLast(".")
        .replace("/", ".")
}

fun ClassNode.packageName(): String {
    return this.name
        .substringAfter("classes/")
        .substringBeforeLast("/")
        .replace("/", ".")
}

fun Int.isNotPrivate(): Boolean {
    return isPublic() || isProtected()
}

fun Int.isNotSynthetic(): Boolean {
    return !matches(ACC_BRIDGE) && !matches(ACC_SYNTHETIC)
}

fun Int.isDeprecated() = matches(ACC_DEPRECATED)

fun Int.isProtected() = matches(ACC_PROTECTED)

fun Int.isPublic() = matches(ACC_PUBLIC)

fun Int.matches(mask: Int) = (this and mask) == mask

fun Int.accDecode(): List<String>? {
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
