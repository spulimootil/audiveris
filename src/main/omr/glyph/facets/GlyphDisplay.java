//----------------------------------------------------------------------------//
//                                                                            //
//                          G l y p h D i s p l a y                           //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright © Hervé Bitteur and others 2000-2013. All rights reserved.      //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.glyph.facets;

import omr.glyph.ui.AttachmentHolder;

import ij.process.ByteProcessor;

import java.awt.Color;

/**
 * Interface {@code GlyphDisplay} defines the facet which handles the
 * way a glyph is displayed (its color, its image).
 *
 * @author Hervé Bitteur
 */
interface GlyphDisplay
        extends GlyphFacet, AttachmentHolder
{
    //~ Methods ----------------------------------------------------------------

    /**
     * Report a basic representation of the glyph, using ascii chars.
     *
     * @return an ascii representation
     */
    String asciiDrawing ();

    /**
     * Report the color to be used to colorize the provided glyph,
     * according to the color policy which is based on the glyph shape.
     *
     * @return the related shape color of the glyph, or the predefined {@link
     * omr.ui.Colors#SHAPE_UNKNOWN} if the glyph has no related shape
     */
    Color getColor ();

    /**
     * Report an image of the glyph (which can be handed to the OCR)
     *
     * @return a black & white image (contour box size )
     */
    ByteProcessor getImage ();
}
