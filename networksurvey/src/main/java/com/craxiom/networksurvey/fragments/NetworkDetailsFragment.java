package com.craxiom.networksurvey.fragments;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.craxiom.messaging.GsmRecord;
import com.craxiom.messaging.ConnectionStatus;
import com.craxiom.messaging.GsmRecordData;
import com.craxiom.messaging.LteRecord;
import com.craxiom.messaging.LteRecordData;
import com.craxiom.messaging.NrRecord;
import com.craxiom.messaging.NrRecordData;
import com.craxiom.messaging.UmtsRecord;
import com.craxiom.messaging.UmtsRecordData;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.LteMessageConstants;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.databinding.FragmentNetworkDetailsBinding;
import com.craxiom.networksurvey.fragments.model.CarrierAggregationViewState;
import com.craxiom.networksurvey.fragments.model.CellularViewModel;
import com.craxiom.networksurvey.fragments.model.GsmNeighbor;
import com.craxiom.networksurvey.fragments.model.LteNeighbor;
import com.craxiom.networksurvey.fragments.model.NrNeighbor;
import com.craxiom.networksurvey.fragments.model.NrSecondaryCellSighting;
import com.craxiom.networksurvey.fragments.model.NrSecondaryCellViewState;
import com.craxiom.networksurvey.fragments.model.UmtsNeighbor;
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener;
import com.craxiom.networksurvey.model.AndroidNrCarriers;
import com.craxiom.networksurvey.model.CellularProtocol;
import com.craxiom.networksurvey.model.CellularRecordWrapper;
import com.craxiom.networksurvey.model.NetworkTechnologyInfo;
import com.craxiom.networksurvey.model.NrCarrier;
import com.craxiom.networksurvey.model.NrRecordWrapper;
import com.craxiom.networksurvey.services.NetworkSurveyService;
import com.craxiom.networksurvey.ui.cellular.CellularChartViewModel;
import com.craxiom.networksurvey.ui.cellular.CellularDetailsCompose;
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo;
import com.craxiom.networksurvey.ui.main.SharedViewModel;
import com.craxiom.networksurvey.util.CalculationUtils;
import com.craxiom.networksurvey.util.CarrierAggregation;
import com.craxiom.networksurvey.util.CellularBandwidthUtils;
import com.craxiom.networksurvey.util.CellularUtils;
import com.craxiom.networksurvey.util.ColorUtils;
import com.craxiom.networksurvey.util.OperatorUtil;
import com.craxiom.networksurvey.util.ParserUtils;
import com.craxiom.networksurvey.util.TelephonyStateUtils;
import com.mackhartley.roundedprogressbar.RoundedProgressBar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import timber.log.Timber;

/**
 * A fragment for displaying the latest cellular network details to the user.
 *
 * @since 1.6.0 (It really came earlier, but was minimal until the 1.6.0 rewrite.
 */
public class NetworkDetailsFragment extends AServiceDataFragment implements ICellularSurveyRecordListener
{
    public static final String SUBSCRIPTION_ID_KEY = "subscription_id";
    static final String UI_SUBSCRIPTION_ID_KEY = "ui_subscription_id";

    // The next two values have been added because certain devices don't follow the Interger#MAX_VALUE approach defined
    // in the Android API. The phone is supposed to report Interger#MAX_VALUE to indicate "Unknown/Unset" values, but
    // Pixel devices seem to report -120 all the time for UMTS RSCP, and Samsung devices seem to report -24 for UMTS RSCP.
    // These values are technically valid and filtering them out is an incorrect thing to do, but it is all I can think
    // of right now to prevent invalid values from being reported.
    private static final int RSCP_UNSET_VALUE_120 = -120;
    private static final int RSCP_UNSET_VALUE_24 = -24;

    // Separator between a technology and its qualifier ("NR · Standalone") and between a pill's
    // label and value ("Voice · VoNR").
    private static final String TECH_SEPARATOR = " · ";

    /**
     * How long the NR Secondary Cell card keeps showing the last-seen cell in its idle state
     * after the NR leg detaches. Past this age the sighting is too stale to be useful and the
     * card hides as it did before the idle state existed.
     * <p>
     * The point of the idle state is to stop the card flickering in and out while the user reads
     * the screen, since on NSA the NR leg drops a few seconds after each data transfer ends. Two
     * minutes covers that comfortably. It is deliberately short because a held cell is a claim
     * about the recent past, and the longer it is held the less likely it still describes where
     * the user is. A change of serving cell drops the sighting regardless of its age.
     */
    private static final long NR_IDLE_MAX_AGE_MS = 2 * 60 * 1000L;

    /**
     * Alpha applied to the NR Secondary Cell card body while idle. Dimmed enough to read as "not
     * live" next to the Idle badge, but kept high enough that the values stay legible in both
     * themes.
     */
    private static final float NR_IDLE_BODY_ALPHA = 0.65f;

    private int subscriptionId;

    // The NR mode from the most recent onNetworkType callback. Written on the survey executor
    // (onNetworkType always runs just before the same scan's onCellularBatch) and volatile so
    // main-thread readers see the latest value.
    private volatile TelephonyStateUtils.NrMode currentNrMode = TelephonyStateUtils.NrMode.NONE;
    private volatile NetworkTechnologyInfo currentTechnology;
    private final AndroidNrCarriers androidNrCarriers = new AndroidNrCarriers();
    private boolean nrScanActive;
    private volatile boolean nrAirplaneMode;

    private FragmentNetworkDetailsBinding binding;
    private CellularViewModel viewModel;
    private CellularChartViewModel chartViewModel;
    private SharedViewModel sharedViewModel;
    private AirplaneModeReceiver airplaneModeReceiver;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        //noinspection DataFlowIssue
        subscriptionId = args.getInt(SUBSCRIPTION_ID_KEY, -1);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        binding = FragmentNetworkDetailsBinding.inflate(inflater);

        // Single-SIM survey records use a shared default ID to keep exported records slotless.
        // Scope display state to the actual SIM so its retained PLMN survives navigation, not a SIM swap.
        final int uiSubscriptionId = requireArguments().getInt(UI_SUBSCRIPTION_ID_KEY, subscriptionId);
        viewModel = new ViewModelProvider(requireActivity()).get(getClass().getName() + uiSubscriptionId, CellularViewModel.class);
        chartViewModel = new ViewModelProvider(requireActivity()).get(getClass().getName() + "cellular_chart" + subscriptionId, CellularChartViewModel.class);
        sharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        // 全 Compose 渲染：将蜂窝详情 UI 装入 ComposeView
        Fragment parent = getParentFragment();
        if (parent instanceof MainCellularFragment)
        {
            ((MainCellularFragment) parent).setCellularModel(viewModel);
        }
        CellularDetailsCompose.setContent(binding.cellularDetailsComposeView, viewModel, chartViewModel,
                this::showNrDetailsInfoDialog,
                parent instanceof MainCellularFragment ? ((MainCellularFragment) parent).getTopBarScroll() : null);

        return binding.getRoot();
    }
    @Override
    public void onPause()
    {

        // Unregister airplane mode receiver
        if (airplaneModeReceiver != null)
        {
            try
            {
                requireContext().unregisterReceiver(airplaneModeReceiver);
                Timber.d("Unregistered airplane mode receiver");
            } catch (IllegalArgumentException e)
            {
                Timber.w(e, "Airplane mode receiver was not registered");
            }
            airplaneModeReceiver = null;
        }

        super.onPause();
    }

    @Override
    public void onResume()
    {
        super.onResume();

        // Register airplane mode receiver
        airplaneModeReceiver = new AirplaneModeReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        requireContext().registerReceiver(airplaneModeReceiver, filter);

        // Check initial airplane mode state
        boolean isAirplaneModeOn = isAirplaneModeOn(requireContext());
        nrAirplaneMode = isAirplaneModeOn;
        viewModel.setAirplaneModeActive(isAirplaneModeOn);
        if (isAirplaneModeOn)
        {
            clearCellularUi();
        }

        // 直接复用父 Fragment 已绑定的 Service，跳过第二次 bindService 的异步等待。
        // 若父 Service 尚未就绪则回退到自行绑定。
        final Fragment parentFragment = getParentFragment();
        if (parentFragment instanceof MainCellularFragment)
        {
            NetworkSurveyService parentService = ((MainCellularFragment) parentFragment).getService();
            if (parentService != null)
            {
                // 父 Fragment 已绑定 Service，直接复用引用，跳过异步 bindService
                service = parentService;
                onSurveyServiceConnected(service);
            }
            else
            {
                // 父 Fragment 的 Service 尚未绑定完成，回退到自行绑定
                startAndBindToService();
            }
        }
        else
        {
            startAndBindToService();
        }

    }

    @Override
    public void onDestroyView()
    {
        viewModel.getServingCellProtocol().removeObservers(getViewLifecycleOwner());
        viewModel.getCellId().removeObservers(getViewLifecycleOwner());

        super.onDestroyView();
    }

    @Override
    protected void onSurveyServiceConnected(NetworkSurveyService service)
    {
        synchronized (androidNrCarriers) {
            nrScanActive = true;
        }
        service.registerCellularSurveyRecordListener(this);

        service.runSingleCellularScan();
    }

    @Override
    protected void onSurveyServiceDisconnecting(NetworkSurveyService service)
    {
        service.unregisterCellularSurveyRecordListener(this);
        synchronized (androidNrCarriers) {
            nrScanActive = false;
            clearAndroidNrCarriers();
        }

        super.onSurveyServiceDisconnecting(service);
    }

    @Override
    public void onCellularBatch(List<CellularRecordWrapper> cellularGroup, int subscriptionId)
    {
        // The records are for a different SIM, so ignore them because another
        // NetworkDetailsFragment instance will handle them.
        if (this.subscriptionId != subscriptionId) return;

        processCellularGroup(cellularGroup);
    }

    @Override
    public void onNetworkType(NetworkTechnologyInfo technologyInfo, int subscriptionId)
    {
        // The records are for a different SIM, so ignore them because another
        // NetworkDetailsFragment instance will handle them.
        if (this.subscriptionId != subscriptionId) return;

        currentNrMode = technologyInfo.nrMode();
        currentTechnology = technologyInfo;
        viewModel.setNrMode(currentNrMode);
        viewModel.updateRegisteredPlmn(technologyInfo.registeredPlmn(), technologyInfo.registrationLost());
        if (!technologyInfo.subscriptionCarrier().isEmpty()) viewModel.setCarrier(technologyInfo.subscriptionCarrier());

        final TelephonyStateUtils.HeroResult hero = TelephonyStateUtils.deriveHero(
                technologyInfo.nrMode(), technologyInfo.baseDataRat(), technologyInfo.registrationRows());
        final String heroText = heroDisplay(hero);

        viewModel.setHeroText(heroText);
        viewModel.setHeroColorId(heroColorId(hero.state()));

        // The pill row supports a live technology hero. On the degraded No Service and Unknown
        // states the pills usually carry nothing but "None"/"Unknown" noise the hero already
        // conveys, so the row hides (a null voice value hides it) unless a pill still has a real
        // value to show (e.g. on API 26-30 during Wi-Fi calling the hero is Unknown while the
        // legacy data network type is IWLAN). On every live state show all three pills with
        // explicit values so nothing appears or disappears during normal use.
        if (!TelephonyStateUtils.shouldShowPillRow(hero.state(), technologyInfo.voiceDisplay(),
                technologyInfo.dataDisplay(),
                Set.of(NetworkSurveyConstants.UNKNOWN, getString(R.string.voice_bearer_none))))
        {
            viewModel.setVoicePillValue(null);
            viewModel.setDataPillValue(null);
            viewModel.setBrandingPillValue(null);
        } else
        {
            viewModel.setVoicePillValue(technologyInfo.voiceDisplay());
            viewModel.setDataPillValue(technologyInfo.dataDisplay());
            // Branding shows the carrier's marketing override label, or a uniform "None" when there
            // is no branding override (or it is not yet known). Gating on the raw override int keeps
            // the value consistent across API levels and scan paths; overrideDisplay itself is "N/A"
            // or "Unknown" on some paths, which would not match the info dialog copy.
            viewModel.setBrandingPillValue(
                    TelephonyStateUtils.isBrandingOverride(technologyInfo.overrideNetworkType())
                            ? technologyInfo.overrideDisplay()
                            : getString(R.string.branding_none));
        }

    }

    /**
     * @return The technology line shared by the Android and DIAG operator card.
     */
    private String heroDisplay(TelephonyStateUtils.HeroResult hero)
    {
        return switch (hero.state())
        {
            case NR_STANDALONE -> "5G" + TECH_SEPARATOR + "NR SA";
            case NR_NON_STANDALONE -> "5G" + TECH_SEPARATOR + "NR NSA";
            case DATA_RAT -> switch (hero.rat())
            {
                case TelephonyManager.NETWORK_TYPE_NR -> "5G" + TECH_SEPARATOR + "NR";
                case TelephonyManager.NETWORK_TYPE_LTE -> "4G" + TECH_SEPARATOR + "LTE";
                default -> CalculationUtils.getNetworkType(hero.rat());
            };
            case NO_SERVICE -> getString(R.string.hero_no_service);
            case UNKNOWN -> getString(R.string.unknown);
        };
    }

    /**
     * @return The hero text color resource: accent for a live technology, orange for no service,
     * and faded for unknown, so a bad state never renders in the "all good" accent color.
     */
    private int heroColorId(TelephonyStateUtils.HeroState state)
    {
        return switch (state)
        {
            case NO_SERVICE -> R.color.rssi_orange;
            case UNKNOWN -> R.color.fadedText;
            default -> R.color.colorAccent;
        };
    }

    /**
     * @return The carrier aggregation view state (per-carrier chip labels plus the summary line),
     * or null when fewer than two valid component carriers are reported (so the section is hidden).
     */
    private CarrierAggregationViewState carrierAggregationViewState(CarrierAggregation.Snapshot snapshot)
    {
        if (!snapshot.getVisible()) return null;
        final int label = switch (snapshot.getKind()) {
            case LTE_CA -> R.string.ca_lte_cellinfo;
            case NR_CA -> R.string.ca_nr_cellinfo;
            case EN_DC -> R.string.ca_endc_cellinfo;
            default -> R.string.ca_multi_bandwidths;
        };
        final List<String> chips = snapshot.getComponents().stream()
                .map(CarrierAggregation::chipLabel).collect(Collectors.toList());
        return new CarrierAggregationViewState(chips, getString(label, chips.size(),
                CellularBandwidthUtils.formatBandwidthMhz(snapshot.getAggregateKhz())));
    }
    /**
     * Clears out the UI, which is needed if the phone stops seeing towers or something else happens (e.g. airplane mode).
     */
    private void clearCellularUi()
    {
        viewModel.setNrBandwidthDiagnostic(null);
        viewModel.setServingCellProtocol(CellularProtocol.NONE);
        viewModel.setLteServingCell(null);
        viewModel.setNrServingCell(null);
        viewModel.setNrMode(TelephonyStateUtils.NrMode.NONE);

        viewModel.setMcc("");
        viewModel.setMnc("");
        viewModel.setCellPlmns("");
        viewModel.setAreaCode("");
        viewModel.setCellId(null);
        viewModel.setChannelNumber("");
        viewModel.setFrequency("");
        viewModel.setUplinkReference(null);
        viewModel.setBand("");
        viewModel.setLteBand("");

        viewModel.setPci("");
        viewModel.setBandwidth("");
        viewModel.setTa("");
        viewModel.setCqi("");

        viewModel.setSignalOne(null);
        viewModel.setSignalTwo(null);
        viewModel.setSignalThree(null);

        viewModel.setNrNeighbors(Collections.emptyList());
        viewModel.setLteNeighbors(Collections.emptyList());
        viewModel.setUmtsNeighbors(Collections.emptySortedSet());
        viewModel.setGsmNeighbors(Collections.emptySortedSet());

        // Carrier aggregation is a property of the serving cell, so clear it when the serving cell
        // goes away. The top-card fields (hero and pills) are intentionally left alone here:
        // onNetworkType refreshes them on every scan before this runs.
        viewModel.setCarrierAggregation(null);

        viewModel.setNrSecondaryCell(null);
        clearAndroidNrCarriers();
        viewModel.setNrLastSeenText("");
        viewModel.clearLastNrSecondaryCell();
    }

    /**
     * The method responsible for handling a new batch of cellular records.
     *
     * @param cellularGroup The new batch of cellular records.
     */
    private void processCellularGroup(List<CellularRecordWrapper> cellularGroup)
    {
        final List<GsmRecordData> gsmNeighbors = new ArrayList<>();
        final List<UmtsRecordData> umtsNeighbors = new ArrayList<>();
        final List<LteRecordData> lteNeighbors = new ArrayList<>();
        final List<CarrierAggregation.Component> lteSecondaries = new ArrayList<>();
        final List<NrRecordWrapper> nrNonServing = new ArrayList<>();
        final NetworkTechnologyInfo technology = currentTechnology;
        final TelephonyStateUtils.NrMode nrMode = currentNrMode;
        final CellularProtocol preferredRat = nrMode == TelephonyStateUtils.NrMode.NON_STANDALONE
                ? CellularProtocol.LTE : CellularProtocol.NR;
        int activeLte = 0;
        int activeNr = 0;
        CellularRecordWrapper servingCellRecord = null;
        for (CellularRecordWrapper record : cellularGroup) {
            if (record.cellularProtocol == CellularProtocol.LTE) {
                final LteRecordData data = ((LteRecord) record.cellularRecord).getData();
                if (isActiveCarrier(data.getServingCell().getValue(), data.getConnectionStatus())) activeLte++;
            } else if (record.cellularProtocol == CellularProtocol.NR) {
                final NrRecordData data = ((NrRecord) record.cellularRecord).getData();
                if (isActiveCarrier(data.getServingCell().getValue(), data.getConnectionStatus())) activeNr++;
            }
            if (isRegisteredCell(record) &&
                    (servingCellRecord == null || (record.cellularProtocol == preferredRat &&
                            servingCellRecord.cellularProtocol != preferredRat))) {
                servingCellRecord = record;
            }
        }
        if (servingCellRecord == null || servingCellRecord.cellularProtocol != CellularProtocol.NR) {
            viewModel.setNrBandwidthDiagnostic(null);
        }
        int secondaryNrBandwidth = 0;
        for (CellularRecordWrapper cellularRecord : cellularGroup)
        {
            switch (cellularRecord.cellularProtocol)
            {
                case NONE:
                    continue;

                case GSM:
                    final GsmRecordData gsmData = ((GsmRecord) cellularRecord.cellularRecord).getData();
                    if (cellularRecord == servingCellRecord)
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processGsmServingCell(gsmData);
                    } else
                    {
                        gsmNeighbors.add(gsmData);
                    }
                    break;

                case CDMA:
                    // We don't support CDMA since it is pretty much gone
                    break;

                case UMTS:
                    final UmtsRecordData umtsData = ((UmtsRecord) cellularRecord.cellularRecord).getData();
                    if (cellularRecord == servingCellRecord)
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processUmtsServingCell(umtsData);
                    } else
                    {
                        umtsNeighbors.add(umtsData);
                    }
                    break;

                case LTE:
                    final LteRecordData lteData = ((LteRecord) cellularRecord.cellularRecord).getData();
                    if (cellularRecord == servingCellRecord)
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        final int preciseBandwidth = LteMessageConstants.getLteBandwidthKhz(lteData.getLteBandwidth());
                        final int[] reported = primaryBandwidths(technology, TelephonyManager.NETWORK_TYPE_LTE,
                                lteData.hasEarfcn() ? lteData.getEarfcn().getValue() : -1);
                        final int bandwidth = preciseBandwidth > 0 ? preciseBandwidth
                                : activeLte == 1 && activeNr == 0 ? CellularBandwidthUtils.singleCarrierBandwidthKhz(reported) : 0;
                        if (activeLte == 1 && activeNr == 1) {
                            secondaryNrBandwidth = CellularBandwidthUtils.secondaryBandwidthKhz(reported, preciseBandwidth);
                        }
                        processLteServingCell(lteData, cellularRecord.additionalPlmns, bandwidth);
                    } else if (lteData.getConnectionStatus() == ConnectionStatus.SECONDARY_SERVING)
                    {
                        lteSecondaries.add(lteComponent(lteData));
                    } else
                    {
                        lteNeighbors.add(lteData);
                    }
                    break;

                case NR:
                    final NrRecordData nrData = ((NrRecord) cellularRecord.cellularRecord).getData();
                    if (cellularRecord == servingCellRecord)
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        final boolean singleCarrier = activeNr == 1 && activeLte == 0;
                        final boolean cellMatches = technology != null && CellularBandwidthUtils.matchesPrimaryNrCell(
                                technology.registeredNci(), nrData.hasNci() ? nrData.getNci().getValue() : -1,
                                technology.primaryChannel(), nrData.hasNarfcn() ? nrData.getNarfcn().getValue() : -1);
                        final int bandwidth = nrServingBandwidth(technology, singleCarrier && cellMatches);
                        final int[] reported = technology != null ? technology.cellBandwidthsKhz() : null;
                        viewModel.setNrBandwidthDiagnostic(new CellularViewModel.NrBandwidthDiagnostic(
                                reported == null ? List.of() : Arrays.stream(reported).boxed().collect(Collectors.toList()),
                                technology == null ? 0 : technology.registeredDataRat(),
                                technology == null ? 0 : technology.baseDataRat(), singleCarrier, cellMatches, bandwidth));
                        processNrServingCell(nrData, (NrRecordWrapper) cellularRecord, bandwidth);
                    } else
                    {
                        nrNonServing.add((NrRecordWrapper) cellularRecord);
                    }
                    break;
            }
        }

        if (servingCellRecord == null) {
            clearCellularUi();
            chartViewModel.recordSignal(CellularProtocol.NONE, null);
        } else {
            if (servingCellRecord.cellularProtocol != CellularProtocol.LTE) viewModel.setLteServingCell(null);
            sharedViewModel.updateLatestServingCellInfo(
                    new ServingCellInfo(servingCellRecord, subscriptionId, System.currentTimeMillis()));
        }

        final long now = SystemClock.elapsedRealtime();
        final List<NrCarrier> reportedCarriers = reportedNrCarriers(servingCellRecord, nrNonServing, now);
        final boolean nrPrimary = servingCellRecord != null && servingCellRecord.cellularProtocol == CellularProtocol.NR;
        if (servingCellRecord != null) {
            final int nrBandwidth = secondaryNrBandwidth;
            final List<CarrierAggregation.Component> nrSecondaries = reportedCarriers.stream()
                    .filter(row -> row.role() != NrCarrier.Role.PRIMARY)
                    .map(row -> nrComponent(row, reportedCarriers.size() == 1 ? nrBandwidth : 0))
                    .collect(Collectors.toList());
            final CarrierAggregation.Component primary = switch (servingCellRecord.cellularProtocol) {
                case LTE -> lteComponent(((LteRecord) servingCellRecord.cellularRecord).getData());
                case NR -> reportedCarriers.stream().filter(row -> row.role() == NrCarrier.Role.PRIMARY)
                        .findFirst().map(row -> nrComponent(row, 0)).orElse(null);
                default -> null;
            };
            final CarrierAggregation.Snapshot fallback = CarrierAggregation.fromServiceState(primary == null || technology == null
                    ? null : primaryBandwidths(technology, nrPrimary ? TelephonyManager.NETWORK_TYPE_NR
                    : TelephonyManager.NETWORK_TYPE_LTE, primary.getArfcn()));
            viewModel.setCarrierAggregation(carrierAggregationViewState(
                    CarrierAggregation.mergeWithCellInfo(fallback, primary, lteSecondaries, nrSecondaries)));
        }
        publishAndroidNrCarriers(servingCellRecord, nrMode, nrNonServing.size() + (nrPrimary ? 1 : 0),
                activeNr, reportedCarriers, now);
        final Set<NrCarrier.Identity> nrServingIdentities = reportedCarriers.stream()
                .map(NrCarrier::identity).collect(Collectors.toSet());
        final NrRecordWrapper secondaryNrCell = servingCellRecord != null
                ? CellularUtils.selectSecondaryServingNrCell(nrNonServing) : null;
        final NrSecondaryCellViewState secondaryState = resolveNrSecondaryCellViewState(secondaryNrCell,
                CellularUtils.getTowerId(servingCellRecord), secondaryNrBandwidth);
        viewModel.setNrSecondaryCell(secondaryState);
        if (servingCellRecord == null || servingCellRecord.cellularProtocol != CellularProtocol.NR)
        {
            viewModel.setNrServingCell(secondaryNrCell != null && secondaryState != null && !secondaryState.idle()
                    ? ((NrRecord) secondaryNrCell.cellularRecord).getData() : null);
        }

        processGsmNeighbors(gsmNeighbors);
        processUmtsNeighbors(umtsNeighbors);
        processLteNeighbors(lteNeighbors);
        processNrNeighbors(nrNonServing.stream()
                .map(wrapper -> ((NrRecord) wrapper.cellularRecord).getData())
                .filter(data -> !isActiveCarrier(data.getServingCell().getValue(), data.getConnectionStatus())
                        && !nrServingIdentities.contains(nrIdentity(data)))
                .collect(Collectors.toList()));
    }

    private static CarrierAggregation.Component lteComponent(LteRecordData data)
    {
        final int earfcn = data.hasEarfcn() ? data.getEarfcn().getValue() : -1;
        return new CarrierAggregation.Component(CarrierAggregation.LTE, data.hasPci() ? data.getPci().getValue() : -1,
                earfcn, CellularUtils.downlinkEarfcnToBand(earfcn),
                LteMessageConstants.getLteBandwidthKhz(data.getLteBandwidth()));
    }

    private static CarrierAggregation.Component nrComponent(NrCarrier carrier, int bandwidth)
    {
        final int arfcn = carrier.identity().arfcn();
        return new CarrierAggregation.Component(CarrierAggregation.NR, carrier.identity().pci(), arfcn,
                CellularUtils.downlinkNarfcnToBand(arfcn), bandwidth);
    }

    private void clearAndroidNrCarriers()
    {
        synchronized (androidNrCarriers) {
            androidNrCarriers.clear();
            viewModel.setNrCarriers(List.of());
        }
    }

    private void publishAndroidNrCarriers(CellularRecordWrapper serving, TelephonyStateUtils.NrMode mode,
                                         int nrRows, int servingNrRows, List<NrCarrier> reported, long now)
    {
        synchronized (androidNrCarriers) {
            if (!nrScanActive || nrAirplaneMode) return;
            final AndroidNrCarriers.Anchor anchor = serving == null
                    || (serving.cellularProtocol == CellularProtocol.LTE
                    && mode != TelephonyStateUtils.NrMode.NON_STANDALONE && reported.size() < 2)
                    ? null : nrCarrierAnchor(serving);
            final List<NrCarrier> display = androidNrCarriers.update(anchor, reported, now);
            viewModel.setNrCarriers(display);
            AndroidNrCarriers.record(new AndroidNrCarriers.Batch(System.currentTimeMillis(), now, subscriptionId,
                    serving == null ? "NONE" : serving.cellularProtocol.name(), mode.name(), nrRows, servingNrRows,
                    reported.size(), (int) display.stream().filter(NrCarrier::lastObservation).count(),
                    reported.stream().mapToLong(row -> now - row.measuredAtElapsedMs()).max().orElse(-1)));
        }
    }

    private static AndroidNrCarriers.Anchor nrCarrierAnchor(CellularRecordWrapper serving)
    {
        if (serving.cellularProtocol == CellularProtocol.NR) {
            final NrRecordData nr = ((NrRecord) serving.cellularRecord).getData();
            return new AndroidNrCarriers.Anchor(true, nr.getPlmn().getValue(),
                    nr.hasNci() ? nr.getNci().getValue() : -1, nr.hasPci() ? nr.getPci().getValue() : -1,
                    nr.hasNarfcn() ? nr.getNarfcn().getValue() : -1);
        }
        if (serving.cellularProtocol == CellularProtocol.LTE) {
            final LteRecordData lte = ((LteRecord) serving.cellularRecord).getData();
            return new AndroidNrCarriers.Anchor(false, lte.getPlmn().getValue(),
                    lte.hasEci() ? lte.getEci().getValue() : -1, lte.hasPci() ? lte.getPci().getValue() : -1,
                    lte.hasEarfcn() ? lte.getEarfcn().getValue() : -1);
        }
        return null;
    }

    private static List<NrCarrier> reportedNrCarriers(CellularRecordWrapper serving, List<NrRecordWrapper> otherNr, long now)
    {
        if (serving == null || (serving.cellularProtocol != CellularProtocol.NR
                && serving.cellularProtocol != CellularProtocol.LTE)) return List.of();
        final boolean nrPrimary = serving.cellularProtocol == CellularProtocol.NR;
        final List<NrCarrier> candidates = new ArrayList<>();
        if (nrPrimary) {
            final NrRecordWrapper primary = (NrRecordWrapper) serving;
            if (!NrCarrier.isFreshAndroid(primary.measuredAtElapsedMs, now)) return List.of();
            candidates.add(androidNrCarrier(primary, NrCarrier.Role.PRIMARY));
        }
        for (NrRecordWrapper wrapper : otherNr) {
            if (!NrCarrier.isFreshAndroid(wrapper.measuredAtElapsedMs, now)) continue;
            final NrRecordData data = ((NrRecord) wrapper.cellularRecord).getData();
            final ConnectionStatus status = data.getConnectionStatus();
            final NrCarrier.Role role = NrCarrier.androidRole(nrPrimary, false,
                    status == ConnectionStatus.PRIMARY_SERVING || data.getServingCell().getValue(),
                    status == ConnectionStatus.SECONDARY_SERVING);
            if (role != null) candidates.add(androidNrCarrier(wrapper, role));
        }
        return NrCarrier.androidSnapshot(candidates);
    }

    private static NrCarrier.Identity nrIdentity(NrRecordData data)
    {
        return new NrCarrier.Identity(data.hasPci() ? data.getPci().getValue() : -1,
                data.hasNarfcn() ? data.getNarfcn().getValue() : -1);
    }

    private static NrCarrier androidNrCarrier(NrRecordWrapper wrapper, NrCarrier.Role role)
    {
        final NrRecordData data = ((NrRecord) wrapper.cellularRecord).getData();
        final NrCarrier.Identity identity = nrIdentity(data);
        final NrCarrier.Signal signal = NrCarrier.Signal.ss(
                data.hasSsRsrp() ? data.getSsRsrp().getValue() : Double.NaN,
                data.hasSsRsrq() ? data.getSsRsrq().getValue() : Double.NaN,
                data.hasSsSinr() ? data.getSsSinr().getValue() : Double.NaN);
        return new NrCarrier(identity, role, -1, CellularUtils.formatNrBands(wrapper.bands, identity.arfcn()), 0, false,
                signal, wrapper.measuredAtElapsedMs, false);
    }

    private static boolean isRegisteredCell(CellularRecordWrapper wrapper)
    {
        return switch (wrapper.cellularProtocol) {
            case GSM -> ((GsmRecord) wrapper.cellularRecord).getData().getServingCell().getValue();
            case UMTS -> ((UmtsRecord) wrapper.cellularRecord).getData().getServingCell().getValue();
            case LTE -> ((LteRecord) wrapper.cellularRecord).getData().getServingCell().getValue();
            case NR -> ((NrRecord) wrapper.cellularRecord).getData().getServingCell().getValue();
            default -> false;
        };
    }

    private static boolean isActiveCarrier(boolean registered, ConnectionStatus status)
    {
        return registered || status == ConnectionStatus.PRIMARY_SERVING || status == ConnectionStatus.SECONDARY_SERVING;
    }

    private static int[] primaryBandwidths(NetworkTechnologyInfo technology, int radioType, int channel)
    {
        if (technology == null || technology.baseDataRat() != radioType || channel < 0) return null;
        final int reportedChannel = technology.primaryChannel();
        return reportedChannel < 0 || reportedChannel == channel ? technology.cellBandwidthsKhz() : null;
    }

    private static int nrServingBandwidth(NetworkTechnologyInfo technology, boolean matchedSingleCarrier)
    {
        if (technology == null || !matchedSingleCarrier) return 0;
        final int rat = technology.registeredDataRat() != TelephonyManager.NETWORK_TYPE_UNKNOWN
                ? technology.registeredDataRat() : technology.baseDataRat();
        return rat == TelephonyManager.NETWORK_TYPE_NR
                ? CellularBandwidthUtils.singleCarrierBandwidthKhz(technology.cellBandwidthsKhz()) : 0;
    }

    /**
     * Takes in the GSM serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the GSM serving cell record.
     */
    private void processGsmServingCell(GsmRecordData data)
    {
        chartViewModel.recordSignal(CellularProtocol.GSM,
                data.hasSignalStrength() ? (int) data.getSignalStrength().getValue() : null);

        setMccMncOnViewModel(data.hasPlmn() ? data.getPlmn().getValue() : null,
                data.hasMcc() ? data.getMcc().getValue() : null,
                data.hasMnc() ? data.getMnc().getValue() : null);
        viewModel.setAreaCode(data.hasLac() ? String.valueOf(data.getLac().getValue()) : "");
        viewModel.setCellId(data.hasCi() ? (long) data.getCi().getValue() : null);
        viewModel.setChannelNumber(data.hasArfcn() ? String.valueOf(data.getArfcn().getValue()) : "");
        viewModel.setPci(data.hasBsic() ? ParserUtils.bsicToString(data.getBsic().getValue()) : "");

        viewModel.setSignalOne(data.hasSignalStrength() ? (int) data.getSignalStrength().getValue() : null);
    }

    /**
     * Takes in the UMTS serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the UMTS serving cell record.
     */
    private void processUmtsServingCell(UmtsRecordData data)
    {
        chartViewModel.recordSignal(CellularProtocol.UMTS,
                data.hasRscp() ? (int) data.getRscp().getValue() : null);

        setMccMncOnViewModel(data.hasPlmn() ? data.getPlmn().getValue() : null,
                data.hasMcc() ? data.getMcc().getValue() : null,
                data.hasMnc() ? data.getMnc().getValue() : null);
        viewModel.setAreaCode(data.hasLac() ? String.valueOf(data.getLac().getValue()) : "");
        viewModel.setCellId(data.hasCid() ? (long) data.getCid().getValue() : null);
        viewModel.setChannelNumber(data.hasUarfcn() ? String.valueOf(data.getUarfcn().getValue()) : "");
        viewModel.setPci(data.hasPsc() ? String.valueOf(data.getPsc().getValue()) : "");

        viewModel.setSignalOne(data.hasSignalStrength() ? (int) data.getSignalStrength().getValue() : null);
        viewModel.setSignalTwo(data.hasRscp() ? (int) data.getRscp().getValue() : null);
    }

    /**
     * Takes in the LTE serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the LTE serving cell record.
     */
    private void processLteServingCell(LteRecordData data, List<String> additionalPlmns, int bandwidthKhz)
    {
        viewModel.setLteServingCell(data);
        chartViewModel.recordSignal(CellularProtocol.LTE,
                data.hasRsrp() ? (int) data.getRsrp().getValue() : null);

        setMccMncOnViewModel(data.hasPlmn() ? data.getPlmn().getValue() : null,
                data.hasMcc() ? data.getMcc().getValue() : null,
                data.hasMnc() ? data.getMnc().getValue() : null, additionalPlmns);
        viewModel.setAreaCode(data.hasTac() ? String.valueOf(data.getTac().getValue()) : "");
        viewModel.setCellId(data.hasEci() ? (long) data.getEci().getValue() : null);

        // Set EARFCN without band information
        viewModel.setChannelNumber(data.hasEarfcn() ? String.valueOf(data.getEarfcn().getValue()) : "");
        viewModel.setFrequency(data.hasEarfcn() ? formatFrequency(
                CellularUtils.downlinkEarfcnToFrequencyMhz(data.getEarfcn().getValue())) : "");
        viewModel.setUplinkReference(data.hasEarfcn()
                ? CellularUtils.lteUplinkReference(data.getEarfcn().getValue()) : null);

        // Set LTE band field with band number and name
        if (data.hasEarfcn())
        {
            int earfcn = data.getEarfcn().getValue();
            int bandNumber = CellularUtils.downlinkEarfcnToBand(earfcn);
            if (bandNumber != -1)
            {
                String bandName = CellularUtils.getLteBandName(bandNumber);
                if (bandName != null)
                {
                    viewModel.setLteBand(bandNumber + " (" + bandName + ")");
                } else
                {
                    viewModel.setLteBand(String.valueOf(bandNumber));
                }
            } else
            {
                viewModel.setLteBand("");
            }
        } else
        {
            viewModel.setLteBand("");
        }

        viewModel.setPci(data.hasPci() ? formatPci(data.getPci().getValue()) : "");
        viewModel.setBandwidth(bandwidthKhz > 0 ? CellularBandwidthUtils.formatBandwidthMhz(bandwidthKhz) : "");
        viewModel.setTa(data.hasTa() ? String.valueOf(data.getTa().getValue()) : "");
        viewModel.setCqi(data.hasCqi() ? String.valueOf(data.getCqi().getValue()) : "");

        viewModel.setSignalOne(data.hasRsrp() ? (int) data.getRsrp().getValue() : null);
        viewModel.setSignalTwo(data.hasRsrq() ? (int) data.getRsrq().getValue() : null);
        viewModel.setSignalThree(data.hasSnr() ? (int) data.getSnr().getValue() : null);
    }

    /**
     * Takes in the NR serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the NR serving cell record.
     */
    private void processNrServingCell(NrRecordData data, NrRecordWrapper wrapper, int bandwidthKhz)
    {
        viewModel.setNrServingCell(data);
        chartViewModel.recordSignal(CellularProtocol.NR,
                data.hasSsRsrp() ? (int) data.getSsRsrp().getValue() : null);

        setMccMncOnViewModel(data.hasPlmn() ? data.getPlmn().getValue() : null,
                data.hasMcc() ? data.getMcc().getValue() : null,
                data.hasMnc() ? data.getMnc().getValue() : null, wrapper.additionalPlmns);
        viewModel.setAreaCode(data.hasTac() ? String.valueOf(data.getTac().getValue()) : "");
        viewModel.setCellId(data.hasNci() ? data.getNci().getValue() : null);

        // Set NARFCN without band information
        viewModel.setChannelNumber(data.hasNarfcn() ? String.valueOf(data.getNarfcn().getValue()) : "");

        viewModel.setBand(CellularUtils.formatNrBands(wrapper.bands,
                data.hasNarfcn() ? data.getNarfcn().getValue() : -1));
        viewModel.setUplinkReference(data.hasNarfcn()
                ? CellularUtils.nrUplinkReference(wrapper.bands, data.getNarfcn().getValue()) : null);

        viewModel.setPci(data.hasPci() ? formatPci(data.getPci().getValue()) : "");

        viewModel.setFrequency(data.hasNarfcn() ? formatNrFrequency(data.getNarfcn().getValue()) : "");
        viewModel.setBandwidth(bandwidthKhz > 0 ? CellularBandwidthUtils.formatBandwidthMhz(bandwidthKhz) : "");

        viewModel.setTa(data.hasTa() ? String.valueOf(data.getTa().getValue()) : "");

        viewModel.setSignalOne(data.hasSsRsrp() ? (int) data.getSsRsrp().getValue() : null);
        viewModel.setSignalTwo(data.hasSsRsrq() ? (int) data.getSsRsrq().getValue() : null);
        viewModel.setSignalThree(data.hasSsSinr() ? (int) data.getSsSinr().getValue() : null);
    }

    /**
     * @return The PCI display value including the Primary and Secondary Sync Sequence breakdown,
     * e.g. "146 (2/48)".
     */
    private String formatPci(int pci)
    {
        final int primarySyncSequence = CalculationUtils.getPrimarySyncSequence(pci);
        final int secondarySyncSequence = CalculationUtils.getSecondarySyncSequence(pci);
        return pci + " (" + primarySyncSequence + "/" + secondarySyncSequence + ")";
    }

    /**
     * @return The NR frequency display value in MHz for the provided NARFCN (e.g. "3709.920 MHz"),
     * or an empty string when the NARFCN is not in a valid range.
     */
    private String formatNrFrequency(int narfcn)
    {
        return formatFrequency(CellularUtils.narfcnToFrequencyMhz(narfcn));
    }

    private String formatFrequency(double frequencyMhz)
    {
        if (frequencyMhz <= 0) return "";

        return getString(R.string.mhz_value_label, String.format(Locale.US, "%.3f", frequencyMhz));
    }

    /**
     * Builds the NR Secondary Cell card's view state for this scan: the live cell when the scan
     * contains one, the dimmed last-seen idle state while the phone remains on NSA with the NR
     * leg detached (on NSA the leg only attaches during active data), or null to hide the card.
     * <p>
     * Also publishes the idle card's "Last seen ..." line, which is kept out of the returned view
     * state so that its per-scan ticking does not rebuild the rest of the card.
     *
     * @param secondaryNrCell The SECONDARY_SERVING NR cell from this scan, or null when absent.
     * @param servingCellId   The serving cell the phone is attached to for this scan. A sighting
     *                        recorded under a different serving cell describes a place the user
     *                        has since left, so it is never held as idle.
     */
    private NrSecondaryCellViewState resolveNrSecondaryCellViewState(NrRecordWrapper secondaryNrCell,
                                                                     String servingCellId, int bandwidthKhz)
    {
        final long nowMs = SystemClock.elapsedRealtime();
        final NrSecondaryCellSighting sighting = viewModel.getLastNrSecondaryCellSighting();
        final boolean sightingUsable = sighting.isUsableFor(servingCellId);
        final long lastSeenAgeMs = sightingUsable ? nowMs - sighting.realtimeMs() : -1;

        final TelephonyStateUtils.NrCardState cardState = TelephonyStateUtils.resolveNrCardState(
                secondaryNrCell != null, currentNrMode, lastSeenAgeMs, NR_IDLE_MAX_AGE_MS);

        switch (cardState)
        {
            case ACTIVE:
                final NrSecondaryCellViewState state = buildNrSecondaryCellViewState(secondaryNrCell, bandwidthKhz);
                viewModel.setLastNrSecondaryCellSighting(
                        new NrSecondaryCellSighting(state, nowMs, servingCellId));
                viewModel.setNrLastSeenText("");
                return state;

            case IDLE:
                viewModel.setNrLastSeenText(
                        getString(R.string.nr_last_seen_format, formatAge(lastSeenAgeMs)));
                return sighting.state().asIdle();

            default:
                viewModel.setNrLastSeenText("");
                return null;
        }
    }

    /**
     * @return A short age display for the idle card's last-seen line, e.g. "42s" or "3m".
     */
    private String formatAge(long ageMs)
    {
        final long seconds = ageMs / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m";
    }

    /**
     * Builds the view state for the NR Secondary Cell details card from the NR record the device
     * reported as SECONDARY_SERVING (the 5G NSA data leg, or an NR CA SCell under SA).
     */
    private NrSecondaryCellViewState buildNrSecondaryCellViewState(NrRecordWrapper wrapper, int bandwidthKhz)
    {
        final NrRecordData data = ((NrRecord) wrapper.cellularRecord).getData();

        return new NrSecondaryCellViewState(
                CellularUtils.formatNrBands(wrapper.bands,
                        data.hasNarfcn() ? data.getNarfcn().getValue() : -1),
                data.hasNarfcn() ? formatNrFrequency(data.getNarfcn().getValue()) : "",
                data.hasPci() ? formatPci(data.getPci().getValue()) : "",
                data.hasNarfcn() ? String.valueOf(data.getNarfcn().getValue()) : "",
                data.hasSsRsrp() ? (int) data.getSsRsrp().getValue() : null,
                data.hasSsRsrq() ? (int) data.getSsRsrq().getValue() : null,
                data.hasSsSinr() ? (int) data.getSsSinr().getValue() : null,
                false, bandwidthKhz > 0 ? CellularBandwidthUtils.formatBandwidthMhz(bandwidthKhz) : "");
    }
    /**
     * Takes in the current group of UMTS neighbors, converts them to a {@link UmtsNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processGsmNeighbors(List<GsmRecordData> neighbors)
    {
        final TreeSet<GsmNeighbor> gsmNeighbors = neighbors.stream().map(data -> {
            GsmNeighbor.Builder builder = new GsmNeighbor.Builder();
            if (data.hasArfcn()) builder.arfcn(data.getArfcn().getValue());
            if (data.hasBsic()) builder.bsic(data.getBsic().getValue());
            if (data.hasSignalStrength()) builder.rssi((int) data.getSignalStrength().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setGsmNeighbors(gsmNeighbors);
    }

    /**
     * Takes in the current group of UMTS neighbors, converts them to a {@link UmtsNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processUmtsNeighbors(List<UmtsRecordData> neighbors)
    {
        final TreeSet<UmtsNeighbor> umtsNeighbors = neighbors.stream().map(data -> {
            UmtsNeighbor.Builder builder = new UmtsNeighbor.Builder();
            if (data.hasUarfcn()) builder.uarfcn(data.getUarfcn().getValue());
            if (data.hasPsc()) builder.psc(data.getPsc().getValue());
            if (data.hasRscp()) builder.rscp((int) data.getRscp().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setUmtsNeighbors(umtsNeighbors);
    }

    /**
     * Takes in the current group of LTE neighbors, converts them to an {@link LteNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processLteNeighbors(List<LteRecordData> neighbors)
    {
        final TreeSet<LteNeighbor> lteNeighbors = neighbors.stream().map(data -> {
            LteNeighbor.Builder builder = new LteNeighbor.Builder();
            if (data.hasEarfcn()) builder.earfcn(data.getEarfcn().getValue());
            if (data.hasPci()) builder.pci(data.getPci().getValue());
            if (data.hasRsrp()) builder.rsrp((int) data.getRsrp().getValue());
            if (data.hasRsrq()) builder.rsrq((int) data.getRsrq().getValue());
            if (data.hasTa()) builder.ta(data.getTa().getValue());
            if (data.hasSnr()) builder.sinr((int) data.getSnr().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setLteNeighbors(List.copyOf(lteNeighbors));
    }

    /**
     * Takes in the current group of LTE neighbors, converts them to an {@link NrNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processNrNeighbors(List<NrRecordData> neighbors)
    {
        final TreeSet<NrNeighbor> nrNeighbors = neighbors.stream().map(data -> {
            NrNeighbor.Builder builder = new NrNeighbor.Builder();
            if (data.hasNarfcn()) builder.narfcn(data.getNarfcn().getValue());
            if (data.hasPci()) builder.pci(data.getPci().getValue());
            if (data.hasSsRsrp()) builder.ssRsrp((int) data.getSsRsrp().getValue());
            if (data.hasSsRsrq()) builder.ssRsrq((int) data.getSsRsrq().getValue());
            if (data.hasSsSinr()) builder.ssSinr((int) data.getSsSinr().getValue());
            if (data.hasCsiSinr()) builder.csiSinr((int) data.getCsiSinr().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setNrNeighbors(List.copyOf(nrNeighbors));
    }
    /**
     * Builds the two-tone pill text: the label in the faded label color, the separator and value in
     * the pill's normal text color, matching the app's faded-label/bright-value convention.
     */
    private CharSequence buildPillText(@StringRes int labelRes, String value)
    {
        final String label = getString(labelRes);
        final SpannableStringBuilder builder = new SpannableStringBuilder(label);
        builder.append(TECH_SEPARATOR).append(value);

        final Context context = getContext();
        if (context != null)
        {
            builder.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.fadedText)),
                    0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }
    /**
     * Displays a dialog explaining what a 5G NR secondary cell is, plus the terms shown on the
     * card. Deliberately not the generic NR terms dialog: that one documents fields (MCC/MNC, TAC,
     * CID, TA) this card does not display.
     */
    private void showNrDetailsInfoDialog()
    {
        if (getContext() == null) return;
        FragmentDialogs.showCellularInfo(getParentFragmentManager(),
                getString(R.string.nr_secondary_info_description),
                getText(R.string.nr_secondary_cell_explanation));
    }

    /**
     * BroadcastReceiver to detect airplane mode changes.
     * This is independent of the PhoneStateListener and provides immediate UI updates.
     */
    private class AirplaneModeReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction()))
            {
                boolean isAirplaneModeOn = intent.getBooleanExtra("state", false);
                nrAirplaneMode = isAirplaneModeOn;
                Timber.d("Airplane mode broadcast received: %s, subscriptionId: %d",
                        isAirplaneModeOn ? "ON" : "OFF", subscriptionId);

                // Update view model
                viewModel.setAirplaneModeActive(isAirplaneModeOn);

                // Clear cellular UI if airplane mode is on
                if (isAirplaneModeOn)
                {
                    Timber.d("Clearing cellular UI due to airplane mode");
                    clearCellularUi();
                }
            }
        }
    }

    /**
     * Helper method to check if airplane mode is currently enabled.
     *
     * @param context The context to use for checking the setting.
     * @return True if airplane mode is on, false otherwise.
     */
    private static boolean isAirplaneModeOn(Context context)
    {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    /**
     * Sets the MCC and MNC on the view model, preferring the PLMN string (which preserves
     * leading zeros) when available.
     */
    private void setMccMncOnViewModel(String plmn, Integer mcc, Integer mnc)
    {
        setMccMncOnViewModel(plmn, mcc, mnc, List.of());
    }

    private void setMccMncOnViewModel(String plmn, Integer mcc, Integer mnc, List<String> additionalPlmns)
    {
        String identity = plmn == null ? "" : plmn.replace("-", "");
        if (!OperatorUtil.isValidPlmn(identity) && mcc != null && mnc != null
                && mcc >= 1 && mcc <= 999 && mnc >= 0 && mnc <= 999)
            identity = String.format(Locale.ROOT, "%03d%02d", mcc, mnc);
        final String displayedIdentity = formatPlmn(identity);
        viewModel.setMcc(!displayedIdentity.isEmpty() ? identity.substring(0, 3)
                : mcc != null ? String.valueOf(mcc) : "");
        viewModel.setMnc(!displayedIdentity.isEmpty() ? identity.substring(3)
                : mnc != null ? String.valueOf(mnc) : "");

        final List<String> broadcast = new ArrayList<>();
        if (!displayedIdentity.isEmpty()) broadcast.add(displayedIdentity);
        additionalPlmns.stream().map(NetworkDetailsFragment::formatPlmn)
                .filter(value -> !value.isEmpty() && !value.equals(displayedIdentity))
                .distinct().sorted().forEach(broadcast::add);
        viewModel.setCellPlmns(String.join(" · ", broadcast));
    }

    private static String formatPlmn(String plmn)
    {
        return OperatorUtil.isValidPlmn(plmn) ? plmn.substring(0, 3) + "/" + plmn.substring(3) : "";
    }
}
