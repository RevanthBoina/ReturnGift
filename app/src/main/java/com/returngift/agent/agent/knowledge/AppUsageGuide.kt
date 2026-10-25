// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface

/**
 * On-device app usage guide.
 *
 * One place that knows, for the apps people actually have on their phones:
 *  - which [AppSurface] the agent is dealing with (drives the micro-step planner),
 *  - how to get things done inside that app (short, model-readable steps),
 *  - how the app is spelled by the user (aliases).
 *
 * The same data feeds:
 *  1. the system prompt (compact section for the apps a task mentions), and
 *  2. Settings → App Permissions (per-app "how the agent will use this" hint),
 * so the UI and the model can never disagree about how an app works.
 *
 * Pure Kotlin — unit tested in `AppUsageGuideTest`.
 */
object AppUsageGuide {

    data class AppGuide(
        val packageName: String,
        val label: String,
        val surface: AppSurface,
        /** Lower-case tokens the user may type instead of the label. */
        val aliases: List<String>,
        /** Ordered, concrete actions the agent should take inside the app. */
        val tips: List<String>,
    ) {
        /** One-line hint for the settings row. */
        val shortHint: String get() = tips.firstOrNull().orEmpty()
    }

    private val GUIDES: List<AppGuide> = listOf(
        AppGuide(
            packageName = "com.android.chrome",
            label = "Chrome",
            surface = AppSurface.BROWSER_ADDRESS,
            aliases = listOf("chrome", "browser", "web", "google chrome"),
            tips = listOf(
                "Tap the address bar (\"Search or type URL\") → type the site/query → press Enter.",
                "Wait for the page to load; then read the screen once instead of re-observing repeatedly.",
                "Cookie/notification banners: tap \"Accept all\" or \"No thanks\", then continue.",
            ),
        ),
        AppGuide(
            packageName = "com.linkedin.android",
            label = "LinkedIn",
            surface = AppSurface.SOCIAL_POST,
            aliases = listOf("linkedin", "linked in"),
            tips = listOf(
                "Search: prefer the deep link linkedin://search?keywords=<term>; if the screen does not change, tap the search icon → type → Enter → open the first result row.",
                "Post: tap \"Start a post\" → type in the composer → tap \"Post\" → verify \"Post successful\".",
                "A \"Sign in\" / \"Join LinkedIn\" screen means the session expired: stop and ask the user to sign in — never type credentials.",
                "Cookie/terms banner: tap \"Accept\" once, then continue; do not re-tap it on later steps.",
                "Feed actions (like/comment) act on the post under the tapped text, not the screen centre.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            surface = AppSurface.VIDEO,
            aliases = listOf("youtube", "yt"),
            tips = listOf(
                "Search: tap the search icon → type the query → Enter → tap the first video row.",
                "Playback: tap the player once to show controls, then Play/Pause; back returns to results.",
                "Ads: wait for \"Skip ad\" (5s) and tap it before acting on the video.",
            ),
        ),
        AppGuide(
            packageName = "com.whatsapp",
            label = "WhatsApp",
            surface = AppSurface.CHAT_COMPOSER,
            aliases = listOf("whatsapp", "wa"),
            tips = listOf(
                "Message: open the chat → type in \"Type a message\" → tap the Send FAB (Enter adds a newline).",
                "New chat: tap the \"New chat\" FAB → search the contact name → tap the contact row.",
                "After sending, verify the bubble appears in the thread before finishing.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.gm",
            label = "Gmail",
            surface = AppSurface.EMAIL_COMPOSE,
            aliases = listOf("gmail", "email", "mail", "google mail"),
            tips = listOf(
                "Compose: tap the Compose FAB → fill \"To\" (Enter commits the address) → Subject → Body.",
                "Send: tap the paper-plane in the top bar, then verify the \"Message sent\" snackbar.",
                "Search mail: tap the search bar → type → Enter → open the first thread.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.apps.maps",
            label = "Maps",
            surface = AppSurface.MAPS,
            aliases = listOf("maps", "google maps", "directions"),
            tips = listOf(
                "Search: tap the search bar → type the place → Enter → tap the first place row.",
                "Directions: open the place → tap \"Directions\" → pick the transport mode.",
                "Long-press pins open a bottom sheet; read it once before tapping.",
            ),
        ),
        AppGuide(
            packageName = "com.android.vending",
            label = "Play Store",
            surface = AppSurface.PLAY_STORE,
            aliases = listOf("play store", "playstore", "google play", "store"),
            tips = listOf(
                "Search: tap the search bar → type the app name → Enter → tap the matching app row.",
                "Install: tap \"Install\" → wait for the progress → tap \"Open\" when it appears.",
                "Verify the developer/rating in the listing if several apps share the name.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.contacts",
            label = "Contacts",
            surface = AppSurface.SEARCH_FIELD,
            aliases = listOf("contacts", "phonebook", "address book"),
            tips = listOf(
                "Tap the search field → type the name → open the first contact row.",
                "Inside a contact use \"Call\" or \"Message\" rather than guessing coordinates.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.dialer",
            label = "Phone",
            surface = AppSurface.DIALER,
            aliases = listOf("phone", "dialer", "call"),
            tips = listOf(
                "Type the number on the keypad, then tap the green \"Call\" button.",
                "Verify the call screen (\"End call\" visible) before reporting success.",
            ),
        ),
        AppGuide(
            packageName = "com.android.settings",
            label = "Settings",
            surface = AppSurface.SETTINGS,
            aliases = listOf("settings", "system settings"),
            tips = listOf(
                "Use the search bar at the top to jump straight to a setting instead of scrolling.",
                "Toggle the switch, then verify its state changed before pressing Back.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.GoogleCamera",
            label = "Camera",
            surface = AppSurface.CAMERA,
            aliases = listOf("camera", "take a photo", "photo"),
            tips = listOf(
                "Grant camera permission if asked, then tap the shutter.",
                "Confirm with \"Done\"/\"Use photo\" so the capture is kept.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.apps.photos",
            label = "Photos",
            surface = AppSurface.FILE_PICKER,
            aliases = listOf("photos", "gallery", "pictures"),
            tips = listOf(
                "Pick the first matching item in the grid, then confirm \"Done\"/\"Open\".",
                "Use the search tab for a person/place instead of scrolling the whole library.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.documentsui",
            label = "Files",
            surface = AppSurface.FILE_PICKER,
            aliases = listOf("files", "documents", "downloads"),
            tips = listOf(
                "Navigate Downloads/Documents, tap the file, then \"Open\".",
                "Long-press gives move/delete/rename actions.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.calendar",
            label = "Calendar",
            surface = AppSurface.FORM,
            aliases = listOf("calendar", "event", "meeting"),
            tips = listOf(
                "Tap the + FAB → title → date/time → Save.",
                "Verify the new event shows on the day view before finishing.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.apps.messaging",
            label = "Messages",
            surface = AppSurface.CHAT_COMPOSER,
            aliases = listOf("messages", "sms", "text message"),
            tips = listOf(
                "\"Start chat\" → search the contact → type in \"Text message\" → tap Send.",
                "RCS/SMS switch does not change the composer flow.",
            ),
        ),
        AppGuide(
            packageName = "com.instagram.android",
            label = "Instagram",
            surface = AppSurface.SOCIAL_POST,
            aliases = listOf("instagram", "ig", "insta"),
            tips = listOf(
                "Post: + → Post → pick media → Next → caption → Share.",
                "DMs: paper-plane icon → search the person → type → Send.",
            ),
        ),
        AppGuide(
            packageName = "org.telegram.messenger",
            label = "Telegram",
            surface = AppSurface.CHAT_COMPOSER,
            aliases = listOf("telegram", "tg"),
            tips = listOf(
                "Type in \"Message\" → tap the Send icon (Enter adds a newline).",
                "Search: magnifier in the top bar → type the chat name → open the first row.",
            ),
        ),
        AppGuide(
            packageName = "com.spotify.music",
            label = "Spotify",
            surface = AppSurface.MEDIA,
            aliases = listOf("spotify", "music", "song", "playlist"),
            tips = listOf(
                "Search tab → type the song/artist → Enter → tap the first result → Play.",
                "Verify the now-playing bar at the bottom before finishing.",
            ),
        ),
        AppGuide(
            packageName = "com.google.android.googlequicksearchbox",
            label = "Google",
            surface = AppSurface.SEARCH_FIELD,
            aliases = listOf("google", "google app", "search"),
            tips = listOf(
                "Tap the search box → type → Enter; open the first result.",
                "Prefer Chrome for anything that needs a full page.",
            ),
        ),
    )

    fun all(): List<AppGuide> = GUIDES

    /** Guide for an installed package (exact first, then substring for variants). */
    fun forPackage(packageName: String?): AppGuide? {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return null
        GUIDES.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }?.let { return it }
        return GUIDES.firstOrNull {
            pkg.contains(it.packageName.lowercase()) || it.packageName.lowercase().contains(pkg)
        }
    }

    /** Surface for a package, or null when the guide has no opinion. */
    fun surfaceFor(packageName: String?): AppSurface? = forPackage(packageName)?.surface

    /** Guides whose label/alias appears in the task text (word-boundary-ish match). */
    fun matchingTask(task: String): List<AppGuide> {
        val lower = task.lowercase()
        if (lower.isBlank()) return emptyList()
        return GUIDES.filter { guide ->
            guide.aliases.any { alias -> containsToken(lower, alias) } ||
                containsToken(lower, guide.label.lowercase())
        }
    }

    /**
     * Compact system-prompt section for the task.
     *
     * Apps named in the task are included; when the task names none, the guided
     * apps installed on this device are used so the model knows the recipes before
     * it opens anything. Empty string when nothing applies (keeps the prompt small
     * for knowledge-only tasks).
     *
     * @param installedPackages launcher packages on this device (optional)
     * @param maxApps           hard cap so a local model's context stays small
     */
    fun promptSection(
        task: String,
        installedPackages: List<String> = emptyList(),
        maxApps: Int = 4,
    ): String {
        val mentioned = matchingTask(task)
        // Apps named in the task are the only ones that matter; only when the task
        // names none do we fall back to the guided apps installed on the device
        // (keeps a local model's context small).
        val installedGuides = installedPackages
            .mapNotNull { pkg -> forPackage(pkg) }
            .distinctBy { it.packageName }
        val chosen = (if (mentioned.isNotEmpty()) mentioned else installedGuides)
            .distinctBy { it.packageName }
            .take(maxApps)
        if (chosen.isEmpty()) return ""
        return buildString {
            append("\n\n## App usage guide (installed apps)\n")
            append("Follow these app-specific steps — do not improvise selectors.\n")
            chosen.forEach { guide ->
                append("- **").append(guide.label).append("** (").append(guide.packageName).append("): ")
                append(guide.tips.joinToString(" "))
                append('\n')
            }
            // Deep links skip the in-app navigation ladder entirely; only offered for
            // apps that actually publish one.
            append(
                com.returngift.agent.agent.flow.AppDeepLinks.promptSection(
                    packages = chosen.map { it.packageName },
                    query = com.returngift.agent.agent.flow.AppDeepLinks.queryHint(task),
                )
            )
        }
    }

    /** True when the package has a guide (used to flag "known app" in the UI). */
    fun isKnownApp(packageName: String?): Boolean = forPackage(packageName) != null

    private fun containsToken(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val before = if (index == 0) ' ' else haystack[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}
