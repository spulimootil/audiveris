//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      S y m b o l M e n u                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Hervé Bitteur and others 2000-2017. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.glyph.ui;

import org.audiveris.omr.classifier.Classifier;

import static org.audiveris.omr.classifier.Classifier.Condition.ALLOWED;
import static org.audiveris.omr.classifier.Classifier.Condition.CHECKED;

import org.audiveris.omr.classifier.Evaluation;
import org.audiveris.omr.classifier.ShapeClassifier;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.Grades;
import org.audiveris.omr.glyph.Shape;
import org.audiveris.omr.glyph.ShapeSet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.SystemManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static javax.swing.Action.NAME;
import static javax.swing.Action.SHORT_DESCRIPTION;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

/**
 * Class {@code SymbolMenu} defines the menu which is linked to the current selection of
 * one or several glyphs.
 * <p>
 * <b>THIS MENU IS CURRENTLY DISABLED</b>
 *
 * @author Hervé Bitteur
 */
public class SymbolMenu
        extends AbstractActionMenu
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(SymbolMenu.class);

    //~ Instance fields ----------------------------------------------------------------------------
    /** The controller in charge of user gesture */
    private final GlyphsController controller;

    // Links to partnering entities
    private final ShapeFocusBoard shapeFocus;

    private Classifier classifier;

    // To handle proposed compound shape
    private Glyph proposedGlyph;

    private Shape proposedShape;

    /** Current number of known glyphs */
    private int knownNb;

    /** Current number of stems */
    private int stemNb;

    /** Current number of virtual glyphs */
    private int virtualNb;

    /** Sure we have no virtual glyphs? */
    private boolean noVirtuals;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create the Symbol menu.
     *
     * @param symbolsController the top companion
     * @param shapeFocus        the current shape focus
     */
    public SymbolMenu (final SymbolsController symbolsController,
                       ShapeFocusBoard shapeFocus)
    {
        super(symbolsController.getModel().getSheet(), "Glyphs ...");

        this.controller = symbolsController;
        this.shapeFocus = shapeFocus;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //------------//
    // updateMenu //
    //------------//
    @Override
    public int updateMenu (Collection<Glyph> glyphs)
    {
        // Analyze the context
        knownNb = 0;
        stemNb = 0;
        virtualNb = 0;

        if (glyphs != null) {
            for (Glyph glyph : glyphs) {
                if (glyph.isVirtual()) {
                    virtualNb++;
                }
            }
        }

        noVirtuals = (virtualNb == 0);

        return super.updateMenu(glyphs);
    }

    //----------//
    // initMenu //
    //----------//
    @Override
    protected void initMenu ()
    {
        // Copy & Paste actions
        //        register(0, new PasteAction());
        //        register(0, new CopyAction());

        // Deassign selected glyph(s)
        register(0, new DeassignAction());

        // Manually assign a shape
        register(0, new AssignAction());

        // Build a compound, with menu for shape selection
        register(0, new CompoundAction());

        // Segment the glyph into stems & leaves
        register(0, new StemSegmentAction());

        // Build a compound, with proposed shape
        register(0, new ProposedAction());

        // Trim large slur glyphs
        register(0, new TrimSlurAction());

        // Dump current glyph(s)
        register(0, new DumpAction());

        // Dump current glyph(s) text info
        register(0, new DumpTextAction());

        // Display score counterpart
        ///register(0, new TranslationAction());
        // Display all glyphs of the same shape
        ///register(0, new ShapeAction());
        // Display all glyphs similar to the current glyph
        register(0, new SimilarAction());

        super.initMenu();
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //--------------//
    // AssignAction //
    //--------------//
    /**
     * Assign to each glyph the shape selected in the menu.
     */
    protected class AssignAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public AssignAction ()
        {
            super(20);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            // Default action is to open the menu
            assert false;
        }

        @Override
        public JMenuItem getMenuItem ()
        {
            JMenu menu = new JMenu(this);
            ShapeSet.addAllShapes(menu, new AssignListener(false));

            return menu;
        }

        @Override
        public void update ()
        {
            if ((glyphNb > 0) && noVirtuals) {
                setEnabled(true);

                if (glyphNb == 1) {
                    putValue(NAME, "Assign glyph as ...");
                } else {
                    putValue(NAME, "Assign each glyph as ...");
                }

                putValue(SHORT_DESCRIPTION, "Manually force an assignment");
            } else {
                setEnabled(false);
                putValue(NAME, "Assign glyph as ...");
                putValue(SHORT_DESCRIPTION, "No glyph to assign a shape to");
            }
        }
    }

    //----------------//
    // AssignListener //
    //----------------//
    /**
     * A standard listener used in all shape assignment menus.
     */
    protected class AssignListener
            implements ActionListener
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final boolean compound;

        //~ Constructors ---------------------------------------------------------------------------
        /**
         * Creates the AssignListener, with the compound flag.
         *
         * @param compound true if we assign a compound, false otherwise
         */
        public AssignListener (boolean compound)
        {
            this.compound = compound;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            JMenuItem source = (JMenuItem) e.getSource();
            controller.asyncAssignGlyphs(
                    nest.getSelectedGlyphList(),
                    Shape.valueOf(source.getText()),
                    compound);
        }
    }

    //----------------//
    // CompoundAction //
    //----------------//
    /**
     * Build a compound and assign the shape selected in the menu.
     */
    protected class CompoundAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public CompoundAction ()
        {
            super(30);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            // Default action is to open the menu
            assert false;
        }

        @Override
        public JMenuItem getMenuItem ()
        {
            JMenu menu = new JMenu(this);
            ShapeSet.addAllShapes(menu, new AssignListener(true));

            return menu;
        }

        @Override
        public void update ()
        {
            if ((glyphNb > 1) && noVirtuals) {
                setEnabled(true);
                putValue(NAME, "Build compound as ...");
                putValue(SHORT_DESCRIPTION, "Manually build a compound");
            } else {
                setEnabled(false);
                putValue(NAME, "No compound");
                putValue(SHORT_DESCRIPTION, "No glyphs for a compound");
            }
        }
    }

    //
    //    //------------//
    //    // CopyAction //
    //    //------------//
    //    /**
    //     * Copy the shape of the selected glyph shape (in order to replicate
    //     * the assignment to another glyph later).
    //     */
    //    protected class CopyAction
    //            extends DynAction
    //    {
    //        //~ Constructors ---------------------------------------------------------------------------
    //
    //        public CopyAction ()
    //        {
    //            super(10);
    //        }
    //
    //        //~ Methods --------------------------------------------------------------------------------
    //        @Override
    //        public void actionPerformed (ActionEvent e)
    //        {
    //            Glyph glyph = nest.getSelectedGlyph();
    //
    //            if (glyph != null) {
    //                Shape shape = glyph.getShape();
    //
    //                if (shape != null) {
    //                    controller.setLatestShapeAssigned(shape);
    //                }
    //            }
    //        }
    //
    //        @Override
    //        public void update ()
    //        {
    //            Glyph glyph = nest.getSelectedGlyph();
    //
    //            if (glyph != null) {
    //                Shape shape = glyph.getShape();
    //
    //                if (shape != null) {
    //                    setEnabled(true);
    //                    putValue(NAME, "Copy " + shape);
    //                    putValue(SHORT_DESCRIPTION, "Copy this shape");
    //
    //                    return;
    //                }
    //            }
    //
    //            setEnabled(false);
    //            putValue(NAME, "Copy");
    //            putValue(SHORT_DESCRIPTION, "No shape to copy");
    //        }
    //    }
    //
    //----------------//
    // DeassignAction //
    //----------------//
    /**
     * Deassign each glyph in the selected collection of glyphs.
     */
    protected class DeassignAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public DeassignAction ()
        {
            super(20);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            // Remember which is the current selected glyph
            Glyph glyph = nest.getSelectedGlyph();

            // Actually deassign the whole set
            List<Glyph> glyphs = nest.getSelectedGlyphList();

            if (noVirtuals) {
                controller.asyncDeassignGlyphs(glyphs);

                //
                //                // Update focus on current glyph, if reused in a compound
                //                if (glyph != null) {
                //                    Glyph newGlyph = glyph.getFirstSection().getCompound();
                //
                //                    if (glyph != newGlyph) {
                //                        nest.getEntityService().publish(
                //                                new GlyphEvent(this, SelectionHint.GLYPH_INIT, null, newGlyph));
                //                    }
                //                }
            } else {
                controller.asyncDeleteVirtualGlyphs(glyphs);
            }
        }

        @Override
        public void update ()
        {
            if ((knownNb > 0) && (noVirtuals || (virtualNb == knownNb))) {
                setEnabled(true);

                StringBuilder sb = new StringBuilder();

                if (noVirtuals) {
                    sb.append("Deassign ");
                    sb.append(knownNb).append(" glyph");

                    if (knownNb > 1) {
                        sb.append("s");
                    }
                } else {
                    sb.append("Delete ");

                    if (virtualNb > 0) {
                        sb.append(virtualNb).append(" virtual glyph");

                        if (virtualNb > 1) {
                            sb.append("s");
                        }
                    }
                }

                if (stemNb > 0) {
                    sb.append(" w/ ").append(stemNb).append(" stem");

                    if (stemNb > 1) {
                        sb.append("s");
                    }
                }

                putValue(NAME, sb.toString());
                putValue(SHORT_DESCRIPTION, "Deassign selected glyphs");
            } else {
                setEnabled(false);
                putValue(NAME, "Deassign");
                putValue(SHORT_DESCRIPTION, "No glyph to deassign");
            }
        }
    }

    //------------//
    // DumpAction //
    //------------//
    /**
     * Dump each glyph in the selected collection of glyphs.
     */
    protected class DumpAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public DumpAction ()
        {
            super(40);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            for (Glyph glyph : nest.getSelectedGlyphList()) {
                logger.info(glyph.dumpOf());
            }
        }

        @Override
        public void update ()
        {
            if (glyphNb > 0) {
                setEnabled(true);

                StringBuilder sb = new StringBuilder();
                sb.append("Dump ").append(glyphNb).append(" glyph");

                if (glyphNb > 1) {
                    sb.append("s");
                }

                putValue(NAME, sb.toString());
                putValue(SHORT_DESCRIPTION, "Dump selected glyphs");
            } else {
                setEnabled(false);
                putValue(NAME, "Dump");
                putValue(SHORT_DESCRIPTION, "No glyph to dump");
            }
        }
    }

    //
    //    //-------------//
    //    // PasteAction //
    //    //-------------//
    //    /**
    //     * Paste the latest shape to the glyph(s) at end.
    //     */
    //    protected class PasteAction
    //            extends DynAction
    //    {
    //        //~ Static fields/initializers -------------------------------------------------------------
    //
    //        private static final String PREFIX = "Paste ";
    //
    //        //~ Constructors ---------------------------------------------------------------------------
    //        public PasteAction ()
    //        {
    //            super(10);
    //        }
    //
    //        //~ Methods --------------------------------------------------------------------------------
    //        @Override
    //        public void actionPerformed (ActionEvent e)
    //        {
    //            JMenuItem source = (JMenuItem) e.getSource();
    //            Shape shape = Shape.valueOf(source.getText().substring(PREFIX.length()));
    //            Glyph glyph = nest.getSelectedGlyph();
    //
    //            if (glyph != null) {
    //                controller.asyncAssignGlyphs(Collections.singleton(glyph), shape, false);
    //            }
    //        }
    //
    //        @Override
    //        public void update ()
    //        {
    //            Shape latest = controller.getLatestShapeAssigned();
    //
    //            if ((glyphNb > 0) && (latest != null) && noVirtuals) {
    //                setEnabled(true);
    //                putValue(NAME, PREFIX + latest.toString());
    //                putValue(SHORT_DESCRIPTION, "Assign latest shape");
    //            } else {
    //                setEnabled(false);
    //                putValue(NAME, PREFIX);
    //                putValue(SHORT_DESCRIPTION, "No shape to assign again");
    //            }
    //        }
    //    }
    //
    //----------------//
    // DumpTextAction //
    //----------------//
    /**
     * Dump the text information of each glyph in the selected
     * collection of glyphs.
     */
    private class DumpTextAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public DumpTextAction ()
        {
            super(40);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            for (Glyph glyph : nest.getSelectedGlyphList()) {
                logger.info(glyph.dumpOf());
            }
        }

        @Override
        public void update ()
        {
            if (glyphNb > 0) {
                setEnabled(true);

                StringBuilder sb = new StringBuilder();
                sb.append("Dump text of ").append(glyphNb).append(" glyph");

                if (glyphNb > 1) {
                    sb.append("s");
                }

                putValue(NAME, sb.toString());
                putValue(SHORT_DESCRIPTION, "Dump text of selected glyphs");
            } else {
                setEnabled(false);
                putValue(NAME, "Dump text");
                putValue(SHORT_DESCRIPTION, "No glyph to dump text");
            }
        }
    }

    //----------------//
    // ProposedAction //
    //----------------//
    /**
     * Accept the proposed compound with its evaluated shape.
     */
    private class ProposedAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public ProposedAction ()
        {
            super(30);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            Glyph glyph = nest.getSelectedGlyph();

            if ((glyph != null) && (glyph == proposedGlyph)) {
                controller.asyncAssignGlyphs(Collections.singleton(glyph), proposedShape, false);
            }
        }

        @Override
        public void update ()
        {
            // Proposed compound?
            Glyph glyph = nest.getSelectedGlyph();

            if ((glyphNb > 0) && (glyph != null) && (glyph.getId() == 0)) {
                SystemManager systemManager = sheet.getSystemManager();

                for (SystemInfo system : systemManager.getSystemsOf(glyph)) {
                    if (classifier == null) {
                        classifier = ShapeClassifier.getInstance();
                    }

                    Evaluation[] evals = classifier.evaluate(
                            glyph,
                            system,
                            1,
                            Grades.symbolMinGrade,
                            EnumSet.of(ALLOWED, CHECKED));

                    if (evals.length > 0) {
                        proposedGlyph = glyph;
                        proposedShape = evals[0].shape;
                        setEnabled(true);
                        putValue(NAME, "Build compound as " + proposedShape);
                        putValue(SHORT_DESCRIPTION, "Accept the proposed compound");

                        return;
                    }
                }
            }

            // Nothing to propose
            proposedGlyph = null;
            proposedShape = null;
            setEnabled(false);
            putValue(NAME, "Build compound");
            putValue(SHORT_DESCRIPTION, "No proposed compound");
        }
    }

    //
    //    //-------------//
    //    // ShapeAction //
    //    //-------------//
    //    /**
    //     * Set the focus on all glyphs with the same shape.
    //     */
    //    private class ShapeAction
    //            extends DynAction
    //    {
    //        //~ Constructors ---------------------------------------------------------------------------
    //
    //        public ShapeAction ()
    //        {
    //            super(70);
    //        }
    //
    //        //~ Methods --------------------------------------------------------------------------------
    //        @Override
    //        public void actionPerformed (ActionEvent e)
    //        {
    //            Set<Glyph> glyphs = nest.getSelectedGlyphSet();
    //
    //            if ((glyphs != null) && (glyphs.size() == 1)) {
    //                Glyph glyph = glyphs.iterator().next();
    //
    //                if (glyph.getShape() != null) {
    //                    shapeFocus.setCurrentShape(glyph.getShape());
    //                }
    //            }
    //        }
    //
    //        @Override
    //        public void update ()
    //        {
    //            Glyph glyph = nest.getSelectedGlyph();
    //
    //            if ((glyph != null) && (glyph.getShape() != null)) {
    //                setEnabled(true);
    //                putValue(NAME, "Show all " + glyph.getShape() + "'s");
    //                putValue(SHORT_DESCRIPTION, "Display all glyphs of this shape");
    //            } else {
    //                setEnabled(false);
    //                putValue(NAME, "Show all");
    //                putValue(SHORT_DESCRIPTION, "No shape defined");
    //            }
    //        }
    //    }
    //
    //---------------//
    // SimilarAction //
    //---------------//
    /**
     * Set the focus on all glyphs similar to the selected glyph.
     */
    private class SimilarAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public SimilarAction ()
        {
            super(70);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            List<Glyph> glyphs = nest.getSelectedGlyphList();

            if ((glyphs != null) && (glyphs.size() == 1)) {
                Glyph glyph = glyphs.iterator().next();

                if (glyph != null) {
                    shapeFocus.setSimilarGlyph(glyph);
                }
            }
        }

        @Override
        public void update ()
        {
            Glyph glyph = nest.getSelectedGlyph();

            if (glyph != null) {
                setEnabled(true);
                putValue(NAME, "Show similar glyphs");
                putValue(SHORT_DESCRIPTION, "Display all glyphs similar to this one");
            } else {
                setEnabled(false);
                putValue(NAME, "Show similar");
                putValue(SHORT_DESCRIPTION, "No glyph selected");
            }
        }
    }

    //-------------------//
    // StemSegmentAction //
    //-------------------//
    /**
     * Perform a segmentation on the selected glyphs, into stems and
     * leaves.
     */
    private class StemSegmentAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public StemSegmentAction ()
        {
            super(50);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            List<Glyph> glyphs = nest.getSelectedGlyphList();
            ((SymbolsController) controller).asyncSegment(glyphs);
        }

        @Override
        public void update ()
        {
            putValue(NAME, "Look for verticals");

            if ((glyphNb > 0) && noVirtuals) {
                setEnabled(true);
                putValue(SHORT_DESCRIPTION, "Extract stems and leaves");
            } else {
                setEnabled(false);
                putValue(SHORT_DESCRIPTION, "No glyph to segment");
            }
        }
    }

    //
    //    //-------------------//
    //    // TranslationAction //
    //    //-------------------//
    //    /**
    //     * Display the score entity that translates this glyph.
    //     */
    //    private class TranslationAction
    //            extends DynAction
    //    {
    //        //~ Constructors ---------------------------------------------------------------------------
    //
    //        public TranslationAction ()
    //        {
    //            super(40);
    //        }
    //
    //        //~ Methods --------------------------------------------------------------------------------
    //        @Override
    //        public void actionPerformed (ActionEvent e)
    //        {
    //            Set<Glyph> glyphs = nest.getSelectedGlyphSet();
    //            ((SymbolsController) controller).showTranslations(glyphs);
    //        }
    //
    //        @Override
    //        public void update ()
    //        {
    //            if (glyphNb > 0) {
    //                for (Glyph glyph : nest.getSelectedGlyphSet()) {
    //                    if (glyph.isTranslated()) {
    //                        setEnabled(true);
    //
    //                        StringBuilder sb = new StringBuilder();
    //                        sb.append("Show translations");
    //                        putValue(NAME, sb.toString());
    //                        putValue(SHORT_DESCRIPTION, "Show translations related to the glyph(s)");
    //
    //                        return;
    //                    }
    //                }
    //            }
    //
    //            // No translation to show
    //            setEnabled(false);
    //            putValue(NAME, "Translations");
    //            putValue(SHORT_DESCRIPTION, "No translation");
    //        }
    //    }
    //----------------//
    // TrimSlurAction //
    //----------------//
    /**
     * Cleanup a glyph with focus on its slur shape.
     */
    private class TrimSlurAction
            extends DynAction
    {
        //~ Constructors ---------------------------------------------------------------------------

        public TrimSlurAction ()
        {
            super(60);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void actionPerformed (ActionEvent e)
        {
            List<Glyph> glyphs = nest.getSelectedGlyphList();
            ((SymbolsController) controller).asyncTrimSlurs(glyphs);
        }

        @Override
        public void update ()
        {
            putValue(NAME, "Trim slur");

            if ((glyphNb > 0) && noVirtuals) {
                setEnabled(true);
                putValue(SHORT_DESCRIPTION, "Extract slur from large glyph");
            } else {
                setEnabled(false);
                putValue(SHORT_DESCRIPTION, "No slur to fix");
            }
        }
    }
}
