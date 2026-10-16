package com.mrcpdf.quality.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The relationship between the 3-class label and the binary MRC gate.
 *
 * <p>Both are derived from the same audited word F1, on the same map row, and
 * they are written to the same file. That makes it possible for them to
 * disagree, and they did: with the class cuts at the terciles of the steering
 * marginal, {@code badBelow} was 0.5333 while {@code SeverityScore.LABEL_CUTOFF}
 * was 0.60, leaving 63 rows marked {@code class=bad} beside a
 * {@code binaryLabel} of 0 -- a page the map called unreadable while the column
 * that gates the actual compression decision said keep MRC.
 *
 * <p>The fix was to put {@code badBelow} on {@code LABEL_CUTOFF}, so the two
 * agree by construction rather than by two constants happening to be close.
 * This class is what keeps that true across a retune.
 */
class ClassCutTest {

    /** The cuts this corpus is built with; see RealTrainDump's field comment. */
    private static final double GOOD_ABOVE = 0.90;
    private static final double BAD_BELOW = SeverityScore.LABEL_CUTOFF;

    /**
     * The invariant itself, swept rather than spot-checked. Any score either side
     * of either cut has to agree, including inside the bands.
     */
    @Test
    void classBadAndBinaryGateNameTheSamePages() {
        for (int i = 0; i <= 10000; i++) {
            double audit = i / 10000.0;
            boolean classBad = RealTrainDump.classOf(audit, GOOD_ABOVE, BAD_BELOW) == 2;
            boolean gateBad = SeverityScore.label(audit) == 1;
            assertEquals(classBad, gateBad,
                    "class=bad and binaryLabel=1 must agree at audit=" + audit);
        }
    }

    /**
     * The agreement above is arithmetic, so it survives only as long as the cut
     * and the shared constant stay put. This is the assertion that fails when
     * someone moves one of them, which is the edit the sweep above would
     * otherwise absorb silently.
     */
    @Test
    void badCutIsTheSharedLabelCutoff() {
        assertEquals(0.60, BAD_BELOW, 0.0,
                "class=bad must be cut at SeverityScore.LABEL_CUTOFF so the 3-class "
                        + "label and the binary MRC gate cannot drift apart");
        assertEquals(SeverityScore.LABEL_CUTOFF, BAD_BELOW, 0.0);
    }

    /**
     * The other half of the drift: if {@code goodAbove} moves without
     * {@code badBelow} following, the gate invariant above still passes (it does
     * not involve the good cut) and only the class balance moves. This is the
     * configuration the dump now rejects outright, and this is why.
     */
    @Test
    void movingTheGoodCutCannotSilentlyChangeTheBadCut() {
        double raisedGood = 0.95;
        assertEquals(0, RealTrainDump.classOf(0.92, GOOD_ABOVE, BAD_BELOW),
                "0.92 is good under the shipped cut");
        assertEquals(1, RealTrainDump.classOf(0.92, raisedGood, BAD_BELOW),
                "0.92 becomes middle when only the good cut moves");
        assertEquals(0, SeverityScore.label(0.92),
                "the binary gate is untouched by the good cut, which is the point");
        assertEquals(2, RealTrainDump.classOf(0.55, raisedGood, BAD_BELOW),
                "bad is unchanged by the good cut");
    }

    /** Both boundaries, stated explicitly rather than left to the sweep. */
    @Test
    void boundariesClassifyAsIntended() {
        assertEquals(2, RealTrainDump.classOf(0.5999, GOOD_ABOVE, BAD_BELOW),
                "just under the gate is bad");
        assertEquals(1, SeverityScore.label(0.5999), "just under the gate skips MRC");

        assertEquals(1, RealTrainDump.classOf(0.6001, GOOD_ABOVE, BAD_BELOW),
                "just over the gate is middle");
        assertEquals(0, SeverityScore.label(0.6001), "just over the gate keeps MRC");

        assertEquals(0, RealTrainDump.classOf(0.9000, GOOD_ABOVE, BAD_BELOW),
                "good is inclusive at its lower edge");
        assertEquals(1, RealTrainDump.classOf(0.8999, GOOD_ABOVE, BAD_BELOW),
                "just under good is middle");
    }

    /**
     * Guards the test above from becoming vacuous. If every score mapped to
     * {@code good} or every score to {@code bad}, the sweep would pass trivially
     * and the invariant would prove nothing -- the same trap as a test written
     * against behaviour that was never actually a bug.
     */
    @Test
    void allThreeClassesAreReachable() {
        assertEquals(0, RealTrainDump.classOf(0.97, GOOD_ABOVE, BAD_BELOW));
        assertEquals(1, RealTrainDump.classOf(0.75, GOOD_ABOVE, BAD_BELOW));
        assertEquals(2, RealTrainDump.classOf(0.30, GOOD_ABOVE, BAD_BELOW));
        assertNotEquals(RealTrainDump.classOf(0.30, GOOD_ABOVE, BAD_BELOW),
                RealTrainDump.classOf(0.75, GOOD_ABOVE, BAD_BELOW));
    }
}