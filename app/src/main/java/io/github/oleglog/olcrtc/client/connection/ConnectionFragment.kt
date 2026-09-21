package io.github.oleglog.olcrtc.client.connection

import android.app.Activity
import android.content.res.ColorStateList
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.databinding.FragmentConnectionBinding
import io.github.oleglog.olcrtc.client.databinding.ItemConnectionProfileBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.DecodedImportPayload
import io.github.oleglog.olcrtc.client.importer.ImportPayload
import io.github.oleglog.olcrtc.client.importer.QrScannerActivity
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.orderProfiles
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.statistics.ConnectionSessionRepository
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import io.github.oleglog.olcrtc.client.vpn.ConnectionStage
import io.github.oleglog.olcrtc.client.vpn.ProfileLatencyProbe
import io.github.oleglog.olcrtc.client.vpn.ProfileProbeTarget
import io.github.oleglog.olcrtc.client.vpn.VpnState
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val settings by lazy { RoutingSettings.open(requireContext().applicationContext) }
    private val statistics by lazy { ConnectionSessionRepository.open(requireContext().applicationContext) }
    private val connectivity by lazy { requireContext().getSystemService(ConnectivityManager::class.java) }
    private val storage = Executors.newSingleThreadExecutor()
    private val latency = Executors.newSingleThreadExecutor()
    private val profileProbeWorkers = Executors.newFixedThreadPool(12)
    private val ticker = Handler(Looper.getMainLooper())
    private val bundleImports = BundleImportDispatcher()
    private var currentState = VpnState.NO_PROFILE
    private var selectedProfileId: Long? = null
    private var selectedSubscriptionProfileId: String? = null
    private var connectedProfileId: Long? = null
    private var connectedSubscriptionProfileId: String? = null
    private var currentStage = ConnectionStage.IDLE
    private var currentReconnectAttempt = 0
    private var vpnStateRevision = 0L
    private var latencyRequestId = 0L
    private var latencyInProgress = false
    private var lastLatencyMillis: Long? = null
    private var lastLatencyReference: String? = null
    private var connectionItems = emptyList<ConnectionListItem>()
    private val connectionAdapter = ConnectionAdapter()
    private val profileProbeResults = mutableMapOf<String, Long?>()
    private val checkingProfiles = mutableSetOf<String>()
    private var profileProbeRequestId = 0L
    private var profileProbeInProgress = false
    private var profileProbeTask: Future<*>? = null
    @Volatile private var activeProfileProbe: ProfileLatencyProbe? = null
    @Volatile private var defaultNetwork: Network? = null
    private var profilesLoaded = false
    private var currentSessionStartedAt: Long? = null
    private var todayBytesUp = 0L
    private var todayBytesDown = 0L
    @Volatile private var dashboardLoadInFlight = false
    private val dashboardTicker = object : Runnable {
        override fun run() {
            if (currentState == VpnState.CONNECTED && currentSessionStartedAt == null) {
                loadDashboardSummary()
            }
            updateDashboard()
            ticker.postDelayed(this, DASHBOARD_REFRESH_MILLIS)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleDefaultNetwork(network)

        override fun onLost(network: Network) {
            if (defaultNetwork == network) handleDefaultNetwork(null)
        }
    }

    private val qrScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
                ?.let { validatePreview(it, R.string.source_camera) }
        } else {
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
                ?.let { if (_binding != null) showStatus(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.profileList.adapter = connectionAdapter
        binding.connect.isEnabled = false
        binding.testSelected.isEnabled = false
        renderContentState(ConnectionContentState.LOADING)
        binding.connect.setOnClickListener {
            binding.connect.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when {
                currentState in BUSY_STATES -> activityHost.stopVpn()
                shouldSwitchProfile(currentState, hasPendingProfileSwitch()) -> connectSelected()
                currentState == VpnState.CONNECTED -> activityHost.stopVpn()
                else -> connectSelected()
            }
        }
        binding.addProfile.setOnClickListener { showAddConnectionMenu() }
        binding.contentStateAction.setOnClickListener { showAddConnectionMenu() }
        binding.testSelected.setOnClickListener { testSelectedProfile() }
        binding.testAllProfiles.setOnClickListener { testAllProfiles() }
        // Issue #47: Auto failover toggle. Persisted in RoutingSettings,
        // default off; the service reads it on each failure.
        binding.autoFailover.isChecked = settings.getAutoFailover()
        binding.autoFailover.setOnCheckedChangeListener { _, checked ->
            binding.autoFailover.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            storage.execute {
                val saved = runCatching { settings.setAutoFailoverBlocking(checked) }
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    saved.onFailure { showStatus(it.message) }
                }
            }
        }
        // UDP relay over the olcRTC carrier (server-v1.9.76+). Default off;
        // takes effect on the next connect, same as auto failover.
        binding.udpRelay.isChecked = settings.getUdpRelay()
        binding.udpRelay.setOnCheckedChangeListener { _, checked ->
            binding.udpRelay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            storage.execute {
                val saved = runCatching { settings.setUdpRelayBlocking(checked) }
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    saved.onFailure { showStatus(it.message) }
                }
            }
        }
        updateConnectPulse()
    }

    override fun onStart() {
        super.onStart()
        handleDefaultNetwork(connectivity.activeNetwork)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        loadProfiles()
        loadDashboardSummary()
    }

    override fun onStop() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        activityHost.setVpnStateListener(::showVpnState)
        activityHost.setImportListener(R.id.connectionFragment) { validatePreview(it, R.string.source_deep_link) }
        loadProfiles()
        ticker.removeCallbacks(dashboardTicker)
        ticker.post(dashboardTicker)
    }

    override fun onPause() {
        activityHost.setImportListener(R.id.connectionFragment, null)
        activityHost.setVpnStateListener(null)
        ticker.removeCallbacks(dashboardTicker)
        super.onPause()
    }

    override fun onDestroyView() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        ticker.removeCallbacks(dashboardTicker)
        binding.profileList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cancelProfileProbe(clearResults = true)
        storage.shutdownNow()
        latency.shutdownNow()
        profileProbeWorkers.shutdownNow()
        super.onDestroy()
    }


    private fun loadProfiles() {
        if (storage.isShutdown) return
        if (!profilesLoaded) renderContentState(ConnectionContentState.LOADING)
        storage.execute {
            val result = runCatching {
                val localFavorites = settings.getFavoriteLocalProfileIds()
                val local = profiles.listLocal().map { profile ->
                    ConnectionListItem(
                        reference = "local:${profile.id}",
                        name = profile.name,
                        type = connectionTypeLabel(profile.type, profile.endpoint),
                        favorite = profile.id in localFavorites,
                        localId = profile.id,
                        config = profiles.get(profile.id),
                    )
                }
                val subscription = profiles.listSubscriptions().flatMap { source ->
                    profiles.listSubscriptionProfiles(source.id).map { profile ->
                        ConnectionListItem(
                            reference = "subscription:${profile.id}",
                            name = profile.name,
                            type = connectionTypeLabel(profile.type, profile.endpoint),
                            favorite = profile.favorite,
                            subscriptionProfileId = profile.id,
                            config = profiles.getSubscriptionProfile(profile.id),
                        )
                    }
                }
                val lastSuccessful = settings.getLastSuccessfulProfileReference()
                ConnectionScreenModel(
                    profiles = orderProfiles(
                        local + subscription,
                        lastSuccessful,
                        ConnectionListItem::reference,
                        ConnectionListItem::favorite,
                    ),
                )
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { model ->
                    val profilesChanged = connectionItems.isNotEmpty() &&
                        connectionItems.associate { it.reference to it.config } !=
                        model.profiles.associate { it.reference to it.config }
                    if (profilesChanged) cancelProfileProbe(clearResults = true)
                    connectionItems = model.profiles
                    if (currentState != VpnState.CONNECTED) {
                        selectedProfileId = selectedProfileId?.takeIf { selected ->
                            model.profiles.any { it.localId == selected }
                        }
                        selectedSubscriptionProfileId = selectedSubscriptionProfileId?.takeIf { selected ->
                            model.profiles.any { it.subscriptionProfileId == selected }
                        }
                        if (selectedProfileId == null && selectedSubscriptionProfileId == null) {
                            selectedProfileId = model.profiles.firstOrNull()?.localId
                            selectedSubscriptionProfileId = model.profiles.firstOrNull()?.subscriptionProfileId
                        }
                    }
                    val hasProfiles = model.profiles.isNotEmpty()
                    connectionAdapter.submitList(model.profiles)
                    profilesLoaded = true
                    updateLatencyAction()
                    renderContentState(
                        connectionContentState(loading = false, hasProfiles = hasProfiles, failed = false),
                    )
                    updateActionAvailability()
                }.onFailure { error ->
                    if (profilesLoaded && connectionAdapter.itemCount > 0) {
                        showStatus(error.message ?: getString(R.string.connection_error_message))
                    } else {
                        renderContentState(
                            connectionContentState(loading = false, hasProfiles = false, failed = true),
                        )
                    }
                }
            }
        }
    }

    private fun selectedReference(): String? = when {
        selectedProfileId != null -> "local:$selectedProfileId"
        selectedSubscriptionProfileId != null -> "subscription:$selectedSubscriptionProfileId"
        else -> null
    }

    private inner class ConnectionAdapter :
        ListAdapter<ConnectionListItem, ConnectionViewHolder>(
            object : DiffUtil.ItemCallback<ConnectionListItem>() {
                override fun areItemsTheSame(old: ConnectionListItem, new: ConnectionListItem): Boolean =
                    old.reference == new.reference

                override fun areContentsTheSame(old: ConnectionListItem, new: ConnectionListItem): Boolean = old == new
            },
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder =
            ConnectionViewHolder(
                ItemConnectionProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private inner class ConnectionViewHolder(
        private val views: ItemConnectionProfileBinding,
    ) : RecyclerView.ViewHolder(views.root) {
        fun bind(item: ConnectionListItem) {
            views.profileName.text = item.name
            views.profileActions.contentDescription = getString(R.string.connection_profile_actions, item.name)
            views.profileActions.setOnClickListener { showProfileActions(it, item) }
            views.root.setOnClickListener {
                views.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                item.localId?.let(::selectProfile)
                    ?: selectSubscriptionProfile(requireNotNull(item.subscriptionProfileId))
            }
            applyCardAppearance(views, item)
        }
    }

    private fun showProfileActions(anchor: View, item: ConnectionListItem) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(
                if (item.favorite) R.string.profile_remove_favorite else R.string.profile_add_favorite,
            ).setOnMenuItemClickListener {
                setFavorite(item, !item.favorite)
                true
            }
            menu.add(R.string.edit).setOnMenuItemClickListener {
                item.localId?.let(::editLocalProfile)
                    ?: editSubscriptionProfile(requireNotNull(item.subscriptionProfileId))
                true
            }
            menu.add(R.string.profile_copy_link).setOnMenuItemClickListener {
                confirmCopyProfileLink(item)
                true
            }
            menu.add(R.string.delete).setOnMenuItemClickListener {
                item.localId?.let { confirmDeleteProfile(it, item.name) }
                    ?: confirmDeleteSubscriptionProfile(requireNotNull(item.subscriptionProfileId), item.name)
                true
            }
            show()
        }
    }

    private fun applyCardAppearance(views: ItemConnectionProfileBinding, item: ConnectionListItem) {
        val card = views.root
        val state = connectionCardState(
            selected = isReferenceSelected(item.reference),
            connected = isReferenceConnected(item.reference),
        )
        card.strokeColor = if (state == ConnectionCardState.INACTIVE) {
            resolveColor(com.google.android.material.R.attr.colorOutline)
        } else {
            resolveColor(androidx.appcompat.R.attr.colorPrimary)
        }
        card.strokeWidth = when (state) {
            ConnectionCardState.CONNECTED -> dimen(R.dimen.card_border_active)
            ConnectionCardState.SELECTED, ConnectionCardState.INACTIVE -> dimen(R.dimen.card_border)
        }
        animateCardElevation(card, when (state) {
            ConnectionCardState.CONNECTED -> dimen(R.dimen.card_elevation_connected).toFloat()
            ConnectionCardState.SELECTED -> dimen(R.dimen.card_elevation_selected).toFloat()
            ConnectionCardState.INACTIVE -> 0f
        })
        card.alpha = 1f
        card.setCardBackgroundColor(
            ColorUtils.blendARGB(
                resolveColor(com.google.android.material.R.attr.colorSurfaceContainer),
                resolveColor(androidx.appcompat.R.attr.colorPrimary),
                when (state) {
                    ConnectionCardState.CONNECTED -> 0.18f
                    ConnectionCardState.SELECTED -> 0.10f
                    ConnectionCardState.INACTIVE -> 0f
                },
            ),
        )
        card.isSelected = state != ConnectionCardState.INACTIVE
        card.isActivated = state == ConnectionCardState.CONNECTED
        updateCardContent(views, item, state)
    }

    private fun animateCardElevation(card: MaterialCardView, targetElevation: Float) {
        if (!animationsEnabled()) {
            card.cardElevation = targetElevation
            return
        }
        val start = card.cardElevation
        if (start == targetElevation) return
        android.animation.ValueAnimator.ofFloat(start, targetElevation).apply {
            duration = 150L
            addUpdateListener { card.cardElevation = it.animatedValue as Float }
        }.start()
    }

    private fun resolveColor(attribute: Int): Int {
        val values = requireContext().obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, 0).also { values.recycle() }
    }

    private fun refreshCardAppearance() {
        connectionAdapter.notifyItemRangeChanged(0, connectionAdapter.itemCount)
    }

    private fun updateCardContent(
        views: ItemConnectionProfileBinding,
        item: ConnectionListItem,
        state: ConnectionCardState,
    ) {
        val card = views.root
        val connectionStatus = when {
            state == ConnectionCardState.CONNECTED && currentState == VpnState.CONNECTED ->
                getString(R.string.connection_card_connected)
            (state == ConnectionCardState.CONNECTED || state == ConnectionCardState.SELECTED) &&
                currentState in CARD_PROGRESS_STATES -> vpnStateText(currentState, currentStage, currentReconnectAttempt)
            state == ConnectionCardState.SELECTED -> getString(R.string.connection_card_selected)
            else -> null
        }
        val latencyMs = profileProbeResults[item.reference]
        val probeStatus = when {
            checkingProfiles.contains(item.reference) -> null
            !profileProbeResults.containsKey(item.reference) -> null
            latencyMs == null -> getString(R.string.profile_test_unavailable)
            else -> getString(R.string.connection_latency_value, latencyMs)
        }
        views.profileDetail.text = listOfNotNull(
            item.type,
            connectionStatus,
        ).joinToString(" · ")
        views.profileLatency.text = probeStatus
        views.profileLatency.isVisible = probeStatus != null
        if (latencyMs != null) {
            val colorRes = when {
                latencyMs < 100 -> R.color.latency_good
                latencyMs < 200 -> R.color.latency_moderate
                else -> R.color.latency_poor
            }
            views.profileLatency.setTextColor(ContextCompat.getColor(card.context, colorRes))
        } else {
            views.profileLatency.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        views.profileProgress.isVisible = checkingProfiles.contains(item.reference) ||
            connectionStatus != null && currentState in CARD_PROGRESS_STATES
        card.contentDescription = listOfNotNull(
            item.name,
            item.type,
            connectionStatus,
            probeStatus,
        ).joinToString(", ")
        ViewCompat.setStateDescription(card, connectionStatus)
    }

    private fun isReferenceSelected(reference: String): Boolean = when {
        reference.startsWith("local:") -> reference.removePrefix("local:").toLongOrNull() == selectedProfileId
        reference.startsWith("subscription:") -> reference.removePrefix("subscription:") == selectedSubscriptionProfileId
        else -> false
    }

    private fun isReferenceConnected(reference: String): Boolean = when {
        reference.startsWith("local:") -> reference.removePrefix("local:").toLongOrNull() == connectedProfileId
        reference.startsWith("subscription:") -> reference.removePrefix("subscription:") == connectedSubscriptionProfileId
        else -> false
    }

    private fun selectSubscriptionProfile(id: String) {
        if (!canSelectProfile(currentState)) return
        selectedProfileId = null
        selectedSubscriptionProfileId = id
        showStatus(null)
        updateConnectButtonText()
        updateActionAvailability()
        refreshCardAppearance()
    }

    private fun selectProfile(id: Long) {
        if (!canSelectProfile(currentState)) return
        selectedSubscriptionProfileId = null
        selectedProfileId = id
        showStatus(null)
        updateConnectButtonText()
        updateActionAvailability()
        refreshCardAppearance()
    }

    private fun setFavorite(item: ConnectionListItem, favorite: Boolean) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                item.localId?.let { settings.setLocalProfileFavorite(it, favorite) }
                    ?: profiles.setSubscriptionProfileFavorite(requireNotNull(item.subscriptionProfileId), favorite)
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { loadProfiles() }
                    .onFailure { showStatus(it.message ?: getString(R.string.connection_error_message)) }
            }
        }
    }

    private fun connectSelected() {
        selectedProfileId?.let { activityHost.requestVpnPermission(it); return }
        selectedSubscriptionProfileId?.let { activityHost.requestSubscriptionVpnPermission(it); return }
        showStatus(getString(R.string.profile_select_required))
    }

    private fun testSelectedProfile() {
        if (currentState != VpnState.CONNECTED) {
            showStatus(getString(R.string.profile_latency_no_active_session))
            return
        }
        val host = activityHost
        val requestId = ++latencyRequestId
        val stateRevision = vpnStateRevision
        latencyInProgress = true
        updateActionAvailability()
        showStatus(getString(R.string.profile_latency_checking))
        latency.execute {
            val result = runCatching { host.testConnectionLatency().coerceAtLeast(1) }
            activity?.runOnUiThread {
                if (_binding == null || requestId != latencyRequestId) return@runOnUiThread
                latencyInProgress = false
                updateActionAvailability()
                if (!shouldShowLatencyResult(stateRevision, vpnStateRevision, currentState == VpnState.CONNECTED)) {
                    return@runOnUiThread
                }
                result.onSuccess {
                    lastLatencyMillis = it
                    lastLatencyReference = selectedReference()
                    updateLatencyAction()
                    showStatus(getString(R.string.profile_latency_result, it))
                }
                    .onFailure { showStatus(getString(latencyErrorText(latencyErrorKind(it)))) }
            }
        }
    }

    private fun testAllProfiles() {
        if (profileProbeInProgress || currentState !in PROFILE_PROBE_IDLE_STATES) return
        val targets = connectionItems.mapNotNull { item ->
            item.config?.let { ProfileProbeTarget(item.reference, it) }
        }
        profileProbeResults.clear()
        checkingProfiles.clear()
        if (targets.isEmpty()) {
            connectionItems.forEach { profileProbeResults[it.reference] = null }
            refreshCardAppearance()
            return
        }

        val requestId = ++profileProbeRequestId
        val probe = ProfileLatencyProbe(
            requireContext().applicationContext,
            settings.getDnsServer(),
            profileProbeWorkers,
            udpRelay = settings.getUdpRelay(),
        )
        activeProfileProbe = probe
        profileProbeInProgress = true
        updateActionAvailability()
        refreshCardAppearance()
        profileProbeTask = latency.submit {
            runCatching {
                probe.testAll(
                    targets = targets,
                    onStarted = { references ->
                        activity?.runOnUiThread {
                            if (requestId != profileProbeRequestId) return@runOnUiThread
                            checkingProfiles.addAll(references)
                            if (_binding != null) refreshCardAppearance()
                        }
                    },
                    onResult = { reference, result ->
                        activity?.runOnUiThread {
                            if (requestId != profileProbeRequestId) return@runOnUiThread
                            checkingProfiles.remove(reference)
                            profileProbeResults[reference] = result.getOrNull()
                            if (_binding != null) refreshCardAppearance()
                        }
                    },
                )
            }
            activity?.runOnUiThread {
                if (requestId != profileProbeRequestId) return@runOnUiThread
                connectionItems.forEach { item ->
                    if (!profileProbeResults.containsKey(item.reference)) {
                        profileProbeResults[item.reference] = null
                    }
                }
                checkingProfiles.clear()
                profileProbeInProgress = false
                profileProbeTask = null
                activeProfileProbe = null
                if (_binding != null) {
                    updateActionAvailability()
                    refreshCardAppearance()
                }
            }
        }
    }

    private fun cancelProfileProbe(clearResults: Boolean) {
        if (!profileProbeInProgress && !clearResults) return
        profileProbeRequestId++
        profileProbeTask?.cancel(true)
        profileProbeTask = null
        val probe = activeProfileProbe
        activeProfileProbe = null
        profileProbeInProgress = false
        checkingProfiles.clear()
        if (clearResults) profileProbeResults.clear()
        if (probe != null && !storage.isShutdown) {
            storage.execute { runCatching { probe.stop() } }
        }
        if (_binding != null) {
            updateActionAvailability()
            refreshCardAppearance()
        }
    }

    private fun handleDefaultNetwork(network: Network?) {
        activity?.runOnUiThread {
            val changed = defaultNetwork != null && defaultNetwork != network
            defaultNetwork = network
            if (changed) cancelProfileProbe(clearResults = true)
            else if (_binding != null) updateActionAvailability()
        }
    }

    private fun showAddConnectionMenu() {
        val dialog = BottomSheetDialog(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.space_6),
                dimen(R.dimen.space_5),
                dimen(R.dimen.space_6),
                dimen(R.dimen.space_6),
            )
            addView(TextView(requireContext()).apply {
                setText(R.string.add_connection)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setPadding(0, 0, 0, dimen(R.dimen.space_4))
            })
            addView(
                addConnectionAction(
                    R.drawable.ic_scan_qr_24,
                    R.string.scan_qr,
                    R.string.scan_qr_description,
                ) {
                    dialog.dismiss()
                    requestScanner()
                },
            )
            addView(
                addConnectionAction(
                    R.drawable.ic_paste_24,
                    R.string.paste_clipboard,
                    R.string.paste_clipboard_description,
                ) {
                    dialog.dismiss()
                    pasteClipboard()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_2) },
            )
        }
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, dimen(R.dimen.corner_sheet).toFloat())
                        .setTopRightCorner(CornerFamily.ROUNDED, dimen(R.dimen.corner_sheet).toFloat())
                        .build(),
                ).apply {
                    fillColor = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorSurface))
                }
                sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                BottomSheetBehavior.from(sheet).apply {
                    isFitToContents = true
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        dialog.show()
    }

    private fun addConnectionAction(
        iconRes: Int,
        titleRes: Int,
        descriptionRes: Int,
        action: () -> Unit,
    ): MaterialCardView = MaterialCardView(requireContext()).apply {
        isClickable = true
        isFocusable = true
        radius = dimen(R.dimen.corner_card).toFloat()
        strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
        strokeWidth = dimen(R.dimen.card_border)
        cardElevation = 0f
        contentDescription = "${getString(titleRes)}, ${getString(descriptionRes)}"
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
            )
            addView(ImageView(requireContext()).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(
                    resolveColor(androidx.appcompat.R.attr.colorPrimary),
                )
                contentDescription = null
            }, LinearLayout.LayoutParams(24.dp, 24.dp))
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    setText(titleRes)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                })
                addView(TextView(requireContext()).apply {
                    setText(descriptionRes)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_1) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dimen(R.dimen.space_4)
            })
        })
        setOnClickListener { action() }
    }

    private fun showVpnState(
        state: VpnState,
        error: String?,
        stage: ConnectionStage,
        reconnectAttempt: Int,
    ) {
        if (_binding == null) return
        val stateChanged = currentState != state
        vpnStateRevision++
        currentState = state
        currentStage = stage
        currentReconnectAttempt = reconnectAttempt
        if (state !in PROFILE_PROBE_IDLE_STATES && profileProbeInProgress) {
            cancelProfileProbe(clearResults = false)
        }
        if (state != VpnState.CONNECTED && latencyInProgress) {
            latencyRequestId++
            latencyInProgress = false
        }
        when (state) {
            VpnState.CONNECTED -> {
                val active = activityHost.activeProfileReference()
                val activeChanged = active != when {
                    connectedProfileId != null -> "local:$connectedProfileId"
                    connectedSubscriptionProfileId != null -> "subscription:$connectedSubscriptionProfileId"
                    else -> null
                }
                connectedProfileId = active?.removePrefix("local:")?.takeIf { it != active }?.toLongOrNull()
                connectedSubscriptionProfileId = active?.removePrefix("subscription:")?.takeIf { it != active }
                if (stateChanged || activeChanged) {
                    selectedProfileId = connectedProfileId
                    selectedSubscriptionProfileId = connectedSubscriptionProfileId
                }
                loadProfiles()
                if (stateChanged) loadDashboardSummary()
            }
            VpnState.DISCONNECTED, VpnState.ERROR, VpnState.NO_PROFILE -> {
                connectedProfileId = null
                connectedSubscriptionProfileId = null
                if (stateChanged) loadDashboardSummary()
            }
            else -> {}
        }
        binding.connectionSummary.text = if (state == VpnState.CONNECTED) {
            getString(R.string.connection_protected)
        } else {
            vpnStateText(state, stage, reconnectAttempt)
        }
        binding.connectionStatusDot.alpha = if (state == VpnState.CONNECTED) 1f else 0.45f
        showStatus(error ?: if (state == VpnState.ERROR) getString(R.string.vpn_notification_error) else null)
        updateConnectButtonText()
        applyingPulse = state == VpnState.CONNECTED
        updateConnectPulse()
        updateActionAvailability()
        updateLatencyAction()
        updateDashboard()
        refreshCardAppearance()
    }

    private fun setConnectButtonText(text: CharSequence) {
        if (_binding == null) return
        val title = binding.connectTitle
        binding.connect.contentDescription = text
        if (title.text == text) return
        if (!animationsEnabled()) {
            title.text = text
            return
        }
        title.animate().cancel()
        title.animate()
            .alpha(0f)
            .setDuration(100L)
            .withEndAction {
                title.text = text
                title.animate().cancel()
                title.animate().alpha(1f).setDuration(120L).start()
            }
            .start()
    }

    private fun updateConnectButtonText() {
        val text = getString(
            when {
                currentState == VpnState.PREPARING || currentState == VpnState.CONNECTING -> R.string.cancel_connection
                shouldSwitchProfile(currentState, hasPendingProfileSwitch()) -> R.string.switch_connection
                currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING -> R.string.disconnect
                currentState == VpnState.STOPPING -> R.string.disconnecting
                currentState == VpnState.ERROR -> R.string.retry
                else -> R.string.connect
            },
        )
        setConnectButtonText(text)
    }

    private fun hasPendingProfileSwitch(): Boolean =
        selectedProfileId != null && selectedProfileId != connectedProfileId ||
            selectedSubscriptionProfileId != null && selectedSubscriptionProfileId != connectedSubscriptionProfileId

    private fun animationsEnabled(): Boolean {
        if (!activityHost.currentAppearance().motionEnabled) return false
        val resolver = requireContext().contentResolver
        val scale = android.provider.Settings.Global.getFloat(
            resolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale != 0f
    }

    private var applyingPulse = false
    private var pulseAnimator: android.animation.ObjectAnimator? = null

    private fun updateConnectPulse() {
        if (_binding == null) return
        val halo = binding.connectionHalo
        pulseAnimator?.cancel()
        pulseAnimator = null
        val intensity = activityHost.currentAppearance().glowIntensity
        val targetAlpha = connectionGlowAlpha(intensity, applyingPulse)
        if (!applyingPulse || !animationsEnabled() || targetAlpha == 0f) {
            halo.alpha = targetAlpha
            halo.scaleX = 1f
            halo.scaleY = 1f
            return
        }
        // ponytail: ObjectAnimator used because ViewPropertyAnimator has no repeat.
        android.animation.ObjectAnimator.ofPropertyValuesHolder(
            halo,
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, targetAlpha * 0.62f, targetAlpha),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.06f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.06f),
        ).apply {
            duration = 2_800L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
            pulseAnimator = this
        }
    }

    private fun vpnStateText(state: VpnState, stage: ConnectionStage, reconnectAttempt: Int): String = when (state) {
        VpnState.NO_PROFILE -> getString(R.string.vpn_notification_disconnected)
        VpnState.DISCONNECTED -> getString(R.string.vpn_notification_disconnected)
        VpnState.PREPARING, VpnState.CONNECTING -> connectionStageText(stage)
        VpnState.CONNECTED -> getString(R.string.vpn_notification_connected)
        VpnState.RECONNECTING -> if (reconnectAttempt > 0) {
            failoverAttemptName()?.let { name ->
                getString(R.string.vpn_failover_attempt, name, reconnectAttempt)
            } ?: getString(R.string.vpn_reconnecting_attempt, reconnectAttempt)
        } else {
            connectionStageText(stage)
        }
        VpnState.STOPPING -> getString(R.string.vpn_notification_stopping)
        VpnState.ERROR -> getString(R.string.vpn_notification_error)
    }

    /**
     * Issue #47: during Auto failover the header names the profile currently
     * being tried ("Trying Telemost…"), so the walk is visible. Returns null
     * when the active profile is unknown (legacy same-profile retry text).
     */
    private fun failoverAttemptName(): String? {
        val active = activityHost.activeProfileReference() ?: return null
        return connectionItems.firstOrNull { it.reference == active }?.name
    }

    private fun connectionStageText(stage: ConnectionStage): String = getString(
        when (stage) {
            ConnectionStage.LOAD_PROFILE -> R.string.vpn_stage_load_profile
            ConnectionStage.WAIT_NETWORK -> R.string.vpn_stage_wait_network
            ConnectionStage.PREPARE_ASSETS -> R.string.vpn_stage_prepare_assets
            ConnectionStage.CREATE_TUN -> R.string.vpn_stage_create_tun
            ConnectionStage.START_CARRIER -> R.string.vpn_stage_start_carrier
            ConnectionStage.START_XRAY -> R.string.vpn_stage_start_xray
            ConnectionStage.START_HEV -> R.string.vpn_stage_start_hev
            ConnectionStage.VERIFY_DATAPATH -> R.string.vpn_stage_verify_datapath
            ConnectionStage.READY -> R.string.vpn_notification_connected
            ConnectionStage.STOPPING -> R.string.vpn_notification_stopping
            ConnectionStage.IDLE -> R.string.vpn_notification_connecting
        },
    )

    private fun renderContentState(state: ConnectionContentState) {
        if (_binding == null) return
        val contentVisible = state == ConnectionContentState.CONTENT
        binding.contentState.isVisible = !contentVisible
        binding.actionArea.isVisible = contentVisible
        binding.contentLoading.isVisible = state == ConnectionContentState.LOADING
        binding.contentStateAction.isVisible = state == ConnectionContentState.EMPTY ||
            state == ConnectionContentState.ERROR
        when (state) {
            ConnectionContentState.LOADING -> {
                binding.contentStateTitle.setText(R.string.connection_loading_title)
                binding.contentStateMessage.setText(R.string.connection_loading_message)
            }
            ConnectionContentState.EMPTY -> {
                binding.contentStateTitle.setText(R.string.connection_empty_title)
                binding.contentStateMessage.setText(R.string.connection_empty_message)
                binding.contentStateAction.setText(R.string.add_connection)
                binding.contentStateAction.setIconResource(R.drawable.ic_add_24)
                binding.contentStateAction.setOnClickListener { showAddConnectionMenu() }
            }
            ConnectionContentState.ERROR -> {
                binding.contentStateTitle.setText(R.string.connection_error_title)
                binding.contentStateMessage.setText(R.string.connection_error_message)
                binding.contentStateAction.setText(R.string.retry)
                binding.contentStateAction.icon = null
                binding.contentStateAction.setOnClickListener { loadProfiles() }
            }
            ConnectionContentState.CONTENT -> Unit
        }
    }

    private fun updateActionAvailability() {
        if (_binding == null) return
        binding.connect.isEnabled = !profileProbeInProgress && currentState != VpnState.STOPPING && (
            currentState == VpnState.CONNECTED || currentState in BUSY_STATES ||
                selectedProfileId != null || selectedSubscriptionProfileId != null
            )
        binding.connect.alpha = if (binding.connect.isEnabled) 1f else 0.55f
        binding.testSelectedContainer.isVisible = currentState == VpnState.CONNECTED
        binding.testSelected.isEnabled = currentState == VpnState.CONNECTED && !hasPendingProfileSwitch() && !latencyInProgress
        binding.pingProgress.isVisible = latencyInProgress
        binding.addProfile.isEnabled = !profileProbeInProgress
        binding.testAllProfiles.isEnabled = currentState in PROFILE_PROBE_IDLE_STATES &&
            defaultNetwork != null && connectionItems.isNotEmpty() && !profileProbeInProgress
        binding.testAllProfiles.alpha = if (binding.testAllProfiles.isEnabled) 1f else 0.38f
        binding.testAllProfiles.isVisible = !profileProbeInProgress
        binding.allProfilesProgress.isVisible = profileProbeInProgress
        if (latencyInProgress) {
            binding.testSelected.text = ""
        } else {
            updateLatencyAction()
        }
    }

    private fun updateLatencyAction() {
        if (_binding == null || latencyInProgress) return
        binding.testSelected.text = lastLatencyMillis
            ?.takeIf { lastLatencyReference == selectedReference() }
            ?.let { getString(R.string.connection_latency_value, it) }
            ?: getString(R.string.profile_ping_vpn)
    }

    private fun loadDashboardSummary() {
        if (dashboardLoadInFlight || storage.isShutdown) return
        dashboardLoadInFlight = true
        storage.execute {
            val result = runCatching { statistics.summary() }
            val host = activity
            if (host == null) {
                dashboardLoadInFlight = false
                return@execute
            }
            host.runOnUiThread {
                dashboardLoadInFlight = false
                if (_binding == null) return@runOnUiThread
                result.onSuccess { summary ->
                    currentSessionStartedAt = summary.current?.startedAt
                    todayBytesUp = summary.today.bytesUp
                    todayBytesDown = summary.today.bytesDown
                    updateDashboard()
                }
            }
        }
    }

    private fun updateDashboard() {
        if (_binding == null) return
        val traffic = activityHost.trafficSnapshot()?.takeIf { it.size >= 4 }
        val sessionActive = currentSessionStartedAt != null && currentState in ACTIVE_SESSION_STATES
        val liveUp = if (sessionActive) traffic?.get(0)?.coerceAtLeast(0) ?: 0 else 0
        val liveDown = if (sessionActive) traffic?.get(1)?.coerceAtLeast(0) ?: 0 else 0
        val upSpeed = if (currentState == VpnState.CONNECTED) traffic?.get(2)?.coerceAtLeast(0) ?: 0 else 0
        val downSpeed = if (currentState == VpnState.CONNECTED) traffic?.get(3)?.coerceAtLeast(0) ?: 0 else 0
        binding.todayDownload.text = getString(R.string.traffic_rate_format, formatBytes(downSpeed))
        binding.todayUpload.text = getString(R.string.traffic_rate_format, formatBytes(upSpeed))
        binding.todayTotal.text = formatBytes(todayBytesUp + todayBytesDown + liveUp + liveDown)
        binding.connectionTimer.text = currentSessionStartedAt
            ?.takeIf { sessionActive }
            ?.let { formatDuration(System.currentTimeMillis() - it) }
            ?: if (currentState in BUSY_STATES || currentState in ACTIVE_SESSION_STATES) {
                getString(R.string.connection_timer_zero)
            } else {
                getString(R.string.connection_tap_to_start)
            }
    }

    private fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(
        requireContext(),
        bytes.coerceAtLeast(0),
    )

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun showStatus(message: CharSequence?) {
        val status = _binding?.status ?: return
        status.text = message
        status.isVisible = !message.isNullOrBlank()
    }

    private fun latencyErrorText(kind: LatencyErrorKind): Int = when (kind) {
        LatencyErrorKind.TIMEOUT -> R.string.profile_latency_timeout
        LatencyErrorKind.DNS -> R.string.profile_latency_dns_error
        LatencyErrorKind.NO_ACTIVE_SESSION -> R.string.profile_latency_no_active_session
        LatencyErrorKind.OTHER -> R.string.profile_latency_failed
    }

    private fun requestScanner() {
        qrScanner.launch(
            Intent(requireContext(), QrScannerActivity::class.java),
        )
    }

    private fun pasteClipboard() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (text.isNullOrBlank()) showStatus(getString(R.string.clipboard_empty))
        else validatePreview(text, R.string.source_clipboard)
    }

    private fun validatePreview(raw: String, source: Int) {
        storage.execute {
            val result = runCatching { parsePreview(raw) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                showPreview(result, R.string.invalid_profile, source)
            }
        }
    }

    private fun parsePreview(raw: String): ImportPreview {
        return when (val payload = ImportPayload.decode(raw)) {
            is DecodedImportPayload.Profile -> saveProfile(payload.uri)
            is DecodedImportPayload.Subscription -> ImportPreview(subscriptionUrl = payload.url)
            is DecodedImportPayload.Bundle -> importBundle(payload.raw)
            is DecodedImportPayload.Multipart -> importBundle(payload.raw)
        }
    }

    private fun importBundle(raw: String): ImportPreview = when (val bundle = bundleImports.accept(raw)) {
        is BundleImportResult.Pending -> ImportPreview(
            multipartReceived = bundle.received,
            multipartTotal = bundle.total,
        )
        is BundleImportResult.Complete -> {
            val subscriptionId = profiles.insertSubscription(bundle.bundle)
            val refresh = (activity as? MainActivity)?.refreshSubscription(subscriptionId)
                ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)
            ImportPreview(
                subscriptionImported = true,
                subscriptionProfileId = profiles.listSubscriptionProfiles(subscriptionId).firstOrNull()?.id,
                subscriptionRefresh = refresh,
            )
        }
    }

    private fun saveProfile(raw: String): ImportPreview = when (val profile = ProfileUri.parse(raw)) {
        is ImportedProfile.Olcrtc -> ImportPreview(
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
        is ImportedProfile.OpenFlux -> ImportPreview(
            localProfileId = profiles.insertLocal(profile.value),
        )
        is ImportedProfile.Standard -> ImportPreview(
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
    }

    private fun showPreview(result: Result<ImportPreview>, fallbackError: Int, source: Int) {
        if (_binding == null) return
        result.onSuccess { preview ->
            preview.subscriptionUrl?.let {
                confirmSubscriptionImport(it, source)
                return@onSuccess
            }
            if (preview.multipartReceived != null && preview.multipartTotal != null) {
                showStatus(
                    "${getString(R.string.import_source, getString(source))}\n${getString(
                        R.string.subscription_qr_progress,
                        preview.multipartReceived,
                        preview.multipartTotal,
                    )}",
                )
                return@onSuccess
            }
            selectedProfileId = preview.localProfileId
            selectedSubscriptionProfileId = preview.subscriptionProfileId
            showStatus(null)
            updateActionAvailability()
            Snackbar.make(
                binding.root,
                if (preview.subscriptionImported) R.string.subscription_imported else R.string.profile_saved,
                Snackbar.LENGTH_SHORT,
            ).show()
            loadProfiles()
            preview.subscriptionRefresh?.let(::showSubscriptionRefreshResult)
        }.onFailure { showStatus(it.message ?: getString(fallbackError)) }
    }

    private fun confirmSubscriptionImport(url: String, source: Int) {
        val host = runCatching { requireNotNull(java.net.URI(url).host) }
            .getOrElse {
                showStatus(it.message ?: getString(R.string.invalid_subscription_link))
                return
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_link_confirm_title)
            .setMessage(getString(R.string.subscription_link_confirm_message, host, host))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.subscription_link_confirm_action) { _, _ ->
                importSubscriptionSource(url, host, source)
            }
            .show()
    }

    private fun importSubscriptionSource(url: String, name: String, source: Int) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                val subscriptionId = profiles.insertSubscriptionSource(name, url, kind = "GENERIC")
                val refresh = (activity as? MainActivity)?.refreshSubscription(subscriptionId)
                    ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)
                ImportPreview(
                    subscriptionImported = true,
                    subscriptionProfileId = profiles.listSubscriptionProfiles(subscriptionId).firstOrNull()?.id,
                    subscriptionRefresh = refresh,
                )
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                showPreview(result, R.string.invalid_subscription_link, source)
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
                            append('\n').append(getString(
                                R.string.subscription_update_source,
                                getString(
                                    if (it == SubscriptionRefresher.Source.PRIMARY) {
                                        R.string.subscription_source_primary
                                    } else {
                                        R.string.subscription_source_mirror
                                    },
                                ),
                            ))
                        }
                    }
                } else {
                    getString(R.string.subscription_update_preserved)
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun editLocalProfile(profileId: Long) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.get(profileId)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        isActive = (activity as? MainActivity)?.activeProfileReference() == "local:$profileId",
                        onSave = { updated, dialog -> saveLocalProfile(profileId, updated, dialog) },
                        onError = { showStatus(it.message ?: getString(R.string.invalid_profile)) },
                    )
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun saveLocalProfile(profileId: Long, profile: ProfileConfig, dialog: AlertDialog) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                when (profile) {
                    is ProfileConfig.Olcrtc -> profiles.update(profileId, profile.value)
                    is ProfileConfig.OpenFlux -> profiles.update(profileId, profile.value)
                    is ProfileConfig.Standard -> profiles.update(profileId, profile.value)
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun editSubscriptionProfile(profileId: String) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.getSubscriptionProfile(profileId)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        isActive = (activity as? MainActivity)?.activeProfileReference() == "subscription:$profileId",
                        onSave = { updated, dialog -> saveSubscriptionProfile(profileId, updated, dialog) },
                        onError = { showStatus(it.message ?: getString(R.string.invalid_profile)) },
                    )
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun saveSubscriptionProfile(profileId: String, profile: ProfileConfig, dialog: AlertDialog) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                profiles.updateSubscriptionProfile(
                    profileId,
                    when (profile) {
                        is ProfileConfig.Olcrtc -> ImportedProfile.Olcrtc(profile.value)
                        is ProfileConfig.OpenFlux -> ImportedProfile.OpenFlux(profile.value)
                        is ProfileConfig.Standard -> ImportedProfile.Standard(profile.value)
                    },
                )
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun confirmCopyProfileLink(item: ConnectionListItem) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                item.localId?.let(profiles::exportProfileUri)
                    ?: profiles.exportSubscriptionProfileUri(requireNotNull(item.subscriptionProfileId))
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { uri ->
                    if (ProfileSharePolicy.containsSecrets(uri)) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.profile_export_secret_warning_title)
                            .setMessage(R.string.profile_export_secret_warning_message)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.copy) { _, _ -> copyProfileLink(uri) }
                            .show()
                    } else {
                        copyProfileLink(uri)
                    }
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun copyProfileLink(uri: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.copySensitive(ProfileSharePolicy.CLIP_LABEL, uri)
        showStatus(getString(R.string.profile_link_copied))
        val copy = uri
        ticker.removeCallbacks(clipboardWipe)
        ticker.postDelayed(clipboardWipe, ProfileSharePolicy.CLEAR_DELAY_MILLIS)
        lastCopiedProfileLink = copy
    }

    private var lastCopiedProfileLink: String? = null

    private val clipboardWipe = Runnable {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val expected = lastCopiedProfileLink ?: return@Runnable
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            lastCopiedProfileLink = null
            return@Runnable
        }
        val label = clip.description.label
        val text = clip.getItemAt(0)?.coerceToText(requireContext())
        if (ProfileSharePolicy.shouldClearClipboard(ProfileSharePolicy.CLIP_LABEL, expected, label, text)) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
        lastCopiedProfileLink = null
    }

    private fun confirmDeleteSubscriptionProfile(profileId: String, name: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (storage.isShutdown) return@setPositiveButton
                storage.execute {
                    val result = runCatching { profiles.deleteSubscriptionProfile(profileId) }
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        result.onSuccess {
                            if (selectedSubscriptionProfileId == profileId) {
                                selectedSubscriptionProfileId = null
                                updateActionAvailability()
                            }
                            loadProfiles()
                        }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
                    }
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(resolveColor(androidx.appcompat.R.attr.colorError))
    }

    private fun confirmDeleteProfile(profileId: Long, name: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (storage.isShutdown) return@setPositiveButton
                storage.execute {
                    val result = runCatching {
                        profiles.deleteLocal(profileId)
                        settings.setLocalProfileFavorite(profileId, false)
                    }
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        result.onSuccess {
                            if (selectedProfileId == profileId) {
                                selectedProfileId = null
                                updateActionAvailability()
                            }
                            loadProfiles()
                        }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
                    }
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(resolveColor(androidx.appcompat.R.attr.colorError))
    }

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun dimen(resource: Int) = resources.getDimensionPixelSize(resource)

    private data class ImportPreview(
        val localProfileId: Long? = null,
        val subscriptionProfileId: String? = null,
        val subscriptionImported: Boolean = false,
        val multipartReceived: Int? = null,
        val multipartTotal: Int? = null,
        val subscriptionRefresh: SubscriptionRefresher.Result? = null,
        val subscriptionUrl: String? = null,
    )

    private data class ConnectionScreenModel(
        val profiles: List<ConnectionListItem>,
    )

    private data class ConnectionListItem(
        val reference: String,
        val name: String,
        val type: String,
        val favorite: Boolean,
        val localId: Long? = null,
        val subscriptionProfileId: String? = null,
        val config: ProfileConfig? = null,
    )

    companion object {
        private val BUSY_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.RECONNECTING,
            VpnState.STOPPING,
        )
        private val CARD_PROGRESS_STATES = BUSY_STATES
        private val ACTIVE_SESSION_STATES = setOf(
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
            VpnState.STOPPING,
        )
        private val PROFILE_PROBE_IDLE_STATES = setOf(
            VpnState.NO_PROFILE,
            VpnState.DISCONNECTED,
            VpnState.ERROR,
        )
        private const val DASHBOARD_REFRESH_MILLIS = 1_000L
    }
}

internal enum class ConnectionCardState {
    CONNECTED,
    SELECTED,
    INACTIVE,
}

internal fun connectionCardState(
    selected: Boolean,
    connected: Boolean,
): ConnectionCardState = when {
    connected -> ConnectionCardState.CONNECTED
    selected -> ConnectionCardState.SELECTED
    else -> ConnectionCardState.INACTIVE
}

internal fun canSelectProfile(state: VpnState): Boolean = when (state) {
    VpnState.PREPARING, VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.STOPPING -> false
    else -> true
}

internal fun shouldSwitchProfile(state: VpnState, hasPendingProfileSwitch: Boolean): Boolean =
    state == VpnState.CONNECTED && hasPendingProfileSwitch

internal fun connectionGlowAlpha(intensity: Int, connected: Boolean): Float {
    val normalized = intensity.coerceIn(0, 100) / 100f
    if (normalized == 0f) return 0f
    val active = 0.24f + normalized * 0.56f
    return if (connected) active else active * 0.14f
}

internal enum class ConnectionContentState {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
}

internal fun connectionContentState(
    loading: Boolean,
    hasProfiles: Boolean,
    failed: Boolean,
): ConnectionContentState = when {
    failed -> ConnectionContentState.ERROR
    loading -> ConnectionContentState.LOADING
    hasProfiles -> ConnectionContentState.CONTENT
    else -> ConnectionContentState.EMPTY
}

internal enum class LatencyErrorKind {
    TIMEOUT,
    DNS,
    NO_ACTIVE_SESSION,
    OTHER,
}

internal fun latencyErrorKind(error: Throwable): LatencyErrorKind {
    val message = generateSequence(error) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return when {
        "not connected" in message || "active session" in message || "service is not connected" in message ->
            LatencyErrorKind.NO_ACTIVE_SESSION
        "dns" in message || "lookup" in message || "no such host" in message -> LatencyErrorKind.DNS
        "timeout" in message || "timed out" in message || "deadline" in message -> LatencyErrorKind.TIMEOUT
        else -> LatencyErrorKind.OTHER
    }
}

internal fun connectionTypeLabel(type: String, endpoint: String): String = when (type.lowercase(Locale.ROOT)) {
    "olcrtc" -> when (endpoint.substringBefore('·').trim().lowercase(Locale.ROOT)) {
        "wbstream" -> "WBStream"
        "telemost" -> "Telemost"
        "jitsi" -> "Jitsi"
        else -> "olcRTC"
    }
    "openflux" -> {
        val lower = endpoint.lowercase(Locale.ROOT)
        when {
            "mail" in lower -> "Mail.ru Docs"
            "volga" in lower || "vyandex" in lower -> "Yandex Volga"
            "yandex" in lower -> "Yandex Docs"
            else -> "OpenFlux"
        }
    }
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    else -> type
}

internal fun shouldShowLatencyResult(
    requestStateRevision: Long,
    currentStateRevision: Long,
    connected: Boolean,
): Boolean = connected && requestStateRevision == currentStateRevision
