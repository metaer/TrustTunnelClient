plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") version "2.2.0"
    id("maven-publish")
}

// Resolve the library version: -PttClientVersion / TT_CLIENT_VERSION env var ->
// git describe --tags --match v* -> 0.0.0-git fallback. This is an AAR, so there
// is no Android versionCode.
fun resolveTtClientVersion(project: Project): String {
    val override = (project.findProperty("ttClientVersion") as String?)
        ?: System.getenv("TT_CLIENT_VERSION")
    if (!override.isNullOrBlank()) {
        return override.trim()
    }
    return try {
        val proc = ProcessBuilder("git", "describe", "--tags", "--match", "v*")
            .directory(project.rootDir)
            .start()
        proc.waitFor()
        val described = proc.inputStream.bufferedReader().readText().trim()
        if (proc.exitValue() == 0 && described.isNotEmpty()) {
            described.removePrefix("v")
        } else {
            "0.0.0-git"
        }
    } catch (ignored: Exception) {
        "0.0.0-git"
    }
}

val ttClientVersion = resolveTtClientVersion(project)
version = ttClientVersion

// Returns the Gradle property [name] (-P<name>=...) or, when it is not set, the environment
// variable [envName]. Blank values count as not set.
fun findPropertyOrEnv(project: Project, name: String, envName: String): String? =
    sequenceOf(project.findProperty(name)?.toString(), System.getenv(envName))
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()

// Prebuilt native libraries: -PttPrebuiltNativeDir=<dir> or TT_PREBUILT_NATIVE_DIR env var.
// <dir>/<abi>/libtrusttunnel_android.so (e.g. the `jni/` directory of a published AAR) is
// packaged as-is instead of building the native code, so neither CMake nor the NDK is needed.
// A relative <dir> is resolved against platform/android. When unset, the regular CMake build runs.
val ttPrebuiltNativeDir: File? = findPropertyOrEnv(project, "ttPrebuiltNativeDir", "TT_PREBUILT_NATIVE_DIR")
    ?.let { rootProject.file(it) }
if (ttPrebuiltNativeDir != null) {
    require(ttPrebuiltNativeDir.isDirectory) { "ttPrebuiltNativeDir '$ttPrebuiltNativeDir' is not a directory" }
    logger.lifecycle("trusttunnel: using prebuilt native libraries from $ttPrebuiltNativeDir (CMake build disabled)")
}

// Maven repository the `release` publication is uploaded to: -PttPublishRepoUrl=<url> or
// TT_PUBLISH_REPO_URL env var. Defaults to the upstream GitHub Packages registry.
val ttPublishRepoUrl: String = findPropertyOrEnv(project, "ttPublishRepoUrl", "TT_PUBLISH_REPO_URL")
    ?: "https://maven.pkg.github.com/TrustTunnel/TrustTunnelClient"

android {
    namespace = "com.adguard.trusttunnel"
    compileSdk = 35
    if (ttPrebuiltNativeDir == null) {
        ndkVersion = "29.0.14206865"
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
        if (ttPrebuiltNativeDir == null) {
            externalNativeBuild {
                cmake {
                    targets += "trusttunnel_android"
                    arguments += "-DANDROID_STL=c++_static"
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                    arguments += "-DTT_CLIENT_VERSION=$ttClientVersion"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests {
            // Calls into the android.jar stubs (e.g. from logback-android) return default
            // values in JVM unit tests instead of throwing "Method ... not mocked".
            isReturnDefaultValues = true
        }
    }
    if (ttPrebuiltNativeDir == null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    } else {
        sourceSets {
            getByName("main") {
                jniLibs.srcDir(ttPrebuiltNativeDir)
            }
        }
        packaging {
            jniLibs {
                // Package the prebuilt libraries unchanged (a published AAR already ships them
                // stripped); this also keeps AGP from looking for the NDK strip tool.
                keepDebugSymbols += "**/*.so"
            }
        }
    }
}

// A library without the native code would fail at runtime, so refuse to hand one out. This is
// checked when the native libraries go to a consumer (the AAR, or an app that includes this project
// with includeBuild) rather than at configuration time, so that compilation, JVM unit tests and
// lint also work with an empty prebuilt directory.
if (ttPrebuiltNativeDir != null) {
    val prebuiltDir: File = ttPrebuiltNativeDir
    val packagingTaskNames = setOf(
        "copyDebugJniLibsProjectOnly",
        "copyReleaseJniLibsProjectOnly",
        "bundleDebugAar",
        "bundleReleaseAar",
    )
    tasks.matching { it.name in packagingTaskNames }.configureEach {
        doFirst {
            val abis = prebuiltDir.listFiles().orEmpty()
                .filter { File(it, "libtrusttunnel_android.so").isFile }
                .map { it.name }
                .sorted()
            if (abis.isEmpty()) {
                throw GradleException(
                    "ttPrebuiltNativeDir '$prebuiltDir' contains no <abi>/libtrusttunnel_android.so, " +
                        "refusing to package the library without native libraries"
                )
            }
            logger.lifecycle("trusttunnel: packaging prebuilt libtrusttunnel_android.so for ${abis.joinToString()}")
        }
    }
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("com.github.tony19:logback-android:2.0.0")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.0")

    implementation("com.akuleshov7:ktoml-core:0.7.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.adguard.trusttunnel"
                artifactId = "trusttunnel-client-android"
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(ttPublishRepoUrl)
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("USERNAME"))
                        .orNull
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("TOKEN"))
                        .orNull
                }
            }
        }
    }
}
