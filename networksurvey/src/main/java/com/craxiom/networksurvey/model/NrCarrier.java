package com.craxiom.networksurvey.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NrCarrier(Identity identity, Role role, int index, String band, int bandwidthMhz,
                        boolean recentDownlink, Signal signal, long measuredAtElapsedMs,
                        boolean lastObservation, CaState downlinkState, CaState uplinkState)
{
    public static final long ANDROID_MAX_AGE_MS = 15_000;

    public NrCarrier(Identity identity, Role role, int index, String band, int bandwidthMhz,
                     boolean recentDownlink, Signal signal, long measuredAtElapsedMs, boolean lastObservation)
    {
        this(identity, role, index, band, bandwidthMhz, recentDownlink, signal, measuredAtElapsedMs,
                lastObservation, CaState.UNKNOWN, CaState.UNKNOWN);
    }

    public NrCarrier(Identity identity, Role role, int index, String band, int bandwidthMhz,
                     boolean recentDownlink, Signal signal)
    {
        this(identity, role, index, band, bandwidthMhz, recentDownlink, signal, 0, false);
    }

    public NrCarrier(Identity identity, Role role, int index, String band, int bandwidthMhz, boolean recentDownlink)
    {
        this(identity, role, index, band, bandwidthMhz, recentDownlink, Signal.EMPTY);
    }

    public enum Role { PRIMARY, SECONDARY, SERVING }

    public enum CaState { UNKNOWN, DECONFIGURED, CONFIGURED, ACTIVATED }

    public record Signal(Double rsrp, Double rsrq, Double sinr)
    {
        public static final Signal EMPTY = new Signal(null, null, null);

        public static Signal ss(double rsrp, double rsrq, double sinr)
        {
            return new Signal(inRange(rsrp, -156, -31), inRange(rsrq, -43, 20), inRange(sinr, -23, 40));
        }

        private static Double inRange(double value, double min, double max)
        {
            return value >= min && value <= max ? value : null;
        }
    }

    public record Identity(int pci, int arfcn)
    {
        public boolean isValid()
        {
            return pci >= 0 && pci <= 1007 && arfcn > 0 && arfcn <= 3_279_165;
        }
    }

    public static List<NrCarrier> androidSnapshot(List<NrCarrier> serving)
    {
        final Map<Identity, NrCarrier> unique = new LinkedHashMap<>();
        for (NrCarrier row : serving) {
            if (row.identity.isValid()) unique.merge(row.identity, row, (first, second) ->
                    second.role == Role.PRIMARY || (first.role == second.role && first.band.isEmpty())
                            ? second : first);
        }
        final List<NrCarrier> sorted = new ArrayList<>(unique.values());
        sorted.sort((a, b) -> {
            final int roleOrder = a.role.compareTo(b.role);
            if (roleOrder != 0) return roleOrder;
            final int channelOrder = Integer.compare(a.identity.arfcn, b.identity.arfcn);
            return channelOrder != 0 ? channelOrder : Integer.compare(a.identity.pci, b.identity.pci);
        });
        return Collections.unmodifiableList(sorted);
    }

    public static Role androidRole(boolean nrAnchor, boolean selected, boolean primary, boolean secondary)
    {
        if (nrAnchor) return selected ? Role.PRIMARY : secondary ? Role.SECONDARY : null;
        return primary || secondary ? Role.SERVING : null;
    }

    public static boolean isFreshAndroid(long measuredAt, long now)
    {
        return measuredAt > 0 && now >= measuredAt && now - measuredAt <= ANDROID_MAX_AGE_MS;
    }

    public NrCarrier asLastObservation()
    {
        return new NrCarrier(identity, role, index, band, bandwidthMhz, false, Signal.EMPTY,
                measuredAtElapsedMs, true);
    }

    public static int totalBandwidthMhz(List<NrCarrier> carriers)
    {
        if (carriers.isEmpty() || carriers.stream().anyMatch(row -> row.bandwidthMhz <= 0)) return 0;
        return carriers.stream().mapToInt(NrCarrier::bandwidthMhz).sum();
    }
}
