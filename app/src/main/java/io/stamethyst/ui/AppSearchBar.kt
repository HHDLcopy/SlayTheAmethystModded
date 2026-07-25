package io.stamethyst.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import io.stamethyst.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    history: List<String>,
    onHistorySelected: (String) -> Unit,
    onHistoryDeleted: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    searchActionLabel: String = stringResource(R.string.workshop_search_action),
    shape: Shape = SearchBarDefaults.dockedShape,
) {
    DockedSearchBar(
        modifier = modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = {
                    onQueryChange(it)
                    onExpandedChange(true)
                },
                onSearch = onSearch,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                placeholder = { Text(placeholder) },
                trailingIcon = {
                    TextButton(onClick = { onSearch(query) }) {
                        Text(searchActionLabel)
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        shape = shape,
    ) {
        SearchHistorySuggestions(
            history = history,
            onSelect = onHistorySelected,
            onDelete = onHistoryDeleted,
        )
    }
}
