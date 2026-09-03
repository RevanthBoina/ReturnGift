import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

fun readLocalOrEnvString(key: String, defaultValue: String = ""): String {
    val props = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    return System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: props.getProperty(key, defaultValue).trim()
}

fun readLocalOrEnvInt(key: String, defaultValue: Int): Int {
    return readLocalOrEnvString(key).toIntOrNull() ?: defaultValue
}

android {
    namespace = "com.returngift.agent"
    
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        ignoreWarnings = true
        checkAllWarnings = false
        warningsAsErrors = false
        ignoreTestSources = true
        baseline = file("lint-baseline.xml")
        // Disable checks for known pre-existing issues
        disable += "MissingPermission"  // Bluetooth permissions handled at runtime
        disable += "NewApi"  // Pre-existing API level annotations needed for minSdk=28
        disable += "LocalContextGetResourceValueCall"  // Compose context usage pattern
        disable += "QueryAllPackagesPermission"  // QUERY_ALL_PACKAGES required for package visibility on older Android
    }
    
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    ndkVersion = "27.0.12077973"

    signingConfigs {
        create("release") {
            fun readSigningValue(key: String): String {
                val envVal = System.getenv(key)
                if (!envVal.isNullOrBlank()) return envVal.trim()
                val rootProps = Properties().apply {
                    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
                }
                val rootVal = rootProps.getProperty(key)
                if (!rootVal.isNullOrBlank()) return rootVal.trim()
                val appProps = Properties().apply {
                    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
                }
                return appProps.getProperty(key, "").trim()
            }
            val keystorePath = readSigningValue("KEYSTORE_FILE")
            if (keystorePath.isNotEmpty()) {
                val kFile = File(keystorePath)
                storeFile = if (kFile.isAbsolute) kFile else rootProject.file(keystorePath)
                storePassword = readSigningValue("KEYSTORE_PASSWORD")
                keyAlias = readSigningValue("KEY_ALIAS")
                keyPassword = readSigningValue("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.returngift.agent"
        minSdk = 28
        targetSdk = 36
        versionCode = readLocalOrEnvInt("RETURNGIFT_VERSION_CODE", 30000)
        versionName = readLocalOrEnvString("RETURNGIFT_VERSION_NAME", "3.0.0")
        buildConfigField("String", "VERSION_INFO", getVersionGit())
        buildConfigField("String", "APP_ORIGIN", "\"ReturnGift — private internal build\"")
        buildConfigField("String", "BUILD_FINGERPRINT", "\"${getBuildFingerprint()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional: shrink the installed APK by dropping native-library ABIs you don't
        // need. Unset by default, which keeps a universal APK (all ABIs) — required for
        // CI/public release builds, since you don't know every installer's device.
        // For a single personal-device build, set RETURNGIFT_ABI (env var or
        // local.properties), e.g. RETURNGIFT_ABI=arm64-v8a — covers effectively every
        // Android phone sold since ~2017 and is the biggest lever on install size, since
        // the on-device model runtime ships native .so files per ABI.
        val restrictAbi = readLocalOrEnvString("RETURNGIFT_ABI")
        if (restrictAbi.isNotEmpty()) {
            ndk {
                abiFilters += restrictAbi.split(",").map { it.trim() }
            }
        }
    }


    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/io.netty.versions.properties",
                "**/descriptor.proto",
                "mozilla/public-suffix-list.txt",
            )
        }
    }

    // NOTE: ABI splits were removed. With splits enabled, the release variant produced
    // multiple outputs (per-ABI + universal) while onVariants below forced the SAME
    // filename ("ReturnGift-release.apk") onto every output, colliding in the output
    // directory and stalling assembleRelease in CI — so the v2.0.0 release published
    // with NO APK asset, breaking both the README download link and the in-app updater
    // (which both target .../releases/latest/download/ReturnGift-release.apk).
    // A single universal APK is what the auto-update flow needs anyway, since the
    // updater downloads one APK that must install on any device ABI. Re-add per-ABI
    // splits only with ABI-aware output naming and an ABI-aware updater.

    // Unit tests run on a plain JVM where android.* stubs throw "not mocked" by default.
    // Production code logs through XLog -> android.util.Log; without this flag every test
    // exercising a logged code path (e.g. WorkflowHistoryRetentionTest) fails with
    // RuntimeException instead of testing real behaviour. returnDefaultValues makes
    // unmocked android methods return 0/false/null (a safe no-op for logging) per
    // https://developer.android.com/training/testing/unit-tests/local-unit-tests#error-not-mocked
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Tier-1 golden corpus lives at repo-root fixtures/ and is read by the JVM golden-corpus
// test under testDebugUnitTest. Expose it as a test-resources root so the file lands on the
// unit-test classpath independent of the launch CWD.
android {
    sourceSets {
        getByName("test") {
            resources.srcDir(file("../fixtures"))
        }
    }
}

// L3 fix: previously, a missing local.properties / env signing config silently produced
// an unsigned (or misconfigured) release build with no warning until Play Store rejection
// or a signature mismatch on update. Fail fast and explicitly instead, but only when a
// release-signing task is actually requested — debug builds must not be affected.
gradle.taskGraph.whenReady {
    val requestsReleaseSigning = allTasks.any { task ->
        task.name.contains("Release") &&
            (task.name.startsWith("assemble") || task.name.startsWith("bundle") || task.name.startsWith("package"))
    }
    if (requestsReleaseSigning) {
        val releaseSigning = android.signingConfigs.getByName("release")
        if (releaseSigning.storeFile == null) {
            throw GradleException(
                "Release build requested but no signing config is present.\n" +
                "Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD as environment " +
                "variables, or add them to local.properties, before building a release artifact.\n" +
                "See RELEASING.md for details."
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.gson)
    implementation(libs.androidx.security.crypto)


    implementation(libs.oapi.sdk)
    implementation(libs.dingtalk)


    // LangChain4j (exclude JDK http-client, use OkHttp adapter for Android)
    implementation(libs.langchain4j.core)
    implementation(libs.langchain4j.openai) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.langchain4j.anthropic) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.utilcode)
    implementation(libs.ok2curl)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.mmkv)
    implementation(libs.adapter)
    implementation(libs.glide)
    implementation(libs.glide.transformations)
    implementation(libs.easyfloat)


    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // LiteRT-LM on-device LLM inference (Google AI Edge)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")

    // ZXing 二维码/条形码扫描
    implementation(libs.zxing)

    // NanoHTTPD 嵌入式 HTTP 服务器（局域网配置服务）
    implementation(libs.nanohttpd)

    // SnakeYAML for parsing skill YAML files
    implementation(libs.snakeyaml)


    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.register("injectBuildFingerprint") {
    doLast {
        val gitHash = try {
            val p = Runtime.getRuntime().exec("git rev-parse HEAD")
            val r = BufferedReader(InputStreamReader(p.inputStream))
            r.readLine()?.trim() ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val ts = System.currentTimeMillis()
        val builder = System.getenv("BUILDER_ID") ?: System.getProperty("user.name") ?: "local"
        val fp = "t=$ts\nc=$gitHash\nb=$builder\nv=${android.defaultConfig.versionName}"
        val hexEncoded = fp.toByteArray().joinToString("") { "%02x".format(it) }
        // Must NOT be a dotfile: aapt silently excludes assets starting with '.' from
        // the packaged APK (verified missing from v2.2.0 release APK).
        file("src/main/assets/build_fingerprint.txt").apply {
            parentFile.mkdirs()
            writeText(hexEncoded)
        }
    }
}
tasks.named("preBuild") { dependsOn("injectBuildFingerprint") }

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = android.defaultConfig.versionName ?: "0.0.0"
            val baseName = if (variant.buildType == "release") {
                "ReturnGift-release"
            } else {
                "ReturnGift_v${versionName}_${getDateTime()}"
            }
            // Use filter suffix for split APKs, plain name for universal
            val filterName = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val fileName = if (filterName != null) {
                "${baseName}-${filterName}.apk"
            } else {
                "${baseName}.apk"
            }
            println("output file name: $fileName")
            output.outputFileName.set(fileName)
        }
    }
}

fun getVersionGit(): String {
    val process1 = Runtime.getRuntime().exec("git rev-parse --abbrev-ref HEAD")
    val reader1 = BufferedReader(InputStreamReader(process1.inputStream))
    val branch = reader1.readLine()?.trim()
    reader1.close()

    val process2 = Runtime.getRuntime().exec("git rev-parse HEAD")
    val reader2 = BufferedReader(InputStreamReader(process2.inputStream))
    val sha1 = reader2.readLine()?.trim()
    reader2.close()
    // 将数据拼接起来，如果只需要SHA-1 那么就可以不执行process1命令
    return "\"" + branch + "_" + sha1 + "\""
}

fun getBuildFingerprint(): String {
    val gitHash = try {
        val p = Runtime.getRuntime().exec("git rev-parse --short HEAD")
        val r = BufferedReader(InputStreamReader(p.inputStream))
        r.readLine()?.trim() ?: "unknown"
    } catch (_: Exception) { "unknown" }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    val builder = System.getenv("BUILDER_ID") ?: System.getProperty("user.name") ?: "local"
    return "$gitHash|$ts|$builder"
}

fun getDateTime(): String {
    val df = SimpleDateFormat("yyyyMMdd_HHmmss");
    return df.format(Date());
}

fun getParameter(key: String, defaultValue: String): String {
    var value = defaultValue
    val hasProperty = project.hasProperty(key)
    if (hasProperty) {
        val property = project.properties[key] as String?
        if (!property.isNullOrEmpty()) {
            value = property
            println("get property[$key]from project:$value")
            return value
        }
    }
    val localPropertiesFile = project.rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        val hasLocalProperty = localProperties.containsKey(key)
        if (hasLocalProperty) {
            val property = localProperties[key] as String?
            if (!property.isNullOrEmpty()) {
                value = property
                println("get property[$key]from local:$value")
                return value
            }
        }
    }
    println("get property[$key] from default:$value")
    return value
}
