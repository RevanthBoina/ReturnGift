// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

import com.returngift.agent.agent.exec.micro.MicroMatch.labelHas
import com.returngift.agent.agent.exec.micro.MicroMatch.pkgIn
import com.returngift.agent.agent.exec.micro.MicroMatch.typedInto
import com.returngift.agent.agent.exec.micro.MicroSteps.allow
import com.returngift.agent.agent.exec.micro.MicroSteps.clear
import com.returngift.agent.agent.exec.micro.MicroSteps.dialogOption
import com.returngift.agent.agent.exec.micro.MicroSteps.dismissKeyboard
import com.returngift.agent.agent.exec.micro.MicroSteps.enter
import com.returngift.agent.agent.exec.micro.MicroSteps.firstResult
import com.returngift.agent.agent.exec.micro.MicroSteps.focus
import com.returngift.agent.agent.exec.micro.MicroSteps.observe
import com.returngift.agent.agent.exec.micro.MicroSteps.openApp
import com.returngift.agent.agent.exec.micro.MicroSteps.scroll
import com.returngift.agent.agent.exec.micro.MicroSteps.stop
import com.returngift.agent.agent.exec.micro.MicroSteps.tap
import com.returngift.agent.agent.exec.micro.MicroSteps.tapText
import com.returngift.agent.agent.exec.micro.MicroSteps.toggle
import com.returngift.agent.agent.exec.micro.MicroSteps.verifyChanged
import com.returngift.agent.agent.exec.micro.MicroSteps.verifyForeground
import com.returngift.agent.agent.exec.micro.MicroSteps.verifyGone
import com.returngift.agent.agent.exec.micro.MicroSteps.verifyText
import com.returngift.agent.agent.exec.micro.MicroSteps.wait
import com.returngift.agent.agent.exec.micro.MicroSteps.waitResults
import com.returngift.agent.agent.flow.FlowRetry
import com.returngift.agent.agent.flow.SessionBlocker
import com.returngift.agent.agent.flow.SessionBlockerDetector

/**
 * The mid-task micro-step catalogue.
 *
 * Every entry answers one question: *"given what just happened, what is the
 * single next thing that MUST happen before the task can be called done?"*
 *
 * Rules are matched against [MicroStepContext]; the highest [MicroRule.priority]
 * match wins, so a specific app flow (Play Store search) beats the generic
 * fallback for the same surface (in-app search).
 *
 * The catalogue is intentionally data-only and pure Kotlin: adding a case means
 * adding one `rule(...)` entry plus one test (see MicroStepPlannerTest).
 */
object MicroStepCatalog {

    private const val P_CHAT = 100
    private const val P_SPECIFIC = 90
    private const val P_SURFACE = 80
    private const val P_FLAG = 70
    private const val P_FALLBACK = 10

    /** App packages the catalogue recognises (substring match, lower-case). */
    private const val PKG_WHATSAPP = "com.whatsapp"
    private const val PKG_TELEGRAM = "org.telegram"
    private const val PKG_MESSAGES = "com.google.android.apps.messaging"
    private const val PKG_GMAIL = "com.google.android.gm"
    private const val PKG_OUTLOOK = "com.microsoft.office.outlook"
    private const val PKG_PLAY = "com.android.vending"
    private const val PKG_YOUTUBE = "com.google.android.youtube"
    private const val PKG_MAPS = "com.google.android.apps.maps"
    private const val PKG_CONTACTS = "com.google.android.contacts"
    private const val PKG_CHROME = "com.android.chrome"
    private const val PKG_LINKEDIN = "com.linkedin.android"
    private const val PKG_CAMERA = "com.google.android.GoogleCamera"
    private const val PKG_LAUNCHER = "launcher"

    private fun snippet(text: String): String = text.trim().take(24)

    private fun rule(
        id: String,
        summary: String,
        priority: Int,
        match: (MicroStepContext) -> Boolean,
        build: (MicroStepContext) -> List<MicroStep>,
    ) = MicroRule(id, summary, priority, match, build)

    /** All rules, highest priority first is applied by the planner. */
    val rules: List<MicroRule> = listOf(

        // ═══════════════════════════════════════════════════════════════════
        // A. Text was typed — submit / advance before anything else
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "type_chat_tap_send",
            "Text typed into a chat with a visible Send button → tap Send, verify the text left the field",
            P_CHAT,
            match = {
                typedInto(it, AppSurface.CHAT_COMPOSER) &&
                    it.submitAffordance == SubmitAffordance.SEND_BUTTON
            },
            build = { listOf(tap("Send"), verifyGone(snippet(it.text)), verifyChanged()) },
        ),
        rule(
            "type_chat_press_enter",
            "Text typed into a chat → submit it (Enter, or tap Send when the app shows one), verify the screen changed",
            P_CHAT - 1,
            match = {
                typedInto(it, AppSurface.CHAT_COMPOSER) &&
                    it.submitAffordance != SubmitAffordance.SEND_BUTTON
            },
            build = { listOf(enter(), verifyChanged()) },
        ),
        rule(
            "type_caption_tap_send",
            "Media caption typed in a chat → tap Send (caption box has no Enter submit)",
            P_CHAT + 1,
            match = { typedInto(it, AppSurface.CHAT_COMPOSER) && labelHas(it, "caption", "add a caption") },
            build = { listOf(tap("Send"), verifyGone(snippet(it.text))) },
        ),
        rule(
            "chat_contact_search_submit",
            "Contact search inside a messaging app → press Enter, then open the first result",
            P_CHAT + 2,
            match = {
                typedInto(it, AppSurface.CHAT_COMPOSER) &&
                    labelHas(it, "search", "to:", "recipient") &&
                    pkgIn(it, PKG_WHATSAPP, PKG_TELEGRAM, PKG_MESSAGES)
            },
            build = { listOf(enter(), waitResults(), firstResult(), wait()) },
        ),
        rule(
            "inapp_search_press_enter",
            "Query typed into an in-app search box with no visible Search button → press Enter, wait for results",
            P_SURFACE,
            match = {
                typedInto(it, AppSurface.SEARCH_FIELD) &&
                    it.submitAffordance != SubmitAffordance.SEARCH_BUTTON
            },
            build = { listOf(enter(), waitResults(), verifyChanged()) },
        ),
        rule(
            "inapp_search_tap_button",
            "Query typed into an in-app search box with a magnifier button → tap Search, wait for results",
            P_SURFACE + 1,
            match = {
                typedInto(it, AppSurface.SEARCH_FIELD) &&
                    it.submitAffordance == SubmitAffordance.SEARCH_BUTTON
            },
            build = { listOf(tap("Search"), waitResults(), verifyChanged()) },
        ),
        rule(
            "play_store_search",
            "Play Store query typed → press Enter, wait for result rows, open the first app",
            P_SPECIFIC + 5,
            match = { typedInto(it, AppSurface.PLAY_STORE) || (typedInto(it, AppSurface.SEARCH_FIELD) && pkgIn(it, PKG_PLAY)) },
            build = { listOf(enter(), waitResults(), firstResult()) },
        ),
        rule(
            "play_store_install",
            "A store listing is open → tap Install, then verify the install started",
            P_SPECIFIC + 4,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.PLAY_STORE && labelHas(it, "install", "get") },
            build = { listOf(tap("Install"), wait(), verifyText("Installing")) },
        ),
        rule(
            "play_store_open_after_install",
            "Install finished and an Open button is on screen → tap Open, verify the app is in front",
            P_SPECIFIC + 4,
            match = {
                it.surface == AppSurface.PLAY_STORE &&
                    it.lastAction == LastAction.TAPPED && labelHas(it, "open") &&
                    it.resultsVisible
            },
            build = { listOf(tap("Open"), wait(), verifyChanged()) },
        ),
        rule(
            "linkedin_search_submit",
            "LinkedIn query typed into search → press Enter, wait for the result list, open the first result",
            P_SPECIFIC + 6,
            match = {
                pkgIn(it, PKG_LINKEDIN) &&
                    (typedInto(it, AppSurface.SEARCH_FIELD) || typedInto(it, AppSurface.LIST))
            },
            build = { listOf(enter(), waitResults(), firstResult(), wait()) },
        ),
        rule(
            "linkedin_deeplink_verify",
            "LinkedIn was opened via a deep link → verify the target screen actually appeared before typing",
            P_SPECIFIC + 7,
            match = {
                pkgIn(it, PKG_LINKEDIN) && it.lastAction == LastAction.OPENED_APP && !it.screenChanged
            },
            build = { listOf(
                verifyChanged(),
                tap("Search", note = "the deep link did not land — use the in-app search control instead"),
            ) },
        ),
        rule(
            "youtube_search",
            "YouTube query typed → press Enter, wait for results, play the first video",
            P_SPECIFIC + 5,
            match = { typedInto(it, AppSurface.VIDEO) || (typedInto(it, AppSurface.SEARCH_FIELD) && pkgIn(it, PKG_YOUTUBE)) },
            build = { listOf(enter(), waitResults(), firstResult(), wait()) },
        ),
        rule(
            "maps_search",
            "Maps query typed → press Enter, wait for the place list, open the first place",
            P_SPECIFIC + 5,
            match = { typedInto(it, AppSurface.MAPS) || (typedInto(it, AppSurface.SEARCH_FIELD) && pkgIn(it, PKG_MAPS)) },
            build = { listOf(enter(), waitResults(), firstResult(), wait()) },
        ),
        rule(
            "contacts_search",
            "Contacts search typed → press Enter, open the first matching contact",
            P_SPECIFIC + 5,
            match = { typedInto(it, AppSurface.SEARCH_FIELD) && pkgIn(it, PKG_CONTACTS) },
            build = { listOf(enter(), waitResults(), firstResult()) },
        ),
        rule(
            "browser_url_enter",
            "URL typed into the address bar → press Enter, wait for the page, verify it changed",
            P_SURFACE + 2,
            match = { typedInto(it, AppSurface.BROWSER_ADDRESS) || (typedInto(it, AppSurface.SEARCH_FIELD) && pkgIn(it, PKG_CHROME)) },
            build = { listOf(enter(), wait(), verifyChanged()) },
        ),
        rule(
            "email_recipient_advance",
            "Recipient typed in a mail composer → press Enter to commit the address, then focus the next field",
            P_SURFACE + 2,
            match = { typedInto(it, AppSurface.EMAIL_COMPOSE) && labelHas(it, "to", "recipient", "cc", "bcc") },
            build = { listOf(enter(), wait(), focus()) },
        ),
        rule(
            "email_subject_advance",
            "Subject typed in a mail composer → move focus to the body before writing it",
            P_SURFACE + 2,
            match = { typedInto(it, AppSurface.EMAIL_COMPOSE) && labelHas(it, "subject") },
            build = { listOf(enter(), focus()) },
        ),
        rule(
            "email_body_send",
            "Mail body typed → tap Send and verify the composer closed",
            P_SURFACE + 3,
            match = {
                typedInto(it, AppSurface.EMAIL_COMPOSE) &&
                    labelHas(it, "body", "message", "compose email") &&
                    pkgIn(it, PKG_GMAIL, PKG_OUTLOOK, "mail", "email")
            },
            build = { listOf(tap("Send"), verifyGone("Compose")) },
        ),
        rule(
            "post_composer_publish",
            "Social post/composer text typed → tap Post and verify it published",
            P_SURFACE + 3,
            // A search box inside a social app reports the same surface as the
            // composer. Typing a search term there must never hit "Post".
            match = {
                typedInto(it, AppSurface.SOCIAL_POST) &&
                    !labelHas(it, "search", "find", "filter")
            },
            build = { listOf(tap("Post"), verifyChanged()) },
        ),
        rule(
            "form_next_field",
            "A form field was filled while more fields remain → move to the next field, do not save yet",
            P_SURFACE,
            match = { typedInto(it, AppSurface.FORM) && it.fieldsRemaining > 0 },
            build = { listOf(focus(), enter()) },
        ),
        rule(
            "form_save",
            "Last form field filled → tap Save/Submit and verify the form closed",
            P_SURFACE + 1,
            match = { typedInto(it, AppSurface.FORM) && it.fieldsRemaining == 0 && !it.validationErrorVisible },
            build = { listOf(tap("Save"), verifyChanged()) },
        ),
        rule(
            "form_validation_error",
            "Form rejected input with a validation error → re-read the field, clear it, refocus before retrying",
            P_SURFACE + 2,
            match = { typedInto(it, AppSurface.FORM) && it.validationErrorVisible },
            build = { listOf(observe(), clear(), focus(), stop("field validation error surfaced")) },
        ),
        rule(
            "dialer_number_call",
            "Number typed into the dialer → tap Call and verify the in-call screen",
            P_SURFACE + 2,
            match = { typedInto(it, AppSurface.DIALER) },
            build = { listOf(tap("Call"), wait(), verifyText("End call")) },
        ),
        rule(
            "calendar_event_save",
            "Event title/details typed → tap Save and verify the calendar returned",
            P_SURFACE + 4,
            match = { typedInto(it, AppSurface.FORM) && pkgIn(it, "calendar") },
            build = { listOf(tap("Save"), verifyChanged()) },
        ),
        rule(
            "list_item_add_done",
            "An item was added in a list screen (todo/notes) → tap Done/Add and verify the row appears",
            P_FALLBACK + 20,
            match = { typedInto(it, AppSurface.LIST) },
            build = { listOf(tap("Done"), verifyText(snippet(it.text))) },
        ),
        rule(
            "type_any_field_needs_submit",
            "Generic fallback: text was typed into an unknown surface → verify the field, and submit only if the field is complete",
            P_FALLBACK,
            match = { it.lastAction == LastAction.TYPED_TEXT && !it.fieldEmpty },
            build = {
                listOf(
                    // Not mandatory: in an unknown app Enter may be a newline, or may
                    // submit a half-filled form. Verify first; press Enter only when the
                    // instruction is complete.
                    MicroStep(
                        MicroStepKind.PRESS_ENTER,
                        label = "enter",
                        mandatory = false,
                        note = "only if the typed text is the complete value for this field",
                    ),
                    verifyChanged(),
                )
            },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // B. Taps that open a second surface — the follow-up tap
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "tap_new_chat_focus",
            "New chat / new message / compose was tapped → wait, focus the composer, keyboard must appear",
            P_SURFACE + 2,
            match = {
                it.lastAction == LastAction.TAPPED && it.surface == AppSurface.CHAT_COMPOSER &&
                    labelHas(it, "new chat", "new message", "compose", "start a chat")
            },
            build = { listOf(wait(), focus(), verifyChanged()) },
        ),
        rule(
            "tap_thread_open_focus",
            "A contact/thread was opened → wait for the thread, then focus the composer",
            P_SURFACE - 2,
            match = {
                it.lastAction == LastAction.TAPPED && it.surface == AppSurface.CHAT_COMPOSER &&
                    it.keyboardVisible.not() && it.screenChanged
            },
            build = { listOf(wait(), focus()) },
        ),
        rule(
            "tap_search_icon_focus",
            "The search icon/search box was tapped → focus the field and verify the keyboard is up",
            P_SURFACE + 2,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "search") && !it.keyboardVisible },
            build = { listOf(focus(), verifyChanged()) },
        ),
        rule(
            "tap_overflow_menu",
            "An overflow (⋮) menu was tapped → wait for the menu, then pick the option",
            P_SURFACE + 2,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "more options", "⋮", "menu", "overflow") },
            build = { listOf(wait(), dialogOption("")) },
        ),
        rule(
            "long_press_context_menu",
            "Long press opens a context menu → wait, then choose an option",
            P_SURFACE + 2,
            match = { it.lastAction == LastAction.LONG_PRESSED },
            build = { listOf(wait(), dialogOption("")) },
        ),
        rule(
            "tap_share_sheet",
            "Share was tapped → wait for the share sheet, then tap Send",
            P_SURFACE + 3,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "share") },
            build = { listOf(wait(), tap("Send"), verifyChanged()) },
        ),
        rule(
            "tap_attach_media",
            "Attach was tapped → choose the source, pick the first item, then Send",
            P_SURFACE + 3,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "attach", "camera", "gallery", "photo") },
            build = { listOf(tap("Photos"), wait(), firstResult(), tap("Send")) },
        ),
        rule(
            "tap_date_picker_ok",
            "A date/time field opened a picker → wait for it, then confirm OK",
            P_SURFACE + 3,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "date", "time", "calendar") && it.surface == AppSurface.FORM },
            build = { listOf(wait(), tap("OK"), verifyChanged()) },
        ),
        rule(
            "tap_dropdown_option",
            "A dropdown/spinner was tapped → wait for the list, then choose the option",
            P_SURFACE + 3,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "select", "dropdown", "spinner") },
            build = { listOf(wait(), dialogOption("")) },
        ),
        rule(
            "tap_menu_list_item",
            "A list row that opens a sub-screen was tapped → verify the sub-screen rendered",
            P_FALLBACK + 25,
            match = {
                it.lastAction == LastAction.TAPPED && it.surface == AppSurface.LIST &&
                    !labelHas(it, "back", "cancel")
            },
            build = { listOf(wait(), verifyChanged()) },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // C. Dialogs, consent and permissions
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "permission_dialog_allow",
            "A runtime permission dialog is up → tap Allow (never skip it), then verify it closed",
            P_CHAT + 5,
            match = { it.permissionDialogVisible },
            build = { listOf(allow(), wait(), verifyGone("Allow")) },
        ),
        rule(
            "permission_denied_report",
            "Permission screen stayed open without an Allow action → stop and report instead of acting blind",
            P_CHAT + 4,
            match = { it.surface == AppSurface.SYSTEM_PERMISSION && it.lastAction == LastAction.GRANTED_PERMISSION && !it.screenChanged },
            build = { listOf(stop("permission was not granted")) },
        ),
        rule(
            "allowlist_prompt_choose",
            "ReturnGift's own app-permission card is up → brand the choice, then continue the task",
            P_CHAT + 4,
            match = {
                it.dialogVisible && labelHas(it, "allow once", "always allow", "app permissions")
            },
            build = { listOf(dialogOption("Allow once"), wait(), verifyChanged()) },
        ),
        rule(
            "dialog_confirm_ok",
            "A confirmation dialog is up → tap OK, then verify the dialog closed",
            P_FLAG + 10,
            match = { it.dialogVisible && labelHas(it, "ok", "confirm", "yes") },
            build = { listOf(dialogOption("OK"), wait(), verifyChanged()) },
        ),
        rule(
            "dialog_dismiss_dismiss",
            "An ad/upsell/promo dialog is up → dismiss with Not now/Skip, then continue",
            P_FLAG + 12,
            match = {
                it.dialogVisible && labelHas(it, "not now", "skip", "no thanks", "later", "dismiss")
            },
            build = { listOf(dialogOption("Not now"), wait(), verifyChanged()) },
        ),
        rule(
            "unsaved_changes_keep",
            "Back triggered an unsaved-changes dialog → Save/Keep, then verify",
            P_FLAG + 12,
            match = { it.dialogVisible && labelHas(it, "discard", "unsaved", "save changes") },
            build = { listOf(dialogOption("Save"), wait(), verifyChanged()) },
        ),
        rule(
            "consent_screen_accept",
            "A consent/terms screen is up → accept, then continue",
            P_FLAG + 14,
            match = { it.lastAction == LastAction.TAPPED && labelHas(it, "agree", "accept", "i consent", "continue") },
            build = { listOf(tap("Agree"), wait(), verifyChanged()) },
        ),
        rule(
            "system_settings_toggle_grant",
            "A system permission settings page is open → toggle the switch on, go back, verify the app",
            P_FLAG + 16,
            match = {
                it.surface == AppSurface.SETTINGS && it.lastAction == LastAction.TAPPED &&
                    labelHas(it, "permission", "allow", "access")
            },
            build = { listOf(toggle(""), wait(), verifyChanged()) },
        ),
        rule(
            "granted_permission_return_to_app",
            "Permission was just granted → go back into the driven app and verify it is in front",
            P_FLAG + 16,
            match = { it.lastAction == LastAction.GRANTED_PERMISSION && it.screenChanged },
            build = { listOf(verifyForeground(it.packageName)) },
        ),
        rule(
            "generic_dialog_needs_choice",
            "Fallback: any modal dialog is up → one explicit choice, never act on the content behind it",
            P_FALLBACK + 30,
            match = { it.dialogVisible },
            build = { listOf(dialogOption(""), observe()) },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // C2. Session blockers — stop, ask, or back off instead of guessing
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "blocker_login_wall",
            "A sign-in wall is up → stop and ask the user to sign in (never type credentials)",
            P_CHAT + 60,
            match = { it.blocker == SessionBlocker.LOGIN_REQUIRED },
            build = {
                val app = it.packageName.ifBlank { "the app" }
                listOf(stop(
                    SessionBlockerDetector.message(SessionBlocker.LOGIN_REQUIRED, app) +
                        " Do not type an email or password — report this to the user and stop."
                ))
            },
        ),
        rule(
            "blocker_captcha",
            "A human-verification screen is up → stop; only a person can clear it",
            P_CHAT + 59,
            match = { it.blocker == SessionBlocker.CAPTCHA_REQUIRED },
            build = {
                listOf(stop(
                    SessionBlockerDetector.message(
                        SessionBlocker.CAPTCHA_REQUIRED,
                        it.packageName.ifBlank { "the app" },
                    )
                ))
            },
        ),
        rule(
            "blocker_account_chooser",
            "An account chooser is up → stop and ask which account to use",
            P_CHAT + 58,
            match = { it.blocker == SessionBlocker.ACCOUNT_CHOOSER },
            build = {
                listOf(stop(
                    SessionBlockerDetector.message(
                        SessionBlocker.ACCOUNT_CHOOSER,
                        it.packageName.ifBlank { "the app" },
                    )
                ))
            },
        ),
        rule(
            "blocker_consent_accept",
            "A cookie/terms consent prompt is up → accept it once, then continue",
            P_CHAT + 57,
            match = { it.blocker == SessionBlocker.CONSENT_REQUIRED },
            build = { listOf(dialogOption("Accept all"), wait(), verifyChanged()) },
        ),
        rule(
            "blocker_rate_limited",
            "The app is throttling requests → wait out the backoff instead of retrying immediately",
            P_CHAT + 56,
            match = { it.blocker == SessionBlocker.RATE_LIMITED },
            build = {
                listOf(
                    wait(),
                    observe(),
                    stop(FlowRetry.guidance(SessionBlocker.RATE_LIMITED)),
                )
            },
        ),
        rule(
            "blocker_network_error",
            "The app lost connectivity → wait briefly, re-read the screen, report if it persists",
            P_CHAT + 55,
            match = { it.blocker == SessionBlocker.NETWORK_ERROR },
            build = {
                listOf(
                    wait(),
                    observe(),
                    stop(FlowRetry.guidance(SessionBlocker.NETWORK_ERROR)),
                )
            },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // D. Navigation, keyboard and screen hygiene
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "keyboard_hides_target",
            "Keyboard is covering the next target → dismiss it before tapping",
            P_FLAG + 20,
            match = { it.keyboardVisible && it.lastAction != LastAction.TYPED_TEXT },
            build = { listOf(dismissKeyboard(), verifyChanged()) },
        ),
        rule(
            "back_verify_change",
            "Back was pressed → verify the screen actually changed (a swallowed back = different plan)",
            P_FLAG + 18,
            match = { it.lastAction == LastAction.PRESSED_KEY && it.key == "back" },
            build = { listOf(verifyChanged(), observe()) },
        ),
        rule(
            "home_verify_launcher",
            "Home was pressed → verify a launcher is in front before opening the next app",
            P_FLAG + 18,
            match = { it.lastAction == LastAction.PRESSED_KEY && it.key == "home" },
            build = { listOf(verifyForeground(PKG_LAUNCHER)) },
        ),
        rule(
            "back_swallowed_escape",
            "Back did not change the screen → use the explicit in-app Back affordance instead",
            P_FLAG + 22,
            match = { it.lastAction == LastAction.PRESSED_KEY && it.key == "back" && !it.screenChanged },
            build = { listOf(tap("Back"), verifyChanged()) },
        ),
        rule(
            "open_app_settle_verify",
            "An app was launched → wait for it to settle, then verify it is the foreground app",
            P_FLAG + 8,
            match = { it.lastAction == LastAction.OPENED_APP },
            build = { listOf(wait(), verifyForeground(it.packageName)) },
        ),
        rule(
            "swipe_verify_scroll",
            "A swipe was expected to scroll → verify it scrolled; if not, scroll explicitly",
            P_FLAG + 6,
            match = { it.lastAction == LastAction.SWIPED && it.screenChanged },
            build = { listOf(verifyChanged()) },
        ),
        rule(
            "swipe_not_scrolled",
            "A swipe did not move the list → scroll with the explicit scroll primitive",
            P_FLAG + 9,
            match = { it.lastAction == LastAction.SWIPED && !it.screenChanged },
            build = { listOf(scroll(), verifyChanged()) },
        ),
        rule(
            "tap_no_effect_observe",
            "A tap had no visible effect → re-observe the screen before tapping again (never blind-retry)",
            P_FLAG + 24,
            match = { it.lastAction == LastAction.TAPPED && !it.screenChanged && it.surface != AppSurface.PLAY_STORE },
            build = { listOf(observe(), stop("tap produced no visible change")) },
        ),
        rule(
            "wait_still_loading",
            "The screen is still loading after a wait → wait for results again instead of tapping dead UI",
            P_FLAG + 15,
            match = { it.loading && it.lastAction == LastAction.WAITED },
            build = { listOf(waitResults()) },
        ),
        rule(
            "observe_then_act",
            "A screen read is complete → act on it; do not chain another observation",
            P_FLAG + 2,
            match = { it.lastAction == LastAction.OBSERVED },
            build = { listOf(verifyChanged()) },
        ),
        rule(
            "app_left_foreground_recover",
            "The driven app is no longer in front → bring it back before any further action",
            P_FLAG + 26,
            match = { !it.appInForeground && it.packageName.isNotEmpty() },
            build = { listOf(openApp(it.packageName), wait(), verifyForeground(it.packageName)) },
        ),
        rule(
            "target_missing_reobserve",
            "The semantic target was not found → re-observe and re-resolve instead of tapping coordinates",
            P_FLAG + 26,
            match = { !it.targetFound },
            build = { listOf(observe(), stop("target not found")) },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // E. Search-result lifecycle
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "search_results_loading",
            "Results are still loading after submit → wait for results, then act",
            P_SURFACE + 6,
            match = {
                (it.lastAction == LastAction.PRESSED_ENTER || it.lastAction == LastAction.TAPPED) &&
                    it.loading && it.surface in setOf(AppSurface.SEARCH_FIELD, AppSurface.PLAY_STORE, AppSurface.VIDEO, AppSurface.MAPS)
            },
            build = { listOf(waitResults(), verifyChanged()) },
        ),
        rule(
            "search_no_results",
            "No result rows appeared → clear the query, refocus, and report rather than tapping nothing",
            P_SURFACE + 7,
            match = {
                it.lastAction == LastAction.PRESSED_ENTER && !it.resultsVisible && !it.loading &&
                    it.surface in setOf(AppSurface.SEARCH_FIELD, AppSurface.PLAY_STORE, AppSurface.VIDEO, AppSurface.MAPS)
            },
            build = { listOf(clear(), focus(), stop("search returned no results")) },
        ),
        rule(
            "search_did_you_mean",
            "A 'did you mean' suggestion appeared → tap the suggestion, then re-submit",
            P_SURFACE + 9,
            match = { labelHas(it, "did you mean", "showing results for") },
            build = { listOf(tapText("did you mean"), enter(), waitResults()) },
        ),
        rule(
            "search_select_first_result",
            "Results are visible → select the first result (never leave the list without choosing)",
            P_SURFACE + 5,
            match = {
                it.resultsVisible &&
                    it.surface in setOf(AppSurface.SEARCH_FIELD, AppSurface.PLAY_STORE, AppSurface.VIDEO, AppSurface.MAPS)
            },
            build = { listOf(firstResult(), wait()) },
        ),
        rule(
            "enter_on_results_opens_first",
            "Enter was pressed while results were already visible → open the first result",
            P_SURFACE + 8,
            match = { it.lastAction == LastAction.PRESSED_ENTER && it.resultsVisible },
            build = { listOf(firstResult(), verifyChanged()) },
        ),
        rule(
            "maps_result_directions",
            "A place was opened in Maps → tap Directions, then verify the route sheet",
            P_SPECIFIC + 7,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.MAPS && labelHas(it, "place", "result") },
            build = { listOf(tap("Directions"), wait(), verifyChanged()) },
        ),
        rule(
            "video_result_play",
            "A video result was opened → tap Play, then verify playback started",
            P_SPECIFIC + 7,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.VIDEO && labelHas(it, "video", "result") },
            build = { listOf(tap("Play"), wait(), verifyChanged()) },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // F. Media, pickers and other structured flows
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "camera_capture_confirm",
            "Camera is open → tap the shutter, then confirm with Done/Use photo",
            P_SURFACE + 2,
            match = { it.lastAction == LastAction.OPENED_APP && it.surface == AppSurface.CAMERA },
            build = { listOf(tap("Shutter"), wait(), tap("Done")) },
        ),
        rule(
            "gallery_pick_confirm",
            "A picker is open → pick the first item, then confirm Done/Open",
            P_SURFACE + 2,
            match = { it.lastAction == LastAction.OPENED_APP && it.surface == AppSurface.FILE_PICKER },
            build = { listOf(firstResult(), tap("Done")) },
        ),
        rule(
            "file_picker_open",
            "A file was selected in the system picker → tap Open to hand it to the app",
            P_FLAG + 7,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.FILE_PICKER },
            build = { listOf(tap("Open"), verifyChanged()) },
        ),
        rule(
            "dialer_call_confirm",
            "The Call button was tapped → verify the call connected (End call visible)",
            P_FLAG + 7,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.DIALER && labelHas(it, "call") },
            build = { listOf(wait(), verifyText("End call")) },
        ),
        rule(
            "media_play_verify",
            "A media app is open → press Play and verify playback",
            P_SURFACE + 1,
            match = { it.lastAction == LastAction.OPENED_APP && it.surface == AppSurface.MEDIA },
            build = { listOf(tap("Play"), wait(), verifyChanged()) },
        ),
        rule(
            "alarm_save_verify",
            "An alarm/reminder was configured → tap Save and verify the list shows it",
            P_FLAG + 11,
            match = { it.surface == AppSurface.FORM && it.lastAction == LastAction.TAPPED && labelHas(it, "alarm", "reminder") },
            build = { listOf(tap("Save"), wait(), verifyChanged()) },
        ),
        rule(
            "settings_toggle_verify",
            "A settings row was tapped → verify the switch state changed",
            P_FLAG + 13,
            match = { it.lastAction == LastAction.TAPPED && it.surface == AppSurface.SETTINGS },
            build = { listOf(toggle(""), verifyChanged()) },
        ),

        // ═══════════════════════════════════════════════════════════════════
        // G. Task-closing hygiene (kept last, lowest priority)
        // ═══════════════════════════════════════════════════════════════════

        rule(
            "finish_needs_visible_result",
            "A task is about to be marked done → the last action must have produced a visible change",
            P_FALLBACK + 1,
            match = { it.lastAction == LastAction.TAPPED && it.screenChanged },
            build = { listOf(verifyChanged()) },
        ),
        rule(
            "empty_text_target",
            "The target field was empty after typing → re-focus and report instead of submitting nothing",
            P_FALLBACK + 2,
            match = { it.lastAction == LastAction.TYPED_TEXT && it.fieldEmpty },
            build = { listOf(focus(), stop("nothing was entered into the field")) },
        ),
        rule(
            "search_needs_query_first",
            "A search screen is open with an empty query → type the query before any submit",
            P_SURFACE + 4,
            match = {
                it.surface == AppSurface.SEARCH_FIELD && it.lastAction == LastAction.OPENED_APP &&
                    it.fieldEmpty
            },
            build = { listOf(focus(), skipReportStep()) },
        ),
    )

    /** A no-op planning step used by the "focus then report" rule. */
    private fun skipReportStep(): MicroStep =
        MicroStep(MicroStepKind.STOP_AND_REPORT, note = "focus the query field, then type", mandatory = false)

    /** Rule ids — used by tests and telemetry. */
    val ids: List<String> get() = rules.map { it.id }
}
