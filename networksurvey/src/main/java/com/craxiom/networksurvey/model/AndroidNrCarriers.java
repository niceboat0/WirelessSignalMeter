package com.craxiom.networksurvey.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Holds identities from the last coherent multi-carrier batch, never stale signal values. */
public final class AndroidNrCarriers
{
    private static final long RETENTION_MS = 30_000;
    private static final int DIAGNOSTIC_LIMIT = 96;
    private static final ArrayDeque<Batch> DIAGNOSTICS = new ArrayDeque<>();

    public record Anchor(boolean nr, String plmn, long cellId, int pci, int channel)
    {
        public boolean isValid()
        {
            final long maxCellId = nr ? (1L << 36) - 1 : (1L << 28) - 1;
            return cellId > 0 && cellId <= maxCellId && cellId != Integer.MAX_VALUE
                    && pci >= 0 && pci <= (nr ? 1007 : 503)
                    && channel >= (nr ? 1 : 0) && channel <= (nr ? 3_279_165 : 262_143);
        }
    }

    public record Batch(long timeMs, long elapsedMs, int subscriptionId, String anchorRat, String nrMode,
                        int nrRows, int servingNrRows, int freshRows, int retainedRows, long oldestFreshAgeMs) {}

    private Anchor anchor;
    private List<NrCarrier> lastMulti = Collections.emptyList();

    public synchronized void clear()
    {
        anchor = null;
        lastMulti = Collections.emptyList();
    }

    public synchronized List<NrCarrier> update(Anchor current, List<NrCarrier> fresh, long now)
    {
        if (current == null || !current.isValid()) {
            clear();
            return fresh;
        }
        if (!current.equals(anchor)) {
            clear();
            anchor = current;
        }
        if (fresh.size() >= 2) {
            lastMulti = fresh;
            return fresh;
        }

        // A newly reported NR cell outside the previous set might be a different PSCell in NSA.
        // Do not manufacture a multi-carrier group by combining it with the old one.
        if (fresh.stream().anyMatch(row -> lastMulti.stream().noneMatch(old -> old.identity().equals(row.identity())))) {
            lastMulti = Collections.emptyList();
            return fresh;
        }
        lastMulti = lastMulti.stream().filter(row -> retainable(row, now)).collect(Collectors.toList());
        if (lastMulti.isEmpty()) return fresh;

        final List<NrCarrier> display = new ArrayList<>(fresh);
        for (NrCarrier old : lastMulti) {
            if (fresh.stream().noneMatch(row -> row.identity().equals(old.identity()))) {
                display.add(old.asLastObservation());
            }
        }
        return NrCarrier.androidSnapshot(display);
    }

    /** Also applied while no new scan callbacks are arriving. */
    public static List<NrCarrier> displayAt(List<NrCarrier> rows, long now)
    {
        return rows.stream().filter(row -> retainable(row, now))
                .map(row -> row.lastObservation() || !NrCarrier.isFreshAndroid(row.measuredAtElapsedMs(), now)
                        ? row.asLastObservation() : row)
                .collect(Collectors.toList());
    }

    private static boolean retainable(NrCarrier row, long now)
    {
        final long t = row.measuredAtElapsedMs();
        return t > 0 && now >= t && now - t <= RETENTION_MS;
    }

    public static synchronized void record(Batch batch)
    {
        while (DIAGNOSTICS.size() >= DIAGNOSTIC_LIMIT) DIAGNOSTICS.removeFirst();
        DIAGNOSTICS.addLast(batch);
    }

    public static synchronized List<Batch> diagnostics()
    {
        return Collections.unmodifiableList(new ArrayList<>(DIAGNOSTICS));
    }
}
