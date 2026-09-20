package io.github.oleglog.olcrtc.client.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.LocaleListCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.oleglog.olcrtc.client.BuildConfig
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.databinding.FragmentSettingsBinding
import io.github.oleglog.olcrtc.client.diagnostics.DiagnosticsLogStore
import io.github.oleglog.olcrtc.client.diagnostics.DiagnosticsRedactor
import io.github.oleglog.olcrtc.client.diagnostics.NetworkDiagnostics
import io.github.oleglog.olcrtc.client.routing.GeoAssetManager
import io.github.oleglog.olcrtc.client.routing.PerAppPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingRule
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.support.IssueReportBuilder
import io.github.oleglog.olcrtc.client.support.IssueReportInfo
import io.github.oleglog.olcrtc.client.updater.GitHubRelease
import io.github.oleglog.olcrtc.client.updater.UpdateAssetSelector
import io.github.oleglog.olcrtc.client.updater.VersionComparator
import io.github.oleglog.olcrtc.client.vpn.GomobileCore
import io.github.oleglog.olcrtc.client.vpn.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val settings by lazy { RoutingSettings.open(requireContext().applicationContext) }
    private val diagnostics by lazy { DiagnosticsLogStore.open(requireContext().applicationContext) }
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val appearanceSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) requireActivity().recreate()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return requireNotNull(_binding).root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = requireNotNull(_binding)
        binding.settingsRoutingRow.setOnClickListener { showRoutingSettings() }
        binding.settingsAppsRow.setOnClickListener { selectApps() }
        binding.settingsAppearanceRow.setOnClickListener {
            appearanceSettings.launch(Intent(requireContext(), AppearanceSettingsActivity::class.java))
        }
        binding.settingsDnsRow.setOnClickListener { showDnsSettings() }
        binding.settingsSystemRow.setOnClickListener {
            showActions(
                R.string.settings_system_title,
                intArrayOf(R.string.settings_language, R.string.settings_always_on, R.string.settings_battery_optimization),
                arrayOf(
                    ::changeLanguage,
                    { openSystemSettings(Settings.ACTION_VPN_SETTINGS) },
                    { (requireActivity() as MainActivity).openBatteryOptimizationSettings() },
                ),
                getString(R.string.settings_system_description),
            )
        }
        binding.settingsUpdatesRow.setOnClickListener { showUpdatesSettings() }
        binding.settingsDiagnosticsRow.setOnClickListener { showDiagnosticsMenu() }
        binding.settingsAboutRow.setOnClickListener { showAbout() }
        binding.settingsAppearanceSummary.setText(R.string.settings_appearance_summary)
        binding.settingsSystemSummary.setText(R.string.settings_system_row_summary)
        binding.settingsUpdatesSummary.text = getString(R.string.settings_version_summary, BuildConfig.VERSION_NAME)
        binding.settingsDiagnosticsSummary.setText(R.string.settings_diagnostics_row_summary)
        binding.settingsAboutSummary.text = getString(R.string.settings_version_summary, BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun changeLanguage() {
        val options = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_russian),
            getString(R.string.settings_language_english),
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = when {
            current.startsWith("ru") -> 1
            current.startsWith("en") -> 2
            else -> 0
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, selected) { dialog, index ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        when (index) {
                            1 -> "ru"
                            2 -> "en"
                            else -> ""
                        },
                    ),
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                LoadedSettings(
                    routingPolicy = settings.get(),
                    dnsServer = settings.getDnsServer(),
                    perAppPolicy = settings.getPerAppPolicy(),
                    routingRules = profiles.getEnabledRoutingRules().size,
                )
            }
            val binding = _binding ?: return@launch
            val preset = getString(
                if (values.routingPolicy.preset == RoutingPolicy.Preset.ALL_VPN) {
                    R.string.routing_all_vpn
                } else {
                    R.string.routing_russia_direct
                },
            )
            binding.settingsRoutingSummary.text = "$preset · ${getString(R.string.settings_routing_rules_summary, values.routingRules)}"
            binding.settingsAppsSummary.text = getString(
                R.string.settings_apps_row_summary,
                getString(when (values.perAppPolicy.mode) {
                    PerAppPolicy.Mode.ALL -> R.string.settings_per_app_all
                    PerAppPolicy.Mode.EXCLUDE_SELECTED -> R.string.settings_per_app_exclude
                    PerAppPolicy.Mode.ONLY_SELECTED -> R.string.settings_per_app_only
                }),
                values.perAppPolicy.packages.size,
            )
            binding.settingsDnsSummary.text = values.dnsServer ?: "1.1.1.1:53"
        }
    }

    private fun selectApps() {
        startActivity(Intent(requireContext(), AppSelectorActivity::class.java))
    }

    private fun showRoutingSettings() {
        val choices = listOf(
            RoutingPolicy.Preset.RUSSIA_DIRECT to R.string.routing_mode_russia,
            RoutingPolicy.Preset.ALL_VPN to R.string.routing_mode_all_vpn,
        )
        val current = settings.get()
        var selectedPreset = current.preset

        val presetSummary = TextView(requireContext()).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        fun updatePresetSummary() {
            presetSummary.setText(
                if (selectedPreset == RoutingPolicy.Preset.RUSSIA_DIRECT) {
                    R.string.routing_mode_russia_description
                } else {
                    R.string.routing_mode_all_vpn_description
                },
            )
        }
        val presetButtons = mutableMapOf<Int, RoutingPolicy.Preset>()
        val presetGroup = MaterialButtonToggleGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
            choices.forEach { (preset, label) ->
                val button = MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    id = View.generateViewId()
                    setText(label)
                    maxLines = 2
                    minHeight = 56.dp
                    applySegmentedStyle()
                }
                presetButtons[button.id] = preset
                addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    selectedPreset = presetButtons.getValue(checkedId)
                    updatePresetSummary()
                }
            }
        }
        presetGroup.check(presetButtons.entries.first { it.value == selectedPreset }.key)
        updatePresetSummary()
        val allowLan = MaterialSwitch(requireContext()).apply {
            setText(R.string.allow_lan)
            isChecked = current.allowLan
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(TextView(requireContext()).apply {
                setText(R.string.settings_routing_preset)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, 0, 0, 8.dp)
            })
            addView(presetGroup)
            addView(presetSummary, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp })
            addView(allowLan, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12.dp })
            addView(TextView(requireContext()).apply {
                setText(R.string.allow_lan_description)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            addView(MaterialButton(requireContext()).apply {
                setText(R.string.settings_manage_rules)
                setOnClickListener { showRoutingRules() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12.dp })
            addView(MaterialButton(requireContext()).apply {
                setText(R.string.settings_update_geo_assets)
                setOnClickListener { updateGeoAssets() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_routing_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_settings) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        settings.set(RoutingPolicy(selectedPreset, allowLan.isChecked))
                    }
                    _binding?.status?.setText(R.string.settings_saved)
                    load()
                }
            }
            .show()
    }

    private fun showRoutingRules() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { profiles.getAllRoutingRules() }
            var parentDialog: AlertDialog? = null
            val list = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24.dp, 8.dp, 24.dp, 8.dp)
                addView(TextView(requireContext()).apply {
                    setText(R.string.settings_routing_preview)
                    setPadding(0, 0, 0, 8.dp)
                })
                if (rules.isEmpty()) {
                    addView(TextView(requireContext()).apply {
                        setText(R.string.settings_routing_rules_empty)
                        setPadding(0, 12.dp, 0, 12.dp)
                    })
                }
                rules.forEach { rule ->
                    addView(routingRuleRow(rule, onEdit = {
                        parentDialog?.dismiss()
                        showRoutingRuleEditor(rule)
                    }, onDelete = {
                        confirmDeleteRoutingRule(rule) {
                            parentDialog?.dismiss()
                            showRoutingRules()
                        }
                    }))
                }
            }
            parentDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_manage_rules)
                .setView(ScrollView(requireContext()).apply { addView(list) })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_add_routing_rule) { _, _ -> showRoutingRuleEditor(null) }
                .show()
        }
    }

    private fun routingRuleRow(
        rule: RoutingRule,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 10.dp, 0, 10.dp)
        addView(TextView(requireContext()).apply {
            text = "${routingActionLabel(rule.action)} · ${routingTypeLabel(rule.matchType)}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        addView(TextView(requireContext()).apply {
            text = rule.value
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        })
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(MaterialSwitch(requireContext()).apply {
                setText(R.string.settings_rule_enabled)
                isChecked = rule.enabled
                setOnCheckedChangeListener { button, checked ->
                    if (button.tag == true) return@setOnCheckedChangeListener
                    button.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { profiles.setRoutingRuleEnabled(rule.id, checked) }
                        }.onSuccess {
                            load()
                        }.onFailure {
                            button.tag = true
                            button.isChecked = rule.enabled
                            button.tag = false
                            _binding?.status?.text = it.message
                        }
                        button.isEnabled = true
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                setText(R.string.edit)
                setOnClickListener { onEdit() }
            })
            addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                setText(R.string.delete)
                setOnClickListener { onDelete() }
            })
        })
    }

    private fun confirmDeleteRoutingRule(rule: RoutingRule, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_rule_delete_title)
            .setMessage(getString(R.string.settings_rule_delete_message, rule.value))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { profiles.deleteRoutingRule(rule.id) }
                    }.onSuccess {
                        load()
                        onDeleted()
                    }.onFailure { _binding?.status?.text = it.message }
                }
            }
            .show()
    }

    private fun showDnsSettings() {
        val input = TextInputEditText(requireContext()).apply {
            setText(settings.getDnsServer() ?: "1.1.1.1:53")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(TextInputLayout(requireContext()).apply {
                hint = getString(R.string.dns_server)
                addView(input)
            })
            addView(android.widget.TextView(requireContext()).apply {
                setText(R.string.settings_dns_explanation)
                setPadding(0, 8.dp, 0, 0)
            })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dns_server)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_settings) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            settings.setDnsServer(input.text?.toString()?.trim()?.takeIf(String::isNotEmpty))
                        }
                    }.onSuccess {
                        _binding?.status?.setText(R.string.settings_saved)
                        load()
                    }.onFailure { _binding?.status?.text = it.message }
                }
            }
            .show()
    }

    private fun showUpdatesSettings() {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(TextView(requireContext()).apply {
                setText(R.string.settings_updates_description)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, 0, 0, 12.dp)
            })
            addView(MaterialSwitch(requireContext()).apply {
                setText(R.string.settings_auto_subscription_refresh)
                isChecked = settings.getAutoSubscriptionRefresh()
                setOnCheckedChangeListener { _, checked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { settings.setAutoSubscriptionRefresh(checked) }
                    }
                }
            })
            addView(TextView(requireContext()).apply {
                setText(R.string.settings_auto_subscription_refresh_description)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
        }
        showActions(
            R.string.settings_updates_title,
            intArrayOf(R.string.settings_check_update, R.string.settings_choose_release_apk),
            arrayOf(::checkUpdate, ::chooseReleaseAndApk),
            message = null,
            header = content,
        )
    }

    private fun showActions(
        title: Int,
        labels: IntArray,
        actions: Array<() -> Unit>,
        message: CharSequence? = null,
        header: View? = null,
    ) {
        require(labels.size == actions.size)
        var dialog: AlertDialog? = null
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            header?.let { addView(it) }
            message?.let {
                addView(TextView(requireContext()).apply {
                    text = it
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(0, 0, 0, 8.dp)
                })
            }
            labels.forEachIndexed { index, label ->
                addView(MaterialButton(requireContext()).apply {
                    setText(label)
                    minHeight = 52.dp
                    setOnClickListener {
                        dialog?.dismiss()
                        actions[index]()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = 8.dp })
            }
        }
        val shownDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog = shownDialog
        shownDialog.show()
    }

    private fun showAbout() {
        showActions(
            R.string.settings_about,
            intArrayOf(R.string.settings_about_repository, R.string.settings_about_telegram),
            arrayOf(
                { openUrl(REPOSITORY_URL) },
                { openUrl(TELEGRAM_URL) },
            ),
            getString(R.string.settings_about_message, BuildConfig.VERSION_NAME, "@Linkloun"),
        )
    }

    private fun showRoutingRuleEditor(existing: RoutingRule?) {
        val actions = RoutingRule.Action.entries
        val types = RoutingRule.MatchType.entries
        var chosenAction = existing?.action ?: actions.first()
        var chosenType = existing?.matchType ?: types.first()

        val actionButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = routingActionLabel(chosenAction)
            setOnClickListener {
                val labels = actions.map(::routingActionLabel).toTypedArray()
                val initial = actions.indexOf(chosenAction)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_rule_action)
                    .setSingleChoiceItems(labels, initial) { d, which ->
                        chosenAction = actions[which]
                        text = labels[which]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        val typeButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = routingTypeLabel(chosenType)
            setOnClickListener {
                val labels = types.map(::routingTypeLabel).toTypedArray()
                val initial = types.indexOf(chosenType)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_rule_type)
                    .setSingleChoiceItems(labels, initial) { d, which ->
                        chosenType = types[which]
                        text = labels[which]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        val value = TextInputEditText(requireContext()).apply { setText(existing?.value.orEmpty()) }
        val valueLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.settings_rule_value)
            addView(value)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(TextView(requireContext()).apply {
                setText(R.string.settings_routing_rule_hint)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setPadding(0, 0, 0, 8.dp)
            })
            addView(actionButton)
            addView(typeButton)
            addView(valueLayout)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.settings_add_routing_rule else R.string.settings_rule_edit_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_settings, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val draft = runCatching {
                    RoutingRule.create(
                        id = existing?.id ?: 0,
                        matchType = chosenType,
                        value = value.text?.toString().orEmpty(),
                        action = chosenAction,
                        enabled = existing?.enabled ?: true,
                        sortOrder = existing?.sortOrder ?: 0,
                    )
                }.getOrElse {
                    valueLayout.error = it.message ?: getString(R.string.invalid_profile)
                    return@setOnClickListener
                }
                valueLayout.error = null
                saveRoutingRule(draft, dialog)
            }
        }
        dialog.show()
    }

    private fun saveRoutingRule(draft: RoutingRule, dialog: AlertDialog) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val rule = if (draft.id == 0L) {
                        draft.copy(sortOrder = (profiles.getAllRoutingRules().maxOfOrNull(RoutingRule::sortOrder) ?: -1) + 1)
                    } else {
                        draft
                    }
                    profiles.saveRoutingRule(rule)
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess {
                dialog.dismiss()
                binding.status.setText(R.string.settings_saved)
                load()
                showRoutingRules()
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun routingActionLabel(action: RoutingRule.Action): String = getString(when (action) {
        RoutingRule.Action.VPN -> R.string.settings_rule_action_vpn
        RoutingRule.Action.DIRECT -> R.string.settings_rule_action_direct
        RoutingRule.Action.BLOCK -> R.string.settings_rule_action_block
    })

    private fun routingTypeLabel(type: RoutingRule.MatchType): String = getString(when (type) {
        RoutingRule.MatchType.DOMAIN -> R.string.settings_rule_type_domain
        RoutingRule.MatchType.DOMAIN_SUFFIX -> R.string.settings_rule_type_domain_suffix
        RoutingRule.MatchType.IP -> R.string.settings_rule_type_ip
        RoutingRule.MatchType.CIDR -> R.string.settings_rule_type_cidr
        RoutingRule.MatchType.PACKAGE -> R.string.settings_rule_type_package
    })

    private fun openSystemSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { _binding?.status?.setText(R.string.settings_system_screen_unavailable) }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { _binding?.status?.text = it.message }
    }

    private fun updateGeoAssets() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GeoAssetManager(appContext).updateFromDefaultSources()
            }
            result.onSuccess { _binding?.status?.setText(R.string.settings_geo_assets_updated) }
                .onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun checkUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    (requireActivity() as MainActivity).checkForUpdateThroughTunnelOrDirect()
                }
            }
            val binding = _binding ?: return@launch
            result.onSuccess { update ->
                binding.status.text = when {
                    update.selectedAsset == null -> getString(R.string.settings_update_no_asset)
                    update.newerThanCurrent -> getString(
                        R.string.settings_update_available,
                        update.release.tagName,
                        update.selectedAsset.name,
                    )
                    else -> getString(R.string.settings_update_current, BuildConfig.VERSION_NAME)
                }
                if (update.newerThanCurrent && update.selectedAsset != null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_update_available_title)
                        .setMessage(binding.status.text)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.settings_update_download_install) { _, _ ->
                            installUpdate(update.release, update.selectedAsset)
                        }
                        .show()
                }
            }.onFailure { binding.status.text = it.message }
        }
    }

    private fun chooseReleaseAndApk() {
        _binding?.status?.setText(R.string.settings_update_loading_latest)
        viewLifecycleOwner.lifecycleScope.launch {
            val release = try {
                withContext(Dispatchers.IO) {
                    (requireActivity() as MainActivity).checkForUpdateThroughTunnelOrDirect().release
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _binding?.status?.text = error.message
                return@launch
            }
            if (_binding == null || !isAdded) return@launch
            showApkPicker(release)
        }
    }

    private fun showApkPicker(release: GitHubRelease) {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val recommended = UpdateAssetSelector.selectApk(release, supportedAbis)
        val assets = UpdateAssetSelector.apkAssets(release)
            .sortedBy { if (it == recommended) 0 else 1 }
        if (assets.isEmpty()) {
            _binding?.status?.setText(R.string.settings_update_no_asset)
            return
        }
        var selectedAsset = recommended ?: assets.first()
        val summary = TextView(requireContext()).apply {
            text = getString(
                R.string.settings_update_apk_option,
                selectedAsset.name,
                Formatter.formatShortFileSize(requireContext(), selectedAsset.size),
                if (selectedAsset == recommended) getString(R.string.settings_update_recommended_suffix) else "",
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val buttonAssets = mutableMapOf<Int, GitHubRelease.ReleaseAsset>()
        val tabs = MaterialButtonToggleGroup(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            isSingleSelection = true
            isSelectionRequired = true
            assets.forEach { asset ->
                val button = MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    id = View.generateViewId()
                    text = "${apkTabLabel(asset)}\n${asset.name}"
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    maxLines = 3
                    applySegmentedStyle()
                }
                buttonAssets[button.id] = asset
                addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    selectedAsset = buttonAssets.getValue(checkedId)
                    summary.text = getString(
                        R.string.settings_update_apk_option,
                        selectedAsset.name,
                        Formatter.formatShortFileSize(requireContext(), selectedAsset.size),
                        if (selectedAsset == recommended) {
                            getString(R.string.settings_update_recommended_suffix)
                        } else {
                            ""
                        },
                    )
                }
            }
        }
        tabs.check(buttonAssets.entries.first { it.value == selectedAsset }.key)
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(tabs)
            addView(summary, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12.dp })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_choose_apk, release.tagName))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_update_download_install) { _, _ ->
                confirmSelectedUpdate(release, selectedAsset, supportedAbis)
            }
            .show()
    }

    private fun apkTabLabel(asset: GitHubRelease.ReleaseAsset): String = when {
        asset.name.contains("-arm64-v8a.apk", ignoreCase = true) -> "ARM64"
        asset.name.contains("-armeabi-v7a.apk", ignoreCase = true) -> "ARMv7"
        asset.name.contains("-universal.apk", ignoreCase = true) -> getString(R.string.settings_update_apk_universal)
        else -> asset.name.removeSuffix(".apk")
    }

    private fun confirmSelectedUpdate(
        release: GitHubRelease,
        asset: GitHubRelease.ReleaseAsset,
        supportedAbis: List<String>,
    ) {
        when {
            VersionComparator.isNewer(BuildConfig.VERSION_NAME, release.tagName) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_update_downgrade_title)
                    .setMessage(
                        getString(
                            R.string.settings_update_downgrade_message,
                            release.tagName,
                            BuildConfig.VERSION_NAME,
                        ),
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            !UpdateAssetSelector.isCompatibleApk(asset, supportedAbis) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_update_incompatible_title)
                    .setMessage(getString(R.string.settings_update_incompatible_message, asset.name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.settings_update_download_install) { _, _ ->
                        installUpdate(release, asset)
                    }
                    .show()
            }
            else -> installUpdate(release, asset)
        }
    }

    private fun installUpdate(release: GitHubRelease, asset: GitHubRelease.ReleaseAsset) {
        _binding?.status?.setText(R.string.settings_update_downloading)
        (requireActivity() as MainActivity).installUpdate(release, asset)
    }

    private fun showDiagnosticsMenu() {
        val versions = GomobileCore.coreVersions()
        val host = activity as? MainActivity
        val state = host?.currentVpnState() ?: VpnState.DISCONNECTED
        val activeProfileType = when (host?.activeProfileReference()?.substringBefore(':')) {
            "local" -> getString(R.string.settings_diagnostics_profile_local)
            "subscription" -> getString(R.string.settings_diagnostics_profile_subscription)
            else -> getString(R.string.settings_diagnostics_profile_none)
        }
        val lastError = host?.currentVpnError()
            ?.let(DiagnosticsRedactor::redact)
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(MAX_SAFE_ERROR_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.settings_diagnostics_no_error)
        val summary = getString(
            R.string.settings_diagnostics_summary,
            BuildConfig.VERSION_NAME,
            versions.xray,
            versions.olcrtc,
            vpnStateLabel(state),
            activeProfileType,
            currentNetworkType(),
            lastError,
        )
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 8.dp)
            addView(TextView(requireContext()).apply {
                text = summary
                setTextIsSelectable(true)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.settings_diagnostics_description)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                setPadding(0, 10.dp, 0, 14.dp)
            })
            listOf(
                R.string.settings_run_network_check to ::runNetworkCheck,
                R.string.settings_view_diagnostics to ::showDiagnosticsLog,
                R.string.settings_copy_diagnostics to ::copyDiagnostics,
                R.string.settings_export_diagnostics to ::exportDiagnostics,
                R.string.settings_report_issue to ::reportIssue,
            ).forEach { (label, action) ->
                addView(com.google.android.material.button.MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    setText(label)
                    cornerRadius = 10.dp
                    minHeight = 44.dp
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = 8.dp
                    }
                    setOnClickListener { action() }
                })
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_diagnostics_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Issue #53, block 1: direct front-door check answering "what does this
     * operator let through". Runs off the UI thread; blocks 2-3 (profile
     * probes through real tunnels) stay on the Connections tab where the
     * existing per-card results already live.
     */
    private fun runNetworkCheck() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_network_check_title)
            .setMessage(getString(R.string.settings_network_check_running))
            .setNegativeButton(R.string.cancel, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { NetworkDiagnostics.checkFrontDoors() }
            val recommendation = NetworkDiagnostics.formatRecommendation(results)
            val text = buildString {
                results.forEach { result ->
                    append(result.label)
                        .append(" = ")
                        .append(
                            if (result.reachable) {
                                getString(
                                    R.string.settings_network_check_reachable,
                                    result.latencyMillis ?: 0,
                                )
                            } else {
                                getString(
                                    R.string.settings_network_check_blocked,
                                    result.detail,
                                )
                            },
                        )
                        .append('\n')
                }
                append('\n')
                append(getString(R.string.settings_network_check_recommendation, recommendation))
                append('\n')
                append(getString(R.string.settings_network_check_hint))
            }
            if (dialog.isShowing) {
                dialog.setMessage(text)
            }
        }
    }

    private fun showDiagnosticsLog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { diagnostics.readRedacted() }
                .ifBlank { getString(R.string.settings_no_diagnostics) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_diagnostics_title)
                .setMessage(text.takeLast(MAX_DIALOG_LOG_CHARS))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            }
    }

    private fun currentNetworkType(): String {
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.allNetworks.asSequence()
            .mapNotNull(connectivity::getNetworkCapabilities)
            .firstOrNull {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            ?: return getString(R.string.settings_diagnostics_network_unknown)
        return getString(when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.settings_diagnostics_network_wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.settings_diagnostics_network_mobile
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.settings_diagnostics_network_ethernet
            else -> R.string.settings_diagnostics_network_other
        })
    }

    private fun vpnStateLabel(state: VpnState): String = getString(when (state) {
        VpnState.NO_PROFILE, VpnState.DISCONNECTED -> R.string.vpn_notification_disconnected
        VpnState.PREPARING, VpnState.CONNECTING -> R.string.vpn_notification_connecting
        VpnState.CONNECTED -> R.string.vpn_notification_connected
        VpnState.RECONNECTING -> R.string.settings_diagnostics_reconnecting
        VpnState.STOPPING -> R.string.vpn_notification_stopping
        VpnState.ERROR -> R.string.vpn_notification_error
    })

    private fun copyDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { diagnostics.readRedacted() }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.settings_diagnostics_title), text))
            _binding?.status?.setText(R.string.settings_diagnostics_copied)
        }
    }

    private fun exportDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(requireContext().cacheDir, "diagnostics").apply { mkdirs() }
                    File(directory, "olcrtc-diagnostics.txt").apply {
                        writeText(diagnostics.readRedacted())
                    }
                }
            }
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.files", file)
                startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.onFailure { _binding?.status?.text = it.message }
        }
    }

    private fun reportIssue() {
        val versions = GomobileCore.coreVersions()
        val url = IssueReportBuilder.buildUrl(
            IssueReportInfo(
                appVersion = BuildConfig.VERSION_NAME,
                protocol = null,
                xrayVersion = versions.xray,
                olcrtcCoreVersion = versions.olcrtc,
            ),
        )
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { _binding?.status?.text = it.message }
    }

    private fun MaterialButton.applySegmentedStyle() {
        backgroundTintList = AppCompatResources.getColorStateList(requireContext(), R.color.segmented_button_background)
        strokeColor = AppCompatResources.getColorStateList(requireContext(), R.color.segmented_button_stroke)
        setTextColor(AppCompatResources.getColorStateList(requireContext(), R.color.segmented_button_text))
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private data class LoadedSettings(
        val routingPolicy: RoutingPolicy,
        val dnsServer: String?,
        val perAppPolicy: PerAppPolicy,
        val routingRules: Int,
    )

    private companion object {
        const val MAX_DIALOG_LOG_CHARS = 12_000
        const val MAX_SAFE_ERROR_CHARS = 240
        const val REPOSITORY_URL = "https://github.com/Oleglog/OlConnect"
        const val TELEGRAM_URL = "https://t.me/Linkloun"
    }
}
