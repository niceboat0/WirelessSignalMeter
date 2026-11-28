package com.craxiom.networksurvey.util;

import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.SignalStrength;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prefers the subscription's SignalStrength LTE RSSNR, as in android-network-survey.
 * CellInfo/vendor values are compatibility fallbacks. Diagnostics contain no cell identity.
 */
public final class AndroidLteSinrReader
{
    public static final String POLICY = "network_survey_signal_strength_first_v1";
    private static final int UNAVAILABLE = Integer.MAX_VALUE;
    private static final int MAX_SAMPLES = 128;
    private static final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public record Sample(long timeMs, long elapsedMs, int subscriptionId, boolean registered,
                         int connectionStatus, int eligibleCells, long cellTimestampMs,
                         int cellRssnr, int cellSinr, Long snapshotTimestampMs,
                         List<Integer> snapshotRssnr, List<Integer> snapshotSinr,
                         String snapshotDecision, String snapshotError,
                         int selectedSinr, String selectedSource, String taskId, String clockId)
    {
    }

    private AndroidLteSinrReader()
    {
    }

    public static int read(CellInfoLte cell, SignalStrength snapshot, int subscriptionId,
                           int eligibleCells, long nowMs)
    {
        return read(cell, snapshot, subscriptionId, eligibleCells, nowMs, null, null);
    }

    public static int read(CellInfoLte cell, SignalStrength snapshot, int subscriptionId,
                           int eligibleCells, long nowMs, String taskId, String clockId)
    {
        CellSignalStrengthLte strength = cell.getCellSignalStrength();
        int raw = strength.getRssnr();
        int cellSinr = LteSinrUtils.read(raw, strength);
        int selected = cellSinr;
        String source = LteSinrUtils.isValid(raw) ? "cell_info"
                : LteSinrUtils.isValid(cellSinr) ? "cell_info_compat" : "unavailable";
        int connectionStatus = cell.getCellConnectionStatus();
        boolean primary = cell.isRegistered()
                && connectionStatus != CellInfo.CONNECTION_SECONDARY_SERVING;
        long cellTimestamp = cell.getTimestampMillis();
        Long snapshotTimestamp = null;
        List<Integer> snapshotRssnr = new ArrayList<>();
        List<Integer> snapshotSinr = new ArrayList<>();
        String decision = snapshot == null ? "no_snapshot" : "not_primary";
        String error = null;

        if (snapshot != null && primary)
        {
            try
            {
                List<CellSignalStrengthLte> strengths = snapshot.getCellSignalStrengths(CellSignalStrengthLte.class);
                // Upstream uses the first LTE entry, even when CellInfo already reports 0 or 1.
                if (!strengths.isEmpty())
                {
                    int candidateRaw = strengths.get(0).getRssnr();
                    if (LteSinrUtils.isReported(candidateRaw))
                    {
                        selected = candidateRaw;
                        source = "signal_strength";
                        decision = "accepted";
                    }
                }
                if (!source.equals("signal_strength"))
                {
                    int fallback = strengths.isEmpty() ? UNAVAILABLE
                            : LteSinrUtils.read(UNAVAILABLE, strengths.get(0));
                    if (!LteSinrUtils.isValid(fallback))
                    {
                        fallback = LteSinrUtils.readSnapshot(snapshot);
                    }
                    decision = LteSinrUtils.isValid(fallback) ? "accepted_compat" : "snapshot_no_sinr";
                    if (LteSinrUtils.isValid(fallback))
                    {
                        selected = fallback;
                        source = "signal_strength_compat";
                    }
                }
                for (CellSignalStrengthLte candidate : strengths)
                {
                    int candidateRaw = candidate.getRssnr();
                    snapshotRssnr.add(candidateRaw);
                    snapshotSinr.add(LteSinrUtils.isReported(candidateRaw) ? candidateRaw : UNAVAILABLE);
                }
                // Timestamps are diagnostic only: upstream does not reject asynchronous modem samples.
                snapshotTimestamp = snapshot.getTimestampMillis();
            } catch (RuntimeException | LinkageError failure)
            {
                // Optional vendor snapshot access must not discard valid CellInfo measurements.
                decision = "snapshot_error";
                error = failure.getClass().getSimpleName();
            }
        }
        if (cell.isRegistered())
        {
            add(new Sample(System.currentTimeMillis(), nowMs, subscriptionId, true,
                    connectionStatus, eligibleCells, cellTimestamp, raw, cellSinr,
                    snapshotTimestamp,
                    Collections.unmodifiableList(new ArrayList<>(snapshotRssnr)),
                    Collections.unmodifiableList(new ArrayList<>(snapshotSinr)),
                    decision, error, selected, source, taskId, clockId));
        }
        return selected;
    }

    private static synchronized void add(Sample sample)
    {
        if (samples.size() == MAX_SAMPLES) samples.removeFirst();
        samples.addLast(sample);
    }

    public static synchronized List<Sample> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(samples));
    }

    public static synchronized void clear()
    {
        samples.clear();
    }
}
