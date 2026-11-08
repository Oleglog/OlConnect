package io.github.oleglog.olcrtc.client.profiles

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SubscriptionSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentProfilesBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.DecodedImportPayload
import io.github.oleglog.olcrtc.client.importer.ImportPayload
import io.github.oleglog.olcrtc.client.importer.QrScannerActivity
import io.github.oleglog.olcrtc.client.importer.SubscriptionBundle
import io.github.oleglog.olcrtc.client.importer.SubscriptionDeepLink
import io.github.oleglog.olcrtc.client.importer.SubscriptionDeepLinkParser
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class ProfilesFragment : Fragment() {
    private var _binding: FragmentProfilesBinding? = null
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val bundleImports = BundleImportDispatcher()
    private val qrScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)?.let(::confirmExternalSubscription)
        } else {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
                ?.let { showError(IllegalStateException(it)) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        binding.addSubscription.setOnClickListener {
            qrScanner.launch(Intent(requireContext(), QrScannerActivity::class.java))
        }
        binding.pasteSubscription.setOnClickListener {
            val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
            val text = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(requireContext())
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
            if (text == null) showError(IllegalStateException(getString(R.string.clipboard_empty)))
            else confirmExternalSubscription(text)
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        loadSubscriptions()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setImportListener(R.id.profilesFragment, ::confirmExternalSubscription)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setImportListener(R.id.profilesFragment, null)
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }

    private fun loadSubscriptions() {
        if (storage.isShutdown) return
        binding.subscriptionStatusRow.isVisible = true
        binding.subscriptionProgress.isVisible = true
        binding.subscriptionStatus.text = getString(R.string.subscription_loading)
        storage.execute {
            val result = runCatching { profiles.listSubscriptions() }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess(::showSubscriptions).onFailure {
                    binding.subscriptionProgress.isVisible = false
                    binding.subscriptionStatus.text = it.message ?: getString(R.string.subscription_load_failed)
                    showError(it)
                }
            }
        }
    }

    private fun showSubscriptions(subscriptions: List<SubscriptionSummary>) {
        val binding = _binding ?: return
        binding.subscriptionProgress.isVisible = false
        binding.subscriptionStatusRow.isVisible = false
        binding.empty.visibility = if (subscriptions.isEmpty()) View.VISIBLE else View.GONE
        binding.profileList.removeAllViews()
        subscriptions.forEach { binding.profileList.addView(subscriptionCard(it)) }
    }

    private fun subscriptionCard(subscription: SubscriptionSummary): View = MaterialCardView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.space_3) }
        radius = resources.getDimension(R.dimen.corner_quiet)
        cardElevation = 0f
        strokeWidth = resources.getDimensionPixelSize(R.dimen.card_border)
        strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 8.dp, 12.dp)
            addView(TextView(requireContext()).apply {
                text = subscription.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            addView(TextView(requireContext()).apply {
                text = subscriptionSummary(subscription)
                setPadding(0, 4.dp, 0, 12.dp)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val progress = ProgressBar(requireContext()).apply {
                    visibility = View.GONE
                    isIndeterminate = true
                }
                val update = MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    setText(R.string.subscription_update_now)
                    cornerRadius = 8.dp
                    setOnClickListener { updateSubscription(subscription.id, this, progress) }
                }
                addView(update)
                addView(progress, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 8.dp })
                addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))
                addView(iconButton(R.drawable.ic_edit_20, R.string.edit) {
                    editSubscription(subscription)
                })
                addView(iconButton(R.drawable.ic_delete_20, R.string.delete) {
                    confirmDeleteSubscription(subscription)
                })
            })
        })
    }

    private fun iconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): AppCompatImageButton =
        AppCompatImageButton(requireContext()).apply {
            val backgroundValue = TypedValue()
            requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                backgroundValue,
                true,
            )
            setBackgroundResource(backgroundValue.resourceId)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            contentDescription = getString(descriptionRes)
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp)
            setPadding(13.dp, 13.dp, 13.dp, 13.dp)
            setOnClickListener { action() }
        }

    private fun subscriptionSummary(subscription: SubscriptionSummary): String = buildString {
        append(subscription.profileCount).append(' ').append(getString(R.string.profiles_count_suffix))
        subscription.serverVersion?.let { append(" · ").append(getString(R.string.subscription_server_badge, it)) }
        subscription.lastErrorCode?.let {
            append(" · ").append(getString(R.string.subscription_last_error, it))
        } ?: subscription.lastSuccessAt?.let {
            append(" · ").append(getString(R.string.subscription_last_success, formatTime(it)))
        }
        if (subscription.mirrorAvailable) append(" · ").append(getString(R.string.subscription_mirror_badge))
        if (!subscription.enabled) append(" · ").append(getString(R.string.subscription_disabled_badge))
    }

    private fun saveNewSubscription(raw: String) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                val deepLink = SubscriptionDeepLinkParser.parseOrNull(raw)
                if (deepLink != null) {
                    importSubscriptionDeepLink(deepLink)
                } else when (val payload = ImportPayload.decode(raw)) {
                    is DecodedImportPayload.Bundle -> importSubscriptionBundle(payload.raw)
                    is DecodedImportPayload.Multipart -> importSubscriptionBundle(payload.raw)
                    is DecodedImportPayload.Subscription -> importSubscriptionSource(payload.url, null)
                    is DecodedImportPayload.Profile -> error(getString(R.string.invalid_subscription_link))
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess(::showSubscriptionRefreshResult).onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun confirmExternalSubscription(raw: String) {
        val source = runCatching {
            SubscriptionDeepLinkParser.parseOrNull(raw)?.let { it.url to it.name }
                ?: when (val payload = ImportPayload.decode(raw)) {
                    is DecodedImportPayload.Subscription -> payload.url to null
                    is DecodedImportPayload.Bundle,
                    is DecodedImportPayload.Multipart,
                    is DecodedImportPayload.Profile,
                    -> null
                }
        }
            .getOrElse {
                showError(it)
                return
            }
        if (source == null) {
            saveNewSubscription(raw)
            return
        }
        val host = requireNotNull(java.net.URI(source.first).host)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_link_confirm_title)
            .setMessage(getString(R.string.subscription_link_confirm_message, source.second ?: host, host))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.subscription_link_confirm_action) { _, _ -> saveNewSubscription(raw) }
            .show()
    }

    private fun importSubscriptionSource(url: String, name: String?): SubscriptionRefresher.Result {
        val validatedUrl = SubscriptionDeepLinkParser.validateHttpsUrl(url)
        val host = requireNotNull(java.net.URI(validatedUrl).host)
        val id = profiles.insertSubscriptionSource(
            name = name ?: host,
            url = validatedUrl,
            kind = "GENERIC",
        )
        return refreshSubscription(id)
    }

    private fun importSubscriptionDeepLink(link: SubscriptionDeepLink): SubscriptionRefresher.Result {
        val host = requireNotNull(java.net.URI(link.url).host)
        val mirrors = link.mirrorUrl?.let { mirrorUrl ->
            listOf(
                SubscriptionBundle.Mirror(
                    type = link.mirrorType,
                    url = mirrorUrl,
                    encrypted = true,
                    algorithm = "AES-256-GCM",
                ),
            )
        }.orEmpty()
        val id = profiles.insertSubscription(
            SubscriptionBundle(
                name = link.name ?: host,
                slug = null,
                url = link.url,
                serverVersion = null,
                mirrors = mirrors,
                mirrorKey = link.mirrorKey,
                deduplication = true,
                updateWhenConnectedOnly = false,
                profiles = emptyList(),
                rejectedProfiles = emptyList(),
            ),
        )
        return refreshSubscription(id)
    }

    private fun importSubscriptionBundle(raw: String): SubscriptionRefresher.Result =
        when (val bundle = bundleImports.accept(raw)) {
            is BundleImportResult.Complete -> {
                val id = profiles.insertSubscription(bundle.bundle)
                refreshSubscription(id)
            }
            is BundleImportResult.Pending -> error("${bundle.received}/${bundle.total}")
        }

    private fun updateSubscription(subscriptionId: Long, button: MaterialButton, progress: ProgressBar) {
        if (storage.isShutdown) return
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        storage.execute {
            val result = runCatching {
                refreshSubscription(subscriptionId).also {
                    check(it.success) {
                        getString(R.string.subscription_update_failed)
                    }
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                button.isEnabled = true
                progress.visibility = View.GONE
                result.onSuccess(::showSubscriptionRefreshResult).onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun showSubscriptionRefreshResult(result: SubscriptionRefresher.Result) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (result.success) R.string.subscription_updated else R.string.subscription_update_failed)
            .setMessage(
                if (result.success) {
                    buildString {
                        append(getString(R.string.subscription_update_summary, result.added, result.removed, result.total))
                        result.source?.let {
                            append('\n').append(getString(R.string.subscription_update_source, subscriptionSourceLabel(it)))
                        }
                    }
                } else {
                    getString(R.string.subscription_update_preserved)
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun subscriptionSourceLabel(source: SubscriptionRefresher.Source): String = getString(
        if (source == SubscriptionRefresher.Source.PRIMARY) {
            R.string.subscription_source_primary
        } else {
            R.string.subscription_source_mirror
        },
    )

    private fun refreshSubscription(subscriptionId: Long): SubscriptionRefresher.Result =
        (activity as? MainActivity)?.refreshSubscription(subscriptionId)
            ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)

    private fun editSubscription(subscription: SubscriptionSummary) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.getSubscriptionSource(subscription.id)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { source ->
                    val form = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24.dp, 8.dp, 24.dp, 0)
                    }
                    val name = textField(form, R.string.subscription_name, subscription.name)
                    val url = textField(form, R.string.subscription_url, source.url)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.subscription_edit)
                        .setView(form)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            saveSubscriptionEdit(
                                dialog,
                                subscription.id,
                                name.text?.toString().orEmpty(),
                                url.text?.toString().orEmpty(),
                            )
                        }
                    }
                    dialog.show()
                }.onFailure(::showError)
            }
        }
    }

    private fun textField(container: LinearLayout, hintRes: Int, value: String): TextInputEditText {
        val input = TextInputEditText(requireContext()).apply { setText(value) }
        container.addView(TextInputLayout(requireContext()).apply {
            hint = getString(hintRes)
            addView(input)
        })
        return input
    }

    private fun saveSubscriptionEdit(
        dialog: AlertDialog,
        subscriptionId: Long,
        name: String,
        url: String,
    ) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { profiles.updateSubscriptionSource(subscriptionId, name, url) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadSubscriptions()
                }.onFailure(::showError)
            }
        }
    }

    private fun confirmDeleteSubscription(subscription: SubscriptionSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(getString(R.string.subscription_delete_message, subscription.name))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.subscription_keep_profiles) { _, _ -> deleteSubscription(subscription.id, true) }
            .setPositiveButton(R.string.delete) { _, _ -> deleteSubscription(subscription.id, false) }
            .show()
    }

    private fun deleteSubscription(subscriptionId: Long, retainProfiles: Boolean) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { profiles.deleteSubscription(subscriptionId, retainProfiles) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun showError(error: Throwable) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.invalid_profile)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
    ).format(Date(value))

    private fun resolveColor(attribute: Int): Int {
        val values = requireContext().obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, 0).also { values.recycle() }
    }

    private val binding get() = requireNotNull(_binding)
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
