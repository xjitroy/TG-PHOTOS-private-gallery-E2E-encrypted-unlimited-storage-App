import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val apiId = (System.getenv("TELEGRAM_API_ID") ?: local.getProperty("TELEGRAM_API_ID", "")).toIntOrNull() ?: 0
val apiHash = System.getenv("TELEGRAM_API_HASH") ?: local.getProperty("TELEGRAM_API_HASH", "")
val releaseStorePath = System.getenv("TG_PHOTOS_RELEASE_KEYSTORE")
val releaseStorePassword = System.getenv("TG_PHOTOS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("TG_PHOTOS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("TG_PHOTOS_RELEASE_KEY_PASSWORD")
require(apiHash.isEmpty() || apiHash.matches(Regex("[a-fA-F0-9]{32}"))) {
    "Invalid Telegram API hash format"
}
ktlint { version.set("1.7.1") }

android {
    namespace = "app.televault"
    compileSdk = 36
    defaultConfig {
        applicationId = providers.gradleProperty("televault.applicationId").getOrElse("app.televault")
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "0.19.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas", "room.incremental" to "true")
            }
        }
        buildConfigField("int", "TELEGRAM_API_ID", apiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$apiHash\"")
    }
    signingConfigs.getByName("debug") {
        val compatible = System.getenv("TG_PHOTOS_SIGNING_KEYSTORE")?.let { file(it) } ?: rootProject.file(".tools/signing/debug.keystore")
        if (compatible.isFile) storeFile = compatible
        // A fresh exported-source checkout can use the standard developer debug identity.
        // It cannot update the owner's install without the compatible private signing key.
    }
    val productionSigning = signingConfigs.create("production") {
        releaseStorePath?.let { storeFile = rootProject.file(it) }
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
    }
    buildTypes {
        getByName("release") {
            applicationIdSuffix = ".photos"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = productionSigning
        }
        create("hardened") {
            initWith(getByName("release"))
            applicationIdSuffix = ""
            versionNameSuffix = "-update"
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFile("preview-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Fail before building an unsigned or development-signed production package. The compatible
// preview remains a separate path; release credentials are never printed or copied into exports.
val validateReleaseInputs = tasks.register("validateReleaseInputs") {
    group = "verification"
    doLast {
        check(apiId > 0 && apiHash.matches(Regex("[a-fA-F0-9]{32}"))) { "Release requires valid private Telegram client configuration." }
        check(!releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            "Release requires TG_PHOTOS_RELEASE_KEYSTORE, TG_PHOTOS_RELEASE_STORE_PASSWORD, TG_PHOTOS_RELEASE_KEY_ALIAS and TG_PHOTOS_RELEASE_KEY_PASSWORD; no development-key fallback is allowed."
        }
        val store = rootProject.file(releaseStorePath!!)
        check(store.isFile) { "Release keystore is unavailable." }
        val keyStore = KeyStore.getInstance(store, releaseStorePassword!!.toCharArray())
        check(keyStore.isKeyEntry(releaseKeyAlias)) { "Release signing alias is unavailable." }
        val privateKey = keyStore.getKey(releaseKeyAlias, releaseKeyPassword!!.toCharArray())
        check(privateKey is PrivateKey) { "Release signing private key is unavailable." }
        val certificate = keyStore.getCertificate(releaseKeyAlias)
        val subject = (certificate as? X509Certificate)?.subjectX500Principal?.name.orEmpty()
        check(!releaseKeyAlias.equals("androiddebugkey", ignoreCase = true) && !subject.contains("CN=Android Debug", ignoreCase = true)) {
            "A standard Android development signing identity is not a production signing key."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        check(digest != "2fd27d17e3708fe40ec693d7887ec07e05cef67f782ddef9a5933a6a9d1d1e66") {
            "The existing development certificate cannot be silently promoted to production. Plan signing and installed-app migration explicitly."
        }
        val expected = rootProject.file("config/public-signing-cert.sha256").takeIf { it.isFile }?.readText()?.trim()
        check(expected != null && digest == expected) { "Public signing certificate does not match the pinned update identity. Restore the established public key; do not replace it silently." }
    }
}
tasks.configureEach {
    if (name in setOf("preReleaseBuild", "validateSigningRelease", "assembleRelease", "bundleRelease", "packageRelease")) dependsOn(validateReleaseInputs)
}
dependencies {
    implementation(files("libs/zip4j-2.11.5.jar"))
    implementation(project(":core:domain"))
    implementation(project(":core:telegram"))
    implementation(project(":core:crypto"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.datastore)
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation(libs.room.runtime)
    annotationProcessor("androidx.room:room-compiler:2.8.3")
    implementation(libs.work)
    implementation(libs.media3)
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
