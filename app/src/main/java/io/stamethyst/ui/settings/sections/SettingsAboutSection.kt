package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.importing.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.rememberCloudControlSettings


@Composable
internal fun SettingsAuthorInfoSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsAuthorSectionTitle(text = stringResource(R.string.settings_author_repo_label))
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_repo_url),
            url = stringResource(R.string.settings_author_repo_url),
        )
        SettingsAuthorSectionTitle(text = stringResource(R.string.settings_author_contributors_label))
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_ketal_name),
            url = stringResource(R.string.settings_author_contributor_ketal_url),
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_apricityx_name),
            url = stringResource(R.string.settings_author_contributor_apricityx_url),
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_freude916_name),
            url = stringResource(R.string.settings_author_contributor_freude916_url),
        )
        SettingsInlineLinkRow(
            label = stringResource(R.string.settings_author_icon_design_label),
            text = stringResource(R.string.settings_author_contributor_raw_filter_name),
            url = stringResource(R.string.settings_author_contributor_raw_filter_url),
        )
        SettingsAuthorSectionTitle(text = stringResource(R.string.settings_author_friend_links_label))
        Text(
            text = stringResource(R.string.settings_author_friend_links_intro),
            style = MaterialTheme.typography.bodySmall
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_friend_links_wsdx233_url),
            url = stringResource(R.string.settings_author_friend_links_wsdx233_url),
        )
        SettingsAuthorSectionTitle(text = stringResource(R.string.settings_author_special_thanks_label))
        SettingsInlineLinkSentence(
            prefix = stringResource(R.string.settings_author_special_thanks_amethyst_prefix),
            linkText = stringResource(R.string.settings_author_special_thanks_amethyst_name),
            suffix = stringResource(R.string.settings_author_special_thanks_amethyst_suffix),
            url = stringResource(R.string.settings_author_special_thanks_amethyst_url),
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_item_2),
            style = MaterialTheme.typography.bodySmall
        )
        SettingsInlineLinkSentence(
            prefix = stringResource(R.string.settings_author_special_thanks_butterfly_prefix),
            linkText = stringResource(R.string.settings_author_special_thanks_butterfly_name),
            suffix = stringResource(R.string.settings_author_special_thanks_butterfly_suffix),
            url = stringResource(R.string.settings_author_special_thanks_butterfly_url),
        )
        SettingsInlineLinkSentence(
            prefix = stringResource(R.string.settings_author_special_thanks_ram_saver_prefix),
            linkText = stringResource(R.string.settings_author_special_thanks_ram_saver_name),
            suffix = stringResource(R.string.settings_author_special_thanks_ram_saver_suffix),
            url = stringResource(R.string.settings_author_special_thanks_ram_saver_url),
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_footer),
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_author_release_notice),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_report_notice),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_follow_notice),
            style = MaterialTheme.typography.bodySmall
        )
        SettingsQqGroupLinkRow()
    }
}


@Composable
internal fun SettingsAuthorSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium
    )
}


@Composable
internal fun SettingsInlineLinkSentence(
    prefix: String,
    linkText: String,
    suffix: String,
    url: String,
) {
    val uriHandler = LocalUriHandler.current
    val linkTag = "url"
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val text = buildAnnotatedString {
        append(prefix)
        val linkStart = length
        append(linkText)
        addStyle(linkStyle, linkStart, length)
        addStringAnnotation(linkTag, url, linkStart, length)
        append(suffix)
    }
    ClickableText(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        onClick = { offset ->
            text.getStringAnnotations(linkTag, offset, offset)
                .firstOrNull()
                ?.let { annotation -> uriHandler.openUri(annotation.item) }
        }
    )
}


@Composable
internal fun SettingsInlineLinkRow(
    label: String,
    text: String,
    url: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
        SettingsExternalLinkText(
            text = text,
            url = url,
        )
    }
}


@Composable
internal fun SettingsQqGroupLinkRow() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cloudControlSettings by rememberCloudControlSettings()
    val groupNumber = cloudControlSettings.qqGroupNumber
    val groupUrl = cloudControlSettings.qqGroupUrl
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_author_qq_group_prefix),
            style = MaterialTheme.typography.bodySmall
        )
        SettingsExternalLinkText(text = groupNumber, url = groupUrl) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("qq-group", groupNumber))
            uriHandler.openUri(groupUrl)
        }
    }
}


@Composable
internal fun SettingsExternalLinkText(
    text: String,
    url: String,
    onClick: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        modifier = Modifier.hapticClickable(enabled = true) {
            if (onClick != null) {
                onClick()
            } else {
                uriHandler.openUri(url)
            }
        }
    )
}


