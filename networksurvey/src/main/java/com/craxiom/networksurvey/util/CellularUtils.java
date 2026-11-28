package com.craxiom.networksurvey.util;

import com.craxiom.messaging.CdmaRecord;
import com.craxiom.messaging.CdmaRecordData;
import com.craxiom.messaging.ConnectionStatus;
import com.craxiom.messaging.GsmRecord;
import com.craxiom.messaging.GsmRecordData;
import com.craxiom.messaging.LteRecord;
import com.craxiom.messaging.LteRecordData;
import com.craxiom.messaging.NrRecord;
import com.craxiom.messaging.NrRecordData;
import com.craxiom.messaging.UmtsRecord;
import com.craxiom.messaging.UmtsRecordData;
import com.craxiom.networksurvey.data.api.Tower;
import com.craxiom.networksurvey.model.CellularProtocol;
import com.craxiom.networksurvey.model.CellularRecordWrapper;
import com.craxiom.networksurvey.model.NrRecordWrapper;
import com.craxiom.networksurvey.ui.cellular.model.ServingCellInfo;
import com.craxiom.networksurvey.ui.cellular.model.ServingSignalInfo;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper methods for working with cellular networks.
 */
public class CellularUtils
{
    /**
     * From 3GPP TS 36.101, Table E-UTRA Operating Bands
     */
    private static final int[][] DOWNLINK_LTE_BANDS = {
            // Band, first DL EARFCN, last DL EARFCN, F_DL_low in 0.1 MHz (-1 where unavailable)
            {1, 0, 599, 21100},
            {2, 600, 1199, 19300},
            {3, 1200, 1949, 18050},
            {4, 1950, 2399, 21100},
            {5, 2400, 2649, 8690},
            {6, 2650, 2749, 8750},
            {7, 2750, 3449, 26200},
            {8, 3450, 3799, 9250},
            {9, 3800, 4149, 18449},
            {10, 4150, 4749, 21100},
            {11, 4750, 4949, 14759},
            {12, 5010, 5179, 7290},
            {13, 5180, 5279, 7460},
            {14, 5280, 5379, 7580},
            {17, 5730, 5849, 7340},
            {18, 5850, 5999, 8600},
            {19, 6000, 6149, 8750},
            {20, 6150, 6449, 7910},
            {21, 6450, 6599, 14959},
            {22, 6600, 7399, 35100},
            {23, 7500, 7699, 21800},
            {24, 7700, 8039, 15250},
            {25, 8040, 8689, 19300},
            {26, 8690, 9039, 8590},
            {27, 9040, 9209, 8520},
            {28, 9210, 9659, 7580},
            {29, 9660, 9769, 7170},
            {30, 9770, 9869, 23500},
            {31, 9870, 9919, 4625},
            {32, 9920, 10359, 14520},
            {33, 36000, 36199, 19000},
            {34, 36200, 36349, 20100},
            {35, 36350, 36949, 18500},
            {36, 36950, 37549, 19300},
            {37, 37550, 37749, 19100},
            {38, 37750, 38249, 25700},
            {39, 38250, 38649, 18800},
            {40, 38650, 39649, 23000},
            {41, 39650, 41589, 24960},
            {42, 41590, 43589, 34000},
            {43, 43590, 45589, 36000},
            {44, 45590, 46589, 7030},
            {45, 46590, 46789, 14470},
            {46, 46790, 54539, 51500},
            {47, 54540, 55239, 58550},
            {48, 55240, 56739, 35500},
            {49, 56740, 58239, 35500},
            {50, 58240, 59089, 14320},
            {51, 59090, 59139, 14270},
            {52, 59140, 60139, 33000},
            {53, 60140, 60254, 24835},
            {54, 60255, 60304, 16700},
            {65, 65536, 66435, 21100},
            {66, 66436, 67335, 21100},
            {67, 67336, 67535, 7380},
            {68, 67536, 67835, 7530},
            {69, 67836, 68335, 25700},
            {70, 68336, 68585, 19950},
            {71, 68586, 68935, 6170},
            {72, 68936, 68985, 4610},
            {73, 68986, 69035, 4600},
            {74, 69036, 69465, 14750},
            {75, 69466, 70315, 14320},
            {76, 70316, 70365, 14270},
            {85, 70366, 70545, 7280},
            {87, 70546, 70595, 4200},
            {88, 70596, 70645, 4220},
            {103, 70646, 70655, 7570},
            {106, 70656, 70705, 9350},
            {108, 70706, 70755, -1},
    };

    /**
     * From 3GPP TS 38.104 Table 5.4.2.3-1, the applicable downlink NR-ARFCN range per NR
     * operating band. Values cross-checked against srsRAN's band_helper.cpp and the NS Analytics
     * web app's cellular-band-utils.ts DOWNLINK_NR_BANDS table (keep these in sync).
     * <p>
     * Unlike the LTE table above, these ranges overlap heavily, which is why
     * {@link #downlinkNarfcnToBand(int)} refuses to answer for a NARFCN in more than one band.
     * <p>
     * Bands are deliberately omitted when including them would shadow a band that operators
     * actually deploy while adding no resolvable range of their own. Every omission below either
     * cannot appear as a downlink NARFCN at all, or has no known commercial deployment:
     * <ul>
     *   <li>SUL bands (n80-n84, n86, n89, n95, n97-n99): uplink only, a downlink NARFCN can never be one</li>
     *   <li>n85: FDD rather than SUL, but undeployed, and including it would swallow n12 entirely</li>
     *   <li>NTN bands (n254-n256): satellite service, not reported for terrestrial cells</li>
     *   <li>n90: spectrum-identical duplicate of n41 (it exists for UE capability signaling)</li>
     *   <li>n105: no known commercial deployments, and it would permanently shadow n71</li>
     *   <li>n26: no confirmed NR deployments; including it shadows both n5 and n18</li>
     *   <li>n65: no known commercial deployments (operators use n1 at 2100 MHz); it has the
     *       identical range to n66, so including it makes n66 unresolvable everywhere</li>
     *   <li>n67: SDL, undeployed (700 SDL lots largely went unsold); it shadows n13</li>
     *   <li>n47: V2X sidelink, so no gNB transmits a downlink there; it shadows part of n46</li>
     *   <li>n75, n76, n91-n94: SDL and undeployed; they shadow n50 and n51</li>
     * </ul>
     * <p>
     * Some overlaps cannot be removed this way because both bands are real and deployed. n78 sits
     * entirely inside n77, n48 inside both, n1 inside n66, n38 inside n41, and n261 inside n257,
     * so a NARFCN in those shared ranges stays unresolved by design rather than being guessed at.
     * Preferring the narrower band would be wrong: US C-band at 3700-3800 MHz is n77 but falls in
     * n78's range, so a narrower-wins rule would confidently mislabel it.
     */
    private static final int[][] DOWNLINK_NR_BANDS = {
            // Band, Lower bound of NARFCN, Upper bound of NARFCN
            {1, 422000, 434000},
            {2, 386000, 398000},
            {3, 361000, 376000},
            {5, 173800, 178800},
            {7, 524000, 538000},
            {8, 185000, 192000},
            {12, 145800, 149200},
            {13, 149200, 151200},
            {14, 151600, 153600},
            {18, 172000, 175000},
            {20, 158200, 164200},
            {24, 305000, 311800},
            {25, 386000, 399000},
            {28, 151600, 160600},
            {29, 143400, 145600}, // SDL
            {30, 470000, 472000},
            {34, 402000, 405000},
            {38, 514000, 524000},
            {39, 376000, 384000},
            {40, 460000, 480000},
            {41, 499200, 537999},
            {46, 743334, 795000},
            {48, 636667, 646666},
            {50, 286400, 303400},
            {51, 285400, 286400},
            {53, 496700, 499000},
            {66, 422000, 440000},
            {70, 399000, 404000},
            {71, 123400, 130400},
            {74, 295000, 303600},
            {77, 620000, 680000},
            {78, 620000, 653333},
            {79, 693334, 733333},
            {96, 795000, 875000},
            {100, 183880, 185000},
            {101, 380000, 382000},
            {102, 796334, 828333},
            {104, 828334, 875000},
            // FR2 (mmWave)
            {257, 2054166, 2104165},
            {258, 2016667, 2070832},
            {259, 2270833, 2337499},
            {260, 2229166, 2279165},
            {261, 2070833, 2084999},
            {262, 2399166, 2415832},
            {263, 2564083, 2794249},
    };

    // FDD bands from TS 36.101 table 5.7.3-1. Band, first UL EARFCN, F_UL_low in 0.1 MHz.
    // A fourth value gives the UL raster size when it is shorter than the DL raster.
    private static final int[][] LTE_PAIRED_UPLINK = {
            {1, 18000, 19200}, {2, 18600, 18500}, {3, 19200, 17100}, {4, 19950, 17100},
            {5, 20400, 8240}, {6, 20650, 8300}, {7, 20750, 25000}, {8, 21450, 8800},
            {9, 21800, 17499}, {10, 22150, 17100}, {11, 22750, 14279}, {12, 23010, 6990},
            {13, 23180, 7770}, {14, 23280, 7880}, {17, 23730, 7040}, {18, 23850, 8150},
            {19, 24000, 8300}, {20, 24150, 8320}, {21, 24450, 14479}, {22, 24600, 34100},
            {23, 25500, 20000}, {24, 25700, 16265}, {25, 26040, 18500}, {26, 26690, 8140},
            {27, 27040, 8070}, {28, 27210, 7030}, {30, 27660, 23050}, {31, 27760, 4525},
            {65, 131072, 19200}, {66, 131972, 17100, 700}, {68, 132672, 6980},
            {70, 132972, 16950, 150}, {71, 133122, 6630}, {72, 133472, 4510},
            {73, 133522, 4500}, {74, 133572, 14270}, {85, 134002, 6980},
            {87, 134182, 4100}, {88, 134232, 4120}, {103, 134282, 7870},
    };

    // TS 38.104 table 5.4.2.3-1. Band, first/last DL N_REF, first/last UL N_REF.
    private static final int[][] NR_PAIRED_UPLINK = {
            {1, 422000, 434000, 384000, 396000}, {2, 386000, 398000, 370000, 382000},
            {3, 361000, 376000, 342000, 357000}, {5, 173800, 178800, 164800, 169800},
            {7, 524000, 538000, 500000, 514000}, {8, 185000, 192000, 176000, 183000},
            {12, 145800, 149200, 139800, 143200}, {13, 149200, 151200, 155400, 157400},
            {14, 151600, 153600, 157600, 159600}, {18, 172000, 175000, 163000, 166000},
            {20, 158200, 164200, 166400, 172400}, {24, 305000, 311800, 325300, 332100},
            {25, 386000, 399000, 370000, 383000}, {26, 171800, 178800, 162800, 169800},
            {28, 151600, 160600, 140600, 149600}, {30, 470000, 472000, 461000, 463000},
            {31, 92500, 93500, 90500, 91500}, {65, 422000, 440000, 384000, 402000},
            {66, 422000, 440000, 342000, 356000}, {70, 399000, 404000, 339000, 342000},
            {71, 123400, 130400, 132600, 139600}, {72, 92200, 93200, 90200, 91200},
            {74, 295000, 303600, 285400, 294000}, {85, 145600, 149200, 139600, 143200},
            {91, 285400, 286400, 166400, 172400}, {92, 286400, 303400, 166400, 172400},
            {93, 285400, 286400, 176000, 183000}, {94, 286400, 303400, 176000, 183000},
            {100, 183880, 185000, 174880, 176000}, {105, 122400, 130400, 132600, 140600},
    };

    public record UplinkReference(String duplex, int arfcn, double mhz) {}

    /**
     * Gets the band name for a given LTE band number.
     * The band names are based on common frequency designations and regional usage.
     *
     * @param bandNumber The LTE band number (e.g., 1, 2, 3, etc.).
     * @return The band name, or null if the band number is not recognized.
     */
    public static String getLteBandName(int bandNumber)
    {
        return switch (bandNumber)
        {
            case 1 -> "2100 MHz";
            case 2 -> "1900 PCS";
            case 3 -> "1800+";
            case 4 -> "AWS-1";
            case 5 -> "850";
            case 6 -> "850 Japan";
            case 7 -> "2600";
            case 8 -> "900 GSM";
            case 9 -> "1800";
            case 10 -> "AWS-3";
            case 11 -> "1500 Lower";
            case 12 -> "700 a";
            case 13 -> "700 c";
            case 14 -> "700 PS";
            case 17 -> "700 b";
            case 18 -> "800 Lower";
            case 19 -> "800 Upper";
            case 20 -> "800 DD";
            case 21 -> "1500 Upper";
            case 22 -> "3500";
            case 23 -> "2000 S-band";
            case 24 -> "1600 L-band";
            case 25 -> "1900+";
            case 26 -> "850+";
            case 27 -> "800 SMR";
            case 28 -> "700 APT";
            case 30 -> "2300 WCS";
            case 31 -> "450";
            case 32 -> "1500 L-band";
            case 33 -> "1900 TDD";
            case 34 -> "2000 TDD";
            case 35 -> "1900 TDD";
            case 36 -> "1900 TDD";
            case 37 -> "1900 TDD";
            case 38 -> "2600 TDD";
            case 39 -> "1900 TDD";
            case 40 -> "2300 TDD";
            case 41 -> "2500 TDD";
            case 42 -> "3400 TDD";
            case 43 -> "3600 TDD";
            case 44 -> "700 TDD";
            case 45 -> "1400 TDD";
            case 46 -> "5200 TDD";
            case 47 -> "5900 TDD";
            case 48 -> "3550 CBRS";
            case 49 -> "3550 TDD";
            case 50 -> "1500 TDD";
            case 51 -> "1500 TDD";
            case 52 -> "3300 TDD";
            case 53 -> "2300 TDD";
            case 65 -> "2100+";
            case 66 -> "AWS";
            case 67 -> "700 EU";
            case 68 -> "700 ME";
            case 70 -> "AWS-4";
            case 71 -> "600";
            case 72 -> "450 PMR/PAMR";
            case 73 -> "450 APAC";
            case 74 -> "L-band";
            case 85 -> "700 a+";
            case 87 -> "410";
            case 88 -> "410+";
            case 103 -> "NB-IoT";
            case 106 -> "900";
            case 111 -> "HD-1800";
            default -> null;
        };
    }

    /**
     * Gets the band name for a given 5G NR band number.
     * The band names are based on the frequency designations from RF wireless specifications.
     *
     * @param bandNumber The NR band number (e.g., 1, 2, 3, etc.).
     * @return The band name, or null if the band number is not recognized.
     */
    public static String getNrBandName(int bandNumber)
    {
        return switch (bandNumber)
        {
            case 1 -> "2100";
            case 2 -> "1900 PCS";
            case 3 -> "1800";
            case 5 -> "850";
            case 7 -> "2600";
            case 8 -> "900 GSM";
            case 12 -> "700 a";
            case 13 -> "700 c";
            case 14 -> "700 PS";
            case 18 -> "800 Lower";
            case 20 -> "800";
            case 24 -> "1600 L";
            case 25 -> "1900+";
            case 26 -> "850+";
            case 28 -> "700 APT";
            case 29 -> "700 d";
            case 30 -> "2300 WCS";
            case 31 -> "450";
            case 34 -> "TD 2000";
            case 38 -> "TD 2600";
            case 39 -> "TD 1900+";
            case 40 -> "TD 2300";
            case 41 -> "TD 2600+";
            case 46 -> "TD Unlicensed";
            case 47 -> "TD V2X";
            case 48 -> "TD 3600";
            case 50 -> "TD 1500+";
            case 51 -> "TD 1500-";
            case 53 -> "TD 2500";
            case 54 -> "TD 1700";
            case 65 -> "2100+";
            case 66 -> "AWS";
            case 67 -> "700 EU";
            case 68 -> "700 ME";
            case 70 -> "AWS-4";
            case 71 -> "600";
            case 72 -> "450 PMR/PAMR";
            case 74 -> "L-band";
            case 75 -> "DL 1500+";
            case 76 -> "DL 1500-";
            case 77 -> "TD 3700";
            case 78 -> "TD 3500";
            case 79 -> "TD 4700";
            case 80 -> "SUL 1800";
            case 81 -> "SUL 900";
            case 82 -> "SUL 800";
            case 83 -> "SUL 700";
            case 84 -> "SUL 2100";
            case 85 -> "SUL 700 a";
            case 86 -> "SUL 1700";
            case 89 -> "SUL 850";
            case 90 -> "TD 2500";
            case 91 -> "L-band 1500";
            case 92 -> "L-band 1500";
            case 93 -> "L-band 1500";
            case 94 -> "L-band 1500";
            case 95 -> "DL 2100";
            case 96 -> "TD L-band";
            case 97 -> "S-band 2300";
            case 98 -> "S-band 1900";
            case 99 -> "L-band 1600";
            case 100 -> "TD 900";
            case 101 -> "1900";
            case 102 -> "TD 5900+";
            case 104 -> "TD 6400+";
            case 105 -> "TD 600";
            case 106 -> "TD 900";
            case 109 -> "1900";
            case 110 -> "TD 700";
            case 256 -> "NTN 2100";
            case 257 -> "28 GHz";
            case 258 -> "26 GHz";
            case 259 -> "41 GHz";
            case 260 -> "39 GHz";
            case 261 -> "28 GHz";
            case 262 -> "47 GHz";
            default -> null;
        };
    }

    /**
     * Converts 5G NR ARFCN to frequency in MHz according to 3GPP TS 38.104 specification.
     * The formula is: F_REF = F_REF-Offs + (N_REF - N_REF-Offs) * Δf
     * <p>
     * Resource: <a href="https://5g-tools.com/5g-nr-arfcn-calculator/">5G NARFCN Calculator</a>
     *
     * @param narfcn The NR-ARFCN (Absolute Radio Frequency Channel Number) to convert.
     * @return The frequency in MHz, or -1.0 if the NARFCN is not in a valid range.
     */
    public static double narfcnToFrequencyMhz(int narfcn)
    {
        if (narfcn < 0)
        {
            return -1.0;
        }

        // 3GPP TS 38.104 Table 5.4.2.1-1: Global frequency raster parameters for NR
        if (narfcn <= 599999)
        {
            // Range 1: 0 ≤ ARFCN ≤ 599,999
            // Δf = 5 kHz, F_REF-Offs = 0, N_REF-Offs = 0
            return narfcn * 0.005; // 5 kHz = 0.005 MHz
        } else if (narfcn <= 2016666)
        {
            // Range 2: 600,000 ≤ ARFCN ≤ 2,016,666
            // Δf = 15 kHz, F_REF-Offs = 3000 MHz, N_REF-Offs = 600,000
            return 3000.0 + (narfcn - 600000) * 0.015; // 15 kHz = 0.015 MHz
        } else if (narfcn <= 3279165)
        {
            // Range 3: 2,016,667 ≤ ARFCN ≤ 3,279,165
            // Δf = 60 kHz, F_REF-Offs = 24,250.08 MHz, N_REF-Offs = 2,016,667
            return 24250.08 + (narfcn - 2016667) * 0.060; // 60 kHz = 0.060 MHz
        } else
        {
            // NARFCN is outside the valid range
            return -1.0;
        }
    }

    /**
     * Returns the LTE band for a given EARFCN.
     *
     * @param earfcn The EARFCN to get the band for.
     * @return The LTE band for the given EARFCN, or -1 if the EARFCN is not in a known band.
     */
    public static int downlinkEarfcnToBand(int earfcn)
    {
        for (int[] band : DOWNLINK_LTE_BANDS)
        {
            if (earfcn >= band[1] && earfcn <= band[2])
            {
                return band[0];
            }
        }

        return -1;
    }

    /**
     * Returns the LTE downlink frequency in MHz, or -1 outside the E-UTRA raster.
     * The lower frequency and EARFCN offset come from TS 36.101 table 5.7.3-1.
     */
    public static double downlinkEarfcnToFrequencyMhz(int earfcn)
    {
        for (int[] band : DOWNLINK_LTE_BANDS)
        {
            if (earfcn >= band[1] && earfcn <= band[2] && band[3] >= 0)
            {
                return (band[3] + earfcn - band[1]) / 10.0;
            }
        }

        return -1;
    }

    public static UplinkReference lteUplinkReference(int earfcn)
    {
        final int band = downlinkEarfcnToBand(earfcn);
        if (band >= 33 && band <= 54)
            return new UplinkReference("TDD", earfcn, downlinkEarfcnToFrequencyMhz(earfcn));
        if (band == 29 || band == 32 || band == 67 || band == 69 || band == 75 || band == 76)
            return new UplinkReference("SDL", -1, -1);

        for (int[] uplink : LTE_PAIRED_UPLINK)
        {
            if (band != uplink[0]) continue;
            for (int[] downlink : DOWNLINK_LTE_BANDS)
            {
                if (band != downlink[0]) continue;
                final int offset = earfcn - downlink[1];
                if (uplink.length == 4 && offset >= uplink[3]) return new UplinkReference("FDD", -1, -1);
                return new UplinkReference("FDD", uplink[1] + offset, (uplink[2] + offset) / 10.0);
            }
        }
        return null;
    }

    /**
     * Maps a downlink reference frequency to its paired uplink reference. Android's NR channel
     * may be an SSB frequency, so this must not be presented as the configured uplink carrier.
     */
    public static UplinkReference nrUplinkReference(int[] reportedBands, int narfcn)
    {
        if (reportedBands == null || reportedBands.length == 0)
            return nrBandUplinkReference(downlinkNarfcnToBand(narfcn), narfcn);
        UplinkReference result = null;
        boolean ambiguousUplink = false;
        for (int band : reportedBands)
        {
            final UplinkReference candidate = nrBandUplinkReference(band, narfcn);
            if (candidate == null) continue;
            if (result != null)
            {
                if (!result.duplex().equals(candidate.duplex())) return null;
                ambiguousUplink |= result.arfcn() != candidate.arfcn();
            }
            result = candidate;
        }
        return ambiguousUplink && result != null ? new UplinkReference(result.duplex(), -1, -1) : result;
    }

    private static UplinkReference nrBandUplinkReference(int band, int narfcn)
    {
        for (int[] paired : NR_PAIRED_UPLINK)
        {
            if (band == paired[0] && narfcn >= paired[1] && narfcn <= paired[2])
            {
                final int uplink = paired[3] + narfcn - paired[1];
                return uplink <= paired[4] ? new UplinkReference("FDD", uplink, narfcnToFrequencyMhz(uplink))
                        : new UplinkReference("FDD", -1, -1);
            }
        }
        if (band == 29 && narfcn >= 143400 && narfcn <= 145600 ||
                band == 67 && narfcn >= 147600 && narfcn <= 151600 ||
                band == 75 && narfcn >= 286400 && narfcn <= 303400 ||
                band == 76 && narfcn >= 285400 && narfcn <= 286400)
            return new UplinkReference("SDL", -1, -1);
        final int tddBand = band == 90 ? 41 : band;
        if (tddBand == 54 && narfcn >= 334000 && narfcn <= 335000)
            return new UplinkReference("TDD", narfcn, narfcnToFrequencyMhz(narfcn));
        for (int[] downlink : DOWNLINK_NR_BANDS)
        {
            if (downlink[0] == tddBand && narfcn >= downlink[1] && narfcn <= downlink[2] &&
                    (tddBand == 34 || tddBand >= 38 && tddBand <= 53 ||
                     tddBand >= 77 && tddBand <= 79 || tddBand == 96 ||
                     tddBand == 101 || tddBand == 102 || tddBand == 104 || tddBand >= 257 && tddBand <= 263))
                return new UplinkReference("TDD", narfcn, narfcnToFrequencyMhz(narfcn));
        }
        return null;
    }

    /**
     * Returns the 5G NR band for a given downlink NARFCN, but only when the NARFCN falls in
     * exactly one operating band. Unlike LTE EARFCNs, NR ARFCN ranges overlap heavily (e.g. n1
     * lies inside n65/n66, n38 and n7 lie inside n41's range, and n48/n78 lie inside n77), so a
     * NARFCN contained in more than one band returns -1 rather than guessing. This keeps the
     * derived value trustworthy at the cost of leaving ambiguous ranges unresolved.
     *
     * @param narfcn The downlink NARFCN to look up.
     * @return The NR band number, or -1 when the NARFCN is invalid, matches no band, or matches
     * more than one band.
     */
    public static int downlinkNarfcnToBand(int narfcn)
    {
        int matchedBand = -1;
        for (int[] band : DOWNLINK_NR_BANDS)
        {
            if (narfcn >= band[1] && narfcn <= band[2])
            {
                if (matchedBand != -1) return -1;
                matchedBand = band[0];
            }
        }

        return matchedBand;
    }

    /**
     * Same as {@link #formatNrBands(int[])}, except that when the device did not report any
     * bands the band is derived from the downlink NARFCN via {@link #downlinkNarfcnToBand(int)}.
     * Device-reported bands always take precedence, and an ambiguous or invalid NARFCN still
     * yields an empty string. The derivation is display-only; logged and streamed records never
     * carry a band.
     *
     * @param bands  The NR band numbers reported for the cell, or null/empty when none were.
     * @param narfcn The cell's downlink NARFCN to fall back to.
     * @return The formatted band string, or an empty string when nothing could be determined.
     */
    public static String formatNrBands(int[] bands, int narfcn)
    {
        if (bands != null && bands.length > 0) return formatNrBands(bands);

        final int derivedBand = downlinkNarfcnToBand(narfcn);
        if (derivedBand == -1) return "";

        return formatNrBands(new int[]{derivedBand});
    }

    /**
     * Formats an array of 5G NR band numbers for display using the standard 3GPP "n" prefix and
     * the friendly band name when one is known (e.g. "n77 (TD 3700)"). Multiple bands are joined
     * with a comma.
     *
     * @param bands The NR band numbers reported for the cell.
     * @return The formatted band string, or an empty string when no bands were reported.
     */
    public static String formatNrBands(int[] bands)
    {
        if (bands == null || bands.length == 0) return "";

        final StringBuilder bandString = new StringBuilder();
        for (int i = 0; i < bands.length; i++)
        {
            final int bandNumber = bands[i];
            final String bandName = getNrBandName(bandNumber);

            bandString.append('n').append(bandNumber);
            if (bandName != null)
            {
                bandString.append(" (").append(bandName).append(")");
            }

            if (i < bands.length - 1)
            {
                bandString.append(", ");
            }
        }

        return bandString.toString();
    }

    /**
     * Selects the NR record to display on the NR Secondary Cell details card from the non-serving
     * NR records of a single scan: the first record whose device-reported connection status is
     * {@link ConnectionStatus#SECONDARY_SERVING}. On 5G NSA (EN-DC) that is the NR cell actively
     * carrying the 5G data (the phone is registered on the LTE anchor, so that cell reports
     * {@code isRegistered() == false}); on 5G SA with NR carrier aggregation it is an NR SCell.
     * <p>
     * The selection is deliberately limited to a device-reported status. An earlier version
     * guessed the NSA data leg by strongest SS-RSRP when no cell reported a status, but a guessed
     * cell rendered identically to a device-reported one and removed a genuine neighbor from the
     * neighbors table, so on devices that report no connection status the card simply does not
     * show. Do not reintroduce an inference here without a provenance flag in the view state.
     *
     * @param nrRecords The non-serving NR records from one scan.
     * @return The selected wrapper, or null when the list is empty or nothing qualifies.
     */
    public static NrRecordWrapper selectSecondaryServingNrCell(List<NrRecordWrapper> nrRecords)
    {
        if (nrRecords == null || nrRecords.isEmpty()) return null;

        for (NrRecordWrapper wrapper : nrRecords)
        {
            final NrRecordData data = ((NrRecord) wrapper.cellularRecord).getData();
            if (data.getConnectionStatus() == ConnectionStatus.SECONDARY_SERVING) return wrapper;
        }

        return null;
    }

    /**
     * Every record in {@code nrRecords} whose connection status is
     * {@link ConnectionStatus#SECONDARY_SERVING}. On NR CA there can be more than one SCell;
     * {@link #selectSecondaryServingNrCell} only returns the first for the secondary-cell card,
     * but all of them must stay out of the neighbors table.
     */
    public static List<NrRecordWrapper> selectAllSecondaryServingNrCells(List<NrRecordWrapper> nrRecords)
    {
        if (nrRecords == null || nrRecords.isEmpty()) return Collections.emptyList();
        final List<NrRecordWrapper> selected = new ArrayList<>();
        for (NrRecordWrapper wrapper : nrRecords)
        {
            final NrRecordData data = ((NrRecord) wrapper.cellularRecord).getData();
            if (data.getConnectionStatus() == ConnectionStatus.SECONDARY_SERVING) selected.add(wrapper);
        }
        return selected;
    }

    public static boolean isSecondaryServing(ConnectionStatus status)
    {
        return status == ConnectionStatus.SECONDARY_SERVING;
    }

    /**
     * @return Returns true if the servingCell field is present and also set to true.
     */
    public static boolean isServingCell(GeneratedMessage message)
    {
        try
        {
            // Get the descriptor for the top-level message
            Descriptors.Descriptor descriptor = message.getDescriptorForType();

            // Get the descriptor for the 'data' field
            Descriptors.FieldDescriptor dataField = descriptor.findFieldByName("data");
            if (dataField == null)
            {
                return false;
            }

            // Get the value of the 'data' field
            GeneratedMessage dataMessage = (GeneratedMessage) message.getField(dataField);

            // Get the descriptor for the 'servingCell' field within the 'data' field
            Descriptors.Descriptor dataDescriptor = dataMessage.getDescriptorForType();
            Descriptors.FieldDescriptor servingCellField = dataDescriptor.findFieldByName("servingCell");
            if (servingCellField == null)
            {
                return false;
            }

            // Get the value of the 'servingCell' field
            return ((BoolValue) dataMessage.getField(servingCellField)).getValue();
        } catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Get the ID used to identify a tower on the map. This is NOT the CGI because I wanted to
     * include the TAC for LTE and NR, but the CGI doesn't include the TAC.
     */
    public static String getTowerId(Tower tower)
    {
        if (tower == null)
        {
            return "";
        }

        String mcc = tower.getMcc() != null ? tower.getMcc() : "0";
        String mnc = tower.getMnc() != null ? tower.getMnc() : "0";
        return mcc + mnc + tower.getArea() + tower.getCid();
    }

    /**
     * Get the ID used to identify a tower on the map. This is NOT the CGI because I wanted to
     * include the TAC for LTE and NR, but the CGI doesn't include the TAC.
     *
     * @param servingCellInfo The ServingCellInfo to get the ID from.
     * @return The ID, or an empty string if the ServingCellInfo is null or the ServingCell is null.
     */
    public static String getTowerId(ServingCellInfo servingCellInfo)
    {
        return servingCellInfo == null ? "" : getTowerId(servingCellInfo.getServingCell());
    }

    /**
     * Get the ID used to identify a tower on the map. This is NOT the CGI because I wanted to
     * include the TAC for LTE and NR, but the CGI doesn't include the TAC.
     *
     * @param cellularRecord The cellular record to get the ID from.
     * @return The ID, or an empty string if the record is null or its protocol has no tower ID.
     */
    public static String getTowerId(CellularRecordWrapper cellularRecord)
    {
        if (cellularRecord == null)
        {
            return "";
        }

        switch (cellularRecord.cellularProtocol)
        {
            case NONE:
                return "";

            case GSM:
                final GsmRecordData gsmData = ((GsmRecord) cellularRecord.cellularRecord).getData();
                String[] gsmMccMnc = NsUtils.extractMccMncStrings(gsmData.hasPlmn(),
                        gsmData.hasPlmn() ? gsmData.getPlmn().getValue() : null,
                        gsmData.getMcc().getValue(), gsmData.getMnc().getValue());
                return gsmMccMnc[0] + gsmMccMnc[1] + gsmData.getLac().getValue() + gsmData.getCi().getValue();

            case CDMA:
                // We don't support CDMA since it is pretty much gone
                break;

            case UMTS:
                final UmtsRecordData umtsData = ((UmtsRecord) cellularRecord.cellularRecord).getData();
                String[] umtsMccMnc = NsUtils.extractMccMncStrings(umtsData.hasPlmn(),
                        umtsData.hasPlmn() ? umtsData.getPlmn().getValue() : null,
                        umtsData.getMcc().getValue(), umtsData.getMnc().getValue());
                return umtsMccMnc[0] + umtsMccMnc[1] + umtsData.getLac().getValue() + umtsData.getCid().getValue();

            case LTE:
                final LteRecordData lteData = ((LteRecord) cellularRecord.cellularRecord).getData();
                String[] lteMccMnc = NsUtils.extractMccMncStrings(lteData.hasPlmn(),
                        lteData.hasPlmn() ? lteData.getPlmn().getValue() : null,
                        lteData.getMcc().getValue(), lteData.getMnc().getValue());
                return lteMccMnc[0] + lteMccMnc[1] + lteData.getTac().getValue() + lteData.getEci().getValue();

            case NR:
                final NrRecordData nrData = ((NrRecord) cellularRecord.cellularRecord).getData();
                String[] nrMccMnc = NsUtils.extractMccMncStrings(nrData.hasPlmn(),
                        nrData.hasPlmn() ? nrData.getPlmn().getValue() : null,
                        nrData.getMcc().getValue(), nrData.getMnc().getValue());
                return nrMccMnc[0] + nrMccMnc[1] + nrData.getTac().getValue() + nrData.getNci().getValue();
        }

        return "";
    }

    public static ServingSignalInfo getSignalInfo(CellularRecordWrapper cellularRecord)
    {
        if (cellularRecord == null || cellularRecord.cellularProtocol == null)
        {
            return null;
        }

        return switch (cellularRecord.cellularProtocol)
        {
            case NONE -> null;
            case GSM ->
            {
                final GsmRecordData gsmData = ((GsmRecord) cellularRecord.cellularRecord).getData();
                yield new ServingSignalInfo(CellularProtocol.GSM, (int) gsmData.getSignalStrength().getValue(), -1);
            }
            case CDMA ->
            {
                final CdmaRecordData cdmaData = ((CdmaRecord) cellularRecord.cellularRecord).getData();
                yield new ServingSignalInfo(CellularProtocol.CDMA, ((int) cdmaData.getEcio().getValue()), -1);
            }
            case UMTS ->
            {
                final UmtsRecordData umtsData = ((UmtsRecord) cellularRecord.cellularRecord).getData();
                yield new ServingSignalInfo(CellularProtocol.UMTS, ((int) umtsData.getSignalStrength().getValue()), (int) umtsData.getRscp().getValue());
            }
            case LTE ->
            {
                final LteRecordData lteData = ((LteRecord) cellularRecord.cellularRecord).getData();
                yield new ServingSignalInfo(CellularProtocol.LTE, (int) lteData.getRsrp().getValue(), (int) lteData.getRsrq().getValue());
            }
            case NR ->
            {
                final NrRecordData nrData = ((NrRecord) cellularRecord.cellularRecord).getData();
                yield new ServingSignalInfo(CellularProtocol.NR, (int) nrData.getSsRsrp().getValue(), (int) nrData.getSsRsrq().getValue());
            }
        };
    }
}
