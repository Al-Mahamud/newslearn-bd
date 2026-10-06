package com.newslearn.bd.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.newslearn.bd.data.remote.ArticleCardDto
import com.newslearn.bd.ui.common.ContentCard
import com.newslearn.bd.ui.common.ExamBadge
import com.newslearn.bd.ui.common.categoryLabel
import com.newslearn.bd.ui.common.relativeTime

@Composable
fun ArticleCard(
    article: ArticleCardDto,
    onClick: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            if (article.imageUrl != null) {
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Column(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (article.examImportant) ExamBadge(article.examImportance)
                    Text(
                        "${categoryLabel(article.category)} · ${article.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onToggleSave) {
                        Icon(
                            if (article.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (article.saved) "Remove from saved" else "Save article",
                            tint = if (article.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        article.title,
                        style = MaterialTheme.typography.titleLarge,
                        // Articles already read are dimmed so new ones stand out.
                        modifier = Modifier.alpha(if (article.read) 0.6f else 1f),
                    )
                    Text(
                        article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(
                            relativeTime(article.publishedAt).takeIf { it.isNotEmpty() },
                            "${article.wordCount} words to learn".takeIf { article.wordCount > 0 },
                            "Read".takeIf { article.read },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
