// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the [P3] configChanges pins for the 3 long-lived activities
 * (ComposeChatActivity, SettingsActivity, VaultActivity) are present in
 * the manifest. This is a pure file-read test — no Android runtime needed.
 *
 * If the working directory at test time is not the repo root, we walk up
 * to find `app/src/main/AndroidManifest.xml` (test JVM may chdir).
 */
class AndroidManifestConfigChangesTest {

    private val configChangesValue =
        "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|locale|layoutDirection|density|fontScale|uiMode"

    private fun readManifest(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "app/src/main/AndroidManifest.xml"),
            File(cwd, "../app/src/main/AndroidManifest.xml"),
            File(cwd, "../../app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?.readText()
            ?: error("AndroidManifest.xml not found relative to ${cwd.absolutePath}")
    }

    private fun activityBlock(manifest: String, activityName: String): String? {
        // Match the <activity ...> block whose android:name contains the given simple class name
        val regex = Regex(
            "<activity\\s+[^>]*android:name=\"[^\"]*${Regex.escape(activityName)}\"[^>]*/>"
        )
        return regex.find(manifest)?.value
    }

    @Test fun `ComposeChatActivity declares the full configChanges set`() {
        val manifest = readManifest()
        val block = activityBlock(manifest, "ComposeChatActivity")
        assertTrue("ComposeChatActivity <activity> block not found", block != null)
        assertTrue(
            "ComposeChatActivity missing configChanges=\"$configChangesValue\" — got: $block",
            block!!.contains("android:configChanges=\"$configChangesValue\""),
        )
    }

    @Test fun `SettingsActivity declares the full configChanges set`() {
        val manifest = readManifest()
        val block = activityBlock(manifest, "SettingsActivity")
        assertTrue("SettingsActivity <activity> block not found", block != null)
        assertTrue(
            "SettingsActivity missing configChanges=\"$configChangesValue\" — got: $block",
            block!!.contains("android:configChanges=\"$configChangesValue\""),
        )
    }

    @Test fun `VaultActivity declares the full configChanges set`() {
        val manifest = readManifest()
        val block = activityBlock(manifest, "VaultActivity")
        assertTrue("VaultActivity <activity> block not found", block != null)
        assertTrue(
            "VaultActivity missing configChanges=\"$configChangesValue\" — got: $block",
            block!!.contains("android:configChanges=\"$configChangesValue\""),
        )
    }
}
