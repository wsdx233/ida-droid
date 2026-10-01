package dev.idadroid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState

/** Async GFM renderer retained during streaming so partial output never blanks the reader. */
@Composable
internal fun MarkdownishText(text: String, selectable: Boolean = false, lightweight: Boolean = false) {
    val state = rememberMarkdownState(content = text, retainState = true)
    val content: @Composable () -> Unit = {
        Markdown(state, modifier = Modifier.fillMaxWidth())
    }
    if (selectable) SelectionContainer { content() } else content()
}
