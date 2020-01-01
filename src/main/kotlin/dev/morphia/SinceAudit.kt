package dev.morphia

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.jboss.forge.roaster.Roaster
import java.io.File

class SinceAudit() {
    private var git: Git
    val repoDir = File("target/morphia.audit")

    init {
        git = if (repoDir.exists()) {
            Git.open(repoDir)
        } else {
            println("Cloning morphia repository to ${repoDir}")
            Git.cloneRepository()
                .setURI("git@github.com:MorphiaOrg/morphia.git")
                .setDirectory(repoDir)
                .call()
        }

        val branches = git.branchList().call()
        if (branches.none { it.name == "refs/heads/2.0-dev" }) {
            git.checkout()
                .setName("2.0-dev")
                .setStartPoint("origin/2.0-dev")
                .setCreateBranch(true)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                .call()
        }
    }

    fun run() {
        baseLine()
    }

    private fun baseLine() {
        println("looking for files")
        val javaFiles = File(repoDir, "morphia/src/main/java").walkTopDown()
            .filter { it.extension == "java" }
            .toList()

        for (javaFile in javaFiles) {
            runBlocking {
                val jobs = mutableListOf<Job>()
                jobs += launch { catalog(javaFile) }

                jobs.joinAll()
            }
        }

    }

    private fun catalog(javaFile: File) {
        val parse = Roaster.parse(javaFile)
        println("parse = ${parse.getQualifiedName()}")
    }
}

fun main() {
    SinceAudit().run()
}