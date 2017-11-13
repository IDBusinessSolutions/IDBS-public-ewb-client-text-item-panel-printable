/**
 * Copyright (C) 1993-2017 ID Business Solutions Limited
 * All rights reserved
 */
package com.idbs.ewb.text.itempanelprintable;

import java.awt.Graphics2D;

/**
 * This class provides the ability to interpret Graphics2D object being used within
 * @see TextItemPanelPrintable#printOnEDT(Graphics, PageFormat, int)
 * by extneding this class and overriding the createGraphics2D() method.
 */
public class Graphics2DProcessor
{
    public Graphics2DProcessor()
    {
    }

    /**
     * Process the provided Graphics2D object and return.
     *
     * @param graphics The Graphics2D object to be processed.
     * @return
     *      The processed Graphics2D object.
     */
    public Graphics2D createGraphics2D(Graphics2D graphics)
    {
        return graphics;
    }
}
