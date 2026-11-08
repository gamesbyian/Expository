// Gradle 8.13 rejects BOTH failOnDynamicVersions and
// failOnChangingVersions when the same configuration uses dependency locking.
// The five locked JVM runtimes and all THREE Android app runtime graphs use
// STRICT lock state plus a request-level unstable-selector/changing guard.
// Every other JVM + Android configuration retains the original Gradle-native
// failOnNonReproducibleResolution() prohibition.
val jvmLockedRuntimeModules = setOf(
    ":core:planner", ":core:engine", ":adapter:ftps", ":adapter:sftp",
    ":testing:fake-endpoints"
)
subprojects {
    configurations.configureEach {
        if ((project.path in jvmLockedRuntimeModules && name == "runtimeClasspath") ||
            (project.path == ":app" &&
                name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath",
                    "debugAndroidTestRuntimeClasspath"))) {
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
 * Android app debug, release and instrumentation-test runtime graphs are locked
 * below. Other Android test/compile configurations and plugin/buildscript
 * configurations remain separate acceptance gates.
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
        // Captured exact Gradle component sets: debug 109, release 108, test 118
        // modules (including BOM/platform-only entries). One shared file
        // retains configuration membership rather than overwriting debug.
        configurations.matching {
            it.name == "debugRuntimeClasspath" ||
                it.name == "releaseRuntimeClasspath" ||
                it.name == "debugAndroidTestRuntimeClasspath"
        }.configureEach {
            resolutionStrategy.activateDependencyLocking()
        }
        dependencyLocking {
            lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
        }
    }
}

/**
 * Mandatory Android-release graph qualification piggybacking on the EXISTING
 * PR SDK/Gradle job, without packaging or signing a release artifact. The
 * full resolvedArtifacts query causes Gradle to enforce STRICT release
 * lockfile state, not merely compile the configuration's Kotlin DSL.
 */
tasks.register("androidReleaseRuntimeLockCheck") {
    group = "verification"
    doLast {
        require(project.findProperty("withAndroid") == "true" &&
            project.findProject(":app") != null) { "ANDROID_APP_NOT_INCLUDED" }
        val runtime = project(":app").configurations.getByName("releaseRuntimeClasspath")
        require(runtime.isCanBeResolved) { "ANDROID_RELEASE_RUNTIME_NOT_RESOLVABLE" }
        val artifacts = runtime.resolvedConfiguration.resolvedArtifacts
        val count = runtime.incoming.resolutionResult.allComponents.count {
            it.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier
        }
        require(artifacts.isNotEmpty() && count >= 70) {
            "INCOMPLETE_ANDROID_RELEASE_RUNTIME_RESOLUTION"
        }
        println("ANDROID_RELEASE_RUNTIME_LOCK_COMPONENTS_CHECKED=${count}")
        println("ANDROID_RELEASE_RUNTIME_ARTIFACTS_RESOLVED=${artifacts.size}")
        println("ANDROID_RELEASE_RUNTIME_LOCK_TRUST=VERSION_SELECTION_NOT_PUBLISHER_AUTHENTICITY")
    }
}
subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidReleaseRuntimeLockCheck"))
    }
}

/**
 * W13/#326: hash the exact Android *release-runtime* external artifact bytes,
 * independently from debug. This is a deterministic LOCAL inventory for
 * later independently sourced publisher checksum review; it is NOT trusted
 * Gradle verification metadata, a signed release or an authenticated SBOM.
 * The existing lintDebug CI lane already resolves this locked configuration.
 */
tasks.register("androidReleaseRuntimeArtifactInventory") {
    group = "verification"
    description = "Hash resolved Android release runtime external artifacts for later publisher review"
    val report = layout.buildDirectory.file("reports/dependencies/android-release-runtime-artifacts.tsv")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        require(project.findProperty("withAndroid") == "true" &&
            project.findProject(":app") != null) { "ANDROID_APP_NOT_INCLUDED" }
        val app = project(":app")
        val runtime = app.configurations.findByName("releaseRuntimeClasspath")
            ?: error("MISSING_ANDROID_RELEASE_RUNTIME")
        require(runtime.isCanBeResolved) { "ANDROID_RELEASE_RUNTIME_NOT_RESOLVABLE" }
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
                val fields = listOf(":app:releaseRuntimeClasspath", id.group,
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
        require(rows.isNotEmpty()) { "NO_ANDROID_RELEASE_RUNTIME_ARTIFACTS" }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "# Expository Android release runtime artifact inventory V1\n" +
            "# scope\tgroup\tname\tversion\ttype\tclassifier\tsha256\n" +
            rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        println("ANDROID_RELEASE_RUNTIME_ARTIFACT_INVENTORY_ROWS=${rows.size}")
        println("ANDROID_RELEASE_RUNTIME_ARTIFACT_INVENTORY_PATH=build/reports/dependencies/android-release-runtime-artifacts.tsv")
        println("ANDROID_RELEASE_RUNTIME_ARTIFACT_INVENTORY_TRUST=LOCAL_HASHES_NOT_PUBLISHER_VERIFICATION")
    }
}

// Piggyback on the existing required Android compile/lint lane. Do not create
// an additional SDK download, Android VM or optional release packaging job.
subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidReleaseRuntimeArtifactInventory"))
    }
}

/**
 * W13/#326: snapshot actual EXTERNAL Android instrumentation-test runtime
 * artifacts. Production debug/release runtime inventories exclude test-only
 * libraries and cannot establish the instrumented APK's resolved graph.
 * This hashes current LOCAL bytes for future independent provenance review;
 * it does not authenticate publication, lock versions or sign an APK.
 *
 * Use the existing mandatory Android CI SDK/Gradle job, no extra workflow.
 */
tasks.register("androidTestRuntimeArtifactInventory") {
    group = "verification"
    description = "Hash resolved Android instrumentation-test runtime external artifacts for later publisher review"
    val report = layout.buildDirectory.file("reports/dependencies/android-test-runtime-artifacts.tsv")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        require(project.findProperty("withAndroid") == "true" &&
            project.findProject(":app") != null) { "ANDROID_APP_NOT_INCLUDED" }
        val app = project(":app")
        val runtime = app.configurations.findByName("debugAndroidTestRuntimeClasspath")
            ?: error("MISSING_ANDROID_TEST_RUNTIME")
        require(runtime.isCanBeResolved) { "ANDROID_TEST_RUNTIME_NOT_RESOLVABLE" }
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
                val fields = listOf(":app:debugAndroidTestRuntimeClasspath", id.group,
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
        require(rows.isNotEmpty()) { "NO_ANDROID_TEST_RUNTIME_ARTIFACTS" }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "# Expository Android instrumentation-test runtime artifact inventory V1\n" +
            "# scope\tgroup\tname\tversion\ttype\tclassifier\tsha256\n" +
            rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        println("ANDROID_TEST_RUNTIME_ARTIFACT_INVENTORY_ROWS=${rows.size}")
        println("ANDROID_TEST_RUNTIME_ARTIFACT_INVENTORY_PATH=build/reports/dependencies/android-test-runtime-artifacts.tsv")
        println("ANDROID_TEST_RUNTIME_ARTIFACT_INVENTORY_TRUST=LOCAL_HASHES_NOT_PUBLISHER_VERIFICATION")
    }
}


subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidTestRuntimeArtifactInventory"))
    }
}

/**
 * W13/#326: observe the full external Android instrumentation-test runtime
 * MODULE COMPONENT graph, including BOM/platform nodes which have no artifact
 * file and therefore do not appear in the earlier 90-artifact hash inventory.
 * The reviewed 118-component graph is now STRICT-locked. This report lists
 * selected versions for audit, without proving independent publisher artifact
 * authenticity, build portability or release reproducibility.
 *
 * The only executor is the existing required Android lint/compile CI lane.
 */
tasks.register("androidTestRuntimeLockBaseline") {
    group = "verification"
    description = "Verify exact external Android instrumentation runtime locked component selections"
    val report = layout.buildDirectory.file(
        "reports/dependencies/android-test-runtime-lock-candidates.txt")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        require(project.findProperty("withAndroid") == "true" &&
            project.findProject(":app") != null) { "ANDROID_APP_NOT_INCLUDED" }
        val runtime = project(":app").configurations
            .findByName("debugAndroidTestRuntimeClasspath")
            ?: error("MISSING_ANDROID_TEST_RUNTIME")
        require(runtime.isCanBeResolved) { "ANDROID_TEST_RUNTIME_NOT_RESOLVABLE" }
        val rows = runtime.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as?
                org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { id ->
                require(listOf(id.group, id.module, id.version).all {
                    it.isNotBlank() && !it.contains('\t') &&
                        !it.contains('\r') && !it.contains('\n')
                }) { "INVALID_ANDROID_TEST_RUNTIME_MODULE" }
                "${id.group}:${id.module}:${id.version}=debugAndroidTestRuntimeClasspath"
            }.toSortedSet()
        require(rows.size >= 70) { "INCOMPLETE_ANDROID_TEST_RUNTIME_MODULE_GRAPH" }
        val file = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "# Observed Android instrumentation runtime module lock candidates V1\n" +
            "# STRICT test runtime versions; no independent publisher artifact trust is established.\n" +
            rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        println("ANDROID_TEST_RUNTIME_LOCK_CANDIDATE_COMPONENTS=${rows.size}")
        println("ANDROID_TEST_RUNTIME_LOCK_CANDIDATE_BEGIN")
        rows.forEach { println(it) }
        println("ANDROID_TEST_RUNTIME_LOCK_CANDIDATE_END")
        println("ANDROID_TEST_RUNTIME_STRICT_LOCK_COMPONENTS_CHECKED=${rows.size}")
        println("ANDROID_TEST_RUNTIME_LOCK_CANDIDATE_TRUST=STRICT_VERSION_LOCK_NOT_PUBLISHER_AUTHENTICATION")
    }
}
subprojects {
    tasks.matching { it.name == "lintDebug" }.configureEach {
        dependsOn(rootProject.tasks.named("androidTestRuntimeLockBaseline"))
    }
}
