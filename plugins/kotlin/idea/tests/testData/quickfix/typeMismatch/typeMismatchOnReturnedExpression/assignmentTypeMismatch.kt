// "Change type of 'f' to '() -> Unit'" "true"
fun foo() {
    val f: () -> Int = {
        var x = 1
        x += 21<caret>
    }
}
/* IGNORE_FIR */

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeFunctionLiteralReturnTypeFix