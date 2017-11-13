/*
 * Copyright 2005-2006 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * This class is based on sun.swing.text.TextComponentPrintable. It differs
 * from that class in that the printShell and viewport are given a height
 * according to the preferred height of the printShell rather than being given
 * a maximum possible height. This is needed in order to overcome a bug in
 * printing PDF generated from E-Workbook in Adobe Reader or other external
 * applications. See SupportWorks ticket F0050209 for information about this
 * problem.
 *
 * To make it possible to compile this class without access to other Sun
 * package-protected classes, support for frames has been removed. Since the
 * ePhox HTML editor does not support the use of frames, this is not a
 * limitation in practice.
 *
 * In contrast to the original TextComponentPrintable class, this class is only
 * intended for printing JEditorPanes. The signature of the getPrintable method
 * has been changed to reflect this.
 *
 * Date of change: 06 Jan 2011
 * Basis of change: Open JDK 6 build b17
 */
package com.idbs.ewb.text.itempanelprintable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An implementation of {@code Printable} to print {@code JTextComponent} with
 * the header and footer.
 * <p/>
 * <h1>
 * WARNING: this class is to be used in
 * javax.swing.text.JTextComponent only.
 * </h1>
 * <p/>
 * <p/>
 * The implementation creates a new {@code JTextComponent} ({@code printShell})
 * to print the content using the {@code Document}, {@code EditorKit} and
 * rendering-affecting properties from the original {@code JTextComponent}.
 * <p/>
 * <p/>
 * {@code printShell} is laid out on the first {@code print} invocation.
 * <p/>
 * <p/>
 * This class can be used on any thread. Part of the implementation is executed
 * on the EDT though.
 *
 * @author Igor Kushnirskiy
 * @since 1.6
 */
public class TextItemPanelPrintable implements Printable
{
    private static final int LIST_SIZE = 1000;

    private boolean isLayouted = false;

    /*
     * The text component to print.
     */
    private final JTextComponent textComponentToPrint;

    /*
     * The FontRenderContext to layout and print with
     */
    private final AtomicReference<FontRenderContext> frc = new AtomicReference<FontRenderContext>(null);

    /**
     * Special text component used to print to the printer.
     */
    private final JTextComponent printShell;

    private final MessageFormat headerFormat;
    private final MessageFormat footerFormat;

    private static final float HEADER_FONT_SIZE = 18.0f;
    private static final float FOOTER_FONT_SIZE = 12.0f;

    private final Font headerFont;
    private final Font footerFont;

    private final Graphics2DProcessor graphics2DProcessor;

    /**
     * stores metrics for the unhandled rows. The only metrics we need are
     * yStart and yEnd when row is handled by updatePagesMetrics it is removed
     * from the list. Thus the head of the list is the fist row to handle.
     * <p/>
     * sorted
     */
    private final List<IntegerSegment> rowsMetrics;

    /**
     * thread-safe list for storing pages metrics. The only metrics we need are
     * yStart and yEnd.
     * It has to be thread-safe since metrics are calculated on
     * the printing thread and accessed on the EDT thread.
     * <p/>
     * sorted
     */
    private final List<IntegerSegment> pagesMetrics;

    /**
     * Returns {@code TextComponentPrintable} to print {@code editorPane}.
     *
     * @param editorPane   {@code JEditorPane} to print
     * @param headerFormat the page header, or {@code null} for none
     * @param footerFormat the page footer, or {@code null} for none
     * @return {@code TextComponentPrintable} to print {@code editorPane}
     */
    public static Printable getPrintable(final JEditorPane editorPane, final MessageFormat headerFormat, final MessageFormat footerFormat, final Graphics2DProcessor graphics2DProcessor)
    {
        return new TextItemPanelPrintable(editorPane, headerFormat, footerFormat, graphics2DProcessor);
    }

    /**
     * Constructs  {@code TextComponentPrintable} to print {@code JTextComponent}
     * {@code textComponent} with {@code headerFormat} and {@code footerFormat}.
     *
     * @param textComponent {@code JTextComponent} to print
     * @param headerFormat  the page header or {@code null} for none
     * @param footerFormat  the page footer or {@code null} for none
     */
    private TextItemPanelPrintable(JTextComponent textComponent, MessageFormat headerFormat, MessageFormat footerFormat, Graphics2DProcessor graphics2DProcessor)
    {
        this.textComponentToPrint = textComponent;
        this.headerFormat = headerFormat;
        this.footerFormat = footerFormat;
        headerFont = textComponent.getFont().deriveFont(Font.BOLD, HEADER_FONT_SIZE);
        footerFont = textComponent.getFont().deriveFont(Font.PLAIN, FOOTER_FONT_SIZE);
        this.pagesMetrics = Collections.synchronizedList(new ArrayList<IntegerSegment>());
        this.rowsMetrics = new ArrayList<IntegerSegment>(LIST_SIZE);
        this.printShell = createPrintShell(textComponent);
        this.graphics2DProcessor = graphics2DProcessor;
    }


    /**
     * creates a printShell.
     * It creates closest text component to {@code textComponent}
     * which uses {@code frc} from the {@code TextComponentPrintable}
     * for the {@code getFontMetrics} method.
     *
     * @param textComponent {@code JTextComponent} to create a
     *                      printShell for
     * @return the print shell
     */
    private JTextComponent createPrintShell(final JTextComponent textComponent)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return createPrintShellOnEDT(textComponent);
        }
        else
        {
            FutureTask<JTextComponent> futureCreateShell = new FutureTask<JTextComponent>(new Callable<JTextComponent>()
            {
                public JTextComponent call() throws Exception
                {
                    return createPrintShellOnEDT(textComponent);
                }
            });
            SwingUtilities.invokeLater(futureCreateShell);
            try
            {
                return futureCreateShell.get();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof Error)
                {
                    throw (Error) cause;
                }
                if (cause instanceof RuntimeException)
                {
                    throw (RuntimeException) cause;
                }
                throw new AssertionError(cause);
            }
        }
    }

    @SuppressWarnings("serial")
    private JTextComponent createPrintShellOnEDT(final JTextComponent textComponent)
    {
        assert SwingUtilities.isEventDispatchThread();

        JTextComponent ret = null;
        if (textComponent instanceof JEditorPane)
        {
            ret = new JEditorPane()
            {
                @Override
                public FontMetrics getFontMetrics(Font font)
                {
                    FontMetrics result = null;
                    if (frc.get() == null)
                    {
                        result = super.getFontMetrics(font);
                    }
                    else
                    {
                        // For correct rendering, we need to use FontDesignMetrics instead of a simple FontMetrics.
                        // This class is access-protected in JDK 6u23, so use Reflection to achieve access.
                        try
                        {
                            Class<?> fontDesignMetricsClass = Class.forName("sun.font.FontDesignMetrics"); //$NON-NLS-1$
                            Method getMetricsMethod = fontDesignMetricsClass.getMethod("getMetrics", //$NON-NLS-1$
                                    Font.class, FontRenderContext.class);
                            Object resultObject = getMetricsMethod.invoke(null, font, frc.get());
                            if (resultObject instanceof FontMetrics)
                            {
                                result = (FontMetrics) resultObject;
                            }
                            else
                            {
                                throw new RuntimeException("Could not obtain FontDesignMetrics"); //$NON-NLS-1$
                            }
                        }
                        catch (ClassNotFoundException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                        catch (IllegalArgumentException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                        catch (IllegalAccessException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                        catch (InvocationTargetException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                        catch (SecurityException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                        catch (NoSuchMethodException e)
                        {
                            throw new RuntimeException("Could not obtain FontDesignMetrics", e); //$NON-NLS-1$
                        }
                    }

                    return result;
                }

                @Override
                public EditorKit getEditorKit()
                {
                    if (getDocument() == textComponent.getDocument())
                    {
                        return ((JEditorPane) textComponent).getEditorKit();
                    }
                    else
                    {
                        return super.getEditorKit();
                    }
                }
            };
        }
        //want to occupy the whole width and height by text
        ret.setBorder(null);

        //set properties from the component to print
        ret.setOpaque(textComponent.isOpaque());
        ret.setEditable(textComponent.isEditable());
        ret.setEnabled(textComponent.isEnabled());
        ret.setFont(textComponent.getFont());
        ret.setBackground(textComponent.getBackground());
        ret.setForeground(textComponent.getForeground());
        ret.setComponentOrientation(textComponent.getComponentOrientation());

        if (ret instanceof JEditorPane)
        {
            ret.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, textComponent.getClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES));
            ret.putClientProperty(JEditorPane.W3C_LENGTH_UNITS, textComponent.getClientProperty(JEditorPane.W3C_LENGTH_UNITS));
            ret.putClientProperty("charset", //$NON-NLS-1$
                    textComponent.getClientProperty("charset")); //$NON-NLS-1$
        }
        ret.setDocument(textComponent.getDocument());
        return ret;
    }


    /**
     * Returns the number of pages in this printable.
     * <p/>
     * This number is defined only after {@code print} returns NO_SUCH_PAGE.
     *
     * @return the number of pages.
     */
    public int getNumberOfPages()
    {
        return pagesMetrics.size();
    }

    /**
     * See Printable.print for the API description.
     * <p/>
     * There are two parts in the implementation.
     * First part (print) is to be called on the printing thread.
     * Second part (printOnEDT) is to be called on the EDT only.
     * <p/>
     * print triggers printOnEDT
     */
    public int print(final Graphics graphics, final PageFormat pf, final int pageIndex) throws PrinterException
    {
        if (!isLayouted)
        {
            if (graphics instanceof Graphics2D)
            {
                frc.set(((Graphics2D) graphics).getFontRenderContext());
            }
            layout((int) Math.floor(pf.getImageableWidth()));
            calculateRowsMetrics();
        }
        int ret;
        if (!SwingUtilities.isEventDispatchThread())
        {
            Callable<Integer> doPrintOnEDT = new Callable<Integer>()
            {
                public Integer call() throws Exception
                {
                    return printOnEDT(graphics, pf, pageIndex);
                }
            };
            FutureTask<Integer> futurePrintOnEDT = new FutureTask<Integer>(doPrintOnEDT);
            SwingUtilities.invokeLater(futurePrintOnEDT);
            try
            {
                ret = futurePrintOnEDT.get();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof PrinterException)
                {
                    throw (PrinterException) cause;
                }
                else
                {
                    if (cause instanceof RuntimeException)
                    {
                        throw (RuntimeException) cause;
                    }
                    else
                    {
                        if (cause instanceof Error)
                        {
                            throw (Error) cause;
                        }
                        else
                        {
                            throw new RuntimeException(cause);
                        }
                    }
                }
            }
        }
        else
        {
            ret = printOnEDT(graphics, pf, pageIndex);
        }
        return ret;
    }


    /**
     * The EDT part of the print method.
     * <p/>
     * This method is to be called on the EDT only. Layout should be done before
     * calling this method.
     */
    private int printOnEDT(final Graphics graphics, final PageFormat pf, final int pageIndex) throws PrinterException
    {
        assert SwingUtilities.isEventDispatchThread();
        Border border = BorderFactory.createEmptyBorder();
        //handle header and footer
        if (headerFormat != null || footerFormat != null)
        {
            //Printable page enumeration is 0 base. We need 1 based.
            Object[] formatArg = new Object[]{Integer.valueOf(pageIndex + 1)};
            if (headerFormat != null)
            {
                border = new TitledBorder(border, headerFormat.format(formatArg), TitledBorder.CENTER, TitledBorder.ABOVE_TOP, headerFont, printShell.getForeground());
            }
            if (footerFormat != null)
            {
                border = new TitledBorder(border, footerFormat.format(formatArg), TitledBorder.CENTER, TitledBorder.BELOW_BOTTOM, footerFont, printShell.getForeground());
            }
        }
        Insets borderInsets = border.getBorderInsets(printShell);
        updatePagesMetrics(pageIndex, (int) Math.floor(pf.getImageableHeight()) - borderInsets.top - borderInsets.bottom);

        if (pagesMetrics.size() <= pageIndex)
        {
            return NO_SUCH_PAGE;
        }

        Graphics2D g2d = graphics2DProcessor.createGraphics2D((Graphics2D) graphics.create());

        g2d.translate(pf.getImageableX(), pf.getImageableY());
        border.paintBorder(printShell, g2d, 0, 0, (int) Math.floor(pf.getImageableWidth()), (int) Math.floor(pf.getImageableHeight()));

        g2d.translate(0, borderInsets.top);
        //want to clip only vertically
        Rectangle clip = new Rectangle(0, 0, (int) pf.getWidth(), pagesMetrics.get(pageIndex).end - pagesMetrics.get(pageIndex).start + 1);

        g2d.clip(clip);
        int xStart = 0;
        if (ComponentOrientation.RIGHT_TO_LEFT == printShell.getComponentOrientation())
        {
            xStart = (int) pf.getImageableWidth() - printShell.getWidth();
        }
        g2d.translate(xStart, -pagesMetrics.get(pageIndex).start);
        printShell.print(g2d);

        g2d.dispose();

        return Printable.PAGE_EXISTS;
    }


    private boolean needReadLock = false;

    /**
     * Tries to release document's readlock
     * <p/>
     * Note: Not to be called on the EDT.
     */
    private void releaseReadLock()
    {
        assert !SwingUtilities.isEventDispatchThread();
        Document document = textComponentToPrint.getDocument();
        if (document instanceof AbstractDocument)
        {
            try
            {
                ((AbstractDocument) document).readUnlock();
                needReadLock = true;
            }
            catch (Error ignore)
            {
                // readUnlock() might throw StateInvariantError
            }
        }
    }


    /**
     * Tries to acquire document's readLock if it was released
     * in releaseReadLock() method.
     * <p/>
     * Note: Not to be called on the EDT.
     */
    private void acquireReadLock()
    {
        assert !SwingUtilities.isEventDispatchThread();
        if (needReadLock)
        {
            try
            {
                /*
                 * wait until all the EDT events are processed
                 * some of the document changes are asynchronous
                 * we need to make sure we get the lock after those changes
                 */
                SwingUtilities.invokeAndWait(new Runnable()
                {
                    public void run()
                    {
                    }
                });
            }
            catch (InterruptedException ignore)
            {
            }
            catch (java.lang.reflect.InvocationTargetException ignore)
            {
            }
            Document document = textComponentToPrint.getDocument();
            ((AbstractDocument) document).readLock();
            needReadLock = false;
        }
    }

    /**
     * Prepares {@code printShell} for printing.
     * <p/>
     * Sets properties from the component to print.
     * Sets width and FontRenderContext.
     * <p/>
     * Triggers Views creation for the printShell.
     * <p/>
     * There are two parts in the implementation.
     * First part (layout) is to be called on the printing thread.
     * Second part (layoutOnEDT) is to be called on the EDT only.
     * <p/>
     * {@code layout} triggers {@code layoutOnEDT}.
     *
     * @param width width to layout the text for
     */
    private void layout(final int width)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            Callable<Object> doLayoutOnEDT = new Callable<Object>()
            {
                public Object call() throws Exception
                {
                    layoutOnEDT(width);
                    return null;
                }
            };
            FutureTask<Object> futureLayoutOnEDT = new FutureTask<Object>(doLayoutOnEDT);

            /*
             * We need to release document's readlock while printShell is
             * initializing
             */
            releaseReadLock();
            SwingUtilities.invokeLater(futureLayoutOnEDT);
            try
            {
                futureLayoutOnEDT.get();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            catch (ExecutionException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException)
                {
                    throw (RuntimeException) cause;
                }
                else
                {
                    if (cause instanceof Error)
                    {
                        throw (Error) cause;
                    }
                    else
                    {
                        throw new RuntimeException(cause);
                    }
                }
            }
            finally
            {
                acquireReadLock();
            }
        }
        else
        {
            layoutOnEDT(width);
        }

        isLayouted = true;
    }

    /**
     * The EDT part of layout method.
     * <p/>
     * This method is to be called on the EDT only.
     */
    private void layoutOnEDT(final int width)
    {
        assert SwingUtilities.isEventDispatchThread();

        CellRendererPane rendererPane = new CellRendererPane();

        //need to use JViewport since text is layouted to the viewPort width
        //otherwise it will be layouted to the maximum text width
        JViewport viewport = new JViewport();
        viewport.setBorder(null);
        Dimension size = new Dimension(width, printShell.getPreferredSize().height);
        printShell.setSize(size);
        viewport.setComponentOrientation(printShell.getComponentOrientation());
        viewport.setSize(size);
        viewport.add(printShell);
        rendererPane.add(viewport);
    }

    /**
     * Calculates pageMetrics for the pages up to the {@code pageIndex} using
     * {@code rowsMetrics}.
     * Metrics are stored in the {@code pagesMetrics}.
     *
     * @param pageIndex  the page to update the metrics for
     * @param pageHeight the page height
     */
    private void updatePagesMetrics(final int pageIndex, final int pageHeight)
    {
        while (pageIndex >= pagesMetrics.size() && !rowsMetrics.isEmpty())
        {
            // add one page to the pageMetrics
            int lastPage = pagesMetrics.size() - 1;
            int pageStart = (lastPage >= 0) ? pagesMetrics.get(lastPage).end + 1 : 0;
            int rowIndex;
            for (rowIndex = 0; rowIndex < rowsMetrics.size() && (rowsMetrics.get(rowIndex).end - pageStart + 1) <= pageHeight; rowIndex++)
            {
            }
            if (rowIndex == 0)
            {
                // can not fit a single row
                // need to split
                pagesMetrics.add(new IntegerSegment(pageStart, pageStart + pageHeight - 1));
            }
            else
            {
                rowIndex--;
                pagesMetrics.add(new IntegerSegment(pageStart, rowsMetrics.get(rowIndex).end));
                for (int i = 0; i <= rowIndex; i++)
                {
                    rowsMetrics.remove(0);
                }
            }
        }
    }

    /**
     * Calculates rowsMetrics for the document. The result is stored
     * in the {@code rowsMetrics}.
     * <p/>
     * Two steps process.
     * First step is to find yStart and yEnd for the every document position.
     * Second step is to merge all intersected segments ( [yStart, yEnd] ).
     */
    private void calculateRowsMetrics()
    {
        final int documentLength = printShell.getDocument().getLength();
        List<IntegerSegment> documentMetrics = new ArrayList<IntegerSegment>(LIST_SIZE);
        Rectangle rect;
        for (int i = 0, previousY = -1, previousHeight = -1; i < documentLength; i++)
        {
            try
            {
                rect = printShell.modelToView(i);
                if (rect != null)
                {
                    int y = (int) rect.getY();
                    int height = (int) rect.getHeight();
                    if (height != 0 && (y != previousY || height != previousHeight))
                    {
                        /*
                         * we do not store the same value as previous. in our
                         * documents it is often for consequent positons to have
                         * the same modelToView y and height.
                         */
                        previousY = y;
                        previousHeight = height;
                        documentMetrics.add(new IntegerSegment(y, y + height - 1));
                    }
                }
            }
            catch (BadLocationException e)
            {
                assert false;
            }
        }
        /*
         * Merge all intersected segments.
         */
        Collections.sort(documentMetrics);
        int yStart = Integer.MIN_VALUE;
        int yEnd = Integer.MIN_VALUE;
        for (IntegerSegment segment : documentMetrics)
        {
            if (yEnd < segment.start)
            {
                if (yEnd != Integer.MIN_VALUE)
                {
                    rowsMetrics.add(new IntegerSegment(yStart, yEnd));
                }
                yStart = segment.start;
                yEnd = segment.end;
            }
            else
            {
                yEnd = segment.end;
            }
        }
        if (yEnd != Integer.MIN_VALUE)
        {
            rowsMetrics.add(new IntegerSegment(yStart, yEnd));
        }
    }

    /**
     * Class to represent segment of integers.
     * we do not call it Segment to avoid confusion with
     * javax.swing.text.Segment
     */
    private static class IntegerSegment implements Comparable<IntegerSegment>
    {
        final int start;
        final int end;

        IntegerSegment(int start, int end)
        {
            this.start = start;
            this.end = end;
        }

        public int compareTo(IntegerSegment object)
        {
            int startsDelta = start - object.start;
            return (startsDelta != 0) ? startsDelta : end - object.end;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj instanceof IntegerSegment)
            {
                return compareTo((IntegerSegment) obj) == 0;
            }
            else
            {
                return false;
            }
        }

        @Override
        public int hashCode()
        {
            // from the "Effective Java: Programming Language Guide"
            int result = 17;
            result = 37 * result + start;
            result = 37 * result + end;
            return result;
        }

        @Override
        public String toString()
        {
            return "IntegerSegment [" + start + ", " + end + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
