// Gradle 8.13 rejects BOTH failOnDynamicVersions and
// failOnChangingVersions when the same configuration uses dependency locking.
// The five locked JVM runtimes and Android app debug runtime use STRICT lock
// state plus a request-level unstable-selector/explicit changing-module guard.
// Every other JVM + Android configuration retains the original Gradle-native
// failOnNonReproducibleResolution() prohibition.
val jvmLockedRuntimeModules = setOf(
    ":core:planner", ":core:engine", ":adapter:ftps", ":adapter:sftp",
    ":testing:fake-endpoints"
)
subprojects {
    configurations.configureEach {
        if ((project.path in jvmLockedRuntimeModules && name == "runtimeClasspath") ||
            (project.path == ":app" && name == "debugRuntimeClasspath")) {
            // Inspect direct AND transitive requests. Strict lock mode
            // separately refuses changes to the resolved component set.
            resolutionStrategy.eachDependency {
                val version = requested.version.orEmpty()
                if (version.contains('+') || version.contains('[') ||
                    version.contains('(') || version.startsWith("latest.") ||
                    version.endsWith("-SNAPSHOT", ignoreCase = true)) {
                    throw org.gradle.api.GradleException(
                        "UNSTABLE_LOCKED_RUNTIME_DEPENDENCY_VERSION")
                }
            }
            // Gradle's 'changing' flag can be independent of a version
            // suffix on explicit top-level external dependencies.
            dependencies.withType(
                org.gradle.api.artifacts.ExternalModuleDependency::class.java
            ).configureEach {
                if (isChanging) throw org.gradle.api.GradleException(
                    "CHANGING_LOCKED_RUNTIME_DEPENDENCY")
            }
        } else {
            resolutionStrategy.failOnNonReproducibleResolution()
        }
    }
}


/**
 * T6/#326: deterministic inventory of actually resolved external JVM runtime
 * artifacts. SHA-256 is measured from the local resolved bytes, NOT
 * independently verified publication provenance; reviewers must compare
 * against trusted publisher checksums before promoting it to verification
 * metadata. No repository-private filenames, cache paths, credentials or
 * application contents are included. The opt-in Android variant is a separate
 * future artifact gate, never silently represented as covered here.
 */
tasks.register("jvmRuntimeArtifactInventory") {
    group = "verification"
    description = "Inventory exact resolved JVM runtime dependency artifact bytes for review"
    val report = layout.buildDirectory.file("reports/dependencies/jvm-runtime-artifacts.tsv")
    outputs.file(report)
    // A report must rehash current resolved bytes on each explicit invocation.
    outputs.upToDateWhen { false }
    doLast {
        val rows = sortedSetOf<String>()
        val mandatory = listOf(
            ":core:planner", ":core:engine",
            ":adapter:ftps", ":adapter:sftp", ":testing:fake-endpoints")
        mandatory.forEach { module ->
            val child = project(module)
            val runtime = child.configurations.findByName("runtimeClasspath")
                ?: error("MISSING_JVM_RUNTIME_CONFIGURATION")
            require(runtime.isCanBeResolved) { "JVM_RUNTIME_NOT_RESOLVABLE" }
            val artifacts = runtime.resolvedConfiguration.resolvedArtifacts
            artifacts.forEach { artifact ->
                // Gradle's resolved external module identity excludes local
                // project dependencies; those are not Maven artifacts.
                if (artifact.id.componentIdentifier is
                    org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                    val id = artifact.moduleVersion.id
                    require(id.group.isNotBlank() && id.name.isNotBlank() &&
                        id.version.isNotBlank()) { "UNIDENTIFIED_DEPENDENCY" }
                    val file = artifact.file
                    require(file.isFile) { "DEPENDENCY_ARTIFACT_UNAVAILABLE" }
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) md.update(buffer, 0, read)
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    val fields = listOf(module, id.group, id.name, id.version,
                        artifact.type, artifact.classifier.orEmpty(), hex)
                    require(fields.all { field ->
                        field.isNotBlank() || field == artifact.classifier.orEmpty()
                    } && fields.none { field ->
                        field.contains('\t') || field.contains('\n') ||
                            field.contains('\r')
                    }) { "UNSAFE_DEPENDENCY_INVENTORY_FIELD" }
                    rows += fields.joinToString("\t")
                }
            }
        }
        require(rows.isNotEmpty()) { "NO_RESOLVED_EXTERNAL_JVM_ARTIFACTS" }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "# Expository JVM runtime artifact inventory V1\n" +
            "# scope\tgroup\tname\tversion\ttype\tclassifier\tsha256\n" +
            rows.joinToString("\n", postfix = "\n"),
            Charsets.UTF_8)
        println("JVM_RUNTIME_ARTIFACT_INVENTORY_MODULES=5")
        println("JVM_RUNTIME_ARTIFACT_INVENTORY_ROWS=${rows.size}")
        println("JVM_RUNTIME_ARTIFACT_INVENTORY_PATH=build/reports/dependencies/jvm-runtime-artifacts.tsv")
        println("JVM_RUNTIME_ARTIFACT_INVENTORY_TRUST=LOCAL_HASHES_NOT_PUBLISHER_VERIFICATION")
    }
}

// Existing JVM check invocations exercise this inventory against real
// resolved artifacts, not a hand-authored fixture or an unchecked build task.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("jvmRuntimeArtifactInventory"))
    }
}


/**
 * Companion T6/#326 inventory for the Android app's *debug runtime* graph.
 * Do not confuse this with release-runtime, plugins, all Android test
 * configurations, publication checksums, or an authenticated SBOM.
 *
 * The production app is conditionally included via -PwithAndroid=true.
 * This task is selected from app:lintDebug's existing CI path rather than
 * introducing another Android SDK/provisioning workflow.
 */
tasks.register("androidDebugRuntimeArtifactInventory") {
    group = "verification"
    description = "Hash resolved Android debug runtime external artifacts for later publisher review"
    val report = layout.buildDirectory.file("reports/dependencies/android-debug-runtime-artifacts.tsv")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        require(project.findProperty("withAndroid") == "true" &&
            project.findProject(":app") != null) { "ANDROID_APP_NOT_INCLUDED" }
        val app = project(":app")
        val runtime = app.configurations.findByName("debugRuntimeClasspath")
            ?: error("MISSING_ANDROID_DEBUG_RUNTIME")
        require(runtime.isCanBeResolved) { "ANDROID_DEBUG_RUNTIME_NOT_RESOLVABLE" }
        val rows = sortedSetOf<String>()
        runtime.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            if (artifact.id.componentIdentifier is
                org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                val id = artifact.moduleVersion.id
                require(id.group.isNotBlank() && id.name.isNotBlank() &&
                    id.version.isNotBlank()) { "UNIDENTIFIED_ANDROID_DEPENDENCY" }
                require(artifact.file.isFile) { "ANDROID_DEPENDENCY_ARTIFACT_UNAVAILABLE" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                artifact.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) digest.update(buffer, 0, n)
                    }
                }
                val fields = listOf(":app:debugRuntimeClasspath", id.group,
                    id.name, id.version, artifact.type,
                    artifact.classifier.orEmpty(),
                    digest.digest().joinToString("") { "%02x".format(it) })
                require(fields.none { field ->
                    field.contains('\t') || field.contains('\n') ||
                        field.contains('\r')
                }) { "UNSAFE_ANDROID_DEPENDENCY_INVENTORY_FIELD" }
                rows += fields.joinToString("\t")
            }
        }
        require(rows.isNotEmpty()) { "NO_ANDROID_DEBUG_RUNTIME_ARTIFACTS" }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "# Expository Android debug runtime artifact inventory V1\n" +
            "# scope\tgroup\tname\tversion\ttype\tclassifier\tsha256\n" +
            rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        println("ANDROID_DEBUG_RUNTIME_ARTIFACT_INVENTORY_ROWS=${rows.size}")
        println("ANDROID_DEBUG_RUNTIME_ARTIFACT_INVENTORY_PATH=build/reports/dependencies/android-debug-runtime-artifacts.tsv")
        println("ANDROID_DEBUG_RUNTIME_ARTIFACT_INVENTORY_TRUST=LOCAL_HASHES_NOT_PUBLISHER_VERIFICATION")
    }
}

// Existing required PR Android compile/lint job exercises the real runtime
// graph. JVM-only builds do not resolve or provision Android dependencies.
subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidDebugRuntimeArtifactInventory"))
    }
}

/**
 * W13/#326: activate precise Gradle 8.13 version locks for all five resolved
 * JVM production runtime graphs. The committed per-module gradle.lockfile
 * entries were observed from exact-head Gradle resolution, not guessed from
 * direct declarations. Strict mode also refuses missing lock state.
 *
 * Updating dependencies requires an explicit reviewed lockfile change and
 * fresh exact-head CI; do not use --write-locks in routine CI.
 * The app debug runtime is independently locked below; Android test/release
 * and plugin/buildscript configurations remain separate acceptance gates.
 */
subprojects {
    if (path in jvmLockedRuntimeModules) {
        configurations.matching { it.name == "runtimeClasspath" }.configureEach {
            resolutionStrategy.activateDependencyLocking()
        }
        dependencyLocking {
            lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
        }
    } else if (path == ":app") {
        // Generated from the complete resolved Gradle component graph (109
        // modules including BOM/platform metadata-only components), not
        // inferred from the 78 downloaded runtime artifacts.
        configurations.matching { it.name == "debugRuntimeClasspath" }.configureEach {
            resolutionStrategy.activateDependencyLocking()
        }
        dependencyLocking {
            lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
        }
    }
}

/**
 * Temporary W13 release-runtime component observation on the EXISTING
 * Android lint CI path. Do not merge this reporter: replace with exact
 * committed strict releaseRuntimeClasspath lock state after real resolution.
 */
tasks.register("androidReleaseRuntimeVersionBaseline") {
    group = "verification"
    doLast {
        val config = project(":app").configurations.getByName("releaseRuntimeClasspath")
        require(config.isCanBeResolved) { "RELEASE_RUNTIME_NOT_RESOLVABLE" }
        val modules = config.incoming.resolutionResult.allComponents.mapNotNull {
            val id = it.id as?
                org.gradle.api.artifacts.component.ModuleComponentIdentifier
            if (id == null) null else {
                val names = listOf(id.group, id.module, id.version)
                require(names.all { n -> n.isNotBlank() && !n.contains('=') &&
                    !n.contains('\n') && !n.contains('\r') &&
                    !n.contains('\t') }) { "INVALID_RELEASE_RUNTIME_MODULE" }
                names.joinToString(":") + "=releaseRuntimeClasspath"
            }
        }.toSortedSet()
        require(modules.size >= 70) { "INCOMPLETE_RELEASE_RUNTIME_MODULE_GRAPH" }
        println("ANDROID_RELEASE_LOCK_BASELINE_BEGIN")
        modules.forEach(::println)
        println("ANDROID_RELEASE_LOCK_BASELINE_END")
        println("ANDROID_RELEASE_LOCK_BASELINE_ROWS=${modules.size}")
    }
}
subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidReleaseRuntimeVersionBaseline"))
    }
}
