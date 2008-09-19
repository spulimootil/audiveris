//----------------------------------------------------------------------------//
//                                                                            //
//                         L o c a t i o n E v e n t                          //
//                                                                            //
//  Copyright (C) Herve Bitteur 2000-2007. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Contact author at herve.bitteur@laposte.net to report bugs & suggestions. //
//----------------------------------------------------------------------------//
//
package omr.selection;

import java.awt.Rectangle;

/**
 * Class <code>LocationEvent</code> is an event from which a Rectangle
 * information can be retrieved, so this represents ScoreLocationEvent and
 * SheetLocationEvent classes
 *
 *
 * <dl>
 * <dt><b>Publishers:</b><dd>see subclasses
 * <dt><b>Subscribers:</b><dd>ZoomedPanel
 * <dt><b>Readers:</b><dd>
 * </dl>
 * @author Herv&eacute Bitteur
 * @version $Id$
 */
public abstract class LocationEvent
    extends UserEvent
{
    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new LocationEvent object.
     * @param source the actual entity that created this event
     * @param hint how the event originated
     * @param movement the precise mouse movement
     */
    public LocationEvent (Object        source,
                          SelectionHint hint,
                          MouseMovement movement)
    {
        super(source, hint, movement);
    }

    //~ Methods ----------------------------------------------------------------

    public abstract Rectangle getRectangle ();

    //-----------//
    // getEntity //
    //-----------//
    @Override
    public Rectangle getData ()
    {
        return getRectangle();
    }
}