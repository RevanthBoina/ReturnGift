// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per mid-task micro-step case (73 cases in [MicroStepCatalog]) plus
 * state-machine tests for [MicroStepTracker].
 *
 * Each case test asserts BOTH:
 *  1. the intended rule wins for its realistic context (so a broad rule cannot
 *     silently shadow a specific one), and
 *  2. the first follow-up step is the expected micro-step (Enter, Send, Allow…).
 */
class MicroStepPlannerTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun typed(
        surface: AppSurface,
        text: String = "Hello there",
        affordance: SubmitAffordance = SubmitAffordance.NONE,
        pkg: String = "com.example.app",
        label: String = "",
        keyboard: Boolean = false,
    ) = MicroStepContext(
        lastAction = LastAction.TYPED_TEXT,
        surface = surface,
        packageName = pkg,
        text = text,
        label = label,
        fieldEmpty = text.isBlank(),
        submitAffordance = affordance,
        keyboardVisible = keyboard,
        stepIndex = 1,
    )

    private fun tapped(
        surface: AppSurface = AppSurface.UNKNOWN,
        label: String = "",
        pkg: String = "com.example.app",
        keyboard: Boolean = false,
        screenChanged: Boolean = true,
        resultsVisible: Boolean = false,
    ) = MicroStepContext(
        lastAction = LastAction.TAPPED,
        surface = surface,
        packageName = pkg,
        label = label,
        keyboardVisible = keyboard,
        screenChanged = screenChanged,
        resultsVisible = resultsVisible,
        stepIndex = 1,
    )

    private fun key(key: String, screenChanged: Boolean = true, pkg: String = "com.example.app") =
        MicroStepContext(
            lastAction = LastAction.PRESSED_KEY,
            key = key,
            packageName = pkg,
            screenChanged = screenChanged,
            stepIndex = 1,
        )

    /** Asserts the winning rule and the first micro-step kind. */
    private fun assertPlan(
        ctx: MicroStepContext,
        ruleId: String,
        firstKind: MicroStepKind,
    ) {
        val plan = MicroStepPlanner.plan(ctx)
        assertEquals("wrong rule for context $ctx", ruleId, plan.ruleId)
        assertFalse("plan should not be complete for $ruleId", plan.isComplete)
        assertEquals("wrong first step for $ruleId", firstKind, plan.steps.first().kind)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A. Typed text → submit (the "type then Enter/Send" family)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 01 chat with send button taps Send`() =
        assertPlan(
            typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.SEND_BUTTON),
            "type_chat_tap_send",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 02 chat without send button presses Enter`() =
        assertPlan(
            typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.ENTER_KEY),
            "type_chat_press_enter",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 03 media caption uses Send not Enter`() =
        assertPlan(
            typed(AppSurface.CHAT_COMPOSER, label = "Add a caption"),
            "type_caption_tap_send",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 04 contact search in WhatsApp submits and opens result`() =
        assertPlan(
            typed(
                AppSurface.CHAT_COMPOSER,
                text = "Mom",
                pkg = "com.whatsapp",
                label = "Search",
            ),
            "chat_contact_search_submit",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 05 in-app search presses Enter`() =
        assertPlan(
            typed(AppSurface.SEARCH_FIELD, text = "invoice"),
            "inapp_search_press_enter",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 06 in-app search taps the magnifier`() =
        assertPlan(
            typed(
                AppSurface.SEARCH_FIELD,
                text = "invoice",
                affordance = SubmitAffordance.SEARCH_BUTTON,
            ),
            "inapp_search_tap_button",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 07 Play Store query searches and opens first result`() =
        assertPlan(
            typed(AppSurface.PLAY_STORE, text = "notes", pkg = "com.android.vending"),
            "play_store_search",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 08 Play Store Install is tapped`() =
        assertPlan(
            tapped(AppSurface.PLAY_STORE, label = "Install", pkg = "com.android.vending"),
            "play_store_install",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 09 Play Store Open after install`() =
        assertPlan(
            tapped(
                AppSurface.PLAY_STORE,
                label = "Open",
                pkg = "com.android.vending",
                resultsVisible = true,
            ),
            "play_store_open_after_install",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 10 YouTube search plays the first video`() =
        assertPlan(
            typed(AppSurface.VIDEO, text = "lo-fi", pkg = "com.google.android.youtube"),
            "youtube_search",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 11 Maps search opens the first place`() =
        assertPlan(
            typed(AppSurface.MAPS, text = "coffee", pkg = "com.google.android.apps.maps"),
            "maps_search",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 12 Contacts search opens the first contact`() =
        assertPlan(
            typed(
                AppSurface.SEARCH_FIELD,
                text = "Asha",
                pkg = "com.google.android.contacts",
            ),
            "contacts_search",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 13 browser address bar navigates`() =
        assertPlan(
            typed(AppSurface.BROWSER_ADDRESS, text = "example.com", pkg = "com.android.chrome"),
            "browser_url_enter",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 14 email recipient commits then advances`() =
        assertPlan(
            typed(AppSurface.EMAIL_COMPOSE, text = "a@b.com", pkg = "com.google.android.gm", label = "To"),
            "email_recipient_advance",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 15 email subject advances to body`() =
        assertPlan(
            typed(AppSurface.EMAIL_COMPOSE, text = "Report", pkg = "com.google.android.gm", label = "Subject"),
            "email_subject_advance",
            MicroStepKind.PRESS_ENTER,
        )

    @Test
    fun `case 16 email body sends`() =
        assertPlan(
            typed(AppSurface.EMAIL_COMPOSE, text = "Body text", pkg = "com.google.android.gm", label = "Message body"),
            "email_body_send",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 17 social post publishes`() =
        assertPlan(
            typed(AppSurface.SOCIAL_POST, text = "Shipped v3", pkg = "com.linkedin.android"),
            "post_composer_publish",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 18 form with fields remaining advances focus`() {
        val ctx = typed(AppSurface.FORM, text = "Asha").copy(fieldsRemaining = 2)
        assertPlan(ctx, "form_next_field", MicroStepKind.FOCUS_FIELD)
    }

    @Test
    fun `case 19 last form field saves`() {
        val ctx = typed(AppSurface.FORM, text = "Asha").copy(fieldsRemaining = 0)
        assertPlan(ctx, "form_save", MicroStepKind.TAP_LABEL)
    }

    @Test
    fun `case 20 form validation error refocuses`() {
        val ctx = typed(AppSurface.FORM, text = "bad@").copy(validationErrorVisible = true)
        assertPlan(ctx, "form_validation_error", MicroStepKind.OBSERVE_SCREEN)
    }

    @Test
    fun `case 21 dialer number calls`() =
        assertPlan(
            typed(AppSurface.DIALER, text = "9876543210", pkg = "dialer"),
            "dialer_number_call",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 22 calendar event saves`() =
        assertPlan(
            typed(AppSurface.FORM, text = "Dentist", pkg = "com.google.android.calendar"),
            "calendar_event_save",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 23 list item add confirms Done`() =
        assertPlan(
            typed(AppSurface.LIST, text = "Buy milk"),
            "list_item_add_done",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 24 unknown field falls back to Enter`() =
        assertPlan(
            typed(AppSurface.UNKNOWN, text = "hello"),
            "type_any_field_needs_submit",
            MicroStepKind.PRESS_ENTER,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // B. Taps that need a confirming follow-up
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 25 new chat focuses composer`() =
        assertPlan(
            tapped(AppSurface.CHAT_COMPOSER, label = "New chat"),
            "tap_new_chat_focus",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 26 opened thread focuses composer`() =
        assertPlan(
            tapped(AppSurface.CHAT_COMPOSER, label = "Mom"),
            "tap_thread_open_focus",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 27 search icon focuses the field`() =
        assertPlan(
            tapped(label = "Search"),
            "tap_search_icon_focus",
            MicroStepKind.FOCUS_FIELD,
        )

    @Test
    fun `case 28 overflow menu waits then picks`() =
        assertPlan(
            tapped(label = "More options"),
            "tap_overflow_menu",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 29 long press waits for context menu`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.LONG_PRESSED, label = "photo"),
            "long_press_context_menu",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 30 share sheet taps Send`() =
        assertPlan(
            tapped(label = "Share"),
            "tap_share_sheet",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 31 attach opens photos then sends`() =
        assertPlan(
            tapped(AppSurface.CHAT_COMPOSER, label = "Attach photo"),
            "tap_attach_media",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 32 date field confirms picker`() =
        assertPlan(
            tapped(AppSurface.FORM, label = "Date"),
            "tap_date_picker_ok",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 33 dropdown chooses an option`() =
        assertPlan(
            tapped(AppSurface.FORM, label = "Select country"),
            "tap_dropdown_option",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 34 list row opens a sub-screen`() =
        assertPlan(
            tapped(AppSurface.LIST, label = "Wi-Fi"),
            "tap_menu_list_item",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // C. Dialogs, consent and permissions
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 35 runtime permission dialog allows`() =
        assertPlan(
            MicroStepContext(
                lastAction = LastAction.TAPPED,
                permissionDialogVisible = true,
                label = "Allow ReturnGift to access photos",
            ),
            "permission_dialog_allow",
            MicroStepKind.GRANT_PERMISSION,
        )

    @Test
    fun `case 36 ungranted permission reports instead of faking success`() =
        assertPlan(
            MicroStepContext(
                lastAction = LastAction.GRANTED_PERMISSION,
                surface = AppSurface.SYSTEM_PERMISSION,
                screenChanged = false,
                packageName = "com.example.app",
            ),
            "permission_denied_report",
            MicroStepKind.STOP_AND_REPORT,
        )

    @Test
    fun `case 37 ReturnGift allow-list card picks Allow once`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, dialogVisible = true, label = "Allow once"),
            "allowlist_prompt_choose",
            MicroStepKind.CHOOSE_DIALOG_OPTION,
        )

    @Test
    fun `case 38 confirmation dialog taps OK`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, dialogVisible = true, label = "OK"),
            "dialog_confirm_ok",
            MicroStepKind.CHOOSE_DIALOG_OPTION,
        )

    @Test
    fun `case 39 promo dialog is dismissed`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, dialogVisible = true, label = "Not now"),
            "dialog_dismiss_dismiss",
            MicroStepKind.CHOOSE_DIALOG_OPTION,
        )

    @Test
    fun `case 40 unsaved changes dialog saves`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, dialogVisible = true, label = "Discard changes"),
            "unsaved_changes_keep",
            MicroStepKind.CHOOSE_DIALOG_OPTION,
        )

    @Test
    fun `case 41 consent screen accepts`() =
        assertPlan(
            tapped(label = "I agree"),
            "consent_screen_accept",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 42 system settings permission row toggles`() =
        assertPlan(
            tapped(AppSurface.SETTINGS, label = "Allow access to files", pkg = "com.android.settings"),
            "system_settings_toggle_grant",
            MicroStepKind.TOGGLE_SWITCH,
        )

    @Test
    fun `case 43 granted permission verifies foreground app`() =
        assertPlan(
            MicroStepContext(
                lastAction = LastAction.GRANTED_PERMISSION,
                packageName = "com.whatsapp",
                screenChanged = true,
            ),
            "granted_permission_return_to_app",
            MicroStepKind.VERIFY_FOREGROUND,
        )

    @Test
    fun `case 44 unknown dialog forces one explicit choice`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, dialogVisible = true, label = "Dialog"),
            "generic_dialog_needs_choice",
            MicroStepKind.CHOOSE_DIALOG_OPTION,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // D. Navigation, keyboard and screen hygiene
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 45 keyboard blocks target so dismiss first`() =
        assertPlan(
            tapped(label = "", keyboard = true),
            "keyboard_hides_target",
            MicroStepKind.DISMISS_KEYBOARD,
        )

    @Test
    fun `case 46 back verifies the screen changed`() =
        assertPlan(key("back"), "back_verify_change", MicroStepKind.VERIFY_SCREEN_CHANGED)

    @Test
    fun `case 47 home verifies a launcher is in front`() =
        assertPlan(key("home"), "home_verify_launcher", MicroStepKind.VERIFY_FOREGROUND)

    @Test
    fun `case 48 swallowed back uses in-app Back`() =
        assertPlan(key("back", screenChanged = false), "back_swallowed_escape", MicroStepKind.TAP_LABEL)

    @Test
    fun `case 49 launched app settles then verifies foreground`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OPENED_APP, packageName = "com.example.app"),
            "open_app_settle_verify",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 50 swipe verifies it scrolled`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.SWIPED),
            "swipe_verify_scroll",
            MicroStepKind.VERIFY_SCREEN_CHANGED,
        )

    @Test
    fun `case 51 swipe that did not scroll retries explicitly`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.SWIPED, screenChanged = false),
            "swipe_not_scrolled",
            MicroStepKind.SCROLL,
        )

    @Test
    fun `case 52 dead tap re-observes instead of blind retry`() =
        assertPlan(
            tapped(screenChanged = false),
            "tap_no_effect_observe",
            MicroStepKind.OBSERVE_SCREEN,
        )

    @Test
    fun `case 53 wait with spinner on screen waits again`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.WAITED, loading = true),
            "wait_still_loading",
            MicroStepKind.WAIT_FOR_RESULTS,
        )

    @Test
    fun `case 54 observation is followed by action not another read`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OBSERVED),
            "observe_then_act",
            MicroStepKind.VERIFY_SCREEN_CHANGED,
        )

    @Test
    fun `case 55 left the driven app so it must be reopened`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, appInForeground = false, packageName = "com.whatsapp"),
            "app_left_foreground_recover",
            MicroStepKind.OPEN_APP,
        )

    @Test
    fun `case 56 missing target re-observes`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.TAPPED, targetFound = false),
            "target_missing_reobserve",
            MicroStepKind.OBSERVE_SCREEN,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // E. Search-result lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 57 results still loading waits`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.PRESSED_ENTER, surface = AppSurface.SEARCH_FIELD, loading = true),
            "search_results_loading",
            MicroStepKind.WAIT_FOR_RESULTS,
        )

    @Test
    fun `case 58 empty results clears and reports`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.PRESSED_ENTER, surface = AppSurface.SEARCH_FIELD),
            "search_no_results",
            MicroStepKind.CLEAR_FIELD,
        )

    @Test
    fun `case 59 did-you-mean suggestion is tapped`() =
        assertPlan(
            tapped(label = "Did you mean photosynthesis?"),
            "search_did_you_mean",
            MicroStepKind.TAP_TEXT,
        )

    @Test
    fun `case 60 visible results pick the first row`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.WAITED, surface = AppSurface.SEARCH_FIELD, resultsVisible = true),
            "search_select_first_result",
            MicroStepKind.SELECT_FIRST_RESULT,
        )

    @Test
    fun `case 61 Enter on visible results opens the first`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.PRESSED_ENTER, surface = AppSurface.SEARCH_FIELD, resultsVisible = true),
            "enter_on_results_opens_first",
            MicroStepKind.SELECT_FIRST_RESULT,
        )

    @Test
    fun `case 62 Maps place opens Directions`() =
        assertPlan(
            tapped(AppSurface.MAPS, label = "Place: Cafe Coffee Day", pkg = "com.google.android.apps.maps"),
            "maps_result_directions",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 63 video result starts playback`() =
        assertPlan(
            tapped(AppSurface.VIDEO, label = "Video result", pkg = "com.google.android.youtube"),
            "video_result_play",
            MicroStepKind.TAP_LABEL,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // F. Media, pickers and structured flows
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 64 camera shutter then confirm`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OPENED_APP, surface = AppSurface.CAMERA),
            "camera_capture_confirm",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 65 gallery picker picks then confirms`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OPENED_APP, surface = AppSurface.FILE_PICKER),
            "gallery_pick_confirm",
            MicroStepKind.SELECT_FIRST_RESULT,
        )

    @Test
    fun `case 66 picked file is opened`() =
        assertPlan(
            tapped(AppSurface.FILE_PICKER, label = "report.pdf"),
            "file_picker_open",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 67 call button verifies connected call`() =
        assertPlan(
            tapped(AppSurface.DIALER, label = "Call", pkg = "dialer"),
            "dialer_call_confirm",
            MicroStepKind.WAIT_FOR_SETTLE,
        )

    @Test
    fun `case 68 media app presses play`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OPENED_APP, surface = AppSurface.MEDIA),
            "media_play_verify",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 69 alarm save verifies`() =
        assertPlan(
            tapped(AppSurface.FORM, label = "Alarm"),
            "alarm_save_verify",
            MicroStepKind.TAP_LABEL,
        )

    @Test
    fun `case 70 settings toggle verifies state`() =
        assertPlan(
            tapped(AppSurface.SETTINGS, label = "Wi-Fi", pkg = "com.android.settings"),
            "settings_toggle_verify",
            MicroStepKind.TOGGLE_SWITCH,
        )

    // ═════════════════════════════════════════════════════════════════════════
    // G. Task-closing hygiene
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `case 71 tap must have a visible result before finishing`() =
        assertPlan(tapped(surface = AppSurface.UNKNOWN, label = ""), "finish_needs_visible_result", MicroStepKind.VERIFY_SCREEN_CHANGED)

    @Test
    fun `case 72 empty field after typing is reported`() =
        assertPlan(
            typed(AppSurface.UNKNOWN, text = ""),
            "empty_text_target",
            MicroStepKind.FOCUS_FIELD,
        )

    @Test
    fun `case 73 fresh search screen needs a query first`() =
        assertPlan(
            MicroStepContext(lastAction = LastAction.OPENED_APP, surface = AppSurface.SEARCH_FIELD),
            "search_needs_query_first",
            MicroStepKind.FOCUS_FIELD,
        )

    @Test
    fun `catalogue exposes at least 50 distinct cases`() {
        assertTrue("expected ≥50 micro-step cases, got ${MicroStepCatalog.ids.size}", MicroStepCatalog.ids.size >= 50)
        assertEquals(
            "rule ids must be unique",
            MicroStepCatalog.ids.size,
            MicroStepCatalog.ids.distinct().size,
        )
        MicroStepCatalog.ids.forEach { id -> assertNotNull("rule $id must resolve", MicroStepPlanner.rule(id)) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tracker: enforcement across rounds
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `tracker blocks finish after typing without submitting`() {
        val tracker = MicroStepTracker()
        tracker.observe(typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.ENTER_KEY))
        val reason = tracker.blockFinishReason()
        assertNotNull("finish must be blocked while the message is unsent", reason)
        assertTrue(reason!!.contains("press Enter"))
        assertFalse(tracker.canFinish())
    }

    @Test
    fun `tracker clears the block once Enter is pressed`() {
        val tracker = MicroStepTracker()
        tracker.observe(typed(AppSurface.CHAT_COMPOSER))
        tracker.observe(
            MicroStepContext(
                lastAction = LastAction.PRESSED_ENTER,
                surface = AppSurface.CHAT_COMPOSER,
                packageName = "com.example.app",
                keyboardVisible = false,
            ),
        )
        assertNull(tracker.blockFinishReason())
        assertTrue(tracker.canFinish())
    }

    @Test
    fun `tracker guidance is injected once per pending rule`() {
        val tracker = MicroStepTracker()
        val ctx = typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.SEND_BUTTON)
        tracker.observe(ctx)
        val first = tracker.guidanceOnce()
        assertNotNull(first)
        assertTrue(first!!.contains("type_chat_tap_send"))
        assertTrue(first.contains("\"Send\""))
        assertNull("guidance must not repeat every round", tracker.guidanceOnce())
    }

    @Test
    fun `tracker reset clears pending work`() {
        val tracker = MicroStepTracker()
        tracker.observe(typed(AppSurface.CHAT_COMPOSER))
        tracker.reset()
        assertNull(tracker.blockFinishReason())
        assertEquals("none", tracker.lastRuleId())
    }

    @Test
    fun `tracker keeps the unsent-message block after an unrelated tap`() {
        val tracker = MicroStepTracker()
        tracker.observe(typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.ENTER_KEY))
        // The model wanders off and taps something else instead of submitting.
        tracker.observe(tapped(label = "", screenChanged = true))
        assertEquals("type_chat_press_enter", tracker.outstandingRuleId())
        assertNotNull("unsent message must still block finish", tracker.blockFinishReason())
    }

    @Test
    fun `tracker clears the block when Send is tapped`() {
        val tracker = MicroStepTracker()
        tracker.observe(typed(AppSurface.CHAT_COMPOSER, affordance = SubmitAffordance.SEND_BUTTON))
        tracker.observe(tapped(AppSurface.CHAT_COMPOSER, label = "Send"))
        assertNull(tracker.blockFinishReason())
    }

    @Test
    fun `verification-only plans never block finish`() {
        val tracker = MicroStepTracker()
        // A tap produces a verify step (loop-owned), not a model action.
        val plan = tracker.observe(tapped(label = "", screenChanged = true))
        assertEquals("finish_needs_visible_result", plan.ruleId)
        assertNull(tracker.blockFinishReason())
        assertTrue(tracker.canFinish())
    }

    @Test
    fun `tracker allows finish when nothing is pending`() {
        val tracker = MicroStepTracker()
        tracker.observe(MicroStepContext(lastAction = LastAction.NO_ACTION))
        assertTrue(tracker.canFinish())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Bridge: tool call → context mapping
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `bridge maps input_text in WhatsApp to chat composer typing`() {
        val ctx = MicroStepBridge.observe(
            toolName = "input_text",
            params = mapOf("text" to "hello"),
            success = true,
            foregroundPackage = "com.whatsapp",
            submitVisible = true,
        )
        assertEquals(LastAction.TYPED_TEXT, ctx.lastAction)
        assertEquals(AppSurface.CHAT_COMPOSER, ctx.surface)
        assertEquals(SubmitAffordance.SEND_BUTTON, ctx.submitAffordance)
        assertEquals("type_chat_tap_send", MicroStepPlanner.plan(ctx).ruleId)
    }

    @Test
    fun `bridge maps system_key enter to a submit action`() {
        val ctx = MicroStepBridge.observe(
            toolName = "system_key",
            params = mapOf("key" to "enter"),
            success = true,
            foregroundPackage = "com.whatsapp",
        )
        assertEquals(LastAction.PRESSED_ENTER, ctx.lastAction)
    }

    @Test
    fun `bridge maps browser typing to address bar`() {
        val ctx = MicroStepBridge.observe(
            toolName = "input_text",
            params = mapOf("text" to "example.com"),
            success = true,
            foregroundPackage = "com.android.chrome",
        )
        assertEquals(AppSurface.BROWSER_ADDRESS, ctx.surface)
        assertEquals("browser_url_enter", MicroStepPlanner.plan(ctx).ruleId)
    }

    @Test
    fun `bridge failed tool maps to no action`() {
        val ctx = MicroStepBridge.observe(
            toolName = "input_text",
            params = mapOf("text" to "hello"),
            success = false,
            foregroundPackage = "com.whatsapp",
        )
        assertEquals(LastAction.NO_ACTION, ctx.lastAction)
    }
}
