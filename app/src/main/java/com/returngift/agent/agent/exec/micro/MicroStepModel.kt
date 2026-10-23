// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

/**
 * Mid-task micro-step model.
 *
 * A "micro-step" is the small, mandatory action that has to happen immediately
 * after the action the agent just took — the classic example being
 * *type into the composer → press Enter / tap Send*. The open LLM loop happily
 * stops after the typing step and reports success, so the planner makes the
 * follow-up explicit and enforceable.
 *
 * Everything in this package is pure Kotlin (no `android.*`), so the whole
 * catalogue is JVM-unit-testable.
 */

/** Where the agent currently is, semantically. */
enum class AppSurface {
    CHAT_COMPOSER,      // messaging thread / comment box
    SEARCH_FIELD,       // in-app search box (Play Store, YouTube, files, contacts…)
    BROWSER_ADDRESS,    // browser URL bar
    EMAIL_COMPOSE,      // mail compose (recipient/subject/body)
    SOCIAL_POST,        // LinkedIn/Facebook/X composer
    FORM,               // settings form, signup, checkout, event creation
    LIST,               // a scrollable result/settings list
    DIALOG,             // modal dialog / bottom sheet
    SYSTEM_PERMISSION,  // Android runtime permission or special-access screen
    SETTINGS,           // system or app settings
    HOME,               // launcher
    PLAY_STORE,         // store listing / search
    MAPS,               // map + place search
    VIDEO,              // video app (YouTube etc.)
    DIALER,             // phone dialer
    CAMERA,             // camera / capture UI
    FILE_PICKER,        // SAF / gallery picker
    MEDIA,              // music/podcast player
    UNKNOWN,
}

/** The action the agent performed in the step that just completed. */
enum class LastAction {
    OPENED_APP,
    TYPED_TEXT,
    TAPPED,
    LONG_PRESSED,
    SWIPED,
    PRESSED_ENTER,
    PRESSED_KEY,
    WAITED,
    OBSERVED,
    GRANTED_PERMISSION,
    NO_ACTION,
}

/** How the field the agent typed into is submitted. */
enum class SubmitAffordance {
    /** Enter/return key submits (most chat and search fields). */
    ENTER_KEY,
    /** A visible Send button/label must be tapped (WhatsApp-ish composers). */
    SEND_BUTTON,
    /** A magnifier/search icon button must be tapped. */
    SEARCH_BUTTON,
    /** Nothing visible/known — Enter is the safe default, then verify. */
    NONE,
}

/**
 * Everything the planner is allowed to look at. The agent loop builds this from
 * the tool call it just executed, the foreground package and the last screen
 * observation; nothing here needs the accessibility tree itself.
 */
data class MicroStepContext(
    val lastAction: LastAction,
    val surface: AppSurface = AppSurface.UNKNOWN,
    val packageName: String = "",
    /** Text that was typed (or the label of the node that was tapped). */
    val text: String = "",
    /** Key pressed for PRESSED_KEY / PRESSED_ENTER. */
    val key: String = "",
    /** Visible label of the tapped node (lower-cased by the matcher helpers). */
    val label: String = "",
    val keyboardVisible: Boolean = false,
    val submitAffordance: SubmitAffordance = SubmitAffordance.NONE,
    /** A modal dialog (non-permission) is covering the screen. */
    val dialogVisible: Boolean = false,
    /** The Android runtime/special-access permission dialog is up. */
    val permissionDialogVisible: Boolean = false,
    /** Progress bar / spinner / skeleton is on screen. */
    val loading: Boolean = false,
    /** The action visibly changed the screen (verified by the loop). */
    val screenChanged: Boolean = true,
    /** Result rows/list items are visible. */
    val resultsVisible: Boolean = false,
    /** Number of form fields still unfilled after the current one. */
    val fieldsRemaining: Int = 0,
    /** Field-level validation error is visible. */
    val validationErrorVisible: Boolean = false,
    /** The app the agent is driving is (still) in the foreground. */
    val appInForeground: Boolean = true,
    /** The field is empty (no text was actually entered). */
    val fieldEmpty: Boolean = true,
    /** The semantic target of the step was found on screen. */
    val targetFound: Boolean = true,
    /** 1-based index of the step inside the task (used only for logs/telemetry). */
    val stepIndex: Int = 0,
)

/** The atomic follow-up the agent must perform. */
enum class MicroStepKind {
    PRESS_ENTER,
    TAP_LABEL,
    TAP_TEXT,
    FOCUS_FIELD,
    CLEAR_FIELD,
    DISMISS_KEYBOARD,
    WAIT_FOR_SETTLE,
    WAIT_FOR_RESULTS,
    VERIFY_TEXT_APPEARS,
    VERIFY_TEXT_DISAPPEARS,
    VERIFY_SCREEN_CHANGED,
    VERIFY_FOREGROUND,
    OBSERVE_SCREEN,
    SCROLL,
    SELECT_FIRST_RESULT,
    GRANT_PERMISSION,
    CHOOSE_DIALOG_OPTION,
    TOGGLE_SWITCH,
    OPEN_APP,
    ASK_USER,
    STOP_AND_REPORT,
}

/**
 * One mandatory micro-step.
 *
 * @param label UI label to tap / expected value (Send, Post, Allow, OK…).
 * @param expected text expected to appear (VERIFY_TEXT_APPEARS) or the package
 *   expected in the foreground (VERIFY_FOREGROUND).
 * @param mandatory when true the task cannot be finished while this step is
 *   still outstanding (the loop blocks a premature `finish` call).
 */
data class MicroStep(
    val kind: MicroStepKind,
    val label: String = "",
    val expected: String = "",
    val mandatory: Boolean = true,
    val note: String = "",
) {
    /**
     * True when the model must actively do this (press/tap/type/open/ask).
     * Waits and verifications are loop-owned, so they never veto `finish`.
     */
    val isActionable: Boolean
        get() = when (kind) {
            MicroStepKind.PRESS_ENTER, MicroStepKind.TAP_LABEL, MicroStepKind.TAP_TEXT,
            MicroStepKind.FOCUS_FIELD, MicroStepKind.CLEAR_FIELD, MicroStepKind.DISMISS_KEYBOARD,
            MicroStepKind.SCROLL, MicroStepKind.SELECT_FIRST_RESULT,
            MicroStepKind.GRANT_PERMISSION, MicroStepKind.CHOOSE_DIALOG_OPTION,
            MicroStepKind.TOGGLE_SWITCH, MicroStepKind.OPEN_APP, MicroStepKind.ASK_USER -> true
            MicroStepKind.WAIT_FOR_SETTLE, MicroStepKind.WAIT_FOR_RESULTS,
            MicroStepKind.VERIFY_TEXT_APPEARS, MicroStepKind.VERIFY_TEXT_DISAPPEARS,
            MicroStepKind.VERIFY_SCREEN_CHANGED, MicroStepKind.VERIFY_FOREGROUND,
            MicroStepKind.OBSERVE_SCREEN, MicroStepKind.STOP_AND_REPORT -> false
        }
}

/** Result of planning: which rule fired and the ordered follow-up steps. */
data class MicroPlan(
    val ruleId: String,
    val summary: String,
    val steps: List<MicroStep>,
    val stepIndex: Int = 0,
) {
    val isComplete: Boolean get() = steps.isEmpty()

    /**
     * The first step that the model itself must perform. Verification/wait
     * steps are executed by the loop, so they never block a `finish` call.
     */
    val firstMandatory: MicroStep? get() = steps.firstOrNull { it.mandatory && it.isActionable }

    /** Any outstanding action step (what `finish` must wait for). */
    val hasBlockingAction: Boolean get() = firstMandatory != null
}

/** A single mid-task planning rule. */
data class MicroRule(
    val id: String,
    val summary: String,
    /** Higher wins when several rules match the same context. */
    val priority: Int,
    val match: (MicroStepContext) -> Boolean,
    val build: (MicroStepContext) -> List<MicroStep>,
)

/** Small predicate/step helpers so the catalogue reads like prose. */
object MicroMatch {
    fun labelHas(ctx: MicroStepContext, vararg tokens: String): Boolean {
        val l = ctx.label.lowercase()
        return l.isNotEmpty() && tokens.any { l.contains(it) }
    }

    fun typedInto(ctx: MicroStepContext, surface: AppSurface): Boolean =
        ctx.lastAction == LastAction.TYPED_TEXT && ctx.surface == surface && !ctx.fieldEmpty

    fun typedMoreThan(ctx: MicroStepContext, chars: Int): Boolean =
        ctx.text.trim().length > chars

    fun pkgIn(ctx: MicroStepContext, vararg packages: String): Boolean {
        val p = ctx.packageName.lowercase()
        return p.isNotEmpty() && packages.any { p.contains(it) }
    }
}

/** Step constructors. */
object MicroSteps {
    /**
     * Submit with Enter. Some chat apps (WhatsApp) map Enter to a newline, so the
     * guidance always allows tapping a visible Send button instead.
     */
    fun enter(note: String = "submit the typed text — press Enter, or tap Send if the app shows a Send button") =
        MicroStep(MicroStepKind.PRESS_ENTER, label = "enter", note = note)

    fun tap(label: String, note: String = "") =
        MicroStep(MicroStepKind.TAP_LABEL, label = label, note = note)

    fun tapText(text: String) = MicroStep(MicroStepKind.TAP_TEXT, label = text)

    // Focusing is usually automatic after a tap, so it is a hint, not a blocker.
    fun focus() = MicroStep(
        MicroStepKind.FOCUS_FIELD,
        mandatory = false,
        note = "focus the field before typing",
    )

    fun clear() = MicroStep(MicroStepKind.CLEAR_FIELD, mandatory = false)

    fun dismissKeyboard() =
        MicroStep(MicroStepKind.DISMISS_KEYBOARD, mandatory = false, note = "keyboard hides the next target")

    fun wait() = MicroStep(MicroStepKind.WAIT_FOR_SETTLE, mandatory = false)

    fun waitResults() = MicroStep(MicroStepKind.WAIT_FOR_RESULTS)

    fun verifyText(text: String) =
        MicroStep(MicroStepKind.VERIFY_TEXT_APPEARS, expected = text)

    fun verifyGone(text: String) =
        MicroStep(MicroStepKind.VERIFY_TEXT_DISAPPEARS, expected = text)

    fun verifyChanged() = MicroStep(MicroStepKind.VERIFY_SCREEN_CHANGED)

    fun verifyForeground(pkg: String) =
        MicroStep(MicroStepKind.VERIFY_FOREGROUND, expected = pkg)

    fun observe() = MicroStep(MicroStepKind.OBSERVE_SCREEN, mandatory = false)

    fun scroll() = MicroStep(MicroStepKind.SCROLL, mandatory = false)

    fun firstResult() = MicroStep(MicroStepKind.SELECT_FIRST_RESULT)

    fun allow() = MicroStep(MicroStepKind.GRANT_PERMISSION, label = "Allow")

    fun dialogOption(label: String) = MicroStep(MicroStepKind.CHOOSE_DIALOG_OPTION, label = label)

    fun toggle(label: String) = MicroStep(MicroStepKind.TOGGLE_SWITCH, label = label)

    fun openApp(pkg: String) = MicroStep(MicroStepKind.OPEN_APP, expected = pkg)

    fun ask(question: String) = MicroStep(MicroStepKind.ASK_USER, label = question)

    fun stop(reason: String) =
        MicroStep(MicroStepKind.STOP_AND_REPORT, note = reason, mandatory = false)
}
