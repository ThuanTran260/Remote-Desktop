package com.ltm.rd.server;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;

/** Test spy: wraps the real JPEG reader by delegation and counts read/dispose calls. */
final class DelegatingImageReader extends ImageReader {
    private final ImageReader delegate;
    final AtomicInteger readCalls = new AtomicInteger();
    final AtomicInteger disposeCalls = new AtomicInteger();

    DelegatingImageReader() {
        this(ImageIO.getImageReadersByFormatName("jpeg").next());
    }

    private DelegatingImageReader(ImageReader delegate) {
        super(delegate.getOriginatingProvider());
        this.delegate = delegate;
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        delegate.setInput(input, seekForwardOnly, ignoreMetadata);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return delegate.getNumImages(allowSearch);
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return delegate.getWidth(imageIndex);
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return delegate.getHeight(imageIndex);
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        return delegate.getImageTypes(imageIndex);
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return delegate.getStreamMetadata();
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        return delegate.getImageMetadata(imageIndex);
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        readCalls.incrementAndGet();
        return delegate.read(imageIndex, param);
    }

    @Override
    public boolean canReadRaster() {
        return delegate.canReadRaster();
    }

    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        readCalls.incrementAndGet();
        return delegate.readRaster(imageIndex, param);
    }

    @Override
    public void dispose() {
        disposeCalls.incrementAndGet();
        delegate.dispose();
        super.dispose();
    }
}
