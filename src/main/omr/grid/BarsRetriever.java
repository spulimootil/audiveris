//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   B a r s R e t r i e v e r                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.grid;

import omr.Main;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.GlyphLayer;
import omr.glyph.GlyphNest;
import omr.glyph.Glyphs;
import omr.glyph.Shape;
import omr.glyph.facets.Glyph;
import static omr.grid.BarPeak.Attribute.*;

import omr.lag.BasicLag;
import omr.lag.JunctionShiftPolicy;
import omr.lag.Lag;
import omr.lag.Lags;
import omr.lag.Section;
import omr.lag.SectionsBuilder;

import omr.math.AreaUtil;
import omr.math.AreaUtil.CoreData;
import omr.math.BasicLine;
import omr.math.Clustering;
import omr.math.Clustering.Gaussian;
import omr.math.GeoPath;
import omr.math.GeoUtil;
import omr.math.Histogram;
import omr.math.PointUtil;
import static omr.run.Orientation.*;
import omr.run.RunsTable;

import omr.sheet.PartInfo;
import omr.sheet.Picture;
import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.Skew;
import omr.sheet.SystemInfo;

import omr.sig.AbstractVerticalInter;
import omr.sig.BarConnectionInter;
import omr.sig.BarConnectionRelation;
import omr.sig.BarGroupRelation;
import omr.sig.BarlineInter;
import omr.sig.BracketConnectionInter;
import omr.sig.BracketInter;
import omr.sig.BracketInter.BracketKind;
import omr.sig.GradeImpacts;
import omr.sig.Relation;
import omr.sig.SIGraph;

import omr.step.StepException;

import omr.ui.Colors;
import omr.ui.util.ItemRenderer;
import omr.ui.util.UIUtil;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;
import omr.util.IntUtil;
import omr.util.Navigable;
import omr.util.VerticalSide;
import static omr.util.VerticalSide.*;

import ij.process.ByteProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Class {@code BarsRetriever} focuses on the retrieval of vertical bar lines and
 * brackets.
 * <p>
 * Bar lines are used to:
 * <ul>
 * <li>Determine the gathering of staves into systems and parts</li>
 * <li>Define staff sides precisely.</li>
 * <li>Determine measures (later)</li>
 * </ul>
 *
 * @author Hervé Bitteur
 */
public class BarsRetriever
        implements ItemRenderer
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(BarsRetriever.class);

    //~ Instance fields ----------------------------------------------------------------------------
    //
    /** Related sheet. */
    @Navigable(false)
    private final Sheet sheet;

    /** Global sheet scale. */
    @Navigable(false)
    private final Scale scale;

    /** Scale-dependent constants. */
    private final Parameters params;

    /** Lag of vertical runs. */
    private Lag vLag;

    /** Related staff manager. */
    private final StaffManager staffManager;

    /** Staff projectors. */
    private final List<StaffProjector> projectors = new ArrayList<StaffProjector>();

    /** All alignments found between bars across staves. */
    private final Set<BarAlignment> alignments = new LinkedHashSet<BarAlignment>();

    /** All (physical) connections found between bars across staves. */
    private final Set<BarConnection> connections = new LinkedHashSet<BarConnection>();

    //~ Constructors -------------------------------------------------------------------------------
    //---------------//
    // BarsRetriever //
    //---------------//
    /**
     * Retrieve the bar lines of all staves.
     *
     * @param sheet the sheet to process
     */
    public BarsRetriever (Sheet sheet)
    {
        this.sheet = sheet;

        scale = sheet.getScale();

        // Scale-dependent parameters
        params = new Parameters(scale);

        // Companions
        staffManager = sheet.getStaffManager();
    }

    //~ Methods ------------------------------------------------------------------------------------
    //
    //------------------//
    // buildVerticalLag //
    //------------------//
    /**
     * Build the underlying vertical lag, from the provided runs table.
     * This method must be called before building info.
     *
     * @param longVertTable the provided table of (long) vertical runs
     */
    public void buildVerticalLag (RunsTable longVertTable)
    {
        vLag = new BasicLag(Lags.VLAG, VERTICAL);

        SectionsBuilder sectionsBuilder = new SectionsBuilder(
                vLag,
                new JunctionShiftPolicy(params.maxRunShift));
        sectionsBuilder.createSections(longVertTable, true);

        sheet.setLag(Lags.VLAG, vLag);

        setVipSections();
    }

    //------//
    // plot //
    //------//
    /**
     * Display the staff projection for a provided staff.
     *
     * @param staff provided staff
     */
    public void plot (StaffInfo staff)
    {
        final int index = staff.getId() - 1;

        if (index < projectors.size()) {
            projectors.get(index).plot();
        }
    }

    //-------------//
    // renderItems //
    //-------------//
    /**
     * Render the axis of each bar line / bracket / connection.
     *
     * @param g graphics context
     */
    @Override
    public void renderItems (Graphics2D g)
    {
        if (!constants.showVerticalLines.isSet()) {
            return;
        }

        final Rectangle clip = g.getClipBounds();
        final Stroke oldStroke = UIUtil.setAbsoluteStroke(g, 1f);
        final Color oldColor = g.getColor();
        g.setColor(Colors.ENTITY_MINOR);

        // Draw bar lines (only within staff height)
        for (StaffInfo staff : staffManager.getStaves()) {
            for (BarPeak peak : staff.getBarPeaks()) {
                Rectangle peakBox = new Rectangle(
                        peak.getStart(),
                        peak.getTop(),
                        peak.getWidth(),
                        peak.getBottom() - peak.getTop());

                if (clip.intersects(peakBox)) {
                    double xMid = (peak.getStart() + peak.getStop()) / 2d;
                    Line2D line = new Line2D.Double(xMid, peak.getTop(), xMid, peak.getBottom());
                    g.draw(line);
                }
            }
        }

        // Draw Connections (outside of staff height)
        for (BarConnection connection : connections) {
            Line2D median = connection.getMedian();

            if (median.intersects(clip)) {
                g.draw(median);
            }
        }

        g.setStroke(oldStroke);
        g.setColor(oldColor);
    }

    //------------------//
    // retrieveBarlines //
    //------------------//
    /**
     * Retrieve all bar lines (and brackets) in the sheet and create systems and parts
     * indicated by bar lines that connect across staves.
     *
     * @throws StepException raised if processing must stop
     */
    public void retrieveBarlines ()
            throws StepException
    {
        // Individual staff analysis to find bar peaks on x-axis projection
        findBarPeaks();

        // Build core glyph for each peak
        buildBarSticks();

        // Remove braces
        purgeBracePeaks();

        // Find all bar (or bracket) alignments across staves
        findAlignments();

        // Find all concrete connections across staves
        findConnections();

        // Purge conflicting connections
        purgeConnections();

        // Purge alignments incompatible with connections
        purgeAlignments();

        // Detect top and bottom portions of brackets
        detectBracketEnds();

        // Detect middle portions of brackets
        detectBracketMiddles();

        // Detect long peaks that do not connect staves
        detectLongPeaks();

        // Purge long peaks (and delete their alignments/connections)
        purgeLongPeaks();

        // Create systems & parts from bar connections
        createSystemsAndParts();

        // Purge alignments across systems, they are not relevant
        purgeCrossAlignments();

        // Define precisely all staff side abscissae
        refineSides();

        // Purge C-clef-based false barlines
        purgeCClefs();

        // In multi-staff systems, boost the aligned peaks, weaken/delete the isolated ones
        checkUnalignedPeaks();

        // Partition peaks between thin and thick
        partitionWidths();

        // Create barline and bracket interpretations within each system
        createInters();

        // Create bar and bracket connection across staves
        createConnectionInters();

        // Detect grouped bar lines
        groupBarlines();

        // Record bars in staff
        recordBars();
    }

    //--------------//
    // alignedPeaks //
    //--------------//
    /**
     * Report the BarPeak instance(s) that are aligned with the provided peak, looking
     * on the provided vertical side.
     *
     * @param peak the peak to check from
     * @param side which side to look (from provided peak)
     * @return the collection of peaks found, perhaps empty
     */
    private List<BarPeak> alignedPeaks (BarPeak peak,
                                        VerticalSide side)
    {
        final List<BarPeak> found = new ArrayList<BarPeak>();

        for (BarAlignment alignment : alignmentsOf(peak, side.opposite())) {
            found.add(alignment.getPeak(side));
        }

        return found;
    }

    //--------------//
    // alignmentsOf //
    //--------------//
    /**
     * Report the collection of alignments for which the provided peak is involved on
     * desired vertical side.
     *
     * @param peak the peak to check for
     * @param side the desired vertical side
     * @return the collection found, perhaps empty.
     */
    private Set<BarAlignment> alignmentsOf (BarPeak peak,
                                            VerticalSide side)
    {
        Set<BarAlignment> found = new HashSet<BarAlignment>();

        for (BarAlignment alignment : alignments) {
            if (alignment.getPeak(side) == peak) {
                found.add(alignment);
            }
        }

        return found;
    }

    //----------------//
    // buildBarSticks //
    //----------------//
    /**
     * Build the underlying stick of every peak.
     * <p>
     * These glyphs are needed to detect those peaks which go past staff height above,
     * below, or both, and may rather be stems.
     * They are used also to detect curly peaks that are due to brace portions.
     * <p>
     * For each peak, we take a vertical "slice" of the relevant sections using a lookup area.
     * We then run a dedicated factory on the sections and make it focus on the bar core area.
     */
    private void buildBarSticks ()
    {
        // Preselect sections of proper max width
        final int maxWidth = getMaxPeaksWidth();
        List<Section> allSections = getSectionsByWidth(maxWidth);
        logger.debug("sections:{}", allSections.size());

        BarGlyphFactory factory = new BarGlyphFactory(
                sheet.getScale(),
                sheet.getNest(),
                GlyphLayer.DEFAULT,
                VERTICAL);

        for (StaffInfo staff : staffManager.getStaves()) {
            for (BarPeak peak : staff.getBarPeaks()) {
                // Take proper slice of sections for this peak
                List<Section> sections = getPeakSections(peak, allSections);
                Glyph glyph = factory.retrieveBarGlyph(sections, peak.getBounds());
                peak.setGlyph(glyph);
                logger.debug("Staff#{} {}", staff.getId(), peak);
            }
        }
    }

    //-----------------//
    // buildSerifGlyph //
    //-----------------//
    /**
     * Build the glyph that may represent a bracket end serif.
     *
     * @param staff    containing staff
     * @param sections the population of candidate sections
     * @param side     top or bottom of staff
     * @param roi      the rectangular roi for the serif
     * @return the glyph found
     */
    private Glyph buildSerifGlyph (StaffInfo staff,
                                   Set<Section> sections,
                                   VerticalSide side,
                                   Rectangle roi)
    {
        // Retrieve all glyphs out of connected sections
        GlyphNest nest = sheet.getNest();
        List<Glyph> glyphs = nest.retrieveGlyphsFromIsolatedSections(
                sections,
                GlyphLayer.DEFAULT,
                true);
        logger.debug("Staff#{} serif {}", staff.getId(), Glyphs.toString(glyphs));

        if (glyphs.size() > 1) {
            // Sort glyphs according to their distance from bar/roi vertex
            final Point vertex = new Point(roi.x, roi.y + ((side == TOP) ? (roi.height - 1) : 0));
            Collections.sort(
                    glyphs,
                    new Comparator<Glyph>()
                    {
                        @Override
                        public int compare (Glyph g1,
                                            Glyph g2)
                        {
                            double d1 = PointUtil.length(
                                    GeoUtil.vectorOf(g1.getCentroid(), vertex));
                            double d2 = PointUtil.length(
                                    GeoUtil.vectorOf(g2.getCentroid(), vertex));

                            return Double.compare(d1, d2);
                        }
                    });

            // Pickup the first ones and stop as soon as minimum weight is reached
            int totalWeight = 0;

            for (int i = 0; i < glyphs.size(); i++) {
                Glyph glyph = glyphs.get(i);
                totalWeight += glyph.getWeight();

                if (totalWeight >= params.serifMinWeight) {
                    glyphs = glyphs.subList(0, i + 1);

                    break;
                }
            }

            return nest.buildGlyph(glyphs, true);
        } else {
            return glyphs.get(0);
        }
    }

    //-----------------//
    // checkConnection //
    //-----------------//
    /**
     * Check whether the provided alignment is a true connection, that is with concrete
     * foreground pixels on the alignment line.
     * <p>
     * For this, we define a spline which goes through top & bottom points of each of the aligned
     * peaks and look for pixels in the area.
     *
     * @param alignment the alignment to check.
     * @return the connection if OK, null otherwise
     */
    private BarConnection checkConnection (BarAlignment alignment)
    {
        ByteProcessor pixelFilter = sheet.getPicture().getSource(Picture.SourceKey.BINARY);
        BarPeak p1 = alignment.topPeak;
        BarPeak p2 = alignment.bottomPeak;

        // Theoretical lines on left and right sides
        final GeoPath leftLine = new GeoPath(
                new Line2D.Double(
                        new Point2D.Double(p1.getStart(), p1.getBottom()),
                        new Point2D.Double(p2.getStart(), p2.getTop())));
        final GeoPath rightLine = new GeoPath(
                new Line2D.Double(
                        new Point2D.Double(p1.getStop(), p1.getBottom()),
                        new Point2D.Double(p2.getStop(), p2.getTop())));

        final CoreData data = AreaUtil.verticalCore(pixelFilter, leftLine, rightLine);

        if (p1.getGlyph().isVip() || p2.getGlyph().isVip()) {
            logger.info(
                    "VIP checkConnection S#{} {} and S#{} {} {}",
                    p1.getStaff().getId(),
                    p1,
                    p2.getStaff().getId(),
                    p2,
                    data);
        }

        if ((data.gap <= params.maxConnectionGap)
            && (data.whiteRatio <= params.maxConnectionWhiteRatio)) {
            double whiteImpact = 1 - (data.whiteRatio / params.maxConnectionWhiteRatio);
            double gapImpact = 1 - ((double) data.gap / params.maxConnectionGap);
            double alignImpact = alignment.getImpacts().getGrade() / alignment.getImpacts()
                    .getIntrinsicRatio();
            GradeImpacts impacts = new BarConnection.Impacts(alignImpact, whiteImpact, gapImpact);

            return new BarConnection(alignment, impacts);
        } else {
            return null;
        }
    }

    //---------------------//
    // checkUnalignedPeaks //
    //---------------------//
    /**
     * Only for multi-staff systems, check the peaks alignments.
     * Give a bonus to every peak aligned (or connected) with a peak in a staff nearby and
     * weaken (or delete) the isolated ones.
     */
    private void checkUnalignedPeaks ()
    {
        final boolean deletion = constants.deleteUnalignedBars.isSet();

        for (SystemInfo system : sheet.getSystems()) {
            if (system.getStaves().size() > 1) {
                for (StaffInfo staff : system.getStaves()) {
                    List<BarPeak> toRemove = new ArrayList<BarPeak>();

                    for (BarPeak peak : staff.getBarPeaks()) {
                        final AbstractVerticalInter inter = peak.getInter();

                        if (isAligned(peak, TOP) || isAligned(peak, BOTTOM)) {
                            peak.set(ALIGNED);
                        } else {
                            peak.set(UNALIGNED);

                            if (deletion) {
                                toRemove.add(peak);
                            }
                        }
                    }

                    if (!toRemove.isEmpty()) {
                        logger.debug("Staff#{} removing isolated {}", staff.getId(), toRemove);
                        staff.removePeaks(toRemove);
                    }
                }
            }
        }
    }

    //------------------------//
    // createConnectionInters //
    //------------------------//
    /**
     * Populate all systems sigs with connection inters for bar line and brackets.
     */
    private void createConnectionInters ()
    {
        for (BarConnection connection : connections) {
            BarPeak topPeak = connection.topPeak;
            SystemInfo system = topPeak.getStaff().getSystem();
            SIGraph sig = system.getSig();

            if (topPeak.isBracket()) {
                sig.addVertex(new BracketConnectionInter(connection, connection.getImpacts()));
            } else {
                sig.addVertex(
                        new BarConnectionInter(
                                connection,
                                topPeak.isSet(THICK) ? Shape.THICK_CONNECTION : Shape.THIN_CONNECTION,
                                connection.getImpacts()));
            }

            // Also, connected bars support each other
            Relation bcRel = new BarConnectionRelation(connection.getImpacts());
            BarPeak bottomPeak = connection.bottomPeak;
            sig.addEdge(topPeak.getInter(), bottomPeak.getInter(), bcRel);
        }
    }

    //--------------//
    // createInters //
    //--------------//
    /**
     * Based on remaining peaks, populate each system sig with proper inters for bar
     * lines and for brackets.
     */
    private void createInters ()
    {
        final double up = constants.alignedIncreaseRatio.getValue();
        final double down = constants.unalignedDecreaseRatio.getValue();

        for (SystemInfo system : sheet.getSystems()) {
            SIGraph sig = system.getSig();

            for (StaffInfo staff : system.getStaves()) {
                for (BarPeak peak : staff.getBarPeaks()) {
                    BasicLine median = new BasicLine(
                            new double[]{
                                (peak.getStart() + peak.getStop()) / 2d,
                                (peak.getStart() + peak.getStop()) / 2d
                            },
                            new double[]{peak.getTop(), peak.getBottom()});

                    AbstractVerticalInter inter;

                    if (peak.isBracket()) {
                        BracketKind kind = getBracketKind(peak);
                        inter = new BracketInter(
                                peak.getGlyph(),
                                peak.getImpacts(),
                                median,
                                peak.getWidth(),
                                kind);
                    } else {
                        inter = new BarlineInter(
                                peak.getGlyph(),
                                peak.isSet(THICK) ? Shape.THICK_BARLINE : Shape.THIN_BARLINE,
                                peak.getImpacts(),
                                median,
                                peak.getWidth());

                        if (peak.isSet(ALIGNED)) {
                            inter.increase(up);
                        }

                        if (peak.isSet(UNALIGNED)) {
                            inter.decrease(down);
                        }
                    }

                    sig.addVertex(inter);
                    peak.setInter(inter);
                }
            }
        }
    }

    //-------------//
    // createParts //
    //-------------//
    /**
     * Create PartInfo for each part.
     *
     * @param partTops parts starting staves
     */
    private void createParts (Integer[] partTops)
    {
        for (SystemInfo system : sheet.getSystems()) {
            system.getParts().clear(); // Start from scratch

            int partTop = -1;
            PartInfo part = null;

            for (StaffInfo staff : system.getStaves()) {
                int topId = partTops[staff.getId() - 1];

                if (topId != partTop) {
                    part = new PartInfo();
                    system.addPart(part);
                    partTop = topId;
                }

                part.addStaff(staff);
            }
        }
    }

    //---------------//
    // createSystems //
    //---------------//
    /**
     * Build SystemInfo of each system.
     *
     * @param systems starting staves
     * @return the sequence of systems
     */
    private List<SystemInfo> createSystems (Integer[] systemTops)
    {
        List<SystemInfo> newSystems = new ArrayList<SystemInfo>();
        Integer staffTop = null;
        int systemId = 0;
        SystemInfo system = null;

        for (int i = 0; i < staffManager.getStaffCount(); i++) {
            StaffInfo staff = staffManager.getStaff(i);

            // System break?
            if ((staffTop == null) || (staffTop < systemTops[i])) {
                // Start of a new system
                staffTop = systemTops[i];

                system = new SystemInfo(++systemId, sheet, staffManager.getRange(staff, staff));
                newSystems.add(system);
            } else {
                // Continuing current system
                system.setStaves(staffManager.getRange(system.getFirstStaff(), staff));
            }
        }

        return newSystems;
    }

    //-----------------------//
    // createSystemsAndParts //
    //-----------------------//
    /**
     * Gather staves per systems and parts and create the related info instances.
     */
    private void createSystemsAndParts ()
    {
        final int staffCount = staffManager.getStaffCount();

        // For each staff, gives the staff that starts the containing system
        final Integer[] systemTops = new Integer[staffCount];

        // For each staff, gives the staff that starts the containing part
        final Integer[] partTops = new Integer[staffCount];

        gatherStaves(systemTops, partTops);
        sheet.setSystems(createSystems(systemTops));
        createParts(partTops);
    }

    //-------------------//
    // detectBracketEnds //
    //-------------------//
    /**
     * Detect the peaks that correspond to top or bottom end of brackets.
     * <p>
     * Such bracket end is characterized as follows:<ul>
     * <li>It is the first peak on staff left (since braces have been removed at this point).</li>
     * <li>It is a rather thick peak.</li>
     * <li>It sometimes (but not always) goes a bit beyond staff top or bottom line.</li>
     * <li>It has a serif shape at end.</li>
     * </ul>
     */
    private void detectBracketEnds ()
    {
        for (StaffInfo staff : staffManager.getStaves()) {
            List<BarPeak> peaks = staff.getBarPeaks();

            if (peaks.isEmpty()) {
                continue;
            }

            final BarPeak peak = peaks.get(0);
            final BarPeak nextPeak = (peaks.size() > 1) ? peaks.get(1) : null;

            // Check first peak is on left of staff
            if (peak.getStart() > staff.getAbscissa(LEFT)) {
                continue;
            }

            // Sufficient width?
            if (peak.getWidth() < params.minBracketWidth) {
                continue;
            }

            // It cannot go too far beyond staff height
            for (VerticalSide side : VerticalSide.values()) {
                double ext = extensionOf(peak, side);

                // Check for serif shape
                //TODO: perhaps record serif glyph in peak (so that it can be erased)?
                if ((ext <= params.maxBracketExtension) && hasSerif(staff, peak, nextPeak, side)) {
                    logger.debug("Staff#{} {} bracket end", staff.getId(), side);

                    peak.setBracketEnd(side);
                }
            }
        }
    }

    //----------------------//
    // detectBracketMiddles //
    //----------------------//
    /**
     * Among peaks, flag the ones that correspond to brackets middle portions.
     */
    private void detectBracketMiddles ()
    {
        // Flag recursively any peak connected to a bracket end
        boolean modified;

        do {
            modified = false;

            for (BarConnection connection : connections) {
                BarPeak top = connection.topPeak;
                BarPeak bottom = connection.bottomPeak;

                if (top.isBracket()) {
                    if (!bottom.isBracket()) {
                        bottom.set(BRACKET_MIDDLE);
                        modified = true;
                    }
                } else {
                    if (bottom.isBracket()) {
                        top.set(BRACKET_MIDDLE);
                        modified = true;
                    }
                }
            }
        } while (modified == true);
    }

    //-----------------//
    // detectLongPeaks //
    //-----------------//
    /**
     * Detect long bars (those getting above or below the related staff).
     * <p>
     * Just after a bracket end, the glyph of following bar line may go slightly beyond staff.
     */
    private void detectLongPeaks ()
    {
        for (StaffInfo staff : staffManager.getStaves()) {
            for (int index = 0; index < staff.getBarPeaks().size(); index++) {
                List<BarPeak> peaks = staff.getBarPeaks();
                BarPeak peak = peaks.get(index);

                // Check whether the glyph gets above and/or below the staff
                if (!peak.isBracket()) {
                    for (VerticalSide side : VerticalSide.values()) {
                        double ext = extensionOf(peak, side);

                        if (ext > params.maxBarExtension) {
                            if (!isJustAfterBracket(peaks, peak, side)) {
                                peak.setBeyond(side);
                            }
                        }
                    }
                }
            }
        }
    }

    //-------------//
    // extensionOf //
    //-------------//
    /**
     * Report how much a peak goes beyond a staff limit line.
     *
     * @param peak the peak to check
     * @param side TOP or BOTTOM
     * @return delta ordinate beyond staff
     */
    private double extensionOf (BarPeak peak,
                                VerticalSide side)
    {
        final Rectangle glyphBox = peak.getGlyph().getBounds();
        final double halfLine = scale.getMaxFore() / 2.0;

        if (side == TOP) {
            return (peak.getTop() - halfLine - glyphBox.y);
        } else {
            return ((glyphBox.y + glyphBox.height) - 1 - halfLine - peak.getBottom());
        }
    }

    //----------------//
    // findAlignments //
    //----------------//
    /**
     * Find all bar (or bracket) alignments across staves.
     */
    private void findAlignments ()
    {
        // Check for peaks aligned across staves
        for (StaffInfo staff : staffManager.getStaves()) {
            for (VerticalSide side : VerticalSide.values()) {
                List<StaffInfo> otherStaves = staffManager.vertNeighbors(staff, side);

                // Make sure there are other staves on this side and they are "short-wise compatible"
                // with current staff
                if (otherStaves.isEmpty() || (otherStaves.get(0).isShort() != staff.isShort())) {
                    continue;
                }

                // Look for all alignment/connection relations
                for (BarPeak peak : staff.getBarPeaks()) {
                    // Look for a suitable partnering peak in stave(s) nearby
                    for (StaffInfo otherStaff : otherStaves) {
                        lookupPeaks(peak, side, otherStaff);
                    }
                }
            }
        }
    }

    //--------------//
    // findBarPeaks //
    //--------------//
    /**
     * Use individual staff projections to retrieve bar peaks.
     */
    private void findBarPeaks ()
    {
        // Analysis staff per staff
        for (StaffInfo staff : staffManager.getStaves()) {
            StaffProjector projector = new StaffProjector(sheet, staff);
            projectors.add(projector);
            projector.process();
        }
    }

    //-----------------//
    // findConnections //
    //-----------------//
    /**
     * Find all bar (or bracket) concrete connections across staves.
     */
    private void findConnections ()
    {
        // Check among the alignments for peaks connected across staves
        for (Iterator<BarAlignment> it = alignments.iterator(); it.hasNext();) {
            BarAlignment alignment = it.next();

            // Look for concrete connection
            BarConnection connection = checkConnection(alignment);

            if (connection != null) {
                connections.add(connection);
                // Remove the underlying alignment
                it.remove();
            }
        }
    }

    //--------------//
    // gatherStaves //
    //--------------//
    /**
     * Use connections across staves to gather staves into systems and parts.
     * <p>
     * A first connection between two staves make them system partners.
     * A second connection between two staves makes them part partners, provided that the second
     * connection is sufficiently abscissa-shifted from the first one.
     *
     * @param systemTops (output) systems starting staves
     * @param partTops   (output) parts starting staves
     */
    private void gatherStaves (Integer[] systemTops,
                               Integer[] partTops)
    {
        BarConnection prevConnection = null;

        // Connections are ordered per top staff then per abscissa.
        for (BarConnection connection : connections) {
            int top = connection.topPeak.getStaff().getId();
            int bottom = connection.bottomPeak.getStaff().getId();

            if (systemTops[top - 1] == null) {
                // First connection ever between the 2 staves
                systemTops[top - 1] = top;
            } else {
                // Is this a truely separate second connection?
                // Check horizontal gap with previous one
                int gap = connection.topPeak.getStart() - prevConnection.topPeak.getStop() - 1;

                if (gap > params.maxDoubleBarGap) {
                    if (partTops[top - 1] == null) {
                        partTops[top - 1] = top;
                    }

                    partTops[bottom - 1] = partTops[top - 1];
                }
            }

            systemTops[bottom - 1] = systemTops[top - 1];
            prevConnection = connection;
        }

        // Complete assignments
        for (int i = 1; i <= systemTops.length; i++) {
            if (systemTops[i - 1] == null) {
                systemTops[i - 1] = i;
            }

            if (partTops[i - 1] == null) {
                partTops[i - 1] = i;
            }
        }

        final int[] ids = new int[staffManager.getStaffCount()];

        for (int i = 0; i < ids.length; i++) {
            ids[i] = i + 1;
        }

        logger.info("{}Staves:  {}", sheet.getLogPrefix(), ids);
        logger.info("{}Parts:   {}", sheet.getLogPrefix(), partTops);
        logger.info("{}Systems: {}", sheet.getLogPrefix(), systemTops);
    }

    //----------------//
    // getBracketKind //
    //----------------//
    private BracketKind getBracketKind (BarPeak peak)
    {
        if (peak.isSet(BRACKET_MIDDLE)) {
            return BracketKind.NONE;
        }

        if (peak.isBracketEnd(TOP)) {
            if (peak.isBracketEnd(BOTTOM)) {
                return BracketKind.BOTH;
            } else {
                return BracketKind.TOP;
            }
        }

        if (peak.isBracketEnd(BOTTOM)) {
            return BracketKind.BOTTOM;
        } else {
            return null;
        }
    }

    //------------------//
    // getMaxPeaksWidth //
    //------------------//
    /**
     * Retrieve the maximum width among all peaks.
     *
     * @return the maximum width
     */
    private int getMaxPeaksWidth ()
    {
        int maxWidth = 0;

        for (StaffInfo staff : staffManager.getStaves()) {
            for (BarPeak peak : staff.getBarPeaks()) {
                maxWidth = Math.max(maxWidth, peak.getWidth());
            }
        }

        return maxWidth;
    }

    //-----------------//
    // getPeakSections //
    //-----------------//
    /**
     * Select proper candidate sections for the peak at hand.
     *
     * @param peak        the peak to process
     * @param allSections pre-filtered sheet-level collection
     * @return the sub-collection of relevant sections
     */
    private List<Section> getPeakSections (BarPeak peak,
                                           List<Section> allSections)
    {
        final Rectangle peakBox = new Rectangle(
                peak.getStart(),
                peak.getTop(),
                peak.getWidth(),
                peak.getBottom() - peak.getTop() + 1);

        // These constants don't need precision and are large enough
        peakBox.grow(scale.getInterline(), params.bracketLookupExtension);

        final int xBreak = peakBox.x + peakBox.width;
        final List<Section> sections = new ArrayList<Section>();
        final int maxSectionWidth = peak.getWidth();

        for (Section section : allSections) {
            final Rectangle sectionBox = section.getBounds();

            if (sectionBox.intersects(peakBox)) {
                if (section.getLength(HORIZONTAL) <= maxSectionWidth) {
                    sections.add(section);
                }
            } else if (sectionBox.x >= xBreak) {
                break; // Since allSections are sorted by abscissa
            }
        }

        return sections;
    }

    //--------------------//
    // getSectionsByWidth //
    //--------------------//
    /**
     * Select relevant sections for bar sticks.
     * <p>
     * Both vertical and horizontal sections are OK if they are not wider than the maximum allowed.
     * The global collection is sorted on abscissa.
     *
     * @param maxWidth maximum section horizontal width
     * @return the abscissa-sorted list of compliant sections
     */
    private List<Section> getSectionsByWidth (int maxWidth)
    {
        List<Section> sections = new ArrayList<Section>();
        Lag hLag = sheet.getLag(Lags.HLAG);

        for (Lag lag : Arrays.asList(vLag, hLag)) {
            for (Section section : lag.getSections()) {
                if (section.getLength(HORIZONTAL) <= maxWidth) {
                    sections.add(section);
                }
            }
        }

        Collections.sort(sections, Section.byAbscissa);

        return sections;
    }

    //-------------------//
    // getWidthHistogram //
    //-------------------//
    /**
     * Build an histogram on widths of peaks.
     *
     * @return the width histogram
     */
    private Histogram<Integer> getWidthHistogram ()
    {
        final Histogram<Integer> histo = new Histogram<Integer>();

        for (StaffInfo staff : staffManager.getStaves()) {
            for (BarPeak peak : staff.getBarPeaks()) {
                if (!peak.isBracket()) {
                    int width = peak.getWidth();
                    histo.increaseCount(width, 1);
                }
            }
        }

        logger.info("Peaks width histogram {}", histo.dataString());

        return histo;
    }

    //---------------//
    // groupBarlines //
    //---------------//
    /**
     * Detect bar lines organized in groups.
     * TODO: should we exclude brackets from such groups?
     */
    private void groupBarlines ()
    {
        for (SystemInfo system : sheet.getSystems()) {
            SIGraph sig = system.getSig();

            for (StaffInfo staff : system.getStaves()) {
                BarPeak prevPeak = null;

                for (BarPeak peak : staff.getBarPeaks()) {
                    if (prevPeak != null) {
                        int gap = peak.getStart() - prevPeak.getStop() - 1;

                        if (gap <= params.maxDoubleBarGap) {
                            BarGroupRelation rel = new BarGroupRelation(scale.pixelsToFrac(gap));
                            sig.addEdge(prevPeak.getInter(), peak.getInter(), rel);
                        }
                    }

                    prevPeak = peak;
                }
            }
        }
    }

    //----------//
    // hasSerif //
    //----------//
    /**
     * Check whether the provided peak glyph exhibits a serif on desired side.
     * Define a region of interest just beyond glyph end and look for sections contained in roi.
     * Build a glyph from connected sections and check its shape.
     *
     * @param peak     provided peak
     * @param nextPeak following peak, if any
     * @param side     TOP or BOTTOM
     * @return true if serif is detected
     */
    private boolean hasSerif (StaffInfo staff,
                              BarPeak peak,
                              BarPeak nextPeak,
                              VerticalSide side)
    {
        // Constants
        final int halfLine = (int) Math.ceil(scale.getMaxFore() / 2.0);

        // Define lookup region for serif
        final Glyph barGlyph = peak.getGlyph();
        final Rectangle glyphBox = barGlyph.getBounds();
        final int yBox = (side == TOP)
                ? (Math.min(
                        glyphBox.y + params.serifThickness,
                        peak.getTop() - halfLine) - params.serifRoiHeight)
                : Math.max(
                        (glyphBox.y + glyphBox.height) - params.serifThickness,
                        peak.getBottom() + halfLine);
        final Rectangle roi = new Rectangle(
                peak.getStop() + 1,
                yBox,
                params.serifRoiWidth,
                params.serifRoiHeight);
        barGlyph.addAttachment(((side == TOP) ? "t" : "b") + "Serif", roi);

        // Look for intersected sections
        // Remove sections from bar peak (and from next peak if any)
        Lag hLag = sheet.getLag(Lags.HLAG);
        Set<Section> sections = hLag.intersectedSections(roi);
        sections.addAll(vLag.intersectedSections(roi));
        sections.removeAll(barGlyph.getMembers());

        if (nextPeak != null) {
            sections.removeAll(nextPeak.getGlyph().getMembers());
        }

        if (sections.isEmpty()) {
            return false;
        }

        // Retrieve serif glyph from sections
        Glyph serif = buildSerifGlyph(staff, sections, side, roi);
        double slope = serif.getLine().getSlope();
        logger.debug(
                "Staff#{} {} {} serif#{} weight:{} slope:{}",
                staff.getId(),
                peak,
                side,
                serif.getId(),
                serif.getWeight(),
                slope);

        if (serif.getWeight() < params.serifMinWeight) {
            logger.info(
                    "Staff#{} serif normalized weight too small {}",
                    serif.getNormalizedWeight());

            return false;
        }

        int dir = (side == TOP) ? (-1) : 1;

        if ((slope * dir) < params.serifMinSlope) {
            logger.info("Staff#{} serif slope too small {}", staff.getId(), slope * dir);

            return false;
        }

        return true;
    }

    //-----------//
    // isAligned //
    //-----------//
    /**
     * Report whether the provided peak is involved in an alignment or a connection on
     * the desired side.
     *
     * @param peak the peak to check
     * @param side which side to look
     * @return true if aligned or connected
     */
    private boolean isAligned (BarPeak peak,
                               VerticalSide side)
    {
        for (BarAlignment alignment : alignments) {
            if (alignment.getPeak(side) == peak) {
                return true;
            }
        }

        for (BarAlignment alignment : connections) {
            if (alignment.getPeak(side) == peak) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // isConnected //
    //-------------//
    /**
     * Check whether the provided peak is connected on the provided vertical side.
     *
     * @param peak the peak to check
     * @param side which vertical side to look for from peak
     * @return true if a compliant connection was found, false otherwise
     */
    private boolean isConnected (BarPeak peak,
                                 VerticalSide side)
    {
        final VerticalSide opposite = side.opposite();

        for (BarConnection connection : connections) {
            if (connection.getPeak(opposite) == peak) {
                return true;
            }
        }

        return false;
    }

    //--------------------//
    // isJustAfterBracket //
    //--------------------//
    /**
     * Check whether the provided peak is located just after a bracket end.
     * (if so, it is likely to go slightly beyond staff height)
     *
     * @param peaks sequence of peaks
     * @param peak  the provided peak
     * @param side  which vertical side is being considered
     * @return true if just after bracket end
     */
    private boolean isJustAfterBracket (List<BarPeak> peaks,
                                        BarPeak peak,
                                        VerticalSide side)
    {
        int index = peaks.indexOf(peak);

        if (index == 0) {
            return false;
        }

        BarPeak prevPeak = peaks.get(index - 1);

        if (!prevPeak.isBracketEnd(side)) {
            return false;
        }

        return (peak.getStart() - prevPeak.getStop() - 1) <= params.maxDoubleBarGap;
    }

    //-------------//
    // lookupPeaks //
    //-------------//
    /**
     * Lookup in the provided staff for one or several peaks compliant with (de-skewed)
     * peak abscissa and peak kind.
     * This populates the 'alignments' set.
     *
     * @param peak       the reference peak
     * @param side       vertical side with respect to reference peak
     * @param otherStaff the other staff to be browsed for alignment with peak
     */
    private void lookupPeaks (BarPeak peak,
                              VerticalSide side,
                              StaffInfo otherStaff)
    {
        final Skew skew = sheet.getSkew();
        final int mid = (peak.getStart() + peak.getStop()) / 2;
        final double dsk = skew.deskewed(new Point(mid, peak.getOrdinate(side))).getX();

        for (BarPeak otherPeak : otherStaff.getBarPeaks()) {
            //TODO: perhaps check that peaks widths are "compatible"?
            // Vertically aligned, taking sheet slope into account
            int otherMid = (otherPeak.getStart() + otherPeak.getStop()) / 2;
            Point otherPt = (side == TOP) ? new Point(otherMid, otherPeak.getBottom())
                    : new Point(otherMid, otherPeak.getTop());
            double otherDsk = skew.deskewed(otherPt).getX();
            double dx = scale.pixelsToFrac(otherDsk - dsk);

            if (Math.abs(dx) <= constants.maxAlignmentDx.getValue()) {
                double alignImpact = 1
                                     - (Math.abs(dx) / constants.maxAlignmentDx.getValue());
                GradeImpacts impacts = new BarAlignment.Impacts(alignImpact);
                final BarAlignment alignment;

                if (side == TOP) {
                    alignment = new BarAlignment(otherPeak, peak, -dx, impacts);
                } else {
                    alignment = new BarAlignment(peak, otherPeak, dx, impacts);
                }

                alignments.add(alignment);
            }
        }
    }

    //-----------------//
    // partitionWidths //
    //-----------------//
    /**
     * Assign each peak as being thin or thick.
     * <p>
     * We can discard the case of all page peaks being thick, so we simply have to detect whether we
     * do have some thick ones.
     * When this method is called, some peaks are still due to stems. We don't know the average
     * stem width, but typically stem peaks are thinner or equal to bar peaks.
     * <p>
     * Thin and thick Gaussian laws are computed on width histogram.
     * If there is a significant delta between thin and thick mean values then widths are
     * partitioned, else they are all considered as thin.
     */
    private void partitionWidths ()
    {
        final Gaussian thin = new Gaussian(params.typicalThinBarWidth, 1.0);
        final Gaussian thick = new Gaussian(params.typicalThickBarWidth, 1.0);

        final Histogram<Integer> histo = getWidthHistogram();
        final double[] table = new double[histo.getTotalCount()];
        int index = 0;

        for (Map.Entry<Integer, Integer> entry : histo.entrySet()) {
            int key = entry.getKey();
            int count = entry.getValue();

            for (int i = 0; i < count; i++) {
                table[index++] = key;
            }
        }

        final double[] pi = Clustering.EM(table, new Gaussian[]{thin, thick});

        //        if (logger.isDebugEnabled()) {
        logger.info(String.format("THIN  %.3f * %s", pi[0], thin));
        logger.info(String.format("THICK %.3f * %s", pi[1], thick));

        //        }
        final double deltaMean = thick.getMean() - thin.getMean();
        final double normedDelta = scale.pixelsToFrac(deltaMean);

        ///if (logger.isDebugEnabled()) {
        logger.info(
                String.format(
                        "Peaks mean widths thin:%.3f thick:%.3f delta:%.3f(%.3f)",
                        thin.getMean(),
                        thick.getMean(),
                        deltaMean,
                        normedDelta));

        ///}
        Double threshold = null;

        if (normedDelta >= constants.minThinThickDelta.getValue()) {
            SortedMap<Integer, Integer> thins = new TreeMap<Integer, Integer>();
            SortedMap<Integer, Integer> thicks = new TreeMap<Integer, Integer>();

            for (Map.Entry<Integer, Integer> entry : histo.entrySet()) {
                int w = entry.getKey();
                int count = entry.getValue();

                if (w <= thin.getMean()) {
                    thins.put(w, count);
                } else if (w >= thick.getMean()) {
                    thicks.put(w, count);
                } else {
                    double thinProba = thin.proba(w);
                    double thickProba = thick.proba(w);

                    //                if (logger.isDebugEnabled()) {
                    logger.info(
                            String.format("k:%2d thin:%.3f thick:%.3f", w, thinProba, thickProba));

                    //                }
                    if (thickProba > thinProba) {
                        thicks.put(w, count);
                    } else {
                        thins.put(w, count);
                    }
                }
            }

            logger.info("Thin peaks:{}, Thick peaks:{}", thins, thicks);
            threshold = (thins.lastKey() + thicks.firstKey()) / 2.0;
        } else {
            logger.info("All thin peaks:{}", histo.dataString());
        }

        for (StaffInfo staff : staffManager.getStaves()) {
            for (BarPeak peak : staff.getBarPeaks()) {
                if (!peak.isBracket()) {
                    if ((threshold == null) || (peak.getWidth() <= threshold)) {
                        peak.set(THIN);
                    } else {
                        peak.set(THICK);
                    }
                }
            }
        }
    }

    //-----------------//
    // purgeAlignments //
    //-----------------//
    /**
     * Purge the alignments collection.
     * <ul>
     * <li>Remove any alignment that conflicts with a connection.
     * Any connection is given priority against conflicting alignment (simply because connection was
     * validated by presence of enough black pixels in the inter-staff region)</li>
     * <li>Remove duplicates: in the collection of alignments a peak should appear at most once as
     * top and at most once as bottom. In case of conflict, use alignment quality to disambiguate.
     * TODO: A more complex approach to disambiguate could use detection of pair of bars aligned
     * with another pair of bars, and align left with left and right with right. But is it worth the
     * added complexity?</li>
     * </ul>
     */
    private void purgeAlignments ()
    {
        // Purge alignments vs connections
        for (BarConnection connection : connections) {
            for (VerticalSide side : VerticalSide.values()) {
                for (BarAlignment alignment : alignmentsOf(connection.getPeak(side), side)) {
                    alignments.remove(alignment);
                    logger.debug("Removed {}", alignment);
                }
            }
        }

        // Check duplicate alignments (looking to top & bottom)
        for (VerticalSide side : VerticalSide.values()) {
            Map<BarPeak, BarAlignment> map = new HashMap<BarPeak, BarAlignment>();
            Set<BarAlignment> toRemove = new HashSet<BarAlignment>();

            for (BarAlignment alignment : alignments) {
                BarPeak peak = alignment.getPeak(side);
                BarAlignment otherAlignment = map.get(peak);

                if (otherAlignment != null) {
                    // We have a conflict here, make a decision
                    logger.debug("Conflict {} vs {}", alignment, otherAlignment);

                    if (Math.abs(otherAlignment.dx) <= Math.abs(alignment.dx)) {
                        toRemove.add(alignment);
                    } else {
                        toRemove.add(otherAlignment);
                        map.put(peak, alignment);
                    }
                } else {
                    map.put(peak, alignment);
                }
            }

            if (!toRemove.isEmpty()) {
                logger.debug("Purging {}", toRemove);
                alignments.removeAll(toRemove);
            }
        }
    }

    //-----------------//
    // purgeBracePeaks //
    //-----------------//
    /**
     * Purge brace portions mistaken for bar lines peaks.
     * Wrong bar line peaks may result from mistakes on brace portion.
     * Such brace portions are characterized with:
     * - Short average curvature (we use this!)
     * - Low derivative
     * - Location on left side of the staff
     * - Small no-staff blank separation from rest of staff (but perhaps reduced to nothing)
     * - Significant thickness
     */
    private void purgeBracePeaks ()
    {
        for (StaffInfo staff : staffManager.getStaves()) {
            List<BarPeak> toRemove = new ArrayList<BarPeak>();

            for (BarPeak peak : staff.getBarPeaks()) {
                Filament glyph = (Filament) peak.getGlyph();
                double curvature = glyph.getMeanCurvature();

                if (curvature < params.minBarCurvature) {
                    if (glyph.isVip()) {
                        logger.info("VIP removing curved {} glyph#{}", peak, glyph.getId());
                    }

                    peak.set(BRACE);
                    toRemove.add(peak);
                }
            }

            if (!toRemove.isEmpty()) {
                logger.debug("Staff#{} removing curved {}", staff.getId(), toRemove);
                staff.removePeaks(toRemove);
            }
        }
    }

    //-------------//
    // purgeCClefs //
    //-------------//
    /**
     * Purge C-Clef portions mistaken for bar lines.
     * <p>
     * A C-clef exhibits a pair of peaks (a rather thick one followed by a rather thin one).
     * It should be looked for in two location kinds:
     * <ul>
     * <li>At the very beginning of staff with no initial bar line, with only a short chunk of staff
     * lines, so that first peak is not a staff end.</li>
     * <li>After a bar line, provided this bar line is not part of a thin + thick + thin group.
     * For this case the horizontal gap between bar line and start of C-clef must be larger than
     * maximum multi-bar gap.</li>
     * </ul>
     */
    private void purgeCClefs ()
    {
        for (StaffInfo staff : staffManager.getStaves()) {
            final List<BarPeak> peaks = staff.getBarPeaks();
            final int staffStart = staff.getAbscissa(LEFT);
            int measureStart = staffStart;

            for (int i = 0; i < peaks.size(); i++) {
                BarPeak peak = peaks.get(i);

                if (peak.getStart() <= measureStart) {
                    continue;
                }

                // Look for a rather thick first peak
                if (!peak.isSet(STAFF_END)
                    && !peak.isBracket()
                    && (peak.getWidth() >= params.minPeak1WidthForCClef)) {
                    // Check gap is larger than multi-bar gap but smaller than measure
                    int gap = peak.getStart() - measureStart;

                    // Gap is not relevant for first measure, thanks to !peak.isEnd() test
                    int minGap = (measureStart == staffStart) ? 0 : params.maxDoubleBarGap;

                    if ((gap > minGap)
                        && (gap < params.minMeasureWidth)
                        && !isConnected(peak, TOP)
                        && !isConnected(peak, BOTTOM)) {
                        if (logger.isDebugEnabled() || peak.getGlyph().isVip()) {
                            logger.info("VIP Got a C-Clef peak1 at {}", peak);
                        }

                        final List<BarPeak> toRemove = new ArrayList<BarPeak>();
                        peak.set(CCLEF_ONE);
                        toRemove.add(peak);

                        // Look for a rather thin second peak right after the first
                        if ((i + 1) < peaks.size()) {
                            final BarPeak peak2 = peaks.get(i + 1);
                            int gap2 = peak2.getStart() - peak.getStop() - 1;

                            if ((peak2.getWidth() <= params.maxPeak2WidthForCClef)
                                && (gap2 <= params.maxDoubleBarGap)
                                && !isConnected(peak2, TOP)
                                && !isConnected(peak2, BOTTOM)) {
                                if (logger.isDebugEnabled()
                                    || peak.getGlyph().isVip()
                                    || peak2.getGlyph().isVip()) {
                                    logger.info("VIP Got a C-Clef peak2 at {}", peak2);
                                }

                                peak2.set(CCLEF_TWO);
                                toRemove.add(peak2);
                                logger.debug("Staff#{} purging C-Clef {}", staff.getId(), toRemove);
                                i++; // Don't re-browse this peak

                                // Avoid false peaks before the end of C-Clef has been passed
                                if ((i + 1) < peaks.size()) {
                                    int mid2 = (peak2.getStart() + peak2.getStop()) / 2;
                                    int xBreak = mid2 + params.cClefTail;

                                    for (BarPeak otherPeak : peaks.subList(i + 1, peaks.size())) {
                                        int otherMid = (otherPeak.getStart() + otherPeak.getStop()) / 2;

                                        if (otherMid < xBreak) {
                                            logger.debug(
                                                    "Staff#{} purging tail of C-Clef {}",
                                                    staff.getId(),
                                                    otherPeak);
                                            otherPeak.set(CCLEF_TAIL);
                                            toRemove.add(otherPeak);
                                            i++; // Don't re-browse this peak
                                        } else {
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        staff.removePeaks(toRemove);
                    } else {
                        measureStart = peak.getStop() + 1;
                    }
                } else {
                    measureStart = peak.getStop() + 1;
                }
            }
        }
    }

    //------------------//
    // purgeConnections //
    //------------------//
    /**
     * Purge the connections collection of duplicates.
     * <p>
     * In the collection of connections a peak should appear at most once as
     * top and at most once as bottom. In case of conflict, use connection quality to disambiguate.
     */
    private void purgeConnections ()
    {
        // Check duplicate connections (looking to top & bottom)
        for (VerticalSide side : VerticalSide.values()) {
            Map<BarPeak, BarConnection> map = new HashMap<BarPeak, BarConnection>();
            Set<BarConnection> toRemove = new HashSet<BarConnection>();

            for (BarConnection connection : connections) {
                BarPeak peak = connection.getPeak(side);
                BarConnection otherConnection = map.get(peak);

                if (otherConnection != null) {
                    // We have a conflict here, make a decision
                    logger.debug("Conflict {} vs {}", connection, otherConnection);

                    if (otherConnection.getImpacts().getGrade() >= connection.getImpacts().getGrade()) {
                        toRemove.add(connection);
                    } else {
                        toRemove.add(otherConnection);
                        map.put(peak, connection);
                    }
                } else {
                    map.put(peak, connection);
                }
            }

            if (!toRemove.isEmpty()) {
                logger.debug("Purging {}", toRemove);
                connections.removeAll(toRemove);
            }
        }
    }

    //----------------------//
    // purgeCrossAlignments //
    //----------------------//
    /**
     * Only alignments within a system are meaningful.
     * So, alignments across systems must be deleted.
     */
    private void purgeCrossAlignments ()
    {
        final Set<BarAlignment> toRemove = new HashSet<BarAlignment>();

        for (BarAlignment alignment : alignments) {
            final SystemInfo s1 = alignment.getPeak(TOP).getStaff().getSystem();
            final SystemInfo s2 = alignment.getPeak(BOTTOM).getStaff().getSystem();

            if (s1 != s2) {
                toRemove.add(alignment);
            }
        }

        if (!toRemove.isEmpty()) {
            logger.debug("Purging cross-system alignments{}", toRemove);
            alignments.removeAll(toRemove);
        }
    }

    //----------------//
    // purgeLongPeaks //
    //----------------//
    /**
     * Purge long thin bars (those getting above or below the related staff) that do not
     * connect staves.
     * <p>
     * Thick bars are not concerned by this test, because they cannot be mistaken for stems and can
     * appear to be extended because of brackets.
     * <p>
     * The check is relaxed for a bar which is aligned with another bar that exhibits no such length
     * problem.
     */
    private void purgeLongPeaks ()
    {
        for (StaffInfo staff : staffManager.getStaves()) {
            final Set<BarPeak> toRemove = new LinkedHashSet<BarPeak>();

            PeakLoop:
            for (BarPeak peak : staff.getBarPeaks()) {
                if (peak.getGlyph().isVip()) {
                    logger.info("VIP purgeLongPeaks on staff#{} {}", staff.getId(), peak);
                }

                // Check whether peak goes beyond staff
                for (VerticalSide side : VerticalSide.values()) {
                    if (peak.isBeyond(side) && !isConnected(peak, side)) {
                        // Relax check. TODO: is this OK?
                        List<BarPeak> partners = alignedPeaks(peak, side);

                        if (partners.size() == 1) {
                            BarPeak partner = partners.get(0);

                            if (!partner.isBeyond()) {
                                // Consider this bar as safe
                                continue PeakLoop;
                            }
                        }

                        if (peak.getGlyph().isVip()) {
                            logger.info("VIP removed long on staff#{} {}", staff.getId(), peak);
                        }

                        toRemove.add(peak);
                    }
                }
            }

            if (!toRemove.isEmpty()) {
                logger.debug("Staff#{} removing longs {}", staff.getId(), toRemove);
                staff.removePeaks(toRemove);

                // Delete the alignments or connections that involved those peaks
                purgeRelations(toRemove, alignments);
                purgeRelations(toRemove, connections);
            }
        }
    }

    //----------------//
    // purgeRelations //
    //----------------//
    /**
     * Due to peaks being removed, delete the relations (alignments, connections) where
     * those peaks were involved.
     *
     * @param removedPeaks the peaks removed
     * @param rels         the collection to purge
     */
    private void purgeRelations (Set<BarPeak> removedPeaks,
                                 Set<? extends BarAlignment> rels)
    {
        for (Iterator<? extends BarAlignment> it = rels.iterator(); it.hasNext();) {
            BarAlignment alignment = it.next();

            for (VerticalSide side : VerticalSide.values()) {
                BarPeak peak = alignment.getPeak(side);

                if (removedPeaks.contains(peak)) {
                    it.remove();

                    break;
                }
            }
        }
    }

    //------------//
    // recordBars //
    //------------//
    private void recordBars ()
    {
        for (SystemInfo system : sheet.getSystems()) {
            for (StaffInfo staff : system.getStaves()) {
                List<BarPeak> peaks = staff.getBarPeaks();

                // All bars
                List<BarlineInter> bars = new ArrayList<BarlineInter>();

                for (BarPeak peak : peaks) {
                    AbstractVerticalInter inter = peak.getInter();

                    if (inter instanceof BarlineInter) {
                        bars.add((BarlineInter) inter);
                    }
                }

                staff.setBars(bars);

                // Side bars
                if (!bars.isEmpty()) {
                    for (HorizontalSide side : HorizontalSide.values()) {
                        BarlineInter bar = bars.get((side == LEFT) ? 0 : (bars.size() - 1));
                        Rectangle box = bar.getBounds();
                        int end = staff.getAbscissa(side);

                        if ((end >= box.x) && (end <= ((box.x + box.width) - 1))) {
                            staff.setSideBar(side, bar);
                        }
                    }
                }
            }
        }
    }

    //-------------//
    // refineSides //
    //-------------//
    private void refineSides ()
    {
        for (StaffProjector projector : projectors) {
            projector.refineStaffSides();
        }
    }

    //----------------//
    // setVipSections //
    //----------------//
    private void setVipSections ()
    {
        // Debug sections VIPs
        for (int id : params.vipSections) {
            Section sect = vLag.getVertexById(id);

            if (sect != null) {
                sect.setVip();
                logger.info("Vertical vip section: {}", sect);
            }
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        Constant.Boolean showVerticalLines = new Constant.Boolean(
                false,
                "Should we display the vertical lines?");

        Constant.String verticalVipSections = new Constant.String(
                "",
                "(Debug) Comma-separated list of VIP vertical sections");

        final Scale.Fraction typicalThinBarWidth = new Scale.Fraction(
                0.25,
                "Typical width for a THIN bar line");

        final Scale.Fraction typicalThickBarWidth = new Scale.Fraction(
                0.45,
                "Typical width for a THICK bar line");

        final Scale.Fraction minThinThickDelta = new Scale.Fraction(
                0.2,
                "Minimum difference between THIN/THICK mean values");

        Scale.Fraction maxAlignmentDx = new Scale.Fraction(
                0.5,
                "Max abscissa shift for bar alignment");

        Scale.Fraction maxConnectionGap = new Scale.Fraction(
                2.0,
                "Max vertical gap when connecting bar lines");

        Constant.Ratio maxConnectionWhiteRatio = new Constant.Ratio(
                0.25,
                "Max white ratio when connecting bar lines");

        Scale.Fraction maxRunShift = new Scale.Fraction(
                0.05,
                "Max shift between two runs of vertical sections");

        Scale.Fraction maxBarExtension = new Scale.Fraction(
                0.3,
                "Maximum extension for a bar line above or below staff line");

        Scale.Fraction minBarCurvature = new Scale.Fraction(
                20,
                "Minimum mean curvature for a bar line (rather than a brace)");

        Scale.Fraction maxDoubleBarGap = new Scale.Fraction(
                0.6,
                "Max horizontal gap between two members of a double bar");

        Scale.Fraction minMeasureWidth = new Scale.Fraction(
                2.0,
                "Minimum width for a measure");

        Constant.Ratio alignedIncreaseRatio = new Constant.Ratio(
                0.30,
                "Boost ratio for aligned bar lines");

        Constant.Ratio unalignedDecreaseRatio = new Constant.Ratio(
                0.30,
                "Penalty ratio for unaligned bar lines (in multi-staff systems)");

        Constant.Boolean deleteUnalignedBars = new Constant.Boolean(
                true,
                "Should unaligned bar lines be deleted? (in multi-staff systems)");

        // For C-clefs -----------------------------------------------------------------------------
        //
        Scale.Fraction minPeak1WidthForCClef = new Scale.Fraction(
                0.3,
                "Minimum width for first peak of C-Clef");

        Scale.Fraction maxPeak2WidthForCClef = new Scale.Fraction(
                0.3,
                "Maximum width for second peak of C-Clef");

        Scale.Fraction cClefTail = new Scale.Fraction(
                2.0,
                "Typical width for tail of C-Clef, from second peak to right end");

        // For brackets ----------------------------------------------------------------------------
        //
        Scale.Fraction minBracketWidth = new Scale.Fraction(
                0.4,
                "Minimum width for a bracket peak");

        Scale.Fraction maxBracketExtension = new Scale.Fraction(
                1.25,
                "Maximum extension for bracket end above or below staff line");

        Scale.Fraction bracketLookupExtension = new Scale.Fraction(
                2.0,
                "Lookup height for bracket end above or below staff line");

        Scale.Fraction serifRoiWidth = new Scale.Fraction(
                2.0,
                "Width of lookup ROI for bracket serif");

        Scale.Fraction serifRoiHeight = new Scale.Fraction(
                2.0,
                "Height of lookup ROI for bracket serif");

        Scale.Fraction serifThickness = new Scale.Fraction(
                0.3,
                "Typical thickness of bracket serif");

        Scale.AreaFraction serifMinWeight = new Scale.AreaFraction(
                0.25,
                "Minimum weight for bracket serif");

        Constant.Double serifMinSlope = new Constant.Double(
                "tangent",
                0.25,
                "Minimum absolute slope for bracket serif");
    }

    //------------//
    // Parameters //
    //------------//
    private static class Parameters
    {
        //~ Instance fields ------------------------------------------------------------------------

        final int typicalThinBarWidth;

        final int typicalThickBarWidth;

        final int maxAlignmentDx;

        final int maxRunShift;

        final double maxBarExtension;

        final int minBarCurvature;

        final int maxConnectionGap;

        final double maxConnectionWhiteRatio;

        final int maxDoubleBarGap;

        final int minMeasureWidth;

        final int minPeak1WidthForCClef;

        final int maxPeak2WidthForCClef;

        final int cClefTail;

        final int minBracketWidth;

        final int maxBracketExtension;

        final int bracketLookupExtension;

        final int serifRoiWidth;

        final int serifRoiHeight;

        final int serifThickness;

        final int serifMinWeight;

        final double serifMinSlope;

        // Debug
        final List<Integer> vipSections;

        //~ Constructors ---------------------------------------------------------------------------
        /**
         * Creates a new Parameters object.
         *
         * @param scale the scaling factor
         */
        public Parameters (Scale scale)
        {
            typicalThinBarWidth = scale.toPixels(constants.typicalThinBarWidth);
            typicalThickBarWidth = scale.toPixels(constants.typicalThickBarWidth);
            maxAlignmentDx = scale.toPixels(constants.maxAlignmentDx);
            maxRunShift = scale.toPixels(constants.maxRunShift);
            maxBarExtension = scale.toPixels(constants.maxBarExtension);
            minBarCurvature = scale.toPixels(constants.minBarCurvature);
            maxConnectionGap = scale.toPixels(constants.maxConnectionGap);
            maxConnectionWhiteRatio = constants.maxConnectionWhiteRatio.getValue();
            maxDoubleBarGap = scale.toPixels(constants.maxDoubleBarGap);
            minMeasureWidth = scale.toPixels(constants.minMeasureWidth);

            cClefTail = scale.toPixels(constants.cClefTail);
            minPeak1WidthForCClef = scale.toPixels(constants.minPeak1WidthForCClef);
            maxPeak2WidthForCClef = scale.toPixels(constants.maxPeak2WidthForCClef);

            minBracketWidth = scale.toPixels(constants.minBracketWidth);
            maxBracketExtension = scale.toPixels(constants.maxBracketExtension);
            bracketLookupExtension = scale.toPixels(constants.bracketLookupExtension);
            serifRoiWidth = scale.toPixels(constants.serifRoiWidth);
            serifRoiHeight = scale.toPixels(constants.serifRoiHeight);
            serifThickness = scale.toPixels(constants.serifThickness);
            serifMinWeight = scale.toPixels(constants.serifMinWeight);
            serifMinSlope = constants.serifMinSlope.getValue();

            // VIPs
            vipSections = IntUtil.parseInts(constants.verticalVipSections.getValue());

            if (logger.isDebugEnabled()) {
                Main.dumping.dump(this);
            }

            if (!vipSections.isEmpty()) {
                logger.info("Vertical VIP sections: {}", vipSections);
            }
        }
    }
}
