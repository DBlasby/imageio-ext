/*
 *    JImageIO-extension - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *    https://imageio-ext.dev.java.net/
 *    (C) 2008, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.imageio.plugins.jp2k;

import it.geosolutions.imageio.stream.output.FileImageOutputStreamExt;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;

import kdu_jni.Jp2_channels;
import kdu_jni.Jp2_colour;
import kdu_jni.Jp2_dimensions;
import kdu_jni.Jp2_family_tgt;
import kdu_jni.Jp2_palette;
import kdu_jni.Jp2_target;
import kdu_jni.KduException;
import kdu_jni.Kdu_codestream;
import kdu_jni.Kdu_compressed_target;
import kdu_jni.Kdu_simple_file_target;
import kdu_jni.Kdu_stripe_compressor;
import kdu_jni.Siz_params;

public class JP2KKakaduImageWriter extends ImageWriter {

    /** The LOGGER for this class. */
    private static final Logger LOGGER = Logger
            .getLogger("it.geosolutions.imageio.plugins.jp2k");

    private final static int MAX_BUFFER_SIZE = 32 * 1024 * 1024;

    private final static int MIN_BUFFER_SIZE = 1024 * 1024;

    private final static int MIN_QUALITY_LAYERS_THRESHOLD = 128 * 128;

    private final static int MED_QUALITY_LAYERS_THRESHOLD = 512 * 512;

    private final static int MAX_QUALITY_LAYERS_THRESHOLD = 1024 * 1024;

    public ImageWriteParam getDefaultWriteParam() {
        return new JP2KKakaduImageWriteParam();
    }

    /**
     * In case the ratio between the stripe_height and the image height is
     * greater than this value, set the stripe_height to the image height in
     * order to do a single push
     */
    private static final double SINGLE_PUSH_THRESHOLD_RATIO = 0.90;

    private File outputFile;

    public JP2KKakaduImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData,
            ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData,
            ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
            ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    /**
     * Sets the destination to the given <code>Object</code>, usually a
     * <code>File</code> or a {@link FileImageOutputStreamExt}.
     * 
     * @param output
     *                the <code>Object</code> to use for future writing.
     */
    public void setOutput(Object output) {
        super.setOutput(output); // validates output
        if (output instanceof File)
            outputFile = (File) output;
        else if (output instanceof FileImageOutputStreamExt)
            outputFile = ((FileImageOutputStreamExt) output).getFile();
        else if (output instanceof URL) {
            final URL tempURL = (URL) output;
            if (tempURL.getProtocol().equalsIgnoreCase("file")) {
                try {
                    outputFile = new File(URLDecoder.decode(tempURL.getFile(),
                            "UTF-8"));

                } catch (IOException e) {
                    throw new RuntimeException("Not a Valid Input", e);
                }
            }
        }
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image,
            ImageWriteParam param) throws IOException {

        final String fileName = outputFile.getAbsolutePath();
        JP2KKakaduImageWriteParam jp2Kparam;
        final boolean writeCodeStreamOnly;
        final double quality;
        int cLevels;
        final boolean cycc;

        if (param == null)
            param = getDefaultWriteParam();
        if (param instanceof JP2KKakaduImageWriteParam) {
            jp2Kparam = (JP2KKakaduImageWriteParam) param;
            writeCodeStreamOnly = jp2Kparam.isWriteCodeStreamOnly();
            quality = jp2Kparam.getQuality();
            cLevels = jp2Kparam.getCLevels();
        } else {
            writeCodeStreamOnly = true;
            quality = 1;
            cLevels = 5;
        }

        // ////////////////////////////////////////////////////////////////////
        //
        // Image properties initialization
        //
        // ////////////////////////////////////////////////////////////////////
        final RenderedImage inputRenderedImage = image.getRenderedImage();
        final int sourceWidth = inputRenderedImage.getWidth();
        final int sourceHeight = inputRenderedImage.getHeight();
        final int sourceMinX = inputRenderedImage.getMinX();
        final int sourceMinY = inputRenderedImage.getMinY();
        final SampleModel sm = inputRenderedImage.getSampleModel();
        final int dataType = sm.getDataType();
        final boolean isGlobalSigned = dataType == DataBuffer.TYPE_SHORT;

        final ColorModel cm = inputRenderedImage.getColorModel();
        final boolean hasPalette = cm instanceof IndexColorModel ? true : false;
        final int[] numberOfBits = cm.getComponentSize();

        // We suppose all bands share the same bitDepth
        final int bits = numberOfBits[0];
        int nComponents = sm.getNumBands();

        byte[] reds = null;
        byte[] greens = null;
        byte[] blues = null;

        if (hasPalette) {
            cycc = false;
            cLevels = 1;

            // Updating the number of components to write as RGB (3 bands)
            if (writeCodeStreamOnly) {
                nComponents = cm.getNumColorComponents();

                // //
                //
                // Caching look up table for future accesses.
                //
                // //

                IndexColorModel icm = (IndexColorModel) cm;
                final int lutSize = icm.getMapSize();
                reds = new byte[lutSize];
                blues = new byte[lutSize];
                greens = new byte[lutSize];
                icm.getReds(reds);
                icm.getGreens(greens);
                icm.getBlues(blues);
            }
        } else {
            cycc = true;
        }

        // //
        //
        // Setting regions and sizes and retrieving parameters
        //
        // //
        final int xSubsamplingFactor = param.getSourceXSubsampling();
        final int ySubsamplingFactor = param.getSourceYSubsampling();
        final Rectangle originalBounds = new Rectangle(sourceMinX, sourceMinY,
                sourceWidth, sourceHeight);
        final Rectangle imageBounds = (Rectangle) originalBounds.clone();
        final Dimension destSize = new Dimension();
        computeRegions(imageBounds, destSize, param);

        boolean resampleInputImage = false;
        if (xSubsamplingFactor != 1 || ySubsamplingFactor != 1
                || !imageBounds.equals(originalBounds)) {
            resampleInputImage = true;
        }

        // Destination sizes
        final int destinationWidth = destSize.width;
        final int destinationHeight = destSize.height;
        final int rowSize = (destinationWidth * nComponents);
        final int imageSize = rowSize * destinationHeight;

        int qualityLayers = 1;
        // if (imageSizeBits>MAX_QUALITY_LAYERS_THRESHOLD)
        // qualityLayers = 10;
        // else if (imageSizeBits>MED_QUALITY_LAYERS_THRESHOLD)
        // qualityLayers = 5;
        // else if (imageSizeBits>MED_QUALITY_LAYERS_THRESHOLD)
        // qualityLayers = 2;
        // else
        // qualityLayers = 1;

        final long qualityLayersSize = (long) (imageSize * quality * bits * 0.125);

        // ////////////////////////////////////////////////////////////////////
        //
        // Kakadu objects initialization
        //
        // ////////////////////////////////////////////////////////////////////
        Kdu_compressed_target outputTarget = null;
        Jp2_target target = null;
        Jp2_family_tgt familyTarget = null;

        try {
            if (writeCodeStreamOnly) {
                // Open a simple file target
                outputTarget = new Kdu_simple_file_target();
                ((Kdu_simple_file_target) outputTarget).Open(fileName);
                final int extensionIndex = fileName.lastIndexOf(".");
                final String suffix = fileName.substring(extensionIndex,
                        fileName.length());
                if (suffix.equalsIgnoreCase(".jp2")
                        && LOGGER.isLoggable(Level.FINE))
                    LOGGER
                            .log(
                                    Level.FINE,
                                    "When writing codestreams, the \".j2c\" file suffix is suggested instead of \".jp2\"");

            } else {

                familyTarget = new Jp2_family_tgt();
                familyTarget.Open(fileName);
                target = new Jp2_target();
                target.Open(familyTarget);
            }

            // //
            //
            // Parameters initialization
            //
            // //
            final Kdu_codestream codeStream = new Kdu_codestream();
            Siz_params params = new Siz_params();
            initParams(params, destinationWidth, destinationHeight, bits,
                    nComponents, isGlobalSigned);

            if (writeCodeStreamOnly)
                codeStream.Create(params, outputTarget, null);
            else
                codeStream.Create(params, target, null);

            params = codeStream.Access_siz();
            if (quality == 1 && hasPalette)
                params.Parse_string("Creversible=yes");
            else
                params.Parse_string("Creversible=no");

            params.Parse_string("Cycc=" + (cycc ? "yes" : "no"));
            params.Parse_string("Clevels=" + cLevels);
            params.Parse_string("Clayers=" + qualityLayers);

            if (!writeCodeStreamOnly) {
                initHeader(target, params, cm);
                target.Open_codestream();
            }

            // //
            //
            // Setting parameters for stripe compression
            //
            // //

            Kdu_stripe_compressor compressor = new Kdu_stripe_compressor();

            // Array with one entry for each image component, identifying the
            // number of lines supplied for that component in the present call.
            // All entries must be non-negative.
            final int[] stripeHeights = new int[nComponents];

            // Array with one entry for each image component, identifying
            // the separation between horizontally adjacent samples within the
            // corresponding stripe buffer found in the stripe_bufs array.
            final int[] sampleGaps = new int[nComponents];

            // Array with one entry for each image component, identifying
            // the separation between vertically adjacent samples within the
            // corresponding stripe buffer found in the stripe_bufs array.
            final int[] rowGaps = new int[nComponents];

            // Array with one entry for each image component, identifying the
            // position of the first sample of that component within the buffer
            // array.
            final int[] sampleOffsets = new int[nComponents];

            // Array with one entry for each image component, identifying the
            // number of significant bits used to represent each sample.
            // There is no implied connection between the precision values, P,
            // and the bit-depth, B, of each image component, as found in the
            // code-stream's SIZ marker segment, and returned via
            // kdu_codestream::get_bit_depth. The original image sample
            // bit-depth, B, may be larger or smaller than the value of P
            // supplied via the precisions argument. The samples returned by
            // pull_stripe all have a nominally signed representation unless
            // otherwise indicated by a non-NULL isSigned argument
            final int precisions[] = new int[nComponents];

            final long[] cumulativeQualityLayerSizes;
            if (quality < 1) {
                cumulativeQualityLayerSizes = computeQualityLayers(
                        qualityLayers, qualityLayersSize);
            } else {
                cumulativeQualityLayerSizes = null;
            }

            int maxStripeHeight = MAX_BUFFER_SIZE / (rowSize);
            if (maxStripeHeight > destinationHeight)
                maxStripeHeight = destinationHeight;
            else {

                // In case the computed stripeHeight is near to the
                // destination height, I will avoid multiple calls by
                // doing a single push.
                double ratio = (double) maxStripeHeight
                        / (double) destinationHeight;
                if (ratio > SINGLE_PUSH_THRESHOLD_RATIO)
                    maxStripeHeight = destinationHeight;

            }

            int minStripeHeight = MIN_BUFFER_SIZE / (rowSize);
            if (minStripeHeight < 1)
                minStripeHeight = 1;

            for (int component = 0; component < nComponents; component++) {
                stripeHeights[component] = maxStripeHeight;
                sampleGaps[component] = nComponents;
                rowGaps[component] = destinationWidth * nComponents;
                sampleOffsets[component] = component;
                precisions[component] = numberOfBits[component];
            }

            // ////////////////////////////////////////////////////////////////
            //
            // Initializing Stripe Compressor
            //
            // ////////////////////////////////////////////////////////////////

            compressor.Start(codeStream, qualityLayers,
                    cumulativeQualityLayerSizes, null, 0, false, false, true,
                    0, nComponents, false);
            final boolean useRecommendations = compressor
                    .Get_recommended_stripe_heights(minStripeHeight, 1024,
                            stripeHeights, null);
            if (!useRecommendations) {
                // Setting the stripeHeight to the max affordable stripe height
                for (int i = 0; i < nComponents; i++)
                    stripeHeights[i] = maxStripeHeight;
            }
            boolean goOn = true;
            final int stripeSize = rowSize * stripeHeights[0];

            // ////////////////////////////////////////////////////////////////
            //
            // Pushing Stripes
            //
            // ////////////////////////////////////////////////////////////////

            // //
            //
            // Byte Buffer
            //
            // //
            if (bits <= 8) {
                byte[] bufferValues = new byte[stripeSize];
                int y = 0;

                if (!resampleInputImage) {
                    while (goOn) {

                        // Adjusting the stripeHeight in case of multi-pass
                        // stripes-push, in case the last stripeHeight is too
                        // high
                        if (destinationHeight - y < stripeHeights[0]) {
                            for (int i = 0; i < nComponents; i++)
                                stripeHeights[i] = destinationHeight - y;
                            bufferValues = new byte[rowSize * stripeHeights[0]];

                        }
                        Raster rasterData = inputRenderedImage
                                .getData(new Rectangle(0, y, destinationWidth,
                                        stripeHeights[0]));
                        if (hasPalette&&writeCodeStreamOnly) {
                            DataBuffer buff = rasterData.getDataBuffer();
                            final int loopLength = buff.getSize();
                            for (int l = 0; l < loopLength; l++) {
                                int pixel = buff.getElem(l);
                                bufferValues[l * 3] = reds[pixel];
                                bufferValues[(l * 3) + 1] = greens[pixel];
                                bufferValues[(l * 3) + 2] = blues[pixel];
                            }

                        } else
                            rasterData.getDataElements(0, y, destinationWidth,
                                    stripeHeights[0], bufferValues);
                        goOn = compressor.Push_stripe(bufferValues,
                                stripeHeights, sampleOffsets, sampleGaps,
                                rowGaps, precisions, 0);
                        y += stripeHeights[0];
                    }
                } else {
                    int lastY = imageBounds.y;
                    final int lastX = imageBounds.x + imageBounds.width;
                    ByteBuffer buffer = ByteBuffer.allocate(stripeSize);
                    ByteBuffer data;
                    while (goOn) {
                        if (destinationHeight - y < stripeHeights[0]) {
                            for (int i = 0; i < nComponents; i++)
                                stripeHeights[i] = destinationHeight - y;
                            buffer = ByteBuffer.allocate(rowSize
                                    * stripeHeights[0]);

                        }
                        Rectangle rect = new Rectangle(imageBounds.x, lastY,
                                imageBounds.width, stripeHeights[0]
                                        * ySubsamplingFactor);
                        rect = rect.intersection(originalBounds);
                        Raster rasterData = inputRenderedImage.getData(rect);
                        lastY = rect.y + rect.height;

                        // SubSampledRead
                        readSubSampled(rect, originalBounds, lastX, lastY,
                                xSubsamplingFactor, ySubsamplingFactor,
                                rasterData, buffer, nComponents);
                        data = buffer;
                        if (hasPalette && writeCodeStreamOnly) {
                            ByteBuffer bb = ByteBuffer.allocate(rowSize
                                    * stripeHeights[0]);
                            bufferValues = bb.array();
                            final int loopLength = buffer.capacity();
                            for (int l = 0; l < loopLength; l++) {
                                int pixel = buffer.get();
                                bufferValues[l * 3] = reds[pixel];
                                bufferValues[(l * 3) + 1] = greens[pixel];
                                bufferValues[(l * 3) + 2] = blues[pixel];
                            }
                            data = bb;
                        }
                        goOn = compressor.Push_stripe(data.array(),
                                stripeHeights, sampleOffsets, sampleGaps,
                                rowGaps, precisions, 0);

                        y += stripeHeights[0];
                        buffer.clear();
                    }
                }
            } else if (bits > 8 && bits <= 16) {
                // //
                //
                // Short Buffer
                //
                // //
                final boolean[] isSigned = new boolean[nComponents];
                for (int i = 0; i < isSigned.length; i++)
                    isSigned[i] = isGlobalSigned;
                short[] bufferValues = new short[stripeSize];
                int y = 0;

                if (!resampleInputImage) {
                    while (goOn) {
                        if (destinationHeight - y < stripeHeights[0]) {
                            for (int i = 0; i < nComponents; i++)
                                stripeHeights[i] = destinationHeight - y;
                            bufferValues = new short[rowSize * stripeHeights[0]];
                        }
                        Raster rasterData = inputRenderedImage
                                .getData(new Rectangle(0, y, destinationWidth,
                                        stripeHeights[0]));
                        rasterData.getDataElements(0, y, destinationWidth,
                                stripeHeights[0], bufferValues);
                        goOn = compressor.Push_stripe(bufferValues,
                                stripeHeights, sampleOffsets, sampleGaps,
                                rowGaps, precisions, isSigned, 0);
                        y += stripeHeights[0];
                    }
                } else {
                    final int lastX = imageBounds.x + imageBounds.width;
                    int lastY = imageBounds.y;
                    ShortBuffer buffer = ShortBuffer.allocate(stripeSize);
                    while (goOn) {
                        if (destinationHeight - y < stripeHeights[0]) {
                            for (int i = 0; i < nComponents; i++)
                                stripeHeights[i] = destinationHeight - y;
                            buffer = ShortBuffer.allocate(rowSize
                                    * stripeHeights[0]);

                        }
                        Rectangle rect = new Rectangle(imageBounds.x, lastY,
                                imageBounds.width, stripeHeights[0]
                                        * ySubsamplingFactor);
                        rect = rect.intersection(originalBounds);
                        final Raster rasterData = inputRenderedImage
                                .getData(rect);
                        lastY = rect.y + rect.height;

                        // SubSampledRead
                        readSubSampled(rect, originalBounds, lastX, lastY,
                                xSubsamplingFactor, ySubsamplingFactor,
                                rasterData, buffer, nComponents);

                        goOn = compressor.Push_stripe(buffer.array(),
                                stripeHeights, sampleOffsets, sampleGaps,
                                rowGaps, precisions, isSigned, 0);

                        y += stripeHeights[0];
                        buffer.clear();
                    }
                }

            } else if (bits > 16 && bits <= 32) {
                // //
                //
                // Int Buffer
                //
                // //
                int[] bufferValues = new int[stripeSize];
                int y = 0;
                while (goOn) {
                    if (!resampleInputImage) {
                        if (destinationHeight - y < stripeHeights[0]) {
                            for (int i = 0; i < nComponents; i++)
                                stripeHeights[i] = destinationHeight - y;
                            bufferValues = new int[rowSize * stripeHeights[0]];
                        }
                        Raster rasterData = inputRenderedImage
                                .getData(new Rectangle(0, y, destinationWidth,
                                        stripeHeights[0]));

                        rasterData.getDataElements(0, y, destinationWidth,
                                stripeHeights[0], bufferValues);
                        goOn = compressor.Push_stripe(bufferValues,
                                stripeHeights, sampleOffsets, sampleGaps,
                                rowGaps, precisions);
                        y += stripeHeights[0];
                    } else {
                        int lastY = imageBounds.y;
                        final int lastX = imageBounds.x + imageBounds.width;
                        IntBuffer buffer = IntBuffer.allocate(stripeSize);
                        while (goOn) {
                            if (destinationHeight - y < stripeHeights[0]) {
                                for (int i = 0; i < nComponents; i++)
                                    stripeHeights[i] = destinationHeight - y;
                                buffer = IntBuffer.allocate(rowSize
                                        * stripeHeights[0]);

                            }
                            Rectangle rect = new Rectangle(imageBounds.x,
                                    lastY, imageBounds.width, stripeHeights[0]
                                            * ySubsamplingFactor);
                            rect = rect.intersection(originalBounds);
                            final Raster rasterData = inputRenderedImage
                                    .getData(rect);
                            lastY = rect.y + rect.height;

                            // SubSampledRead
                            readSubSampled(rect, originalBounds, lastX, lastY,
                                    xSubsamplingFactor, ySubsamplingFactor,
                                    rasterData, buffer, nComponents);

                            goOn = compressor.Push_stripe(buffer.array(),
                                    stripeHeights, sampleOffsets, sampleGaps,
                                    rowGaps, precisions);

                            y += stripeHeights[0];
                            buffer.clear();
                        }
                    }
                }
            }

            // ////////////////////////////////////////////////////////////////
            //
            // Kakadu Objects Finalization
            //
            // ////////////////////////////////////////////////////////////////
            compressor.Finish();
            compressor.Native_destroy();
            codeStream.Destroy();

            if (writeCodeStreamOnly) {
                outputTarget.Close();
                outputTarget.Native_destroy();
            } else {
                target.Close();
                target.Native_destroy();
                familyTarget.Close();
                familyTarget.Native_destroy();
            }

        } catch (KduException e) {
            throw new RuntimeException(
                    "Error caused by a Kakadu exception during write operation",
                    e);
        }
    }

    /**
     * Given a requested number of qualityLayers, computes the cumulative
     * quality layers sizes to be set as argument of the stripe_compressor.
     * 
     * @param qualityLayers
     *                the number of quality layers
     * @param qualityLayersSize
     *                the total amount of bytes used by the quality layers.
     * @return a <code>long</code> array containing the cumulative layers
     *         sizes.
     */
    private long[] computeQualityLayers(final int qualityLayers,
            long qualityLayersSize) {
        long[] cumulativeQualityLayerSizes = new long[qualityLayers];
        if (qualityLayers > 1) {
            // sub-divide the total amount of bytes in the number of
            // requested quality layers. We use a dicotomic paradigm.
            // The bytes assigned to the N-th layer (Better quality) are 2x the
            // bytes assigned to the (N-1)-th layer.
            final long[] qualityLayerSizes = new long[qualityLayers];
            final int[] multipliers = new int[qualityLayers];
            int multi = 1;
            int totals = 0;

            // Computing the minimum quality layers size as well as the
            // total number of subdivisions.
            for (int i = 0; i < qualityLayers; i++) {
                multi = i != 0 ? multi * 2 : multi;
                totals += multi;
                multipliers[i] = multi;
            }

            double qualityStep = Math.floor((double) qualityLayersSize)
                    / ((double) totals);

            // Setting the cumulative layers sizes.
            for (int i = 0; i < qualityLayers; i++) {
                long step = i != 0 ? qualityLayerSizes[i - 1] : 0;
                qualityLayerSizes[i] = (long) Math.floor(qualityStep
                        * multipliers[i]);
                cumulativeQualityLayerSizes[i] = qualityLayerSizes[i] + step;
            }
        } else {
            // Use a single quality layer.
            cumulativeQualityLayerSizes[0] = qualityLayersSize;
        }

        return cumulativeQualityLayerSizes;
    }

    /**
     * Set jp2 elements to be properly written in the JP2 Header.
     * 
     * @param target
     *                the {@link Jp2_target} object.
     * @param params
     *                the {@link Siz_params} containing information needed for
     *                initialization.
     * @param cm
     *                the color model of the image to be written.
     * @throws KduException
     */
    private void initHeader(final Jp2_target target, final Siz_params params,
            final ColorModel cm) throws KduException {

        // //
        //
        // Init the jp2 dimensions
        //
        // //
        final Jp2_dimensions dims = target.Access_dimensions();
        dims.Init(params);

        // //
        //
        // Init the jp2 colour
        //
        // //
        final Jp2_colour colour = target.Access_colour();
        final int cs = cm.getColorSpace().getType();
        if (cs == ColorSpace.TYPE_RGB) {
            colour.Init(kdu_jni.Kdu_global.JP2_sRGB_SPACE);
        } else if (cs == ColorSpace.TYPE_GRAY) {
            colour.Init(kdu_jni.Kdu_global.JP2_sLUM_SPACE);
        }

        // //
        //
        // In case the input image has a palette,
        // initialize jp2 palette and channels.
        //
        // //
        if (cm instanceof IndexColorModel) {
            final IndexColorModel icm = (IndexColorModel) cm;
            final int bitDepth = icm.getComponentSize(0);
            final int lutSize = icm.getMapSize();
            final int reds[] = new int[lutSize];
            final int blues[] = new int[lutSize];
            final int greens[] = new int[lutSize];

            for (int i = 0; i < lutSize; i++) {
                reds[i] = icm.getRed(i);
                greens[i] = icm.getGreen(i);
                blues[i] = icm.getBlue(i);
            }

            // Setting the look up table for the jp2 output.
            Jp2_palette palette = target.Access_palette();
            palette.Init(3, lutSize);
            palette.Set_lut(0, reds, bitDepth, false);
            palette.Set_lut(1, greens, bitDepth, false);
            palette.Set_lut(2, blues, bitDepth, false);

            Jp2_channels channels = target.Access_channels();
            channels.Init(3);
            channels.Set_colour_mapping(0, 0, 0);
            channels.Set_colour_mapping(1, 0, 1);
            channels.Set_colour_mapping(2, 0, 2);
        }

        // Write the header
        target.Write_header();
    }

    /**
     * Read a region of an input raster and put the data values in the provided
     * Buffer.
     * 
     * 
     * @param region
     *                the requested region
     * @param originalBounds
     *                the original Image Extent
     * @param lastX
     *                the last pixel to be analyzed along the image width.
     * @param lastY
     *                the last pixel to be analyzed along the image height.
     * @param xSubsamplingFactor
     * @param ySubsamplingFactor
     * @param rasterData
     *                the original raster data.
     * @param dataBuffer
     *                the buffer containing the data read.
     * @param nComponents
     *                the number of image components.
     */
    private void readSubSampled(final Rectangle region,
            final Rectangle originalBounds, final int lastX, final int lastY,
            final int xSubsamplingFactor, final int ySubsamplingFactor,
            final Raster rasterData, final Buffer dataBuffer,
            final int nComponents) {

        if (dataBuffer instanceof ByteBuffer) {
            // //
            //
            // Byte read
            // 
            // //
            final byte[] data = new byte[nComponents];
            final ByteBuffer buffer = (ByteBuffer) dataBuffer;
            for (int j = region.y; j < lastY; j++) {
                if (((j - originalBounds.y) % ySubsamplingFactor) != 0) {
                    continue;
                }

                for (int i = region.x; i < lastX; i++) {
                    if (((i - originalBounds.x) % xSubsamplingFactor) != 0) {
                        continue;
                    }
                    rasterData.getDataElements(i, j, data);
                    buffer.put(data, 0, nComponents);
                }
            }
        } else if (dataBuffer instanceof ShortBuffer) {
            // //
            //
            // Short read
            // 
            // //
            final short[] data = new short[nComponents];
            final ShortBuffer buffer = (ShortBuffer) dataBuffer;
            for (int j = region.y; j < lastY; j++) {
                if (((j - originalBounds.y) % ySubsamplingFactor) != 0) {
                    continue;
                }

                for (int i = region.x; i < lastX; i++) {
                    if (((i - originalBounds.x) % xSubsamplingFactor) != 0) {
                        continue;
                    }
                    rasterData.getDataElements(i, j, data);
                    buffer.put(data, 0, nComponents);
                }
            }
        } else if (dataBuffer instanceof IntBuffer) {
            // //
            //
            // Int read
            // 
            // //
            final IntBuffer buffer = (IntBuffer) dataBuffer;
            final int[] data = new int[nComponents];
            for (int j = region.y; j < lastY; j++) {
                if (((j - originalBounds.y) % ySubsamplingFactor) != 0) {
                    continue;
                }

                for (int i = region.x; i < lastX; i++) {
                    if (((i - originalBounds.x) % xSubsamplingFactor) != 0) {
                        continue;
                    }
                    rasterData.getDataElements(i, j, data);
                    buffer.put(data, 0, nComponents);
                }
            }
        } else
            throw new IllegalArgumentException("Unsupported buffer type");

    }

    private void initParams(Siz_params params, final int destinationWidth,
            final int destinationHeight, final int precision,
            final int components, final boolean isGlobalSigned)
            throws KduException {
        params.Set("Ssize", 0, 0, destinationHeight);
        params.Set("Ssize", 0, 1, destinationWidth);
        params.Set("Sprofile", 0, 0, 2);
        params.Set("Sorigin", 0, 0, 0);
        params.Set("Sorigin", 0, 1, 0);
        params.Set("Scomponents", 0, 0, components);
        params.Set("Sprecision", 0, 0, precision);
        params.Set("Sdims", 0, 0, destinationHeight);
        params.Set("Sdims", 0, 1, destinationWidth);
        params.Set("Ssigned", 0, 0, isGlobalSigned);
        params.Finalize();
    }

    /**
     * Compute the source region and destination dimensions taking any parameter
     * settings into account.
     */
    private static void computeRegions(final Rectangle sourceBounds,
            Dimension destSize, ImageWriteParam param) {
        int periodX = 1;
        int periodY = 1;
        if (param != null) {
            final int[] sourceBands = param.getSourceBands();
            if (sourceBands != null
                    && (sourceBands.length != 1 || sourceBands[0] != 0)) {
                throw new IllegalArgumentException("Cannot sub-band image!");
                //TODO: Actually, sourceBands is ignored!!
            }

            // ////////////////////////////////////////////////////////////////
            //
            // Get source region and subsampling settings
            //
            // ////////////////////////////////////////////////////////////////
            Rectangle sourceRegion = param.getSourceRegion();
            if (sourceRegion != null) {
                // Clip to actual image bounds
                sourceRegion = sourceRegion.intersection(sourceBounds);
                sourceBounds.setBounds(sourceRegion);
            }

            // Get subsampling factors
            periodX = param.getSourceXSubsampling();
            periodY = param.getSourceYSubsampling();

            // Adjust for subsampling offsets
            int gridX = param.getSubsamplingXOffset();
            int gridY = param.getSubsamplingYOffset();
            sourceBounds.x += gridX;
            sourceBounds.y += gridY;
            sourceBounds.width -= gridX;
            sourceBounds.height -= gridY;
        }

        // ////////////////////////////////////////////////////////////////////
        //
        // Compute output dimensions
        //
        // ////////////////////////////////////////////////////////////////////
        destSize.setSize((sourceBounds.width + periodX - 1) / periodX,
                (sourceBounds.height + periodY - 1) / periodY);
        if (destSize.width <= 0 || destSize.height <= 0) {
            throw new IllegalArgumentException("Empty source region!");
        }
    }

}