/*
 * Orchestrator Router - Android Build Integration
 * 
 * Copy this to app/build.gradle.kts or apply in your app module
 */

// ============================================================
// 1. Add to app/build.gradle.kts dependencies {}
// ============================================================
dependencies {
    // LiteRT (TensorFlow Lite successor) for on-device inference
    implementation("com.google.ai.edge.litert:litert:0.2.0")
    implementation("com.google.ai.edge.litert:litert-support:0.2.0")
    implementation("com.google.ai.edge.litert:litert-gpu-delegate:0.2.0")
    implementation("com.google.ai.edge.litert:litert-hexagon-delegate:0.2.0") // For Qualcomm NPU

    // SentencePiece tokenizer (choose one):
    // Option A: Official sentencepiece-android (requires JNI)
    // implementation("com.google.sentencepiece:sentencepiece-android:0.1.99")
    //
    // Option B: Pure Kotlin fallback (slow, for testing only)
    // implementation(project(":sentencepiece-kotlin"))

    // Gson for metadata parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines for async routing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

// ============================================================
// 2. Asset packaging for orchestrator resources
// ============================================================
android {
    // Ensure assets are not compressed (needed for mmap)
    aaptOptions {
        noCompress "bin", "tflite", "litertlm", "model", "spiece"
    }

    // Copy compute_centroids.py outputs to assets
    sourceSets {
        main {
            assets.srcDirs += ["src/main/assets", "$projectDir/../orchestrator_assets"]
        }
    }

    // Custom task to sync orchestrator assets
    tasks.register("syncOrchestratorAssets", Copy) {
        group = "build"
        description = "Copy orchestrator centroids + tokenizer to assets"

        from("../orchestrator_assets") {
            include "centroids.bin"
            include "centroids_meta.json"
            include "tokenizer.model"
        }
        into("src/main/assets/orchestrator")
    }

    // Hook into preBuild
    tasks.named("preBuild") {
        dependsOn("syncOrchestratorAssets")
    }
}

// ============================================================
// 3. Model files - place in app/src/main/assets/models/
// ============================================================
/*
app/src/main/assets/
├── models/
│   ├── functiongemma_base.tflite          # Frozen base with hidden states
│   └── functiongemma_encoder.tflite       # Optional distilled encoder
└── orchestrator/
    ├── centroids.bin                      # 4 x 768 float32 (or 1024)
    ├── centroids_meta.json                # Route names, thresholds
    └── tokenizer.model                    # SentencePiece model
*/

// ============================================================
// 4. ProGuard / R8 rules (add to proguard-rules.pro)
// ============================================================
/*
# LiteRT
-keep class com.google.ai.edge.litert.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# SentencePiece JNI
-keep class com.google.sentencepiece.** { *; }
-keep class * implements com.google.sentencepiece.SentencePieceProcessor { *; }

# Orchestrator
-keep class com.returngift.agent.orchestrator.** { *; }
*/