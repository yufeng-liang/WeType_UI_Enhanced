package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference


internal fun LazyListScope.ClipboardSubPageContent(
    showCrossDeviceClipboard: Boolean,
    onShowCrossDeviceClipboardChange: (Boolean) -> Unit,
    removeClipboardRetentionLimit: Boolean,
    onRemoveClipboardRetentionLimitChange: (Boolean) -> Unit,
    removeClipboardTextLimit: Boolean,
    onRemoveClipboardTextLimitChange: (Boolean) -> Unit,
    clipboardImageAdjustRatio: Boolean,
    onClipboardImageAdjustRatioChange: (Boolean) -> Unit,
    clipboardImageCrop: Boolean,
    onClipboardImageCropChange: (Boolean) -> Unit,
    clipboardImageUniformRowHeight: Boolean,
    onClipboardImageUniformRowHeightChange: (Boolean) -> Unit,
    clipboardImageMaxCount: Int,
    onClipboardImageMaxCountChange: (Int) -> Unit,
    clipboardImageMaxSizeMb: Int,
    onClipboardImageMaxSizeMbChange: (Int) -> Unit,
    clipboardSearchEnabled: Boolean,
    onClipboardSearchEnabledChange: (Boolean) -> Unit,
    clipboardSearchClearOnBack: Boolean,
    onClipboardSearchClearOnBackChange: (Boolean) -> Unit,
    clipboardWaterfallFlowEnabled: Boolean,
    onClipboardWaterfallFlowEnabledChange: (Boolean) -> Unit,
    clipboardWaterfallMaxHeightDp: Int,
    onClipboardWaterfallMaxHeightDpChange: (Int) -> Unit,
    onOpenClipboardBackup: () -> Unit
) {
    item {
        SmallTitle(text = stringResource(R.string.settings_clipboard_section_basic))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_cross_device_title),
                summary = stringResource(R.string.settings_clipboard_cross_device_desc),
                checked = showCrossDeviceClipboard,
                onCheckedChange = onShowCrossDeviceClipboardChange
            )
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_retention_title),
                summary = stringResource(R.string.settings_clipboard_retention_desc),
                checked = removeClipboardRetentionLimit,
                onCheckedChange = onRemoveClipboardRetentionLimitChange
            )
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_text_limit_title),
                summary = stringResource(R.string.settings_clipboard_text_limit_desc),
                checked = removeClipboardTextLimit,
                onCheckedChange = onRemoveClipboardTextLimitChange
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_clipboard_section_search))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_search_title),
                summary = stringResource(R.string.settings_clipboard_search_desc),
                checked = clipboardSearchEnabled,
                onCheckedChange = onClipboardSearchEnabledChange
            )
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_search_clear_on_back_title),
                summary = stringResource(R.string.settings_clipboard_search_clear_on_back_desc),
                checked = clipboardSearchClearOnBack,
                onCheckedChange = onClipboardSearchClearOnBackChange
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_clipboard_section_image))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_image_adjust_ratio_title),
                summary = stringResource(R.string.settings_clipboard_image_adjust_ratio_desc),
                checked = clipboardImageAdjustRatio,
                onCheckedChange = onClipboardImageAdjustRatioChange
            )
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_image_crop_title),
                summary = stringResource(R.string.settings_clipboard_image_crop_desc),
                checked = clipboardImageCrop,
                onCheckedChange = onClipboardImageCropChange
            )
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_image_uniform_row_height_title),
                summary = stringResource(R.string.settings_clipboard_image_uniform_row_height_desc),
                checked = clipboardImageUniformRowHeight,
                onCheckedChange = onClipboardImageUniformRowHeightChange
            )
            val imageCountOptions = listOf(0, 20, 50, 100, 200, 500)
            val imageSizeOptions = listOf(0, 128, 256, 512, 1024, 2048)
            val noLimitText = stringResource(R.string.settings_clipboard_no_limit)
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_clipboard_image_max_count_title),
                items = imageCountOptions.map {
                    if (it == 0) noLimitText else stringResource(R.string.settings_clipboard_image_count_option, it)
                },
                selectedIndex = imageCountOptions.indexOf(clipboardImageMaxCount).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxCountChange(imageCountOptions[index])
                }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_clipboard_image_max_size_title),
                items = imageSizeOptions.map {
                    when {
                        it == 0 -> noLimitText
                        it >= 1024 -> stringResource(R.string.settings_clipboard_image_size_gb, it / 1024)
                        else -> stringResource(R.string.settings_clipboard_image_size_mb, it)
                    }
                },
                selectedIndex = imageSizeOptions.indexOf(clipboardImageMaxSizeMb).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxSizeMbChange(imageSizeOptions[index])
                }
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_clipboard_section_layout))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_clipboard_waterfall_title),
                summary = stringResource(R.string.settings_clipboard_waterfall_desc),
                checked = clipboardWaterfallFlowEnabled,
                onCheckedChange = onClipboardWaterfallFlowEnabledChange
            )
            val waterfallHeightOptions = listOf(100, 120, 140, 160, 180, 200, 240)
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_clipboard_waterfall_max_height_title),
                items = waterfallHeightOptions.map {
                    stringResource(R.string.settings_clipboard_dp_option, it)
                },
                selectedIndex = waterfallHeightOptions
                    .indexOf(clipboardWaterfallMaxHeightDp)
                    .coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardWaterfallMaxHeightDpChange(waterfallHeightOptions[index])
                }
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_clipboard_section_backup))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = stringResource(R.string.settings_clipboard_backup_title),
                summary = stringResource(R.string.settings_clipboard_backup_desc),
                onClick = onOpenClipboardBackup
            )
        }
    }
}
