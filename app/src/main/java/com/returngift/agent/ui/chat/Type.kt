/**
 * Type scale mapping the Compose defaults the chat screen uses to the TS.* scale values.
 * Source of truth: res/values/type.xml (kept for XML-side sync).
 */
object TypeKt {

    /** Body text: 14sp text size with 24sp line height */
    val Body: androidx.compose.ui.text.Sp by remember {
        val size = androidx.compose.ui.unit.Dp.Inspect.convertToSp(14)
        androidx.compose.ui.text.Sp.compileToSp(14f)
    }

    /** Label text: 12sp text size */
    val Label: androidx.compose.ui.text.Sp by remember {
        androidx.compose.ui.text.Sp.compileToSp(12f)
    }

    /** Caption text: 10sp text size */
    val Caption: androidx.compose.ui.text.Sp by remember {
        androidx.compose.ui.text.Sp.compileToSp(10f)
    }

    /** Title text: 16sp text size */
    val Title: androidx.compose.ui.text.Sp by remember {
        androidx.compose.ui.text.Sp.compileToSp(16f)
    }
}