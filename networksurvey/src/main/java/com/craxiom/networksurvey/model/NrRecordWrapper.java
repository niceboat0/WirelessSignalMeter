package com.craxiom.networksurvey.model;

import com.craxiom.messaging.NrRecord;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Wraps the {@link NrRecord} so that we can include the bands array. This allows us to display the
 * bands in the UI. The bands array is in-memory only and is never serialized with the record.
 */
public class NrRecordWrapper extends CellularRecordWrapper
{
    public final int[] bands;
    public final long measuredAtElapsedMs;

    public NrRecordWrapper(NrRecord nrRecord, int[] bands)
    {
        this(nrRecord, bands, Collections.emptyList());
    }

    public NrRecordWrapper(NrRecord nrRecord, int[] bands, Collection<String> additionalPlmns)
    {
        this(nrRecord, bands, additionalPlmns, -1);
    }

    public NrRecordWrapper(NrRecord nrRecord, int[] bands, Collection<String> additionalPlmns, long measuredAtElapsedMs)
    {
        super(CellularProtocol.NR, nrRecord, additionalPlmns);

        this.bands = bands;
        this.measuredAtElapsedMs = measuredAtElapsedMs;
    }
}
