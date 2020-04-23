package dev.morphia.model

import dev.morphia.download
import java.io.File
import java.net.URL
import java.util.jar.JarFile

class MorphiaClass(val pkgName: String, val name: String) {
    var versions: MutableMap<Version, State> = Version.values().map { it to State.ABSENT }
        .toMap().toMutableMap()

    override fun toString(): String {
        return "MorphiaClass(pkgName='$pkgName', name='$name')"
    }

    fun fqcn(): String {
        return "$pkgName.$name"
    }

}

class MorphiaMethod(val pkgName: String, val className: String, val name: String) {
    var versions: MutableMap<Version, State> = Version.values()
        .map { it to State.ABSENT }
        .toMap()
        .toMutableMap()


    override fun toString(): String {
        return "MorphiaMethod(name='${pkgName}.${className}#${name}', versions=$versions)"
    }

    fun fullyQualified(): String {
        return "${pkgName}.${className}#${name}"
    }
}

enum class Version {
    v2_0_0_SNAPSHOT {
        override fun artifactId(): String = "morphia-core"
        override fun version() = "2.0.0-SNAPSHOT"
    },
    v1_6_0_SNAPSHOT {
        override fun version() = "1.6.0-SNAPSHOT"
    },
    v1_4_1 {
        override fun version() = "1.4.1"
    };

    open fun artifactId() = "core"
    fun groupId() = "dev.morphia.morphia"
    abstract fun version(): String

    fun download(): JarFile {
        val groupId = groupId().replace('.', '/')

        val path = "$groupId/${artifactId()}/${version()}/${artifactId()}-${version()}.jar"
        val jar = File(System.getProperty("user.home"), ".m2/repository/$path")

        return URL("${baseUrl()}$path").download(jar)
    }

    private fun baseUrl() = "https://repo1.maven.org/maven2/"
}

enum class State {
    ABSENT,
    PRESENT,
    DEPRECATED
}
