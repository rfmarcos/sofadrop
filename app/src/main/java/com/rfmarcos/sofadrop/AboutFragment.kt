package com.rfmarcos.sofadrop

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Version, support link, source code and credits. */
class AboutFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_about_title),
        getString(R.string.plugin_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // Where updates are possible, pressing the version checks for one right away
        actions += action(ID_VERSION, R.drawable.ic_act_status, getString(R.string.about_version), versionText())
            .focusable(Updater.canCheck).build()
        actions += action(ID_WIZARD, R.drawable.ic_act_check, getString(R.string.wizard_title), getString(R.string.wizard_desc)).build()
        actions += action(ID_SETTINGS_FILE, R.drawable.ic_act_download, getString(R.string.settings_file_title), getString(R.string.settings_file_desc))
            .hasNext(true).build()
        // Fire TV has no Play Store
        if (!BuildConfig.PLAY && ScreensaverSetupFragment.route(requireContext()) != ScreensaverSetupFragment.Route.BY_HAND) {
            actions += action(ID_BETA, R.drawable.ic_act_phone, getString(R.string.about_beta), getString(R.string.about_beta_desc))
                .multilineDescription(true).build()
        }
        if (BuildConfig.SUPPORT_URL.isNotBlank() && !BuildConfig.PLAY) {
            actions += action(ID_SUPPORT, R.drawable.ic_act_heart, getString(R.string.about_support), getString(R.string.about_support_desc)).build()
        }
        if (BuildConfig.SOURCE_URL.isNotBlank()) {
            actions += action(ID_SOURCE, R.drawable.ic_act_code, getString(R.string.about_source), BuildConfig.SOURCE_URL.removePrefix("https://")).build()
        }
        // The log's last line says how it ended ("Generated 40 wallpapers · 95 s")
        val log = GenerationLog.read(requireContext())
        actions += action(ID_LOG, R.drawable.ic_act_status, getString(R.string.about_log),
            log?.lines()?.lastOrNull() ?: getString(R.string.about_log_none)).hasNext(log != null).enabled(log != null).build()
        actions += action(ID_CREDITS, R.drawable.badge_tmdb, getString(R.string.about_credits), getString(R.string.about_credits_short)).build()
        explanations[ID_CREDITS] = getString(R.string.about_credits_desc)
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_VERSION -> checkForUpdate()
            ID_LOG -> open(GenerationLogFragment())
            ID_WIZARD -> Wizard.start(parentFragmentManager)
            ID_SETTINGS_FILE -> open(SettingsFileFragment())
            ID_BETA -> LinkQrDialog.newInstance(BETA_URL, R.string.about_beta, R.string.about_beta_scan).show(parentFragmentManager, "beta")
            ID_SUPPORT -> LinkQrDialog.newInstance(BuildConfig.SUPPORT_URL, R.string.about_support, R.string.about_support_scan)
                .show(parentFragmentManager, "support")
            ID_SOURCE -> LinkQrDialog.newInstance(BuildConfig.SOURCE_URL, R.string.about_source, R.string.about_source_scan)
                .show(parentFragmentManager, "source")
        }
    }

    private fun versionText(): String = when {
        Updater.available -> getString(R.string.about_version_update, BuildConfig.VERSION_NAME, Settings.updateVersion)
        Updater.canCheck -> getString(R.string.about_version_check, BuildConfig.VERSION_NAME)
        else -> BuildConfig.VERSION_NAME
    }

    /** Check now, whenever the last check was; a new version is then offered at the top of the settings. */
    private fun checkForUpdate() {
        val checkedBefore = Settings.updateCheckedAt
        updateDescription(ID_VERSION, getString(R.string.update_checking))
        background({ Updater.check(force = true) }) {
            updateDescription(ID_VERSION, versionText())
            val message = when {
                Settings.updateCheckedAt == checkedBefore -> getString(R.string.update_check_failed)
                Updater.available -> getString(R.string.update_found, Settings.updateVersion)
                else -> getString(R.string.update_none)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ID_VERSION = 1L
        private const val ID_SUPPORT = 2L
        private const val ID_SOURCE = 3L
        private const val ID_CREDITS = 4L
        private const val ID_LOG = 6L
        private const val ID_WIZARD = 7L
        private const val ID_SETTINGS_FILE = 8L
        private const val ID_BETA = 9L
        private const val BETA_URL = "https://play.google.com/apps/testing/com.rfmarcos.sofadrop"
    }
}
