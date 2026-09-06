package com.bestiapop.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bestiapop.android.data.model.CatalogCategory
import com.bestiapop.android.ui.theme.ListDensity

/**
 * Reusable horizontal category chips selector for online catalog and discovery feeds.
 * Implements Continuous Granularity:
 * - Level 2: High-level wrapper with standard catalog categories.
 * - Level 1: Fine-grained control with explicit category lists, icons, and styling.
 */

/** Level 2: High-level category chips row using standard [CatalogCategory.entries]. */
@Composable
fun CatalogCategoryChipsRow(
    selectedCategory: CatalogCategory,
    onSelectCategory: (CatalogCategory) -> Unit,
    modifier: Modifier = Modifier,
    showIcons: Boolean = true
) {
    CatalogCategoryChipsRow(
        selectedCategory = selectedCategory,
        onSelectCategory = onSelectCategory,
        categories = CatalogCategory.entries,
        showIcons = showIcons,
        modifier = modifier
    )
}

/** Level 1: Fine-grained category chips row with custom categories and icon display toggle. */
@Composable
fun CatalogCategoryChipsRow(
    selectedCategory: CatalogCategory,
    onSelectCategory: (CatalogCategory) -> Unit,
    categories: List<CatalogCategory>,
    showIcons: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelectCategory(category) },
                label = { Text(category.displayLabel(), fontWeight = FontWeight.Bold) },
                leadingIcon = if (showIcons) {
                    {
                        Icon(
                            imageVector = category.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(ListDensity.filterChipHeight)
            )
        }
    }
}

fun CatalogCategory.displayLabel(): String = when (this) {
    CatalogCategory.SONGS -> "Canciones"
    CatalogCategory.ALBUMS -> "Álbumes"
    CatalogCategory.PLAYLISTS -> "Playlists"
    CatalogCategory.GENRES -> "Géneros"
    CatalogCategory.CHARTS -> "Charts"
}

fun CatalogCategory.icon(): ImageVector = when (this) {
    CatalogCategory.SONGS -> Icons.Default.MusicNote
    CatalogCategory.ALBUMS -> Icons.Default.Album
    CatalogCategory.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    CatalogCategory.GENRES -> Icons.Default.Category
    CatalogCategory.CHARTS -> Icons.Default.Whatshot
}
