package io.github.oleglog.olcrtc.client.connection

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile

internal object ProfileEditorDialog {
    fun show(
        fragment: Fragment,
        profile: ProfileConfig,
        isActive: Boolean = false,
        onSave: (ProfileConfig, AlertDialog) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val context = fragment.requireContext()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(fragment), 8.dp(fragment), 24.dp(fragment), 0)
        }
        if (isActive) {
            TextView(context).apply {
                setText(R.string.profile_active_edit_warning)
                setPadding(0, 0, 0, 12.dp(fragment))
            }.also(form::addView)
        }
        val error = TextView(context).apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorError,
            ))
            visibility = View.GONE
        }.also(form::addView)
        val build = when (profile) {
            is ProfileConfig.Olcrtc -> olcrtcForm(fragment, form, profile.value)
            is ProfileConfig.OpenFlux -> openfluxForm(fragment, form, profile.value)
            is ProfileConfig.Standard -> standardForm(fragment, form, profile.value)
        }
        val initialDraft = runCatching(build).getOrNull() ?: profile
        val title = when (profile) {
            is ProfileConfig.Olcrtc -> profile.value.name
            is ProfileConfig.OpenFlux -> profile.value.name
            is ProfileConfig.Standard -> profile.value.name
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(fragment.getString(R.string.profile_edit_title, title))
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_settings, null)
            .create()
        fun requestClose() {
            if (!profileEditorIsDirty(initialDraft, runCatching(build).getOrNull())) {
                dialog.dismiss()
                return
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profile_discard_title)
                .setMessage(R.string.profile_discard_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.profile_discard) { _, _ -> dialog.dismiss() }
                .show()
        }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setCanceledOnTouchOutside(false)
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { requestClose() }
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            form.inputFields().forEach { input ->
                input.doAfterTextChanged {
                    error.visibility = View.GONE
                    save.isEnabled = runCatching(build).isSuccess
                }
            }
            save.isEnabled = runCatching(build).isSuccess
            save.setOnClickListener {
                runCatching(build).onSuccess { onSave(it, dialog) }.onFailure {
                    error.text = it.message ?: fragment.getString(R.string.invalid_profile)
                    error.visibility = View.VISIBLE
                    onError(it)
                }
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestClose()
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    private fun olcrtcForm(
        fragment: Fragment,
        form: LinearLayout,
        profile: OlcrtcProfile,
    ): () -> ProfileConfig {
        val name = field(fragment, form, R.string.profile_field_name, profile.name)
        val provider = choice(fragment, form, R.string.profile_field_provider, OlcrtcProfile.Provider.entries, profile.provider)
        // Issue #52: only the transports supported by the chosen provider are
        // selectable (single option today, but filtered so saving cannot fail).
        val transport = choice(
            fragment,
            form,
            R.string.profile_field_transport,
            compatibleTransports(provider.selected),
            profile.transport,
        )
        provider.onSelectionChanged {
            val allowed = compatibleTransports(provider.selected)
            transport.retainOrDefault(allowed)
        }
        val roomId = field(fragment, form, R.string.profile_field_room_id, profile.roomId)
        val roomPassword = field(fragment, form, R.string.profile_field_room_password, profile.roomPassword.orEmpty(), secret = true)
        val clientId = field(fragment, form, R.string.profile_field_client_id, profile.clientId)
        val key = field(fragment, form, R.string.profile_field_key, profile.keyHex, secret = true)
        val dns = field(fragment, form, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val advanced = advancedSection(fragment, form)
        val speedPreset = choice(
            fragment,
            advanced,
            R.string.profile_field_speed_preset,
            OlcrtcProfile.Companion.SpeedPreset.entries,
            OlcrtcProfile.Companion.SpeedPreset.matching(profile.vp8Fps, profile.vp8BatchSize)
                ?: OlcrtcProfile.Companion.SpeedPreset.MAX,
        ) { preset ->
            fragment.getString(when (preset) {
                OlcrtcProfile.Companion.SpeedPreset.ECO -> R.string.profile_speed_preset_eco
                OlcrtcProfile.Companion.SpeedPreset.BALANCED -> R.string.profile_speed_preset_balanced
                OlcrtcProfile.Companion.SpeedPreset.MAX -> R.string.profile_speed_preset_max
            })
        }
        val fps = field(fragment, advanced, R.string.profile_field_vp8_fps, profile.vp8Fps.toString(), numeric = true)
        val batch = field(fragment, advanced, R.string.profile_field_vp8_batch, profile.vp8BatchSize.toString(), numeric = true)
        // Tapping the preset applies it to the two read-only numbers below,
        // so unusual stored values (e.g. from QR links) stay visible but save
        // as the chosen preset.
        speedPreset.onSelectionChanged {
            fps.setValue(speedPreset.selected.fps.toString())
            batch.setValue(speedPreset.selected.batchSize.toString())
        }
        fps.makeReadOnly()
        batch.makeReadOnly()
        val keepalive = field(
            fragment,
            advanced,
            R.string.profile_field_keepalive,
            profile.keepaliveIntervalSeconds.toString(),
            numeric = true,
        )
        return {
            ProfileConfig.Olcrtc(
                OlcrtcProfile(
                    name = name.requiredValue(fragment.getString(R.string.profile_value_required)),
                    provider = provider.selected,
                    transport = transport.selected,
                    compatibilityMode = profile.compatibilityMode,
                    roomId = roomId.requiredValue(fragment.getString(R.string.profile_value_required)),
                    roomPassword = roomPassword.optionalValue(),
                    clientId = clientId.requiredValue(fragment.getString(R.string.profile_value_required)),
                    keyHex = key.validatedValue(fragment.getString(R.string.profile_key_invalid)) {
                        it.length == 64 && it.all(Char::isHexDigit)
                    },
                    dnsServer = dns.optionalValue(),
                    vp8Fps = speedPreset.selected.fps,
                    vp8BatchSize = speedPreset.selected.batchSize,
                    keepaliveIntervalSeconds = keepalive.intValue(0..3600, fragment.getString(R.string.profile_value_invalid)),
                ),
            )
        }
    }

    private fun openfluxForm(
        fragment: Fragment,
        form: LinearLayout,
        profile: io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile,
    ): () -> ProfileConfig {
        val name = field(fragment, form, R.string.profile_field_name, profile.name)
        val url = field(fragment, form, R.string.profile_field_address, profile.documentUrl)
        val transport = choice(
            fragment,
            form,
            R.string.profile_field_transport,
            io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Transport.entries,
            profile.transport,
        )
        val codec = choice(
            fragment,
            form,
            R.string.profile_field_codec,
            io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Codec.entries,
            profile.codec,
            labels = {
                when (it) {
                    io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Codec.BATCHED -> "Batched (ZSTD)"
                    io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Codec.LEGACY -> "Legacy (LZ4)"
                }
            },
        )
        val dns = field(fragment, form, R.string.profile_field_dns, profile.dnsServer.orEmpty())

        return {
            ProfileConfig.OpenFlux(
                io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile(
                    name = name.requiredValue(fragment.getString(R.string.profile_value_required)),
                    documentUrl = url.requiredValue(fragment.getString(R.string.profile_value_required)),
                    transport = transport.selected,
                    codec = codec.selected,
                    dnsServer = dns.optionalValue(),
                    encryptionKey = profile.encryptionKey,
                ),
            )
        }
    }

    private fun standardForm(
        fragment: Fragment,
        form: LinearLayout,
        profile: StandardProfile,
    ): () -> ProfileConfig {
        val name = field(fragment, form, R.string.profile_field_name, profile.name)
        val protocol = choice(fragment, form, R.string.profile_field_protocol, StandardProfile.Protocol.entries, profile.protocol)
        val address = field(fragment, form, R.string.profile_field_address, profile.address)
        val port = field(fragment, form, R.string.profile_field_port, profile.port.toString(), numeric = true)
        val uuid = field(fragment, form, R.string.profile_field_uuid, profile.uuid.orEmpty())
        val password = field(fragment, form, R.string.profile_field_password, profile.password.orEmpty(), secret = true)
        val transport = choice(fragment, form, R.string.profile_field_transport, StandardProfile.Transport.entries, profile.transport)
        val security = choice(fragment, form, R.string.profile_field_security, StandardProfile.Security.entries, profile.security)
        val advanced = advancedSection(fragment, form)
        val alterId = field(fragment, advanced, R.string.profile_field_alter_id, profile.alterId.toString(), numeric = true)
        val cipher = field(fragment, advanced, R.string.profile_field_cipher, profile.cipher)
        val flow = field(fragment, advanced, R.string.profile_field_flow, profile.flow.orEmpty())
        val serverName = field(fragment, advanced, R.string.profile_field_server_name, profile.serverName.orEmpty())
        val alpn = field(fragment, advanced, R.string.profile_field_alpn, profile.alpn.joinToString(","))
        val fingerprint = field(fragment, advanced, R.string.profile_field_fingerprint, profile.fingerprint.orEmpty())
        val allowInsecure = CheckBox(fragment.requireContext()).apply {
            setText(R.string.profile_field_allow_insecure)
            isChecked = profile.allowInsecure
        }.also(advanced::addView)
        val realityKey = field(fragment, advanced, R.string.profile_field_reality_key, profile.realityPublicKey.orEmpty())
        val realityShortId = field(fragment, advanced, R.string.profile_field_reality_short_id, profile.realityShortId.orEmpty())
        val realitySpiderX = field(fragment, advanced, R.string.profile_field_reality_spider_x, profile.realitySpiderX.orEmpty())
        val wsHost = field(fragment, advanced, R.string.profile_field_ws_host, profile.webSocketHost.orEmpty())
        val wsPath = field(fragment, advanced, R.string.profile_field_ws_path, profile.webSocketPath.orEmpty())
        val grpcService = field(fragment, advanced, R.string.profile_field_grpc_service, profile.grpcServiceName.orEmpty())
        val xhttpMode = field(fragment, advanced, R.string.profile_field_xhttp_mode, profile.xhttpMode.orEmpty())
        val xhttpHost = field(fragment, advanced, R.string.profile_field_xhttp_host, profile.xhttpHost.orEmpty())
        val xhttpPath = field(fragment, advanced, R.string.profile_field_xhttp_path, profile.xhttpPath.orEmpty())
        val xhttpExtra = field(fragment, advanced, R.string.profile_field_xhttp_extra, profile.xhttpExtraJson.orEmpty())
        val dns = field(fragment, advanced, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val conditional = listOf(
            uuid, password, alterId, cipher, flow, realityKey, realityShortId,
            realitySpiderX, wsHost, wsPath, grpcService, xhttpMode, xhttpHost,
            xhttpPath, xhttpExtra,
        )
        fun updateVisibility() {
            conditional.forEach(FormField::hide)
            when (protocol.selected) {
                StandardProfile.Protocol.VLESS -> uuid.show()
                StandardProfile.Protocol.VMESS -> {
                    advanced.visibility = View.VISIBLE
                    uuid.show()
                    alterId.show()
                    cipher.show()
                }
                StandardProfile.Protocol.TROJAN -> password.show()
                StandardProfile.Protocol.SHADOWSOCKS -> {
                    advanced.visibility = View.VISIBLE
                    password.show()
                    cipher.show()
                }
            }
            when (transport.selected) {
                StandardProfile.Transport.TCP -> Unit
                StandardProfile.Transport.WS -> {
                    wsHost.show()
                    wsPath.show()
                }
                StandardProfile.Transport.GRPC -> grpcService.show()
                StandardProfile.Transport.XHTTP -> {
                    xhttpMode.show()
                    xhttpHost.show()
                    xhttpPath.show()
                    xhttpExtra.show()
                }
            }
            if (security.selected == StandardProfile.Security.REALITY) {
                advanced.visibility = View.VISIBLE
                realityKey.show()
                realityShortId.show()
                realitySpiderX.show()
                if (
                    protocol.selected == StandardProfile.Protocol.VLESS &&
                    transport.selected == StandardProfile.Transport.TCP
                ) flow.show()
            }
            if (transport.selected != StandardProfile.Transport.TCP) advanced.visibility = View.VISIBLE
        }
        protocol.onSelectionChanged(::updateVisibility)
        transport.onSelectionChanged(::updateVisibility)
        security.onSelectionChanged(::updateVisibility)
        updateVisibility()
        return {
            val selectedProtocol = protocol.selected
            val selectedTransport = transport.selected
            val selectedSecurity = security.selected
            ProfileConfig.Standard(
                StandardProfile(
                    name = name.requiredValue(fragment.getString(R.string.profile_value_required)),
                    protocol = selectedProtocol,
                    address = address.requiredValue(fragment.getString(R.string.profile_value_required)),
                    port = port.intValue(1..65535, fragment.getString(R.string.profile_value_invalid)),
                    uuid = if (selectedProtocol in setOf(
                            StandardProfile.Protocol.VLESS,
                            StandardProfile.Protocol.VMESS,
                        )) {
                        uuid.validatedValue(fragment.getString(R.string.profile_uuid_invalid)) {
                            runCatching { java.util.UUID.fromString(it) }.isSuccess
                        }
                    } else null,
                    password = if (selectedProtocol in setOf(
                            StandardProfile.Protocol.TROJAN,
                            StandardProfile.Protocol.SHADOWSOCKS,
                        )) {
                        password.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    alterId = if (selectedProtocol == StandardProfile.Protocol.VMESS) {
                        alterId.intValue(0..Int.MAX_VALUE, fragment.getString(R.string.profile_value_invalid))
                    } else 0,
                    cipher = if (selectedProtocol in setOf(
                            StandardProfile.Protocol.VMESS,
                            StandardProfile.Protocol.SHADOWSOCKS,
                        )) {
                        cipher.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else "auto",
                    transport = selectedTransport,
                    security = selectedSecurity,
                    flow = if (
                        selectedProtocol == StandardProfile.Protocol.VLESS &&
                        selectedTransport == StandardProfile.Transport.TCP &&
                        selectedSecurity == StandardProfile.Security.REALITY
                    ) flow.optionalValue() else null,
                    serverName = serverName.optionalValue(),
                    alpn = alpn.value().split(',').map(String::trim).filter(String::isNotEmpty),
                    fingerprint = fingerprint.optionalValue(),
                    allowInsecure = allowInsecure.isChecked,
                    realityPublicKey = if (selectedSecurity == StandardProfile.Security.REALITY) {
                        realityKey.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    realityShortId = if (selectedSecurity == StandardProfile.Security.REALITY) {
                        realityShortId.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    realitySpiderX = if (selectedSecurity == StandardProfile.Security.REALITY) realitySpiderX.optionalValue() else null,
                    webSocketHost = if (selectedTransport == StandardProfile.Transport.WS) wsHost.optionalValue() else null,
                    webSocketPath = if (selectedTransport == StandardProfile.Transport.WS) {
                        wsPath.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    grpcServiceName = if (selectedTransport == StandardProfile.Transport.GRPC) {
                        grpcService.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    xhttpMode = if (selectedTransport == StandardProfile.Transport.XHTTP) {
                        xhttpMode.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    xhttpHost = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpHost.optionalValue() else null,
                    xhttpPath = if (selectedTransport == StandardProfile.Transport.XHTTP) {
                        xhttpPath.requiredValue(fragment.getString(R.string.profile_value_required))
                    } else null,
                    xhttpExtraJson = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpExtra.optionalValue() else null,
                    dnsServer = dns.optionalValue(),
                ),
            )
        }
    }

    private fun field(
        fragment: Fragment,
        form: LinearLayout,
        label: Int,
        value: String,
        numeric: Boolean = false,
        secret: Boolean = false,
    ): FormField {
        val input = TextInputEditText(fragment.requireContext()).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        val layout = TextInputLayout(fragment.requireContext()).apply {
            hint = fragment.getString(label)
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(input)
        }
        form.addView(layout)
        return FormField(layout, input)
    }

    private fun <T> choice(
        fragment: Fragment,
        form: LinearLayout,
        label: Int,
        values: List<T>,
        selected: T,
        labels: ((T) -> String?)? = null,
    ): ChoiceField<T> {
        val input = MaterialAutoCompleteTextView(fragment.requireContext()).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(ArrayAdapter(fragment.requireContext(), android.R.layout.simple_list_item_1, values.map { labels?.invoke(it) ?: it.toString() }))
            setText(labels?.invoke(selected) ?: selected.toString(), false)
        }
        val layout = TextInputLayout(fragment.requireContext()).apply {
            hint = fragment.getString(label)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            addView(input)
        }
        form.addView(layout)
        return ChoiceField(input, values, selected)
    }

    private fun advancedSection(fragment: Fragment, form: LinearLayout): LinearLayout {
        val content = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        form.addView(com.google.android.material.button.MaterialButton(fragment.requireContext()).apply {
            setText(R.string.profile_advanced)
            setOnClickListener {
                content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        form.addView(content)
        return content
    }

    private class ChoiceField<T>(
        private val input: MaterialAutoCompleteTextView,
        private var values: List<T>,
        selected: T,
        private val labels: ((T) -> String?)? = null,
    ) {
        var selected: T = selected
            private set
        private var onSelectionChanged: () -> Unit = {}

        init {
            input.setOnItemClickListener { _, _, position, _ ->
                this.selected = values[position]
                onSelectionChanged()
            }
        }

        fun onSelectionChanged(action: () -> Unit) {
            onSelectionChanged = action
        }

        /** Replaces the dropdown options; keeps the selection when still valid. */
        fun retainOrDefault(allowed: List<T>) {
            require(allowed.isNotEmpty()) { "No compatible transports" }
            values = allowed
            input.setAdapter(
                ArrayAdapter(input.context, android.R.layout.simple_list_item_1, allowed.map { labels?.invoke(it) ?: it.toString() }),
            )
            if (selected !in allowed) {
                selected = allowed.first()
                input.setText(labels?.invoke(selected) ?: selected.toString(), false)
            }
        }
    }

/** Issue #52: selectable transports per provider. Mirrors carrier_compat.go. */
internal fun compatibleTransports(provider: OlcrtcProfile.Provider): List<OlcrtcProfile.Transport> =
    OlcrtcProfile.Transport.entries.filter(provider::supports)

    private data class FormField(
        val layout: TextInputLayout,
        val input: TextInputEditText,
    ) {
        fun value(): String = input.text?.toString()?.trim().orEmpty()
        fun setValue(value: String) {
            layout.error = null
            input.setText(value)
        }
        fun optionalValue(): String? = value().takeIf(String::isNotEmpty)
        fun requiredValue(message: String): String {
            val current = value()
            layout.error = if (current.isEmpty()) message else null
            require(current.isNotEmpty()) { message }
            return current
        }
        fun validatedValue(message: String, predicate: (String) -> Boolean): String {
            val current = value()
            val valid = predicate(current)
            layout.error = if (valid) null else message
            require(valid) { message }
            return current
        }
        fun intValue(range: IntRange? = null, message: String = "${layout.hint} is invalid"): Int {
            val parsed = value().toIntOrNull()
            val valid = parsed != null && (range == null || parsed in range)
            layout.error = message.takeUnless { valid }
            require(valid) { message }
            return requireNotNull(parsed)
        }
        fun show() {
            layout.visibility = View.VISIBLE
        }
        fun hide() {
            layout.error = null
            layout.visibility = View.GONE
        }
        fun makeReadOnly() {
            input.isEnabled = false
            input.isFocusable = false
        }
    }

    private fun Int.dp(fragment: Fragment): Int =
        (this * fragment.resources.displayMetrics.density).toInt()

    private fun View.inputFields(): Sequence<EditText> = sequence {
        if (this@inputFields is EditText) yield(this@inputFields)
        if (this@inputFields is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).inputFields())
        }
    }
}

internal fun profileEditorIsDirty(original: ProfileConfig, current: ProfileConfig?): Boolean =
    current == null || current != original

internal fun preserveSecretValue(entered: String?, original: String?): String? = entered ?: original

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
