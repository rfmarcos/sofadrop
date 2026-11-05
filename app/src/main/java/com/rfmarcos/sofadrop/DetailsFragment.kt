package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * How each wallpaper looks: style and resolution here, and a screen for the rest (languages, header,
 * synopsis and cast, footer). Rows of the parts the style hides stay in place, disabled.
 */
class DetailsFragment : PreviewSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_details_title),
        getString(R.string.details_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val styleLabels = Settings.STYLES.map { styleLabel(context, it) }
        return listOfNotNull(
            action(ID_STYLE, R.drawable.ic_act_visibility, getString(R.string.style_title), styleLabel(context, profile.style))
                .subActions(options(ID_STYLE, styleLabels, styleLabel(context, profile.style),
                    Settings.STYLES.map { styleDescription(context, it) })).build(),
            action(ID_POSITION, R.drawable.ic_act_place, getString(R.string.position_title), positionLabel(context, profile.position))
                .subActions(options(ID_POSITION, POSITIONS.map { positionLabel(context, it) }, positionLabel(context, profile.position),
                    // Down where the launcher rows are, in Projectivy's usual layout (no rows over the screensaver)
                    POSITIONS.map { positionNote(it) }))
                .build().hiddenBy(profile.drawsLogo),
            screen(ID_ROTATION, R.drawable.ic_act_place, R.string.position_rotation_title, getString(R.string.position_rotation_summary, profile.rotation.size))
                .takeIf { profile.drawsLogo && profile.position == Settings.POSITION_ROTATE },
            action(ID_SHADE, R.drawable.ic_act_layout, getString(R.string.shade_title), shadeLabel(context, profile.shade))
                .subActions(options(ID_SHADE, Settings.SHADES.map { shadeLabel(context, it) }, shadeLabel(context, profile.shade),
                    listOf(getString(R.string.shade_tint_desc), getString(R.string.shade_black_desc))))
                .build(),
            action(ID_BOTTOM_SHADE, R.drawable.ic_act_layout, getString(R.string.bottom_shade_title), bottomShadeLabel(context, profile.bottomShade))
                .subActions(options(ID_BOTTOM_SHADE, Settings.BOTTOM_SHADES.map { bottomShadeLabel(context, it) }, bottomShadeLabel(context, profile.bottomShade),
                    Settings.BOTTOM_SHADES.map {
                        when (it) {
                            Settings.BOTTOM_SHADE_NORMAL -> getString(R.string.bottom_shade_normal_desc)
                            0 -> getString(R.string.bottom_shade_none_desc)
                            else -> null
                        }
                    }))
                .build(),
            screen(ID_LANGUAGE, R.drawable.ic_act_language, R.string.setting_language_title, PartLanguagesFragment.summary(context, profile)),
            screen(ID_HEADER, R.drawable.ic_act_heart, R.string.header_title, HeaderFragment.summary(context, profile)).hiddenBy(profile.drawsDetails),
            screen(ID_SYNOPSIS_CAST, R.drawable.ic_act_count, R.string.synopsis_cast_title, SynopsisCastFragment.summary(context, profile))
                .hiddenBy(profile.drawsDetails),
            screen(ID_FOOTER, R.drawable.ic_act_list, R.string.footer_title, FooterFragment.summary(context, profile)).hiddenBy(profile.drawsDetails),
            action(ID_RESOLUTION, R.drawable.ic_act_hd, getString(R.string.setting_resolution_title), resolutionLabel(context, profile.outputWidth))
                .subActions(options(ID_RESOLUTION, Settings.WIDTH_OPTIONS.map { resolutionLabel(context, it) }, resolutionLabel(context, profile.outputWidth),
                    Settings.WIDTH_OPTIONS.map { width ->
                        if (width > 1920 && DeviceMemory.tightFor4k(context)) getString(R.string.resolution_low_memory) else null
                    }))
                .build(),
            screen(ID_PREVIEW_PIN, R.drawable.ic_act_preview, R.string.preview_pin_title, PreviewPinFragment.summary(context)),
        )
    }

    /** Row opening a sub-screen, with the summary of its settings. */
    private fun screen(id: Long, icon: Int, title: Int, summary: String) = action(id, icon, getString(title), summary).hasNext(true).build()

    // Back from a sub-screen: its summary may have changed
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_LANGUAGE -> open(PartLanguagesFragment())
            ID_HEADER -> open(HeaderFragment())
            ID_SYNOPSIS_CAST -> open(SynopsisCastFragment())
            ID_FOOTER -> open(FooterFragment())
            ID_PREVIEW_PIN -> open(PreviewPinFragment())
            ID_ROTATION -> open(RotationFragment())
        }
    }

    private fun positionNote(position: String): String? {
        val rows = getString(R.string.position_rows_note).takeIf {
            !position.startsWith("top") && !profile.isScreensaver && Projectivy.isInstalled(requireContext())
        }
        return if (position == Settings.POSITION_ROTATE) getString(R.string.position_rotate_desc) else rows
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_STYLE -> {
                profile.style = Settings.STYLES[index]
                // Rows of the parts it hides (or brings back) change
                collapseSubActions()
            }
            ID_POSITION -> profile.position = POSITIONS[index]
            ID_SHADE -> profile.shade = Settings.SHADES[index]
            ID_BOTTOM_SHADE -> profile.bottomShade = Settings.BOTTOM_SHADES[index]
            ID_RESOLUTION -> profile.outputWidth = Settings.WIDTH_OPTIONS[index]
        }
        refreshActions(rows())
        schedulePreview(300)
        return true
    }

    companion object {
        private const val ID_LANGUAGE = 1L
        private const val ID_RESOLUTION = 3L
        private const val ID_FOOTER = 5L
        private const val ID_HEADER = 8L
        private const val ID_PREVIEW_PIN = 9L
        private const val ID_ROTATION = 20L
        private val POSITIONS = listOf(Settings.POSITION_ROTATE) + Settings.POSITIONS
        private const val ID_STYLE = 11L
        private const val ID_SYNOPSIS_CAST = 14L
        private const val ID_POSITION = 15L
        private const val ID_SHADE = 16L
        private const val ID_BOTTOM_SHADE = 21L

        fun shadeLabel(context: Context, shade: String) = context.getString(
            when (shade) {
                Settings.SHADE_BLACK -> R.string.shade_black
                else -> R.string.shade_tint
            }
        )

        fun positionLabel(context: Context, position: String) = context.getString(
            when (position) {
                "center_left" -> R.string.position_center_left
                "bottom_left" -> R.string.position_bottom_left
                "top_right" -> R.string.position_top_right
                "center_right" -> R.string.position_center_right
                "bottom_right" -> R.string.position_bottom_right
                Settings.POSITION_ROTATE -> R.string.position_rotate
                else -> R.string.position_top_left
            }
        )

        fun styleLabel(context: Context, style: String) = context.getString(
            when (style) {
                Settings.STYLE_LOGO -> R.string.style_logo
                Settings.STYLE_BACKDROP -> R.string.style_backdrop
                else -> R.string.style_full
            }
        )

        fun styleDescription(context: Context, style: String) = context.getString(
            when (style) {
                Settings.STYLE_LOGO -> R.string.style_logo_desc
                Settings.STYLE_BACKDROP -> R.string.style_backdrop_desc
                else -> R.string.style_full_desc
            }
        )

        fun bottomShadeLabel(context: Context, shade: Int) = context.getString(
            when (shade) {
                125 -> R.string.bottom_shade_high
                50 -> R.string.bottom_shade_low
                25 -> R.string.bottom_shade_minimal
                0 -> R.string.bottom_shade_none
                else -> R.string.bottom_shade_normal
            }
        )

        fun resolutionLabel(context: Context, width: Int) =
            context.getString(if (width > 1920) R.string.resolution_2160 else R.string.resolution_1080)

        /** "English (US) · short synopsis · streaming in US · 1080p", or "Logo only · English (US) · 1080p", for the main screen. */
        fun summary(context: Context, profile: Profile): String = listOfNotNull(
            styleLabel(context, profile.style).takeIf { !profile.drawsDetails },
            positionLabel(context, profile.position).takeIf { profile.position != Settings.POSITION_TOP_LEFT && profile.drawsLogo },
            context.getString(R.string.shade_summary_black).takeIf { profile.shade == Settings.SHADE_BLACK },
            context.getString(R.string.bottom_shade_summary, bottomShadeLabel(context, profile.bottomShade).lowercase())
                .takeIf { profile.bottomShade != Settings.BOTTOM_SHADE_NORMAL },
            Languages.displayName(Settings.language),
            when (profile.overviewLines) {
                0 -> context.getString(R.string.synopsis_summary_hidden)
                2 -> context.getString(R.string.synopsis_summary_short)
                else -> null
            }?.takeIf { profile.drawsDetails },
            (if (profile.showProviders) context.getString(R.string.streaming_summary, Languages.countryName(Settings.region))
            else context.getString(R.string.streaming_summary_none)).takeIf { profile.drawsDetails },
            context.getString(if (profile.outputWidth > 1920) R.string.resolution_short_2160 else R.string.resolution_short_1080),
        ).joinToString(" · ")
    }
}
