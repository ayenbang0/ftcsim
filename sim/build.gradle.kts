/*
 * The FTCSim application module.
 *
 * Besides the simulator itself this build script wires in the *team's* library
 * dependencies (Pedro Pathing, FTCLib, ...) by reading the dependency
 * declarations from the team repository's Gradle files, so the simulator's
 * classpath matches what the robot controller app would have.
 */
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.LibraryElements
import java.util.zip.ZipFile

plugins {
    application
    java
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(17) }

val repoDir: File? = rootProject.extra["repoDir"] as File?
val ftcSdkVersion: String = rootProject.extra["ftcSdkVersion"] as String

// ---------------------------------------------------------------------------
// AAR support: Android library archives are turned into plain jars
// (classes.jar) so a JVM project can consume them.
// ---------------------------------------------------------------------------
val artifactType = Attribute.of("artifactType", String::class.java)

abstract class AarToJarTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val aar = inputArtifact.get().asFile
        ZipFile(aar).use { zf ->
            val base = aar.name.removeSuffix(".aar")
            val classes = zf.getEntry("classes.jar")
            if (classes != null) {
                val out = outputs.file("$base-classes.jar")
                zf.getInputStream(classes).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            for (e in zf.entries()) {
                if (!e.isDirectory && e.name.startsWith("libs/") && e.name.endsWith(".jar")) {
                    val out = outputs.file("$base-" + e.name.substringAfterLast('/'))
                    zf.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
                }
            }
        }
    }
}

// Treat "aar" library elements as compatible with "jar" so variant matching succeeds.
class AarLibraryElementsCompatRule : AttributeCompatibilityRule<LibraryElements> {
    override fun execute(details: CompatibilityCheckDetails<LibraryElements>) {
        val consumer = details.consumerValue?.name
        val producer = details.producerValue?.name
        if (consumer == LibraryElements.JAR && producer == "aar") details.compatible()
    }
}

dependencies {
    attributesSchema {
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE) {
            compatibilityRules.add(AarLibraryElementsCompatRule::class.java)
        }
    }
    registerTransform(AarToJarTransform::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
}

// ---------------------------------------------------------------------------
// Team dependencies parsed from the team repository.
// ---------------------------------------------------------------------------
val teamRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(artifactType, "jar")
    // Provided by FTCSim itself / not usable on the desktop:
    exclude(group = "org.firstinspires.ftc")
    exclude(group = "androidx.appcompat")
    exclude(group = "androidx.core")
    exclude(group = "androidx.annotation")
    exclude(group = "androidx.lifecycle")
    exclude(group = "androidx.activity")
    exclude(group = "androidx.fragment")
    exclude(group = "com.android.support")
    exclude(group = "com.google.android")
    exclude(group = "com.bylazar")               // Panels: shimmed by FTCSim
    exclude(group = "com.bylazar.sloth")
    exclude(group = "com.acmerobotics.dashboard") // FTC Dashboard: shimmed by FTCSim
    exclude(group = "org.team11260")             // fast-load: APK hot-swap tool
    exclude(group = "dev.frozenmilk.sinister")   // Sloth: APK hot-swap tool
    exclude(group = "dev.frozenmilk")
    exclude(group = "org.openftc")               // EasyOpenCV / native OpenCV
}

data class GradleDep(val notation: String, val configuration: String)

/** Extracts `implementation 'g:a:v'` style declarations and maven repository URLs. */
fun parseGradleDeps(file: File): Pair<List<GradleDep>, List<String>> {
    if (!file.exists()) return Pair(emptyList(), emptyList())
    val text = file.readText()
    val deps = ArrayList<GradleDep>()
    val repos = ArrayList<String>()
    val depRe = Regex("""^\s*(implementation|api|compileOnly|runtimeOnly)\s*\(?\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE)
    for (m in depRe.findAll(text)) {
        val notation = m.groupValues[2]
        if (notation.count { it == ':' } >= 2) deps.add(GradleDep(notation, m.groupValues[1]))
    }
    val repoRe = Regex("""url\s*=?\s*(?:uri\()?\s*['"]([^'"]+)['"]""")
    for (m in repoRe.findAll(text)) repos.add(m.groupValues[1])
    return Pair(deps, repos)
}

val teamDeps = ArrayList<GradleDep>()
val teamRepos = LinkedHashSet<String>()
if (repoDir != null) {
    val files = listOf(
        File(repoDir, "build.dependencies.gradle"),
        File(repoDir, "TeamCode/build.gradle"),
        File(repoDir, "TeamCode/build.gradle.kts"),
        File(repoDir, "build.gradle"),
        File(repoDir, "build.gradle.kts")
    )
    // Also honour a per-repo override file in FTCSim (never modifies the team repo)
    val override = rootProject.file("configs/${repoDir.name}.deps.gradle")
    for (f in files + override) {
        val (d, r) = parseGradleDeps(f)
        teamDeps.addAll(d)
        teamRepos.addAll(r)
    }
}

repositories {
    for (url in teamRepos) {
        if (url.contains("matthewo.tech")) continue
        maven { setUrl(url) }
    }
}

dependencies {
    implementation(project(":android-compat"))
    implementation(project(":ftc-sdk")) // generated R classes and resource strings
    implementation(project(path = ":ftc-sdk", configuration = "desktopSdk"))
    implementation("org.json:json:20240303")
    implementation("com.google.code.gson:gson:2.10.1")
    // Kotlin runtime for Kotlin-based team libraries (FTCLib, okhttp, ...)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")

    for (d in teamDeps) {
        val g = d.notation.substringBefore(":")
        // modules FTCSim replaces with its own implementation (Android-bound web tuner)
        val shimmedModules = setOf("com.pedropathing:tuning")
        val isPresent = teamRuntime.excludeRules.any { it.group == g } || shimmedModules.any { d.notation.startsWith("$it:") }
        if (!isPresent) {
            logger.lifecycle("FTCSim: team dependency $d")
            teamRuntime(d.notation)
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


application {
    mainClass.set("ftcsim.Main")
    applicationDefaultJvmArgs = listOf("-Xmx1g", "-Djava.awt.headless=true")
}

/*
 * Windows desktop packaging of FTCSim (part of sim/build.gradle.kts).
 *
 *   gradlew.bat packageExe
 *
 * produces, in <repo>/dist:
 *   FTCSim/FTCSim.exe        GUI launcher (opens an app-mode Edge/Chrome window, see ftcsim.desktop.DesktopMain)
 *   FTCSim/FTCSim-cli.exe    same program with a console (batch runs, --self-test, --help)
 *   FTCSim/runtime/          jlink'ed Java runtime (includes jdk.compiler: OpModes are compiled in-process)
 *   FTCSim/app/              the simulator jar and its libraries
 *   FTCSim/configs, samples, README.txt
 *   FTCSim-windows-x64.zip   the folder above
 *
 * Needs a JDK 17+ that has jpackage, jlink and jmods/ (the JetBrains Runtime 21 in ~/.jdks/jbr-21.0.11 is known
 * good). It is looked up in: -Pftcsim.jdkHome=<dir>, env FTCSIM_JDK, -Pftcsim.jdk=<dir> (when a path), the sim
 * toolchain, the JVM running Gradle, JAVA_HOME. WiX is not required: only an app-image is built (no installer).
 */


val desktopBuildDir = layout.buildDirectory.dir("desktop").get().asFile
val desktopDistDir = File(rootProject.projectDir, "dist")
val desktopImageDir = File(desktopDistDir, "FTCSim")
val desktopModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.logging", "java.management", "java.naming",
    "java.net.http", "java.prefs", "java.sql", "java.xml",
    "jdk.compiler", "jdk.httpserver", "jdk.unsupported", "jdk.zipfs", "jdk.crypto.ec"
)

fun isJdkWithPackaging(home: File): Boolean =
    File(home, "bin/jpackage.exe").isFile && File(home, "bin/jlink.exe").isFile && File(home, "jmods").isDirectory

fun findPackagingJdk(): File {
    val tried = ArrayList<String>()
    val candidates = ArrayList<Pair<String, File?>>()
    fun path(source: String, value: String?) {
        if (!value.isNullOrBlank() && !value.trim().all { it.isDigit() }) candidates.add(source to File(value.trim()))
    }
    path("-Pftcsim.jdkHome", providers.gradleProperty("ftcsim.jdkHome").orNull)
    path("env FTCSIM_JDK", System.getenv("FTCSIM_JDK"))
    path("-Pftcsim.jdk", providers.gradleProperty("ftcsim.jdk").orNull) // a path only; a bare number is a toolchain version
    try {
        val javaExt = extensions.getByType(JavaPluginExtension::class.java)
        val launcher = extensions.getByType(JavaToolchainService::class.java).launcherFor(javaExt.toolchain).get()
        candidates.add("sim toolchain" to launcher.metadata.installationPath.asFile)
    } catch (e: Exception) {
        tried.add("sim toolchain: ${e.message}")
    }
    candidates.add("Gradle JVM (java.home)" to File(System.getProperty("java.home")))
    candidates.add("env JAVA_HOME" to System.getenv("JAVA_HOME")?.let { File(it) })
    for ((source, home) in candidates) {
        if (home == null) continue
        if (isJdkWithPackaging(home)) return home
        tried.add("$source = $home (needs bin/jpackage.exe, bin/jlink.exe and jmods/)")
    }
    throw GradleException(
        "FTCSim packaging needs a full JDK 17+ with jpackage, jlink and jmods. Pass -Pftcsim.jdkHome=<jdk dir> or set " +
            "FTCSIM_JDK (for example C:/Users/<you>/.jdks/jbr-21.0.11).\nChecked:\n  " + tried.joinToString("\n  ")
    )
}

/** Runs a tool, echoes its output at info level and fails with the output on a non-zero exit code. */
fun runTool(what: String, command: List<String>) {
    logger.lifecycle("FTCSim package: $what")
    logger.info(command.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
    val process = ProcessBuilder(command).directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    val rc = process.waitFor()
    if (out.isNotBlank()) logger.info(out.trim())
    if (rc != 0) throw GradleException("$what failed (exit code $rc):\n${command.joinToString(" ")}\n$out")
}

fun File.fwd(): String = absolutePath.replace('\\', '/')

val jarTask = tasks.named<Jar>("jar")
val runtimeLibs: Configuration = configurations.getByName("runtimeClasspath")

val desktopImage = tasks.register("desktopImage") {
    group = "distribution"
    description = "Builds the Windows app-image dist/FTCSim (FTCSim.exe, FTCSim-cli.exe, jlink runtime, jars)."
    dependsOn(jarTask)
    inputs.files(jarTask.map { it.outputs.files }).withPropertyName("simJar")
    inputs.files(runtimeLibs).withPropertyName("libs")
    inputs.dir(rootProject.file("configs")).withPropertyName("configs").optional()
    inputs.dir(rootProject.file("samples")).withPropertyName("samples").optional()
    inputs.dir(rootProject.file("packaging")).withPropertyName("packaging").optional()
    inputs.file(buildFile).withPropertyName("script")
    outputs.dir(desktopImageDir)

    doLast {
        val jdk = findPackagingJdk()
        logger.lifecycle("FTCSim package: JDK ${jdk.fwd()}")
        val exeSuffix = ".exe"
        val jlink = File(jdk, "bin/jlink$exeSuffix").fwd()
        val jpackage = File(jdk, "bin/jpackage$exeSuffix").fwd()

        // ---- 1. staging: app jars, bundle content
        val inputDir = File(desktopBuildDir, "input")
        val contentDir = File(desktopBuildDir, "content")
        val runtimeDir = File(desktopBuildDir, "runtime")
        val tempDir = File(desktopBuildDir, "jpackage-temp")
        for (d in listOf(inputDir, contentDir, runtimeDir, tempDir, desktopImageDir)) d.deleteRecursively()
        inputDir.mkdirs(); contentDir.mkdirs()

        val simJar = jarTask.get().archiveFile.get().asFile
        val libs = LinkedHashMap<String, File>()
        libs[simJar.name] = simJar
        var n = 0
        for (f in runtimeLibs.files) {
            if (!f.isFile) throw GradleException("Unexpected non-jar runtime classpath entry: $f")
            var name = f.name
            while (libs.containsKey(name)) name = "${++n}-${f.name}"
            libs[name] = f
        }
        for ((name, f) in libs) f.copyTo(File(inputDir, name), overwrite = true)
        logger.lifecycle("FTCSim package: ${libs.size} jars in ${inputDir.fwd()}")

        val configsSrc = rootProject.file("configs")
        if (configsSrc.isDirectory) {
            File(contentDir, "configs").mkdirs()
            configsSrc.listFiles { f -> f.isFile && f.extension == "json" && !f.name.endsWith(".local.json") }!!
                .forEach { it.copyTo(File(contentDir, "configs/${it.name}"), overwrite = true) }
        }
        val samplesSrc = rootProject.file("samples")
        if (samplesSrc.isDirectory) samplesSrc.copyRecursively(File(contentDir, "samples"), overwrite = true)
        val readme = rootProject.file("packaging/bundle-README.txt")
        if (readme.isFile) readme.copyTo(File(contentDir, "README.txt"), overwrite = true)
        for (n in listOf("FTCSim.bat", "FTCSim-cli.bat")) {
            val f = rootProject.file("packaging/$n")
            if (f.isFile) f.copyTo(File(contentDir, n), overwrite = true)
        }

        // ---- 2. jlink: minimal runtime (jdk.compiler is needed at run time for in-process OpMode compilation)
        runTool(
            "jlink runtime",
            listOf(
                jlink, "--module-path", File(jdk, "jmods").fwd(), "--add-modules", desktopModules.joinToString(","),
                "--output", runtimeDir.fwd(), "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6"
            )
        )

        // ---- 3. jpackage app-image with a GUI launcher and a console launcher
        val icon = rootProject.file("packaging/ftcsim.ico")
        val mainClass = "ftcsim.desktop.DesktopMain"
        val homeOpt = "-Dftcsim.home=\$ROOTDIR"
        val cliProps = File(desktopBuildDir, "FTCSim-cli.properties")
        cliProps.writeText(
            buildString {
                appendLine("main-jar=${simJar.name}")
                appendLine("main-class=$mainClass")
                appendLine("win-console=true")
                if (icon.isFile) appendLine("icon=${icon.fwd()}")
                appendLine("java-options=-Dftcsim.console=true -Xmx1g $homeOpt")
            }
        )
        val cmd = arrayListOf(
            jpackage, "--type", "app-image", "--name", "FTCSim", "--input", inputDir.fwd(),
            "--main-jar", simJar.name, "--main-class", mainClass, "--runtime-image", runtimeDir.fwd(),
            "--dest", desktopDistDir.fwd(), "--temp", tempDir.fwd(),
            "--description", "FTC robot code simulator", "--app-version", "1.0",
            "--java-options", "-Xmx1g", "--java-options", homeOpt,
            "--add-launcher", "FTCSim-cli=${cliProps.fwd()}"
        )
        if (icon.isFile) { cmd.add("--icon"); cmd.add(icon.fwd()) }
        val content = contentDir.listFiles()!!.sortedBy { it.name }
        if (content.isNotEmpty()) { cmd.add("--app-content"); cmd.add(content.joinToString(",") { it.fwd() }) }
        desktopDistDir.mkdirs()
        runTool("jpackage app-image", cmd)

        val exe = File(desktopImageDir, "FTCSim.exe")
        if (!exe.isFile) throw GradleException("jpackage did not produce $exe")
        tempDir.deleteRecursively()
        val sizeMb = desktopImageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
        logger.lifecycle("FTCSim package: app-image ${desktopImageDir.fwd()} ($sizeMb MB)")
    }
}

tasks.register<Zip>("packageExe") {
    group = "distribution"
    description = "Builds dist/FTCSim/FTCSim.exe (app-image) and dist/FTCSim-windows-x64.zip."
    dependsOn(desktopImage)
    archiveFileName.set("FTCSim-windows-x64.zip")
    destinationDirectory.set(desktopDistDir)
    isZip64 = true
    from(desktopImageDir) { into("FTCSim") }
    doLast {
        logger.lifecycle("FTCSim package: ${File(desktopImageDir, "FTCSim.exe").fwd()}")
        logger.lifecycle("FTCSim package: ${archiveFile.get().asFile.fwd()}")
    }
}


tasks.named<JavaExec>("run") {
    classpath += teamRuntime
    if (repoDir != null) systemProperty("ftcsim.repo", repoDir.absolutePath)
    (rootProject.extra["autosDir"] as File?)?.let { systemProperty("ftcsim.autos", it.absolutePath) }
    systemProperty("ftcsim.home", rootProject.projectDir.absolutePath)
    standardInput = System.`in`
}

sourceSets.test { compileClasspath += teamRuntime; runtimeClasspath += teamRuntime }

tasks.test {
    useJUnitPlatform()
    classpath += teamRuntime
    systemProperty("ftcsim.home", rootProject.projectDir.absolutePath)
    if (repoDir != null) systemProperty("ftcsim.repo", repoDir.absolutePath)
}

/** Prints the full runtime classpath (used by the ftcsim launcher scripts). */
tasks.register("printClasspath") {
    dependsOn(tasks.named("classes"))
    doLast {
        val cp = sourceSets.main.get().runtimeClasspath + teamRuntime
        println("FTCSIM_CLASSPATH=" + cp.files.joinToString(File.pathSeparator))
    }
}
